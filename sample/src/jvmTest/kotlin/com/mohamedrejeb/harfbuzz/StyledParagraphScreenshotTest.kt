package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.SpanStyle
import com.mohamedrejeb.harfbuzz.compose.StyleRange
import com.mohamedrejeb.harfbuzz.compose.StyledText
import com.mohamedrejeb.harfbuzz.compose.paragraph.ShapedParagraphText
import com.mohamedrejeb.harfbuzz.compose.withTashkeelColor
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Multi-line styled-paragraph screenshot coverage:
 *
 *  - Empty-spans paragraph: byte-identical to the existing String
 *    overload golden (regression guard - the styled path takes a
 *    different code route through `drawShapedParagraph(... styledText)`
 *    and per-line span slicing, so it must produce the same bytes when
 *    no spans are set).
 *  - Span that straddles a line break: paint must apply on both visual
 *    lines, slicing the paragraph-level range into per-line ranges.
 *  - Justified Arabic with tashkeel: KashidaTo widens each line via
 *    Kashida insertion, and the per-line span resolver has to translate
 *    original-text indices through the justification mapping so the
 *    tashkeel still paints red after the line widens.
 */
class StyledParagraphScreenshotTest {

    @get:Rule val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    private fun loadFont(path: String, sizePx: Float): SizedFont = runBlocking {
        val bytes = readFontBytes(path)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont()
        openFonts.add(font); openFonts.add(face)
        SizedFont(font, sizePx)
    }

    private val latinPara = "The quick brown fox jumps over the lazy dog. " +
        "Sphinx of black quartz judge my vow. Pack my box with five dozen liquor jugs."

    @Test
    fun `paragraph string overload renders golden`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("styled_paragraph_regression.png") {
            Box(Modifier.padding(16.dp).width(360.dp)) {
                ShapedParagraphText(text = latinPara, font = font, sizePx = sizePx, color = Color.Black)
            }
        }
    }

    @Test
    fun `paragraph styled empty spans matches string golden`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("styled_paragraph_regression.png") {
            Box(Modifier.padding(16.dp).width(360.dp)) {
                ShapedParagraphText(
                    text = StyledText(latinPara),
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `paragraph span crosses line break`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        // The span covers a slice that straddles where the layout
        // wraps at width=360dp. Both lines should show red on the
        // covered chars and black elsewhere.
        val styled = StyledText(
            text = latinPara,
            spans = listOf(StyleRange(35, 70, SpanStyle(color = Color.Red))),
        )
        rule.captureGolden("styled_paragraph_span_across_line.png") {
            Box(Modifier.padding(16.dp).width(360.dp)) {
                ShapedParagraphText(text = styled, font = font, sizePx = sizePx, color = Color.Black)
            }
        }
    }

    @Test
    fun `paragraph justified arabic tashkeel coloring`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        val arabic = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ. " +
            "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ. الرَّحْمَٰنِ الرَّحِيمِ."
        val styled = StyledText(arabic).withTashkeelColor(Color.Red)
        rule.captureGolden("styled_paragraph_justified_arabic_tashkeel.png") {
            Box(Modifier.padding(16.dp).width(420.dp)) {
                ShapedParagraphText(
                    text = styled,
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Justify,
                    justification = JustificationStrategy.KashidaTo(1.0f),
                )
            }
        }
    }
}
