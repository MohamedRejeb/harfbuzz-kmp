package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredTextJustified
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * Screenshot test for MULTI-LINE JUSTIFY (with and without letter spacing),
 * replicating the app's per-line justify pipeline
 * (`ParagraphLayoutBuilder.buildSnapshotLine` → `buildMeasuredTextJustified` →
 * `computeXOffset` → `planParagraphLines`): build the natural paragraph, take
 * `target = max line advance`, justify every line to it, position each line under
 * Justify alignment, then anchor the block at `-inkOriginX`.
 *
 * The check is a real PIXEL scan, not a geometry assertion: the RTL ink-update
 * bug is self-consistent in `ink` (it shifts `xOffset` and the frame together),
 * so only the actually-rendered glyphs — which sit at pen-local `[0, advance]` —
 * reveal that the line was mispositioned and spills past the frame. PNGs go to
 * the temp dir for visual inspection.
 */
class JustifyScreenshotTest {

    private val outDir = File(System.getProperty("java.io.tmpdir"), "harfbuzz-justify").apply { mkdirs() }
    private val handles = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        handles.reversed().forEach { it.close() }
        handles.clear()
    }

    private suspend fun font(bytes: ByteArray): HbFont {
        val face = HbFace.fromBytes(bytes)
        val f = face.toFont()
        handles.add(f)
        handles.add(face)
        return f
    }

    @Test
    fun justify_multiline_stays_in_bounds() = runBlocking {
        harfBuzzInit()
        val roboto = font(TestFonts.robotoRegular())
        val noto = font(TestFonts.notoNaskhArabicMedium())
        val saudi = font(TestFonts.saudiRegular())

        // Latin (LTR) — sanity baseline.
        check("latin-roboto-ls0", LATIN, HbFontStack(roboto), 28f, 0f, HbDirection.LTR)
        // Arabic with a real Arabic primary font.
        check("arabic-noto-ls0", ARABIC, HbFontStack(noto), 34f, 0f, HbDirection.RTL)
        check("arabic-noto-ls8", ARABIC, HbFontStack(noto), 34f, 8f, HbDirection.RTL)
        check("arabic-saudi-ls0", ARABIC, HbFontStack(saudi), 34f, 0f, HbDirection.RTL)
        check("arabic-saudi-ls8", ARABIC, HbFontStack(saudi), 34f, 8f, HbDirection.RTL)
        // Arabic via the FALLBACK path (Latin primary, Arabic fallback) — the
        // case the bug was most visible on.
        check("arabic-fallback-ls0", ARABIC, HbFontStack(roboto, listOf(noto)), 34f, 0f, HbDirection.RTL)
        check("arabic-fallback-ls8", ARABIC, HbFontStack(roboto, listOf(noto)), 34f, 8f, HbDirection.RTL)
        println("[JustifyScreenshotTest] wrote PNGs to ${outDir.absolutePath}")
    }

    private suspend fun check(
        name: String,
        text: String,
        stack: HbFontStack,
        sizePx: Float,
        letterSpacing: Float,
        direction: HbDirection,
    ) {
        val boxW = 380f
        val margin = 30f

        // 1) Natural paragraph (line-break only) — exactly what the app builds.
        val natural = buildMeasuredParagraph(
            text = text,
            fontStack = stack,
            sizePx = sizePx,
            maxWidth = boxW,
            alignment = ParagraphAlignment.Justify,
            direction = direction,
            features = emptyList(),
            language = HbLanguage.AUTO,
            lineSpacing = 0f,
            justification = JustificationStrategy.None,
            letterSpacing = letterSpacing,
        )
        assertTrue(natural.lineCount >= 2, "$name: fixture must be multi-line; got ${natural.lineCount}")

        // 2) target = widest natural line advance (app's `target`).
        val target = natural.lines.maxOf { it.advance }

        // 3) Justify every line to `target`, position under Justify alignment.
        data class Placed(val measured: MeasuredText, val xOffset: Float)
        val placed = natural.lines.mapNotNull { line ->
            val t = line.layout.text.trim()
            if (t.isEmpty()) return@mapNotNull null
            val measured = buildMeasuredTextJustified(
                text = t,
                fontStack = stack,
                sizePx = sizePx,
                features = emptyList(),
                direction = direction,
                language = HbLanguage.AUTO,
                targetWidthPx = target,
            )
            if (measured.isEmpty) return@mapNotNull null
            Placed(measured, justifyXOffset(measured, boxW, natural.baseDirection))
        }
        assertTrue(placed.isNotEmpty(), "$name: no placed lines")

        // 4) planParagraphLines: anchor the block at -inkOriginX (app draws this way).
        val inkOriginX = placed.minOf { it.xOffset + it.measured.ink.left }
        val inkRight = placed.maxOf { it.xOffset + it.measured.ink.right }
        val inkWidth = inkRight - inkOriginX

        val lineStep = sizePx * 1.7f
        val canvasH = (margin * 2 + placed.size * lineStep).toInt().coerceAtLeast(120)
        // Extra right slack so an overflowing line is captured (not clipped) and visible.
        val canvasW = (margin * 2 + maxOf(inkWidth, boxW) + 320f).toInt()

        val surface = Surface.makeRasterN32Premul(canvasW, canvasH)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = surface.canvas.asComposeCanvas(),
            size = Size(canvasW.toFloat(), canvasH.toFloat()),
        ) {
            drawRect(Color.White, size = Size(canvasW.toFloat(), canvasH.toFloat()))
            // The justify FRAME (what the layer box will be): [margin, margin+inkWidth].
            drawRect(
                color = Color(0xFFB0C4DE), // light steel blue, well above the ink threshold
                topLeft = Offset(margin, margin * 0.5f),
                size = Size(inkWidth, canvasH - margin),
                style = Stroke(width = 1f),
            )
            placed.forEachIndexed { i, p ->
                val x = margin - inkOriginX + p.xOffset
                val y = margin + i * lineStep
                drawShapedText(measured = p.measured, topLeft = Offset(x, y), color = Color.Black)
            }
        }

        // 5) Write PNG for visual inspection.
        surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File(outDir, "$name.png").writeBytes(it)
        }

        // 6) Pixel scan: glyphs are pure black; the frame outline is light. Any
        //    dark pixel is a glyph. Assert none spills past the frame.
        val bitmap = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(canvasW, canvasH)) }
        assertTrue(surface.readPixels(bitmap, 0, 0), "$name: readPixels failed")
        var maxInkX = Float.NEGATIVE_INFINITY
        var minInkX = Float.POSITIVE_INFINITY
        for (py in 0 until canvasH) {
            for (px in 0 until canvasW) {
                val c = bitmap.getColor(px, py)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if ((r + g + b) / 3 < 96) { // dark = glyph ink
                    if (px > maxInkX) maxInkX = px.toFloat()
                    if (px < minInkX) minInkX = px.toFloat()
                }
            }
        }
        assertTrue(maxInkX.isFinite(), "$name: nothing rendered")

        val frameLeft = margin
        val frameRight = margin + inkWidth
        val tol = 3f
        assertTrue(
            maxInkX <= frameRight + tol,
            "$name: glyphs spill PAST the right frame edge by ${maxInkX - frameRight}px " +
                "(maxInkX=$maxInkX, frameRight=$frameRight, inkWidth=$inkWidth, target=$target). See $name.png",
        )
        assertTrue(
            minInkX >= frameLeft - tol,
            "$name: glyphs spill PAST the left frame edge by ${frameLeft - minInkX}px " +
                "(minInkX=$minInkX, frameLeft=$frameLeft). See $name.png",
        )
    }

    /** Mirror of `ParagraphLayoutBuilder.computeXOffset` for the Justify case. */
    private fun justifyXOffset(measured: MeasuredText, maxWidth: Float, baseDirection: HbDirection): Float {
        val inkEmpty = measured.isEmpty || measured.ink.isEmpty
        val left = if (inkEmpty) 0f else measured.ink.left
        val right = if (inkEmpty) measured.advance else measured.ink.right
        return if (baseDirection == HbDirection.RTL) maxWidth - right else -left
    }

    private companion object {
        const val LATIN = "The quick brown fox jumps over the lazy dog and runs away today"
        const val ARABIC = "السلام عليكم ورحمة الله وبركاته اهلا وسهلا بكم في تطبيقنا الجديد"
    }
}
