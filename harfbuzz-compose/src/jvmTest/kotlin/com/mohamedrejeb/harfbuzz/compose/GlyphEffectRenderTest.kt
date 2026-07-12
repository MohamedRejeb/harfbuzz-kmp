package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * Pixel-level contract of the per-glyph effect hook (plain JVM skiko,
 * no Robolectric):
 *
 *  - a null provider and an all-identity provider render byte-identical
 *    output across the fill, brush, stroke, shadow, and styled passes,
 *    so shipping the parameter is a behavioral no-op until callers use
 *    it (the null path also never touches the bucket maps: they are
 *    allocated only under a non-null provider);
 *  - effect alpha at or below zero skips glyphs entirely;
 *  - per-glyph alpha on unified passes (stroke) still renders every
 *    surviving glyph via the quantized-alpha buckets.
 */
class GlyphEffectRenderTest {

    private val canvasW = 260
    private val canvasH = 130
    private val textOrigin = Offset(30f, 30f)

    @Test
    fun `null provider and identity provider are byte identical`() = runBlocking {
        withMeasured("HH") { measured ->
            val identity = GlyphEffectProvider { _, _, _ -> GlyphEffect.NONE }
            val cases = renderCases(measured)
            for ((name, render) in cases) {
                val baseline = render(null)
                val withIdentity = render(identity)
                assertContentEquals(baseline, withIdentity, "case '$name' must be byte-identical")
            }
        }
    }

    @Test
    fun `alpha zero hides every glyph across all passes`() = runBlocking {
        withMeasured("HH") { measured ->
            val hidden = GlyphEffectProvider { _, _, _ -> GlyphEffect(alpha = 0f) }
            val blank = renderPng(measured = null)
            val cases = renderCases(measured)
            for ((name, render) in cases) {
                val output = render(hidden)
                assertContentEquals(blank, output, "case '$name' must render background only")
            }
        }
    }

    @Test
    fun `alpha zero skips a single glyph and leaves the rest untouched`() = runBlocking {
        withMeasured("HH") { measured ->
            val advance0 = measured.paragraph.runs.first().positions.first().xAdvance
            val hideFirst = GlyphEffectProvider { _, glyphIndex, _ ->
                if (glyphIndex == 0) GlyphEffect(alpha = 0f) else GlyphEffect.NONE
            }
            val baseline = renderBitmap(measured, glyphEffects = null)
            val partial = renderBitmap(measured, glyphEffects = hideFirst)

            val firstGlyphRegionEnd = (textOrigin.x + advance0 * 0.7f).toInt()
            assertTrue(
                hasInk(baseline, 0, firstGlyphRegionEnd),
                "baseline must have ink where the first glyph sits",
            )
            assertFalse(
                hasInk(partial, 0, firstGlyphRegionEnd),
                "hidden first glyph must leave its region blank",
            )
            assertTrue(
                hasInk(partial, (textOrigin.x + advance0).toInt(), canvasW),
                "second glyph must still render",
            )
        }
    }

    @Test
    fun `stroke with mixed per-glyph alpha renders both buckets`() = runBlocking {
        withMeasured("HH") { measured ->
            val advance0 = measured.paragraph.runs.first().positions.first().xAdvance
            val fade = GlyphEffectProvider { _, glyphIndex, _ ->
                if (glyphIndex == 0) GlyphEffect(alpha = 0.5f) else GlyphEffect.NONE
            }
            val bitmap = renderBitmap(
                measured = measured,
                style = Stroke(width = 3f),
                glyphEffects = fade,
            )
            assertTrue(
                hasInk(bitmap, 0, (textOrigin.x + advance0 * 0.7f).toInt()),
                "faded glyph's bucket must still stroke",
            )
            assertTrue(
                hasInk(bitmap, (textOrigin.x + advance0).toInt(), canvasW),
                "full-alpha bucket must stroke",
            )
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────

    /** Named render closures covering each hooked pass. */
    private fun renderCases(
        measured: MeasuredText,
    ): List<Pair<String, (GlyphEffectProvider?) -> ByteArray>> {
        val gradient = Brush.horizontalGradient(listOf(Color.Red, Color.Blue))
        val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(3f, 3f), blurRadius = 2f)
        val styled = StyledText(
            text = "HH",
            spans = listOf(StyleRange(0, 1, SpanStyle(color = Color.Red))),
        )
        return listOf(
            "fill" to { p -> renderPng(measured, glyphEffects = p) },
            "fill+shadow" to { p -> renderPng(measured, shadow = shadow, glyphEffects = p) },
            "stroke" to { p -> renderPng(measured, style = Stroke(width = 3f), glyphEffects = p) },
            "stroke+shadow" to { p ->
                renderPng(measured, style = Stroke(width = 3f), shadow = shadow, glyphEffects = p)
            },
            "brush" to { p -> renderPng(measured, brush = gradient, glyphEffects = p) },
            "styled" to { p -> renderPng(measured, styledText = styled, glyphEffects = p) },
        )
    }

    private fun renderPng(
        measured: MeasuredText?,
        styledText: StyledText? = null,
        brush: Brush? = null,
        style: DrawStyle = Fill,
        shadow: Shadow? = null,
        glyphEffects: GlyphEffectProvider? = null,
    ): ByteArray {
        val surface = renderSurface(measured, styledText, brush, style, shadow, glyphEffects)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG encode failed")
    }

    private fun renderBitmap(
        measured: MeasuredText,
        style: DrawStyle = Fill,
        glyphEffects: GlyphEffectProvider?,
    ): Bitmap {
        val surface = renderSurface(measured, null, null, style, null, glyphEffects)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(canvasW, canvasH))
        check(surface.readPixels(bitmap, 0, 0)) { "readPixels failed" }
        return bitmap
    }

    private fun renderSurface(
        measured: MeasuredText?,
        styledText: StyledText?,
        brush: Brush?,
        style: DrawStyle,
        shadow: Shadow?,
        glyphEffects: GlyphEffectProvider?,
    ): Surface {
        val surface = Surface.makeRasterN32Premul(canvasW, canvasH)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = surface.canvas.asComposeCanvas(),
            size = Size(canvasW.toFloat(), canvasH.toFloat()),
        ) {
            drawRect(Color.White, size = Size(canvasW.toFloat(), canvasH.toFloat()))
            if (measured == null) return@draw
            when {
                styledText != null -> drawShapedText(
                    measured = measured,
                    styledText = styledText,
                    topLeft = textOrigin,
                    color = Color.Black,
                    style = style,
                    shadow = shadow,
                    glyphEffects = glyphEffects,
                )
                brush != null -> drawShapedText(
                    measured = measured,
                    brush = brush,
                    topLeft = textOrigin,
                    style = style,
                    shadow = shadow,
                    glyphEffects = glyphEffects,
                )
                else -> drawShapedText(
                    measured = measured,
                    topLeft = textOrigin,
                    color = Color.Black,
                    style = style,
                    shadow = shadow,
                    glyphEffects = glyphEffects,
                )
            }
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

    private suspend fun withMeasured(text: String, block: suspend (MeasuredText) -> Unit) {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val measured = buildMeasuredText(
                text = text,
                fontStack = HbFontStack(font),
                sizePx = 48f,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
            block(measured)
        } finally {
            font.close()
            face.close()
        }
    }

    private companion object {
        const val WHITE = -1 // 0xFFFFFFFF as signed Int ARGB
    }
}
