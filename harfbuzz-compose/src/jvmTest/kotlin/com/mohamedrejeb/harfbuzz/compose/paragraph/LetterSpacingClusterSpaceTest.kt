package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.compose.SpanStyle
import com.mohamedrejeb.harfbuzz.compose.StyleRange
import com.mohamedrejeb.harfbuzz.compose.StyledText
import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.compose.clearMeasuredTextCacheForTest
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * Letter-spaced Arabic paragraph lines widen via Kashida insertion; the
 * line's reported text, char mapping, and the measured shape's cluster
 * space must stay consistent (the same bookkeeping the justification
 * path uses). Regression coverage for cluster ids escaping the reported
 * text length, which silently dropped per-range styling on trailing
 * glyphs.
 */
class LetterSpacingClusterSpaceTest {

    private val text = "السلام عليكم ورحمة الله وبركاته"

    @Test
    fun `cluster ids never exceed the reported text length`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNoto { noto ->
            val paragraph = buildParagraph(noto)
            assertTrue(paragraph.lines.size > 1, "repro needs a wrapped paragraph")
            var sawWidenedLine = false
            for (line in paragraph.lines) {
                assertEquals(
                    line.layout.text.length,
                    line.measured.textLength,
                    "line [${line.charRange}] text/measured length mismatch: " +
                        "text='${line.layout.text}'",
                )
                if (line.originalToJustifiedIndex != null) sawWidenedLine = true
                for (run in line.measured.paragraph.runs) {
                    for (g in run.glyphs) {
                        assertTrue(
                            g.cluster in 0 until line.measured.textLength,
                            "line [${line.charRange}] cluster ${g.cluster} outside " +
                                "[0, ${line.measured.textLength}) for text '${line.layout.text}'",
                        )
                    }
                }
            }
            assertTrue(
                sawWidenedLine,
                "letter spacing on Arabic must widen at least one line via insertion " +
                    "(otherwise this test no longer exercises the insertion path)",
            )
        }
    }

    @Test
    fun `full-coverage span colors every glyph of a letter-spaced line`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNoto { noto ->
            val paragraph = buildParagraph(noto)
            val red = Color(0xFFCC0000)
            val styled = StyledText(
                text = text,
                spans = listOf(StyleRange(0, text.length, SpanStyle(color = red))),
            )
            val w = 360
            val h = 420
            val surface = Surface.makeRasterN32Premul(w, h)
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = surface.canvas.asComposeCanvas(),
                size = Size(w.toFloat(), h.toFloat()),
            ) {
                drawRect(Color.White, size = Size(w.toFloat(), h.toFloat()))
                drawShapedParagraph(
                    paragraph = paragraph,
                    styledText = styled,
                    topLeft = Offset(20f, 20f),
                    color = Color.Black,
                )
            }
            val bitmap = Bitmap()
            bitmap.allocPixels(ImageInfo.makeN32Premul(w, h))
            check(surface.readPixels(bitmap, 0, 0)) { "readPixels failed" }

            var inkPixels = 0
            var blackInk = 0
            for (x in 0 until w) {
                for (y in 0 until h) {
                    val c = bitmap.getColor(x, y)
                    if (c == WHITE) continue
                    inkPixels++
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    // A glyph that missed the span renders in the base black:
                    // dark AND not red-dominant.
                    if (r < 100 && g < 100 && b < 100) blackInk++
                }
            }
            assertTrue(inkPixels > 0, "paragraph must render ink")
            assertFalse(
                blackInk > 0,
                "$blackInk base-color pixels out of $inkPixels ink pixels: " +
                    "some glyphs missed the full-coverage span",
            )
        }
    }

    private suspend fun buildParagraph(noto: HbFont): MeasuredParagraph =
        buildMeasuredParagraph(
            text = text,
            fontStack = HbFontStack(noto),
            sizePx = 48f,
            maxWidth = 300f,
            alignment = ParagraphAlignment.Start,
            direction = HbDirection.RTL,
            features = emptyList(),
            language = HbLanguage.AUTO,
            lineSpacing = 0f,
            justification = JustificationStrategy.None,
            letterSpacing = 14f,
        )

    private suspend fun withNoto(block: suspend (noto: HbFont) -> Unit) {
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val noto = notoFace.toFont()
        try {
            block(noto)
        } finally {
            noto.close()
            notoFace.close()
        }
    }

    private companion object {
        const val WHITE = -1 // 0xFFFFFFFF as signed Int ARGB
    }
}
