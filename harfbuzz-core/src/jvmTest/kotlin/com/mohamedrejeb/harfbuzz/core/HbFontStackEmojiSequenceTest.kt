package com.mohamedrejeb.harfbuzz.core

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Emoji *sequences* through the system-fallback layer. Single-codepoint
 * emoji (😀) exercise the plain notdef→fallback path, but sequences add
 * default-ignorable codepoints - VS16 (`U+FE0F`) and ZWJ (`U+200D`) -
 * which HarfBuzz substitutes with the font's invisible/space glyph
 * (nonzero gid, zero advance) instead of notdef. Cluster-coverage logic
 * that ORs glyph ids across a cluster therefore sees the ignorable's
 * substituted glyph and wrongly marks the whole cluster as covered by
 * the primary font, so ❤️ renders as the primary's notdef instead of
 * falling back to the emoji font.
 */
class HbFontStackEmojiSequenceTest {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val robotoFile = File(sampleFonts, "Roboto-Regular.ttf")
    private val emojiFile = File(sampleFonts, "NotoColorEmoji-Regular.ttf")

    private lateinit var robotoFace: HbFace
    private lateinit var roboto: HbFont

    @BeforeTest
    fun setUp() = runBlocking {
        require(robotoFile.exists()) { "Roboto font not found at ${robotoFile.absolutePath}" }
        require(emojiFile.exists()) { "Noto Color Emoji font not found" }
        robotoFace = HbFace.from { bytes(robotoFile.readBytes()) }
        roboto = robotoFace.toFont()
    }

    @AfterTest
    fun tearDown() {
        clearSharedSystemResolverCacheForTest()
        roboto.close()
        robotoFace.close()
    }

    private suspend fun shapeWithEmojiFallback(text: String): ShapedParagraph {
        // Same seeding trick as HbFontStackSystemFallbackTest: pre-populate
        // the process-wide resolver cache under the key the stack computes
        // (Match() inherits the primary's style) so the deterministic
        // curated resolver answers instead of a host-dependent scan.
        seedSharedSystemResolverForTest(
            SystemFallback.Match(style = roboto.face.styleHint),
            JvmSystemFontResolver(SystemFallback.Match(), listOf(emojiFile)),
        )
        val stack = HbFontStack(roboto, emptyList(), system = SystemFallback.Match())
        return try {
            stack.shapeParagraph(text, sizePx = 24f, baseDirection = HbDirection.LTR)
        } finally {
            stack.close()
        }
    }

    @Test
    fun `red heart with VS16 falls back to the emoji font`() = runBlocking {
        val paragraph = shapeWithEmojiFallback("❤️") // ❤️
        val emojiRun = paragraph.runs.firstOrNull { it.font != roboto }
        assertTrue(
            emojiRun != null,
            "U+2764 U+FE0F must resolve via the emoji fallback, not stay on the primary",
        )
        paragraph.runs.forEach { run ->
            assertTrue(
                run.glyphs.all { it.glyphId != 0 },
                "expected zero notdefs after emoji fallback, run was $run",
            )
        }
    }

    @Test
    fun `zwj family sequence falls back to the emoji font`() = runBlocking {
        // 👨‍👩‍👦 = U+1F468 ZWJ U+1F469 ZWJ U+1F466
        val paragraph = shapeWithEmojiFallback("👨‍👩‍👦")
        val emojiRun = paragraph.runs.firstOrNull { it.font != roboto }
        assertTrue(
            emojiRun != null,
            "the ZWJ sequence must resolve via the emoji fallback, not stay on the primary",
        )
        paragraph.runs.forEach { run ->
            assertTrue(
                run.glyphs.all { it.glyphId != 0 },
                "expected zero notdefs after emoji fallback, run was $run",
            )
        }
    }
}
