package com.mohamedrejeb.harfbuzz.core

/**
 * One paint operation recorded from an [HbPaintSink]. Replaying a
 * `List<RecordedPaintOp>` against a sink reproduces the original glyph
 * exactly - useful for caching paint trees so we don't re-walk HarfBuzz
 * on every redraw.
 */
public sealed interface RecordedPaintOp {
    public data class PushTransform(
        val xx: Float, val yx: Float,
        val xy: Float, val yy: Float,
        val dx: Float, val dy: Float,
    ) : RecordedPaintOp

    public data object PopTransform : RecordedPaintOp

    public data class PushClipGlyph(val glyphId: Int) : RecordedPaintOp

    public data class PushClipRectangle(
        val xMin: Float, val yMin: Float,
        val xMax: Float, val yMax: Float,
    ) : RecordedPaintOp

    public data object PopClip : RecordedPaintOp

    public data class SolidColor(
        val isForeground: Boolean,
        val argb: Int,
    ) : RecordedPaintOp

    public data class LinearGradient(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val extend: GradientExtend,
        val stops: List<ColorStop>,
    ) : RecordedPaintOp

    public data class RadialGradient(
        val x0: Float, val y0: Float, val r0: Float,
        val x1: Float, val y1: Float, val r1: Float,
        val extend: GradientExtend,
        val stops: List<ColorStop>,
    ) : RecordedPaintOp

    public data class SweepGradient(
        val cx: Float, val cy: Float,
        val startAngle: Float, val endAngle: Float,
        val extend: GradientExtend,
        val stops: List<ColorStop>,
    ) : RecordedPaintOp

    public data object PushGroup : RecordedPaintOp

    public data class PopGroup(val mode: CompositeMode) : RecordedPaintOp

    /**
     * Wraps the shared [PaintImage] instance so replays reuse the same
     * decoded-bitmap cache slot across frames.
     */
    public class Image(public val image: PaintImage) : RecordedPaintOp
}

/**
 * An [HbPaintSink] that records every operation into [ops]. Paired with
 * [replay] this is the simplest way to cache a paint tree - call
 * [HbFont.paintGlyph] once into a [RecordingPaintSink], stash
 * [RecordingPaintSink.ops], and replay against any sink later.
 */
public class RecordingPaintSink : HbPaintSink {

    private val _ops: MutableList<RecordedPaintOp> = mutableListOf()

    /** The recorded operations in HarfBuzz emit order. */
    public val ops: List<RecordedPaintOp> get() = _ops

    public fun clear() {
        _ops.clear()
    }

    override fun pushTransform(xx: Float, yx: Float, xy: Float, yy: Float, dx: Float, dy: Float) {
        _ops += RecordedPaintOp.PushTransform(xx, yx, xy, yy, dx, dy)
    }

    override fun popTransform() {
        _ops += RecordedPaintOp.PopTransform
    }

    override fun pushClipGlyph(glyphId: Int) {
        _ops += RecordedPaintOp.PushClipGlyph(glyphId)
    }

    override fun pushClipRectangle(xMin: Float, yMin: Float, xMax: Float, yMax: Float) {
        _ops += RecordedPaintOp.PushClipRectangle(xMin, yMin, xMax, yMax)
    }

    override fun popClip() {
        _ops += RecordedPaintOp.PopClip
    }

    override fun color(isForeground: Boolean, argb: Int) {
        _ops += RecordedPaintOp.SolidColor(isForeground, argb)
    }

    override fun linearGradient(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    ) {
        _ops += RecordedPaintOp.LinearGradient(x0, y0, x1, y1, x2, y2, extend, stops)
    }

    override fun radialGradient(
        x0: Float, y0: Float, r0: Float,
        x1: Float, y1: Float, r1: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    ) {
        _ops += RecordedPaintOp.RadialGradient(x0, y0, r0, x1, y1, r1, extend, stops)
    }

    override fun sweepGradient(
        cx: Float, cy: Float,
        startAngle: Float, endAngle: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    ) {
        _ops += RecordedPaintOp.SweepGradient(cx, cy, startAngle, endAngle, extend, stops)
    }

    override fun pushGroup() {
        _ops += RecordedPaintOp.PushGroup
    }

    override fun popGroup(mode: CompositeMode) {
        _ops += RecordedPaintOp.PopGroup(mode)
    }

    override fun image(image: PaintImage) {
        _ops += RecordedPaintOp.Image(image)
    }
}

/** Replay a recorded list of ops against a fresh sink. */
public fun List<RecordedPaintOp>.replay(sink: HbPaintSink) {
    for (op in this) {
        when (op) {
            is RecordedPaintOp.PushTransform -> sink.pushTransform(
                op.xx, op.yx, op.xy, op.yy, op.dx, op.dy,
            )
            RecordedPaintOp.PopTransform -> sink.popTransform()
            is RecordedPaintOp.PushClipGlyph -> sink.pushClipGlyph(op.glyphId)
            is RecordedPaintOp.PushClipRectangle -> sink.pushClipRectangle(
                op.xMin, op.yMin, op.xMax, op.yMax,
            )
            RecordedPaintOp.PopClip -> sink.popClip()
            is RecordedPaintOp.SolidColor -> sink.color(op.isForeground, op.argb)
            is RecordedPaintOp.LinearGradient -> sink.linearGradient(
                op.x0, op.y0, op.x1, op.y1, op.x2, op.y2, op.extend, op.stops,
            )
            is RecordedPaintOp.RadialGradient -> sink.radialGradient(
                op.x0, op.y0, op.r0, op.x1, op.y1, op.r1, op.extend, op.stops,
            )
            is RecordedPaintOp.SweepGradient -> sink.sweepGradient(
                op.cx, op.cy, op.startAngle, op.endAngle, op.extend, op.stops,
            )
            RecordedPaintOp.PushGroup -> sink.pushGroup()
            is RecordedPaintOp.PopGroup -> sink.popGroup(op.mode)
            is RecordedPaintOp.Image -> sink.image(op.image)
        }
    }
}
