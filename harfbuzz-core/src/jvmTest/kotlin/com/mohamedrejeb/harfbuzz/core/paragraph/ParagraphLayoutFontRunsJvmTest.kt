package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.TestFonts
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Authored font runs in [layoutParagraph]: per-run advances drive the
 * greedy line fit, justified lines keep connectors under the authored
 * font, and the empty-runs call stays identical to the legacy path.
 */
class ParagraphLayoutFontRunsJvmTest {

    @Test
    fun `run in a wider font changes the wrap point and matches the hand-computed break`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, _, roboto ->
            val text = "aaaa bbbb cccc dddd"
            val stack = HbFontStack(roboto, listOf(noto))
            val authoredRun = listOf(FontRun(5, 9, noto))

            // Hand-compute: pick a width strictly between the base-font
            // advance of "aaaa bbbb cccc" and the authored advance of the
            // same prefix, so the two layouts must break differently.
            val basePrefix = stack.shapeParagraph("aaaa bbbb cccc", 32f).totalAdvance
            val authoredPrefix = stack.shapeParagraph(
                "aaaa bbbb cccc", 32f, fontRuns = authoredRun,
            ).totalAdvance
            val lo = minOf(basePrefix, authoredPrefix)
            val hi = maxOf(basePrefix, authoredPrefix)
            assertTrue(hi - lo > 1f, "fonts too similar for the test: $lo vs $hi")
            val width = (lo + hi) / 2f

            val baseLayout = stack.layoutParagraph(text, 32f, maxWidth = width)
            val authoredLayout = stack.layoutParagraph(text, 32f, maxWidth = width, fontRuns = authoredRun)

            val baseFirstLineEnd = baseLayout.lines.first().charRange.last + 1
            val authoredFirstLineEnd = authoredLayout.lines.first().charRange.last + 1
            // The layout whose prefix fits keeps three words; the other
            // wraps after two. Which is which depends on which font is wider.
            val (fitsEnd, overflowEnd) = if (basePrefix < authoredPrefix) {
                baseFirstLineEnd to authoredFirstLineEnd
            } else {
                authoredFirstLineEnd to baseFirstLineEnd
            }
            assertEquals(15, fitsEnd, "prefix that fits keeps three words plus the trailing space")
            assertEquals(10, overflowEnd, "wider prefix wraps after two words")
        }
    }

    @Test
    fun `justified paragraph line keeps connectors under the authored font`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, notoB, _ ->
            val text = "مرحبا بالعالم الواسع مرحبا بالعالم الواسع"
            val stack = HbFontStack(noto)
            val natural = stack.shapeParagraph("مرحبا بالعالم الواسع", 32f, HbDirection.RTL).totalAdvance
            val width = natural + 60f
            val runs = listOf(FontRun(0, 5, notoB))
            val layout = stack.layoutParagraph(
                text, 32f, maxWidth = width,
                alignment = ParagraphAlignment.Justify,
                baseDirection = HbDirection.RTL,
                justification = JustificationStrategy.Mixed,
                fontRuns = runs,
            )
            assertTrue(layout.lines.size > 1, "need a multi-line paragraph")
            val first = layout.lines.first()
            assertTrue(first.advance <= width + 0.5f, "never overshoots maxWidth: ${first.advance} vs $width")
            assertTrue(first.advance > natural, "justification widened the line: ${first.advance} vs $natural")
            // The authored range's tatweels shape with the authored font.
            val tatweelB = notoB.glyphIdForCodepoint(0x0640)
            val authoredTatweels = first.paragraph.runs
                .filter { it.font == notoB }
                .sumOf { r -> r.glyphs.count { it.glyphId == tatweelB } }
            assertTrue(
                authoredTatweels > 0,
                "expected tatweels in the authored range, runs=" +
                    first.paragraph.runs.map { r -> r.font to r.glyphs.map { it.glyphId } },
            )
        }
    }

    @Test
    fun `empty fontRuns layout is identical to the legacy call`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, _, roboto ->
            val text = "Hello world مرحبا بالعالم wrapping across lines"
            val stack = HbFontStack(roboto, listOf(noto))
            val a = stack.layoutParagraph(text, 28f, maxWidth = 220f)
            val b = stack.layoutParagraph(text, 28f, maxWidth = 220f, fontRuns = emptyList())
            assertEquals(a, b)
        }
    }

    private suspend fun withFonts(block: suspend (noto: HbFont, notoB: HbFont, roboto: HbFont) -> Unit) {
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val robotoFace = HbFace.fromBytes(TestFonts.robotoRegular())
        val noto = notoFace.toFont()
        val notoB = notoFace.toFont()
        val roboto = robotoFace.toFont()
        try {
            block(noto, notoB, roboto)
        } finally {
            noto.close()
            notoB.close()
            roboto.close()
            notoFace.close()
            robotoFace.close()
        }
    }
}
