package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.SpanStyle
import com.mohamedrejeb.harfbuzz.compose.StyleRange
import com.mohamedrejeb.harfbuzz.compose.StyledText
import com.mohamedrejeb.harfbuzz.compose.buildStyledText
import com.mohamedrejeb.harfbuzz.compose.withTashkeelColor
import com.mohamedrejeb.harfbuzz.core.HbFace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Spans-using screenshot coverage for [ShapedText] / [StyledText].
 * Each test exercises one feature of the styled draw path so a
 * regression in one case does not silently take the others down.
 */
class StyledTextScreenshotTest {

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
        openFonts.add(font)
        openFonts.add(face)
        SizedFont(font, sizePx)
    }

    @Test
    fun `tashkeel red over black base`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 40f)
        val text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"
        rule.captureGolden("styled_tashkeel_red.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(
                    text = StyledText(text).withTashkeelColor(Color.Red),
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `multi color hello world`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 40f)
        val text = "Hello World"
        val styled = buildStyledText(text) {
            addStyle(SpanStyle(color = Color.Red), 0, 5)   // Hello
            addStyle(SpanStyle(color = Color.Blue), 6, 11) // World
        }
        rule.captureGolden("styled_multi_color_hello_world.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(text = styled, font = font, sizePx = sizePx, color = Color.Black)
            }
        }
    }

    @Test
    fun `single word linear gradient brush`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 40f)
        val text = "Hello World"
        val brush = Brush.linearGradient(listOf(Color.Magenta, Color.Cyan))
        val styled = StyledText(
            text = text,
            spans = listOf(StyleRange(6, 11, SpanStyle(brush = brush))),
        )
        rule.captureGolden("styled_word_brush.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(text = styled, font = font, sizePx = sizePx, color = Color.Black)
            }
        }
    }

    @Test
    fun `lam alef ligature falls back to leading codepoint style`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 48f)
        // LAM (U+0644) followed by ALEF (U+0627) shapes as one ligature glyph.
        // Style on ALEF (index 1) only - per the cluster-position heuristic
        // for ligatures, the glyph paints with the LEADING codepoint's
        // style, which has no override -> default (black) wins.
        val text = "لا"
        val styled = StyledText(
            text = text,
            spans = listOf(StyleRange(1, 2, SpanStyle(color = Color.Red))),
        )
        rule.captureGolden("styled_lam_alef_ligature.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(text = styled, font = font, sizePx = sizePx, color = Color.Black)
            }
        }
    }
}
