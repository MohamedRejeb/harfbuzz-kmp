package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.drawShapedTextWithImageFill
import com.mohamedrejeb.harfbuzz.compose.paragraph.MeasuredParagraph
import com.mohamedrejeb.harfbuzz.compose.paragraph.MeasuredParagraphLoad
import com.mohamedrejeb.harfbuzz.compose.paragraph.drawShapedParagraphWithImageFill
import com.mohamedrejeb.harfbuzz.compose.paragraph.rememberMeasuredParagraph
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Phase 5 image-fill coverage. Each test renders a colour-banded
 * [ImageBitmap] masked through a shaped line so the image is visible
 * only inside glyph ink. Pins:
 *  - Latin: silhouette path of a Latin line clips the image cleanly.
 *  - Arabic RTL: cursive glyph silhouettes mask the same image,
 *    confirming the path accumulator handles RTL pen advance.
 *  - Aref Ruqaa Ink (COLR v1 with base outlines): the silhouette
 *    overrides the font's designed inks, so the image fills the
 *    underlying letter shapes rather than the COLR paint tree.
 */
class ShapedTextImageFillScreenshotTest {

    @get:Rule
    val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    private fun loadFont(path: String, sizePx: Float): HbFont = runBlocking {
        val bytes = readFontBytes(path)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont(sizePx)
        openFonts.add(font)
        openFonts.add(face)
        font
    }

    @Test
    fun `latin image fill horizontal bands`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 64f)
        val image = makeBandedImage()
        rule.captureGolden("shaped_image_fill_latin_bands.png") {
            Stage {
                ImageFilledShapedText(
                    text = "GRADIENT",
                    font = font,
                    image = image,
                    height = 80.dp,
                )
            }
        }
    }

    @Test
    fun `arabic image fill horizontal bands`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 64f)
        val image = makeBandedImage()
        rule.captureGolden("shaped_image_fill_arabic_bands.png") {
            Stage {
                ImageFilledShapedText(
                    text = "نص عربي",
                    font = font,
                    image = image,
                    height = 96.dp,
                )
            }
        }
    }

    @Test
    fun `multi line paragraph image fill across wrap`() {
        // The paragraph wraps to several lines; the silhouette path
        // accumulates each line's outlines translated by (xOffset,
        // top). The colour bands should run across every line so a
        // regression in per-line offset (forgetting to translate, or
        // mixing baseline with top) shows up immediately.
        val font = loadFont(FontPath.LATIN_REGULAR, 28f)
        val image = makeBandedImage(width = 600, height = 240)
        rule.captureGolden("paragraph_image_fill_latin_wrap.png") {
            Stage {
                ImageFilledShapedParagraph(
                    text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor.",
                    font = font,
                    image = image,
                    boxWidth = 280.dp,
                    boxHeight = 160.dp,
                )
            }
        }
    }

    @Test
    fun `aref ruqaa ink image fill replaces designed colors`() {
        // Aref Ruqaa Ink ships COLR v1 paint trees AND base outline
        // silhouettes. The image-fill helper clips through the
        // silhouette only, so the result swaps the font's gradient
        // inks for the supplied image - good visual proof that the
        // mask is the silhouette, not the paint tree.
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 56f)
        val image = makeBandedImage()
        rule.captureGolden("shaped_image_fill_aref_ruqaa_ink.png") {
            Stage {
                ImageFilledShapedText(
                    text = "نص عربي",
                    font = font,
                    image = image,
                    height = 96.dp,
                )
            }
        }
    }

    @Composable
    private fun ImageFilledShapedText(
        text: String,
        font: HbFont,
        image: ImageBitmap,
        height: androidx.compose.ui.unit.Dp,
    ) {
        val loadState by rememberMeasuredText(text = text, font = font)
        val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .drawBehind {
                    val m = measured ?: return@drawBehind
                    if (m.isEmpty) return@drawBehind
                    // Stretch the image to the line's logical box so
                    // the colour bands span every glyph rather than
                    // tiling at the image's native size.
                    val dstSize = IntSize(
                        ceil(m.advance).toInt().coerceAtLeast(1),
                        ceil(m.lineHeight).toInt().coerceAtLeast(1),
                    )
                    drawShapedTextWithImageFill(
                        measured = m,
                        image = image,
                        dstSize = dstSize,
                    )
                },
        )
    }

    @Composable
    private fun ImageFilledShapedParagraph(
        text: String,
        font: HbFont,
        image: ImageBitmap,
        boxWidth: androidx.compose.ui.unit.Dp,
        boxHeight: androidx.compose.ui.unit.Dp,
    ) {
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { boxWidth.toPx() }
        val loadState by rememberMeasuredParagraph(
            text = text,
            font = font,
            maxWidth = widthPx,
            alignment = ParagraphAlignment.Start,
        )
        val paragraph: MeasuredParagraph? =
            (loadState as? MeasuredParagraphLoad.Ready)?.measured
        Box(
            modifier = Modifier
                .width(boxWidth)
                .height(boxHeight)
                .drawBehind {
                    val p = paragraph ?: return@drawBehind
                    if (p.isEmpty) return@drawBehind
                    // Stretch the banded image to span the
                    // paragraph's full logical box - colour bands
                    // then run across every line, making per-line
                    // regressions easy to spot.
                    val dstSize = IntSize(
                        ceil(p.width).toInt().coerceAtLeast(1),
                        ceil(p.height).toInt().coerceAtLeast(1),
                    )
                    drawShapedParagraphWithImageFill(
                        paragraph = p,
                        image = image,
                        dstSize = dstSize,
                    )
                },
        )
    }

    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .background(Color.White)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }

    /**
     * 600x80 pink → purple → blue band image. Three solid horizontal
     * bands keep the golden simple to read (a smooth gradient blends
     * differences between bands and makes regressions harder to spot
     * by eye).
     */
    private fun makeBandedImage(width: Int = 600, height: Int = 80): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val bandH = height / 3f
        val w = width.toFloat()
        canvas.drawRect(
            Rect(0f, 0f, w, bandH),
            Paint().apply { color = Color(0xFFE91E63) },
        )
        canvas.drawRect(
            Rect(0f, bandH, w, bandH * 2f),
            Paint().apply { color = Color(0xFF673AB7) },
        )
        canvas.drawRect(
            Rect(0f, bandH * 2f, w, height.toFloat()),
            Paint().apply { color = Color(0xFF2196F3) },
        )
        return bitmap
    }
}
