package com.mohamedrejeb.harfbuzz.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * System-fallback resolution for notdef intervals that need MORE than one
 * system font, and for text-default emoji qualified by VS16.
 *
 * A notdef interval is resolved by its FIRST codepoint; the chosen font
 * must not be the final word for the whole interval — codepoints it
 * doesn't cover (e.g. Thai after an emoji, or emoji after Latin, with a
 * primary covering neither) must recurse into the next system font
 * instead of rendering invisible notdefs.
 */
class HbFontStackMixedFallbackTest {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val robotoFile = File(sampleFonts, "Roboto-Regular.ttf")
    private val emojiFile = File(sampleFonts, "NotoColorEmoji-Regular.ttf")
    private val arabicFile = File(sampleFonts, "ArefRuqaa-Regular.ttf")
    private val thaiFile = File("/System/Library/Fonts/Supplemental/Ayuthaya.ttf")

    private lateinit var robotoFace: HbFace
    private lateinit var roboto: HbFont

    @BeforeTest
    fun setUp() = runBlocking {
        robotoFace = HbFace.from { bytes(robotoFile.readBytes()) }
        roboto = robotoFace.toFont()
    }

    @AfterTest
    fun tearDown() {
        clearSharedSystemResolverCacheForTest()
        roboto.close()
        robotoFace.close()
    }

    @Test
    fun `mixed emoji plus thai interval resolves both scripts`() = runBlocking {
        assumeTrue("Thai system font not present", thaiFile.exists())
        seedSharedSystemResolverForTest(
            SystemFallback.Match(style = roboto.face.styleHint),
            JvmSystemFontResolver(SystemFallback.Match(), listOf(emojiFile, thaiFile)),
        )
        val stack = HbFontStack(roboto, emptyList(), system = SystemFallback.Match())
        try {
            // "😀สวัสดี": adjacent uncovered codepoints in ONE LTR direction
            // run and ONE notdef interval. The emoji leads the interval, so
            // the emoji font resolves it — Thai must then recurse to the
            // Thai font instead of staying notdef.
            val paragraph = stack.shapeParagraph("Hi 😀สวัสดี", sizePx = 24f, baseDirection = HbDirection.LTR)
            val fonts = paragraph.runs.mapNotNull { it.font }.toSet()
            assertTrue(fonts.size >= 3, "expected primary + emoji + thai fonts, got ${fonts.size}")
            paragraph.runs.forEach { run ->
                assertTrue(
                    run.glyphs.all { it.glyphId != 0 },
                    "expected zero notdefs after recursive fallback, run was $run",
                )
            }
        } finally {
            stack.close()
        }
    }

    @Test
    fun `vs16 qualified arrow resolves to a color font`() = runBlocking {
        val arabicFace = HbFace.from { bytes(arabicFile.readBytes()) }
        val arabic = arabicFace.toFont()
        try {
            assumeTrue(
                "primary must not cover U+2194 for this test",
                arabic.glyphIdForCodepoint(0x2194) == 0,
            )
            // A mono font that genuinely covers U+2194 so the test has a
            // competing first-cover; skip if none is available.
            val monoArrowFile = findMonoArrowFont()
            assumeTrue("no mono font covering U+2194 available", monoArrowFile != null)
            // Curated order puts the mono cover FIRST: without the VS16
            // hint the resolver stops at the first cover (mono arrow);
            // with it, color preference drains on to the emoji font.
            seedSharedSystemResolverForTest(
                SystemFallback.Match(style = arabic.face.styleHint),
                JvmSystemFontResolver(SystemFallback.Match(), listOf(monoArrowFile!!, emojiFile)),
            )
            val stack = HbFontStack(arabic, emptyList(), system = SystemFallback.Match())
            try {
                val paragraph = stack.shapeParagraph("↔️", sizePx = 24f, baseDirection = HbDirection.LTR)
                val arrowFont = paragraph.runs.firstOrNull { it.font != arabic }?.font
                assertTrue(arrowFont != null, "arrow must resolve via system fallback")
                val hasColor = runCatching {
                    arrowFont.face.hasColorPaint() || arrowFont.face.hasColorLayers() ||
                        arrowFont.face.hasColorSvg() || arrowFont.face.hasColorPng()
                }.getOrDefault(false)
                assertTrue(hasColor, "U+2194 U+FE0F must pick the color emoji font, not a mono cover")
            } finally {
                stack.close()
            }
        } finally {
            arabic.close()
            arabicFace.close()
        }
    }

    /** First available font (bundled or macOS system) with a mono U+2194 glyph. */
    private suspend fun findMonoArrowFont(): File? {
        val candidates = listOf(
            robotoFile,
            File("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
            File("/System/Library/Fonts/Apple Symbols.ttf"),
        )
        for (candidate in candidates) {
            if (!candidate.exists()) continue
            val face = runCatching { HbFace.from { bytes(candidate.readBytes()) } }.getOrNull() ?: continue
            val covers = runCatching {
                val font = face.toFont()
                val gid = font.glyphIdForCodepoint(0x2194)
                font.close()
                gid != 0
            }.getOrDefault(false)
            face.close()
            if (covers) return candidate
        }
        return null
    }
}
