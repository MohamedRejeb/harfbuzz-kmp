package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * Pixel-level coverage for authored font runs (plain JVM skiko): the
 * authored font actually changes flat rendering, mixed LTR/RTL lines
 * paint both scripts, color spans and tashkeel helpers compose with
 * authored runs, and the warped arc drawers consult per-run font caches
 * on mixed-font shapes.
 */
class FontRunsRenderTest {

    private val canvasW = 420
    private val canvasH = 200
    private val textOrigin = Offset(10f, 60f)

    @Test
    fun `authored run changes flat rendering`() = runBlocking {
        withFonts { noto, saudi, _ ->
            val text = "مرحبا بالعالم"
            val stack = HbFontStack(noto)
            val plain = buildText(text, stack, HbDirection.RTL)
            val authored = buildText(text, stack, HbDirection.RTL, listOf(FontRun(0, 5, saudi)))

            val plainPng = renderPng { drawShapedText(plain, topLeft = textOrigin, color = Color.Black) }
            val authoredPng = renderPng { drawShapedText(authored, topLeft = textOrigin, color = Color.Black) }
            assertTrue(hasInk(renderBitmap { drawShapedText(authored, topLeft = textOrigin, color = Color.Black) }, 0, canvasW))
            assertFalse(plainPng.contentEquals(authoredPng), "authored font must change the rendered pixels")
        }
    }

    @Test
    fun `mixed ltr rtl line with authored run renders ink for both scripts`() = runBlocking {
        withFonts { noto, saudi, roboto ->
            val text = "Hello مرحبا"
            val arabicStart = text.indexOf('م')
            val stack = HbFontStack(roboto, listOf(noto))
            val measured = buildText(text, stack, HbDirection.AUTO, listOf(FontRun(arabicStart, text.length, saudi)))

            val bitmap = renderBitmap { drawShapedText(measured, topLeft = textOrigin, color = Color.Black) }
            // LTR base: Latin runs paint first, Arabic (authored) runs after.
            // Split the canvas at the Latin runs' total advance.
            val latinWidth = measured.paragraph.runs
                .filter { it.font != saudi }
                .fold(0f) { acc, r -> acc + r.totalAdvance }
            val splitX = (textOrigin.x + latinWidth).toInt()
            assertTrue(hasInk(bitmap, 0, splitX - 4), "Latin half must have ink")
            assertTrue(hasInk(bitmap, splitX + 2, canvasW), "Arabic half must have ink")
        }
    }

    @Test
    fun `color spans and tashkeel helpers apply on an authored mixed-font shape`() = runBlocking {
        withFonts { noto, saudi, _ ->
            val text = "مَرْحَبًا"
            val stack = HbFontStack(noto)
            val measured = buildText(text, stack, HbDirection.RTL, listOf(FontRun(0, 4, saudi)))
            val styled = StyledText(text).withTashkeelColor(Color.Red)

            val plainPng = renderPng { drawShapedText(measured, topLeft = textOrigin, color = Color.Black) }
            val styledPng = renderPng {
                drawShapedText(measured, styledText = styled, topLeft = textOrigin, color = Color.Black)
            }
            assertFalse(plainPng.contentEquals(styledPng), "tashkeel color must change the rendered pixels")

            val bitmap = renderBitmap {
                drawShapedText(measured, styledText = styled, topLeft = textOrigin, color = Color.Black)
            }
            assertTrue(hasRedInk(bitmap), "tashkeel marks must render red on the mixed-font shape")
        }
    }

    @Test
    fun `arc drawing with mixed fonts consults per-run font caches`() = runBlocking {
        withFonts { noto, saudi, _ ->
            val text = "مرحبا بالعالم"
            val stack = HbFontStack(noto)
            val plain = buildText(text, stack, HbDirection.RTL)
            val authored = buildText(text, stack, HbDirection.RTL, listOf(FontRun(0, 5, saudi)))
            val center = Offset(canvasW / 2f, canvasH.toFloat())
            val radius = 120f

            fun arcPng(m: MeasuredText): ByteArray = renderPng {
                drawArcTextLayered(
                    measured = m,
                    center = center,
                    radiusPx = radius,
                    startAngleDeg = 180f,
                    sweepAngleDeg = 180f,
                    fillColor = Color.Black,
                )
            }

            fun arcBitmap(m: MeasuredText): Bitmap = renderBitmap {
                drawArcTextLayered(
                    measured = m,
                    center = center,
                    radiusPx = radius,
                    startAngleDeg = 180f,
                    sweepAngleDeg = 180f,
                    fillColor = Color.Black,
                )
            }

            assertTrue(hasInk(arcBitmap(plain), 0, canvasW), "plain arc must render ink")
            assertTrue(hasInk(arcBitmap(authored), 0, canvasW), "authored arc must render ink")
            assertFalse(
                arcPng(plain).contentEquals(arcPng(authored)),
                "authored font must change the warped arc pixels, proving per-run outline lookup",
            )
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────

    private suspend fun buildText(
        text: String,
        stack: HbFontStack,
        direction: HbDirection,
        fontRuns: List<FontRun> = emptyList(),
    ): MeasuredText {
        clearMeasuredTextCacheForTest()
        return buildMeasuredText(text, stack, 44f, emptyList(), direction, HbLanguage.AUTO, fontRuns)
    }

    private fun renderPng(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ByteArray =
        renderSurface(block).makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG encode failed")

    private fun renderBitmap(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): Bitmap {
        val surface = renderSurface(block)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(canvasW, canvasH))
        check(surface.readPixels(bitmap, 0, 0)) { "readPixels failed" }
        return bitmap
    }

    private fun renderSurface(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): Surface {
        val surface = Surface.makeRasterN32Premul(canvasW, canvasH)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = surface.canvas.asComposeCanvas(),
            size = Size(canvasW.toFloat(), canvasH.toFloat()),
        ) {
            drawRect(Color.White, size = Size(canvasW.toFloat(), canvasH.toFloat()))
            block()
        }
        return surface
    }

    /** True when any pixel in columns `[fromX, toX)` is not pure white. */
    private fun hasInk(bitmap: Bitmap, fromX: Int, toX: Int): Boolean {
        for (x in fromX.coerceAtLeast(0) until toX.coerceAtMost(canvasW)) {
            for (y in 0 until canvasH) {
                if (bitmap.getColor(x, y) != WHITE) return true
            }
        }
        return false
    }

    /** True when any pixel is clearly red-dominant (tashkeel span paint). */
    private fun hasRedInk(bitmap: Bitmap): Boolean {
        for (x in 0 until canvasW) {
            for (y in 0 until canvasH) {
                val c = bitmap.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 150 && r - g > 60 && r - b > 60) return true
            }
        }
        return false
    }

    private suspend fun withFonts(block: suspend (noto: HbFont, saudi: HbFont, roboto: HbFont) -> Unit) {
        harfBuzzInit()
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val saudiFace = HbFace.fromBytes(TestFonts.saudiRegular())
        val robotoFace = HbFace.fromBytes(TestFonts.robotoRegular())
        val noto = notoFace.toFont()
        val saudi = saudiFace.toFont()
        val roboto = robotoFace.toFont()
        try {
            block(noto, saudi, roboto)
        } finally {
            noto.close()
            saudi.close()
            roboto.close()
            notoFace.close()
            saudiFace.close()
            robotoFace.close()
        }
    }

    private companion object {
        const val WHITE = -1 // 0xFFFFFFFF as signed Int ARGB
    }
}
