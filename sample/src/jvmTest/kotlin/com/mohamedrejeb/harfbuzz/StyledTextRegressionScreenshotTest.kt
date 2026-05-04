package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.StyledText
import com.mohamedrejeb.harfbuzz.core.HbFace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Regression coverage: rendering with [StyledText] of empty spans
 * must be byte-identical to the existing String overload of
 * [ShapedText].
 */
class StyledTextRegressionScreenshotTest {

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

    private val arabicSample = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"

    @Test
    fun `string overload renders golden`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 36f)
        rule.captureGolden("styled_regression_arabic.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(text = arabicSample, font = font, sizePx = sizePx, color = Color.Black)
            }
        }
    }

    @Test
    fun `styled text empty spans matches string golden`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 36f)
        // Same golden filename as the test above - the styled path
        // with empty spans MUST produce the same bytes.
        rule.captureGolden("styled_regression_arabic.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(
                    text = StyledText(arabicSample),
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }
}
