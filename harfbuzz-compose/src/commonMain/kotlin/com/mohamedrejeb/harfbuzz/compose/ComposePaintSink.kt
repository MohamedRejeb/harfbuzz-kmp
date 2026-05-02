package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import com.mohamedrejeb.harfbuzz.core.ColorStop
import com.mohamedrejeb.harfbuzz.core.CompositeMode
import com.mohamedrejeb.harfbuzz.core.GradientExtend
import com.mohamedrejeb.harfbuzz.core.HbPaintSink

/**
 * An [HbPaintSink] that translates HarfBuzz paint ops into Compose draws on
 * a captured [Canvas].
 *
 * ## Coordinate space
 *
 * HarfBuzz emits paint coordinates in the font's design space - Y-up. Our
 * [glyphRawPaths] cache holds glyph outlines in that same Y-up space. The
 * caller is expected to apply `canvas.scale(1f, -1f)` (a Y flip) before
 * driving the sink, so all subsequent ops naturally land in screen space.
 *
 * ## Fill semantics
 *
 * In HarfBuzz's model, [color] / gradient calls fill the *currently active
 * clip*. Compose has no "fill clip" primitive, so we fill a sufficiently
 * large rectangle and let the canvas's clip stack mask it down. The
 * intersection of all active push/pop clips ends up being painted.
 *
 * ## Group composite limits
 *
 * `pushGroup` / `popGroup` currently use a plain canvas save/restore - the
 * provided [CompositeMode] is ignored. Aref Ruqaa Ink renders correctly
 * with this since its tree relies on clipped gradient fills, not blend
 * modes; emoji fonts that do use composite modes will fall back to
 * source-over (the visual difference is rarely catastrophic, but it is
 * a known limitation tracked in the COLR v1 plan).
 */
internal class ComposePaintSink(
    private val canvas: Canvas,
    private val glyphRawPaths: Map<Int, Path>,
    private val foreground: Color,
    private val alphaMultiplier: Float,
    private val blendMode: BlendMode,
    /**
     * Bounds large enough to cover any reasonable glyph at this size in
     * any direction. Used as the gradient sizing rect and the fill rect -
     * the canvas clip stack masks it down to the visible region.
     */
    private val coverBounds: Rect,
) : HbPaintSink {

    private val coverSize: Size get() = coverBounds.size

    override fun pushTransform(
        xx: Float, yx: Float,
        xy: Float, yy: Float,
        dx: Float, dy: Float,
    ) {
        canvas.save()
        val m = Matrix().apply {
            // HarfBuzz emits a 2x3 affine that maps (x, y) →
            //   (xx*x + xy*y + dx, yx*x + yy*y + dy)
            //
            // Compose's Matrix.map(Offset) reads the 4x4 like:
            //   new_x = m[0,0]*x + m[1,0]*y + m[3,0]
            //   new_y = m[0,1]*x + m[1,1]*y + m[3,1]
            //
            // So translations live in the bottom row (m[3,0], m[3,1])
            // and the off-diagonals are transposed relative to the
            // 2x3 affine. Get this wrong and pure-translate transforms
            // (e.g. emoji hearts that translate base shapes by a small
            // dx) silently no-op while gradients still appear correct.
            this[0, 0] = xx; this[0, 1] = yx
            this[1, 0] = xy; this[1, 1] = yy
            this[3, 0] = dx; this[3, 1] = dy
        }
        canvas.concat(m)
    }

    override fun popTransform() {
        canvas.restore()
    }

    override fun pushClipGlyph(glyphId: Int) {
        canvas.save()
        val path = glyphRawPaths[glyphId]
        if (path != null) canvas.clipPath(path)
    }

    override fun pushClipRectangle(xMin: Float, yMin: Float, xMax: Float, yMax: Float) {
        canvas.save()
        canvas.clipRect(left = xMin, top = yMin, right = xMax, bottom = yMax)
    }

    override fun popClip() {
        canvas.restore()
    }

    override fun color(isForeground: Boolean, argb: Int) {
        val c = if (isForeground) foreground else argb.toComposeColor()
        val paint = Paint().apply {
            color = c.copy(alpha = c.alpha * alphaMultiplier)
            blendMode = this@ComposePaintSink.blendMode
        }
        fillCurrentClip(paint)
    }

    override fun linearGradient(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    ) {
        if (stops.isEmpty()) return
        val brush = Brush.linearGradient(
            colorStops = stops.toColorStopArray(),
            start = Offset(x0, y0),
            end = Offset(x1, y1),
            tileMode = extend.toTileMode(),
        )
        // The third gradient point (x2, y2) defines a rotation axis for
        // off-perpendicular linear gradients in COLR v1. Compose's
        // Brush.linearGradient is two-point only; (x2, y2) is silently
        // ignored. Most fonts use perpendicular gradients so the visual
        // difference is small.
        applyBrushAndFill(brush)
    }

    override fun radialGradient(
        x0: Float, y0: Float, r0: Float,
        x1: Float, y1: Float, r1: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    ) {
        if (stops.isEmpty()) return
        // COLR v1 radial gradients are two-circle (focal-point) - Compose's
        // Brush.radialGradient is single-circle. We approximate with the
        // larger circle, losing the focal offset; the plan's risk register
        // tracks this.
        val centerX: Float
        val centerY: Float
        val radius: Float
        if (r1 >= r0) {
            centerX = x1; centerY = y1; radius = r1
        } else {
            centerX = x0; centerY = y0; radius = r0
        }
        if (radius <= 0f) return
        val brush = Brush.radialGradient(
            colorStops = stops.toColorStopArray(),
            center = Offset(centerX, centerY),
            radius = radius,
            tileMode = extend.toTileMode(),
        )
        applyBrushAndFill(brush)
    }

    override fun sweepGradient(
        cx: Float, cy: Float,
        startAngle: Float, endAngle: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    ) {
        if (stops.isEmpty()) return
        // Compose's Brush.sweepGradient is full-360°; HB gives a
        // [startAngle, endAngle] sub-arc in radians. We use Compose's full
        // sweep - fonts that genuinely use sub-arcs will look slightly
        // wrong. Tracked in the COLR v1 plan's risk register.
        val brush = Brush.sweepGradient(
            colorStops = stops.toColorStopArray(),
            center = Offset(cx, cy),
        )
        @Suppress("UNUSED_VARIABLE") val unusedExtend = extend  // sweep doesn't honor extend
        @Suppress("UNUSED_VARIABLE") val unusedAngles = startAngle to endAngle
        applyBrushAndFill(brush)
    }

    override fun pushGroup() {
        canvas.save()
    }

    override fun popGroup(mode: CompositeMode) {
        canvas.restore()
    }

    private fun fillCurrentClip(paint: Paint) {
        canvas.drawRect(coverBounds, paint)
    }

    private fun applyBrushAndFill(brush: Brush) {
        val paint = Paint()
        brush.applyTo(coverSize, paint, alphaMultiplier)
        paint.blendMode = blendMode
        fillCurrentClip(paint)
    }
}

/**
 * Convert a 0xAARRGGBB packed Int into a [Color]. The cast through
 * `Long and 0xFFFFFFFFL` is necessary because `Int.toLong()` of a
 * negative value (alpha >= 0x80) would sign-extend.
 */
private fun Int.toComposeColor(): Color = Color(this.toLong() and 0xFFFFFFFFL)

private fun GradientExtend.toTileMode(): TileMode = when (this) {
    GradientExtend.Pad -> TileMode.Clamp
    GradientExtend.Repeat -> TileMode.Repeated
    GradientExtend.Reflect -> TileMode.Mirror
}

private fun List<ColorStop>.toColorStopArray(): Array<Pair<Float, Color>> =
    Array(size) { i ->
        val s = this[i]
        s.offset to s.argb.toComposeColor()
    }
