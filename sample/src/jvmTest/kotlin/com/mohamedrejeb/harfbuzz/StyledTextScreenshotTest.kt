package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.StyledText
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
}
