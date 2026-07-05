package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Integration tests for [SystemFallback.Match]: build an [HbFontStack]
 * with `system = Match`, drive a [JvmSystemFontResolver] backed by a
 * deterministic curated list (Noto Naskh Arabic + Noto Color Emoji),
 * and verify clusters not in the explicit fallback chain still
 * resolve via the system layer.
 *
 * Strategy: we replace the platform `createSystemFontResolver` actual
 * by constructing the resolver directly inside the stack via the
 * `system` field, which the test inspects via reflection-free means
 * (we just call `shapeParagraph` and assert the resulting runs use
 * fonts from the curated list).
 */
class HbFontStackSystemFallbackTest {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val robotoFile = File(sampleFonts, "Roboto-Regular.ttf")
    private val arabicFile = File(sampleFonts, "NotoNaskhArabic-Regular.ttf")
    private val emojiFile = File(sampleFonts, "NotoColorEmoji-Regular.ttf")

    private lateinit var robotoFace: HbFace
    private lateinit var roboto: HbFont

    @BeforeTest
    fun setUp() = runBlocking {
        require(robotoFile.exists()) { "Roboto font not found at ${robotoFile.absolutePath}" }
        require(arabicFile.exists()) { "Noto Naskh Arabic font not found" }
        robotoFace = HbFace.from { bytes(robotoFile.readBytes()) }
        roboto = robotoFace.toFont()
    }

    @AfterTest
    fun tearDown() {
        // The process-wide resolver cache outlives any single stack, so
        // release any test-injected resolvers before the next test runs.
        clearSharedSystemResolverCacheForTest()
        roboto.close()
        robotoFace.close()
    }

    @Test
    fun `system layer resolves Arabic when explicit chain has no Arabic font`() = runBlocking {
        val curated = listOf(arabicFile, emojiFile).filter { it.exists() }
        val stack = TestableHbFontStack(
            primary = roboto,
            fallbacks = emptyList(),
            systemResolver = JvmSystemFontResolver(SystemFallback.Match(), curated),
        )
        try {
            val paragraph = stack.shapeParagraph("Hello مرحبا", sizePx = 24f, baseDirection = HbDirection.LTR)
            // Roboto resolves Latin; system layer resolves Arabic.
            val byFont = paragraph.runs.groupBy { it.font }
            assertNotNull(byFont[roboto], "Latin clusters should attribute to Roboto")
            // The Arabic run uses a font from the curated system list - its
            // font reference is NOT roboto.
            val arabicRunFont = paragraph.runs.firstOrNull { it.font != roboto }?.font
            assertNotNull(arabicRunFont, "Arabic clusters should resolve via the system layer")
            assertNotSame(roboto, arabicRunFont)
            paragraph.runs.forEach { run ->
                assertTrue(
                    run.glyphs.all { it.glyphId != 0 },
                    "expected zero notdefs after system fallback, run was $run",
                )
            }
        } finally {
            stack.close()
        }
    }

    @Test
    fun `system layer resolves emoji when neither primary nor fallbacks cover it`() = runBlocking {
        if (!emojiFile.exists()) return@runBlocking
        val arabicFace = HbFace.from { bytes(arabicFile.readBytes()) }
        val arabic = arabicFace.toFont()
        try {
            val curated = listOf(emojiFile)
            val stack = TestableHbFontStack(
                primary = roboto,
                fallbacks = listOf(arabic),
                systemResolver = JvmSystemFontResolver(SystemFallback.Match(), curated),
            )
            try {
                val paragraph = stack.shapeParagraph(
                    text = "Hi 😀",
                    sizePx = 24f,
                    baseDirection = HbDirection.LTR,
                )
                paragraph.runs.forEach { run ->
                    assertTrue(
                        run.glyphs.all { it.glyphId != 0 },
                        "every cluster should resolve once the system layer kicks in",
                    )
                }
                // The emoji codepoint must have routed to a font that is
                // neither Roboto nor Noto Naskh.
                val emojiFont = paragraph.runs.map { it.font }.toSet().firstOrNull {
                    it != null && it != roboto && it != arabic
                }
                assertNotNull(emojiFont, "emoji codepoint should resolve through the system layer")
            } finally {
                stack.close()
            }
        } finally {
            arabic.close()
            arabicFace.close()
        }
    }

    @Test
    fun `boring Latin-only text with non-Latin primary resolves via system layer`() = runBlocking {
        // Regression test: "Hello" is boring text (all code units < U+0590),
        // which routes through the single-shape fast path. With a primary
        // that has no Latin coverage (NotoColorEmoji stands in for the
        // Arabic-only decorative fonts that hit this in production), the
        // fast path used to return all-notdef output without ever
        // consulting the system resolver - Latin-only text rendered as
        // nothing while mixed Latin+Arabic text (not boring) rendered fine.
        if (!emojiFile.exists()) return@runBlocking
        val emojiFace = HbFace.from { bytes(emojiFile.readBytes()) }
        val emoji = emojiFace.toFont()
        try {
            val stack = TestableHbFontStack(
                primary = emoji,
                fallbacks = emptyList(),
                systemResolver = JvmSystemFontResolver(SystemFallback.Match(), listOf(robotoFile)),
            )
            try {
                val paragraph = stack.shapeParagraph("Hello", sizePx = 24f, baseDirection = HbDirection.AUTO)
                paragraph.runs.forEach { run ->
                    assertTrue(
                        run.glyphs.all { it.glyphId != 0 },
                        "expected zero notdefs after system fallback, run was $run",
                    )
                }
                val latinFont = paragraph.runs.firstOrNull()?.font
                assertNotNull(latinFont, "Latin clusters should resolve via the system layer")
                assertNotSame<HbFont?>(emoji, latinFont)
            } finally {
                stack.close()
            }
        } finally {
            emoji.close()
            emojiFace.close()
        }
    }

    @Test
    fun `system None bypasses resolver entirely`() = runBlocking {
        // Even without explicit fallbacks, system=None means Arabic stays as
        // notdef glyphs from Roboto - the resolver is never consulted.
        val stack = HbFontStack(
            primary = roboto,
            fallbacks = emptyList(),
            system = SystemFallback.None,
        )
        try {
            val paragraph = stack.shapeParagraph("مرحبا", sizePx = 24f, baseDirection = HbDirection.RTL)
            val gids = paragraph.runs.flatMap { it.glyphs.map { g -> g.glyphId } }
            assertTrue(gids.all { it == 0 }, "expected all notdef without system fallback")
            paragraph.runs.forEach { assertSame(roboto, it.font) }
        } finally {
            stack.close()
        }
    }

    @Test
    fun `close on stack with system Match releases the resolver once`() {
        val stack = HbFontStack(
            primary = roboto,
            fallbacks = emptyList(),
            system = SystemFallback.Match(),
        )
        // Calling close before any shape() should not throw - the lazy
        // resolver was never created.
        stack.close()
        // Calling close again is also safe.
        stack.close()
    }

    @Test
    fun `system None close is a no-op`() {
        val stack = HbFontStack(roboto)
        // Repeated close calls do nothing harmful.
        stack.close()
        stack.close()
        // The primary font is NOT closed by the stack - caller still owns it.
        assertEquals(false, roboto.isClosed)
    }
}

/**
 * Test-only [HbFontStack] subclass that injects a pre-built
 * [SystemFontResolver] instead of constructing one from
 * [createSystemFontResolver]. This keeps the system-fallback test
 * deterministic by avoiding the curated path scan that depends on
 * what's installed on the host.
 */
private class TestableHbFontStack(
    primary: HbFont,
    fallbacks: List<HbFont>,
    systemResolver: SystemFontResolver,
) : AutoCloseable {
    private val real = HbFontStack(primary, fallbacks, system = SystemFallback.Match())

    init {
        // Pre-populate the process-wide resolver cache under the key the
        // stack will compute (Match() inherits style from `primary`, so we
        // mirror that here). The test then exercises the real shaping path
        // and we know it's our deterministic resolver answering.
        seedSharedSystemResolverForTest(
            SystemFallback.Match(style = primary.face.styleHint),
            systemResolver,
        )
    }

    suspend fun shapeParagraph(text: String, sizePx: Float, baseDirection: HbDirection): ShapedParagraph =
        real.shapeParagraph(text, sizePx = sizePx, baseDirection = baseDirection)

    override fun close() {
        // Resolver cleanup is shared across tests - see the AfterTest hook
        // on the test class - so close() only releases the stack itself.
        real.close()
    }
}
