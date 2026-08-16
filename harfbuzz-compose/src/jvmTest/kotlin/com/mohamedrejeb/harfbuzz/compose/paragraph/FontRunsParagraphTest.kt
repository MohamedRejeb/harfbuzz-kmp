package com.mohamedrejeb.harfbuzz.compose.paragraph

import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredText
import com.mohamedrejeb.harfbuzz.compose.clearMeasuredTextCacheForTest
import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import com.mohamedrejeb.harfbuzz.core.sliceFontRuns
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Authored font runs on [buildMeasuredParagraph]: per-line slicing in
 * line-local coordinates (including a run spanning a soft wrap),
 * per-line mixed-font metrics, and cache-key discipline for the
 * per-line measured texts.
 */
class FontRunsParagraphTest {

    @Test
    fun `run spanning a line break is sliced to each line in local coordinates`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withFonts { noto, saudi ->
            val text = "مرحبا بالعالم الواسع الجميل"
            val stack = HbFontStack(noto)
            // Wrap after roughly two words so the authored range (words 2..3)
            // spans the soft break.
            val firstTwo = buildMeasuredText(
                "مرحبا بالعالم", stack, 32f, emptyList(), HbDirection.RTL, HbLanguage.AUTO,
            ).advance
            val width = firstTwo + 10f
            val runStart = text.indexOf("بالعالم")
            val runEnd = text.indexOf("الجميل") - 1
            val paragraphRuns = listOf(FontRun(runStart, runEnd, saudi))
            val paragraph = buildMeasuredParagraph(
                text = text,
                fontStack = stack,
                sizePx = 32f,
                maxWidth = width,
                alignment = ParagraphAlignment.Start,
                direction = HbDirection.RTL,
                features = emptyList(),
                language = HbLanguage.AUTO,
                lineSpacing = 0f,
                justification = JustificationStrategy.None,
                fontRuns = paragraphRuns,
            )
            assertTrue(paragraph.lines.size >= 2, "expected a wrap, got ${paragraph.lines.size} line(s)")

            var linesTouchingRun = 0
            for (line in paragraph.lines) {
                val lineStart = line.charRange.first
                val localRuns = sliceFontRuns(paragraphRuns, lineStart, line.charRange.last + 1)
                if (localRuns.isNotEmpty()) linesTouchingRun++
                fun expectAuthored(localCluster: Int): Boolean =
                    localRuns.any { localCluster >= it.start && localCluster < it.end }
                for (run in line.measured.paragraph.runs) {
                    for (g in run.glyphs) {
                        val expected = if (expectAuthored(g.cluster)) saudi else noto
                        assertEquals(
                            expected, run.font,
                            "line [$lineStart..${line.charRange.last}] cluster ${g.cluster}",
                        )
                    }
                }
            }
            assertTrue(linesTouchingRun >= 2, "the authored run must span the line break")

            // A line mixing both fonts carries mixed-font effective metrics.
            val mixedLine = paragraph.lines.firstOrNull { line ->
                line.measured.paragraph.runs.any { it.font == saudi } &&
                    line.measured.paragraph.runs.any { it.font == noto }
            }
            if (mixedLine != null) {
                assertTrue(mixedLine.measured.maxAscent >= mixedLine.measured.ascent)
                assertTrue(mixedLine.measured.maxDescent >= mixedLine.measured.descent)
            }
        }
    }

    @Test
    fun `per-line measured texts separate cache entries by run signature`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withFonts { noto, saudi ->
            val text = "مرحبا بالعالم الواسع الجميل"
            val stack = HbFontStack(noto)
            val runs = listOf(FontRun(0, 5, saudi))
            val plain = buildParagraph(text, stack, emptyList())
            val authored = buildParagraph(text, stack, runs)
            assertEquals(plain.lines.size, authored.lines.size)
            for ((p, a) in plain.lines.zip(authored.lines)) {
                if (a.charRange.first < 5) {
                    assertNotSame(p.measured, a.measured, "line at ${a.charRange} must not share the base-font shape")
                }
            }
            // Same inputs again: per-line shapes come from the cache.
            val again = buildParagraph(text, stack, runs)
            for ((a, b) in authored.lines.zip(again.lines)) {
                assertSame(a.measured, b.measured, "line at ${a.charRange} should be a cache hit")
            }
        }
    }

    private suspend fun buildParagraph(
        text: String,
        stack: HbFontStack,
        runs: List<FontRun>,
    ): MeasuredParagraph = buildMeasuredParagraph(
        text = text,
        fontStack = stack,
        sizePx = 32f,
        maxWidth = 240f,
        alignment = ParagraphAlignment.Start,
        direction = HbDirection.RTL,
        features = emptyList(),
        language = HbLanguage.AUTO,
        lineSpacing = 0f,
        justification = JustificationStrategy.None,
        fontRuns = runs,
    )

    private suspend fun withFonts(block: suspend (noto: HbFont, saudi: HbFont) -> Unit) {
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val saudiFace = HbFace.fromBytes(TestFonts.saudiRegular())
        val noto = notoFace.toFont()
        val saudi = saudiFace.toFont()
        try {
            block(noto, saudi)
        } finally {
            noto.close()
            saudi.close()
            notoFace.close()
            saudiFace.close()
        }
    }
}
