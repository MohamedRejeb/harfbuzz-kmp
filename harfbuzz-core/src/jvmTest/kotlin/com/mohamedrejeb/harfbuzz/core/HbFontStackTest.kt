package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tier 1 fallback shaping: mix a Latin-only font (Roboto) with an
 * Arabic-only font (Noto Naskh Arabic) and verify that the per-cluster
 * fallback algorithm in [HbFontStack.shapeParagraph] picks the right
 * font per cluster.
 *
 * Resources come from the sample module's bundled fonts so the test
 * runs identically on local machines and CI without depending on
 * system-installed fonts.
 */
class HbFontStackTest {

    private val robotoFile = File(
        "../sample/src/commonMain/composeResources/font/Roboto-Regular.ttf",
    )
    private val notoArabicFile = File(
        "../sample/src/commonMain/composeResources/font/NotoNaskhArabic-Regular.ttf",
    )

    private lateinit var robotoFace: HbFace
    private lateinit var arabicFace: HbFace
    private lateinit var roboto: HbFont
    private lateinit var arabic: HbFont

    @BeforeTest
    fun setUp() = runBlocking {
        require(robotoFile.exists()) { "Roboto font not found at ${robotoFile.absolutePath}" }
        require(notoArabicFile.exists()) { "Noto Naskh Arabic font not found at ${notoArabicFile.absolutePath}" }
        robotoFace = HbFace.from { bytes(robotoFile.readBytes()) }
        arabicFace = HbFace.from { bytes(notoArabicFile.readBytes()) }
        roboto = robotoFace.toFont(24f)
        arabic = arabicFace.toFont(24f)
    }

    @AfterTest
    fun tearDown() {
        roboto.close()
        arabic.close()
        robotoFace.close()
        arabicFace.close()
    }

    @Test
    fun `single-font stack delegates to primary shapeParagraph and tags font`() = runBlocking {
        val stack = HbFontStack(roboto)
        val paragraph = stack.shapeParagraph("Hello", baseDirection = HbDirection.LTR)

        assertTrue(paragraph.runs.isNotEmpty())
        paragraph.runs.forEach { run ->
            assertSame(roboto, run.font, "single-font stack should tag every run with primary")
            assertTrue(run.glyphs.all { it.glyphId != 0 }, "Roboto resolves Latin")
        }
    }

    @Test
    fun `Latin primary alone leaves Arabic clusters as notdef`() = runBlocking {
        // Establish baseline: Roboto on Arabic text → all .notdef.
        val paragraph = roboto.shapeParagraph("مرحبا", baseDirection = HbDirection.RTL)
        val gids = paragraph.runs.flatMap { it.glyphs.map { g -> g.glyphId } }
        assertTrue(gids.isNotEmpty())
        assertTrue(gids.all { it == 0 }, "Roboto has no Arabic glyphs - expect all .notdef, got $gids")
    }

    @Test
    fun `fallback resolves Arabic clusters when primary cannot`() = runBlocking {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        val paragraph = stack.shapeParagraph("مرحبا", baseDirection = HbDirection.RTL)

        assertTrue(paragraph.runs.isNotEmpty())
        // Every glyph should have resolved (non-notdef) and be attributed
        // to the Arabic fallback font, since Roboto cannot shape Arabic.
        paragraph.runs.forEach { run ->
            assertSame(arabic, run.font, "Arabic clusters should resolve via fallback")
            assertTrue(run.glyphs.all { it.glyphId != 0 }, "Arabic fallback should resolve all clusters")
        }
        assertTrue(paragraph.totalAdvance > 0f)
    }

    @Test
    fun `mixed Latin and Arabic produces sub-runs attributed to their fonts`() = runBlocking {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        // LTR base with both scripts. BiDi splits this into Latin + Arabic
        // direction-runs first, then per-cluster fallback resolves each.
        val paragraph = stack.shapeParagraph(
            text = "Hi مرحبا",
            baseDirection = HbDirection.LTR,
        )
        assertTrue(paragraph.runs.size >= 2)

        val byFont = paragraph.runs.groupBy { it.font }
        assertNotNull(byFont[roboto], "Latin clusters should attribute to Roboto")
        assertNotNull(byFont[arabic], "Arabic clusters should attribute to Noto fallback")
        // No null-attributed runs in fallback shaping.
        assertNull(byFont[null], "every run should carry a font when shaped via stack")
        // No .notdef glyphs anywhere now that fallback covered Arabic.
        paragraph.runs.forEach { run ->
            assertTrue(
                run.glyphs.all { it.glyphId != 0 },
                "expected zero .notdef in mixed-font run, got ${run.glyphs.map { it.glyphId }}",
            )
        }
    }

    @Test
    fun `cluster offsets remain in original paragraph coordinates after fallback`() = runBlocking {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        val text = "Hi مرحبا"
        val paragraph = stack.shapeParagraph(text, baseDirection = HbDirection.LTR)

        val clusters = paragraph.runs.flatMap { it.glyphs.map { g -> g.cluster } }
        // Clusters must be valid UTF-16 indices into the original `text`.
        assertTrue(clusters.all { it in text.indices }, "clusters out of range: $clusters")
        // We must see clusters from BOTH halves of the input - the Latin
        // prefix (index 0..1) and the Arabic suffix (index 3..7).
        assertTrue(clusters.any { it < 2 }, "no Latin-side clusters found - offset bug?")
        assertTrue(clusters.any { it >= 3 }, "no Arabic-side clusters found - offset bug?")
    }

    @Test
    fun `chain exhausted leaves last-attempted notdef glyphs`() = runBlocking {
        // Only Latin font in the chain - Arabic input has nowhere to fall back.
        // Pin `system = None` so the host platform's fonts don't satisfy the
        // Arabic clusters that we want to assert end up as .notdef.
        val stack = HbFontStack(roboto, fallbacks = emptyList(), system = SystemFallback.None)
        val paragraph = stack.shapeParagraph("مرحبا", baseDirection = HbDirection.RTL)
        val gids = paragraph.runs.flatMap { it.glyphs.map { g -> g.glyphId } }
        assertTrue(gids.isNotEmpty(), "should still produce shaping output")
        assertTrue(gids.all { it == 0 }, "exhausted chain → primary's .notdef wins")
        paragraph.runs.forEach { run ->
            assertSame(roboto, run.font)
        }
    }

    @Test
    fun `RTL sub-run order matches drawing order with fallback`() = runBlocking {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        // For an RTL paragraph "مرحبا Hi", display layout puts the
        // Latin "Hi" on the left and the Arabic "مرحبا" on the right.
        // ShapedParagraph.runs is in drawing order (left-to-right pen
        // sweep), so runs[0] should be the Latin (leftmost) and the
        // last run should be the Arabic (rightmost).
        val paragraph = stack.shapeParagraph(
            text = "مرحبا Hi",
            baseDirection = HbDirection.RTL,
        )
        assertTrue(paragraph.runs.size >= 2)
        assertSame(roboto, paragraph.runs.first().font, "first drawn (leftmost) run is Latin in RTL paragraph")
        assertSame(arabic, paragraph.runs.last().font, "last drawn (rightmost) run is Arabic in RTL paragraph")
        // Same-direction Arabic runs always resolve via fallback; the
        // Latin embedded run resolves via the primary.
        paragraph.runs.forEach { run ->
            assertTrue(
                run.glyphs.all { it.glyphId != 0 },
                "run attributed to ${run.font} should not contain .notdef",
            )
        }
    }

    @Test
    fun `empty text produces empty paragraph`() = runBlocking {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        val paragraph = stack.shapeParagraph("", baseDirection = HbDirection.LTR)
        assertTrue(paragraph.isEmpty)
        assertEquals(0f, paragraph.totalAdvance)
    }

    @Test
    fun `single resolvable cluster does not pay fallback cost`() = runBlocking {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        val paragraph = stack.shapeParagraph("Hello", baseDirection = HbDirection.LTR)
        // Every cluster should be attributed to Roboto - no fallback attempt
        // even though Arabic is configured as a fallback.
        paragraph.runs.forEach { run ->
            assertSame(roboto, run.font)
        }
    }

    @Test
    fun `stack of accepts varargs and matches direct construction`() {
        val a = HbFontStack.of(roboto, arabic)
        val b = HbFontStack(roboto, listOf(arabic))
        assertEquals(a.fonts, b.fonts)
        assertSame(a.primary, b.primary)
    }

    @Test
    fun `system fallback defaults to Match with inherit-from-primary style`() {
        val stack = HbFontStack(roboto, fallbacks = listOf(arabic))
        val match = stack.system as? SystemFallback.Match
        assertNotNull(match, "default should be SystemFallback.Match - got ${stack.system}")
        assertNull(match.style, "default Match.style is null so it inherits from primary at resolve time")
    }
}
