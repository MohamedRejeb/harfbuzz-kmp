package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.core.HbFace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shadow's own opacity lives in `shadow.color`'s alpha channel. A
 * 50 %-opacity black shadow over a white background must therefore render
 * mid-gray (~128), not solid black. Regression test for the shadow paint
 * overwriting that alpha with the pass alpha, which made every shadow
 * fully opaque regardless of the user's opacity setting.
 */
class ShadowOpacityTest {

    @get:Rule
    val rule = composeRule()

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
    fun `shadow honours its color alpha`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.setContent {
            // Fixed size + white fill so every captured pixel is opaque
            // white except where the shadow darkens it.
            Box(
                Modifier
                    .width(320.dp)
                    .height(120.dp)
                    .background(Color.White)
                    .padding(16.dp),
            ) {
                ShapedText(
                    text = "Shadow",
                    font = font,
                    sizePx = sizePx,
                    // Transparent fill isolates the shadow; blur 0 keeps a
                    // solid interior to sample.
                    color = Color.Transparent,
                    shadow = Shadow(
                        color = Color(0x80000000),
                        offset = Offset(0f, 0f),
                        blurRadius = 0f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
        repeat(50) {
            Thread.sleep(50)
            rule.waitForIdle()
        }

        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var darkest = 255
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val r = (pixels[x, y].red * 255f).toInt()
                if (r < darkest) darkest = r
            }
        }

        // 50 % black over white = ~128. The bug forced alpha to 1 -> ~0.
        // A missing shadow would leave darkest at 255; all three are
        // distinguished by this range.
        assertTrue(
            "Darkest shadow pixel red=$darkest; expected mid-gray (~128). " +
                "Near-black means the shadow ignored its opacity.",
            darkest in 90..170,
        )
    }
}
