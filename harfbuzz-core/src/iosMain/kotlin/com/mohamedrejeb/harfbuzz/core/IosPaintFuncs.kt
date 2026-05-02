@file:OptIn(ExperimentalForeignApi::class)

package com.mohamedrejeb.harfbuzz.core

import com.mohamedrejeb.harfbuzz.native.hb_bool_t
import com.mohamedrejeb.harfbuzz.native.hb_color_get_alpha
import com.mohamedrejeb.harfbuzz.native.hb_color_get_blue
import com.mohamedrejeb.harfbuzz.native.hb_color_get_green
import com.mohamedrejeb.harfbuzz.native.hb_color_get_red
import cnames.structs.hb_blob_t
import cnames.structs.hb_font_t
import cnames.structs.hb_paint_funcs_t
import com.mohamedrejeb.harfbuzz.native.hb_color_line_get_color_stops
import com.mohamedrejeb.harfbuzz.native.hb_color_line_get_extend
import com.mohamedrejeb.harfbuzz.native.hb_color_line_t
import com.mohamedrejeb.harfbuzz.native.hb_color_stop_t
import com.mohamedrejeb.harfbuzz.native.hb_color_t
import com.mohamedrejeb.harfbuzz.native.hb_glyph_extents_t
import com.mohamedrejeb.harfbuzz.native.hb_paint_composite_mode_t
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_create
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_make_immutable
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_color_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_image_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_linear_gradient_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_pop_clip_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_pop_group_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_pop_transform_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_push_clip_glyph_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_push_clip_rectangle_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_push_group_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_push_transform_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_radial_gradient_func
import com.mohamedrejeb.harfbuzz.native.hb_paint_funcs_set_sweep_gradient_func
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value

/**
 * Cinterop bridge between HarfBuzz's `hb_paint_funcs_t` callbacks and a
 * Kotlin [HbPaintSink]. The shared `hb_paint_funcs_t` is initialised
 * lazily on first use; callbacks unpack the sink from the StableRef
 * passed via paint_data.
 *
 * Hard cap on stops per gradient mirrors the JVM JNI implementation -
 * keeps adversarial fonts from triggering huge allocations.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosPaintFuncs {

    private const val MAX_GRADIENT_STOPS: Int = 256

    /**
     * Pull the [HbPaintSink] back out of `paint_data`. We pass a
     * `StableRef<HbPaintSink>` as the user data on every
     * `hb_font_paint_glyph` call.
     */
    private fun sinkFromPaintData(paintData: COpaquePointer?): HbPaintSink? {
        if (paintData == null) return null
        return paintData.asStableRef<HbPaintSink>().get()
    }

    /** Pack an `hb_color_t` into 0xAARRGGBB. Same convention as JVM. */
    private fun hbColorToArgb(color: hb_color_t): Int {
        val c = color
        val r = hb_color_get_red(c).toInt() and 0xFF
        val g = hb_color_get_green(c).toInt() and 0xFF
        val b = hb_color_get_blue(c).toInt() and 0xFF
        val a = hb_color_get_alpha(c).toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Build an `hb_color_t` from the caller's 0xAARRGGBB int. Mirrors
     * the C `HB_COLOR(b,g,r,a)` macro: the byte layout is BGRA in the
     * underlying uint32_t.
     */
    fun argbToHbColor(argb: Int): hb_color_t {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val packed: UInt = (b.toUInt() shl 24) or (g.toUInt() shl 16) or (r.toUInt() shl 8) or a.toUInt()
        return packed
    }

    private fun readStops(line: CPointer<hb_color_line_t>): Pair<GradientExtend, List<ColorStop>> {
        val extend = GradientExtend.fromHbValue(hb_color_line_get_extend(line).value.toInt())
        return memScoped {
            val probe = alloc<UIntVar>().apply { value = 0u }
            // Probe call: return value carries the total count; `*count`
            // is set to the number actually written into the (here null)
            // buffer (= 0).
            val total = hb_color_line_get_color_stops(line, 0u, probe.ptr, null).toInt()
            if (total <= 0) return@memScoped extend to emptyList<ColorStop>()
            val capped = if (total > MAX_GRADIENT_STOPS) MAX_GRADIENT_STOPS else total
            val stops = allocArray<hb_color_stop_t>(capped)
            val gotVar = alloc<UIntVar>().apply { value = capped.toUInt() }
            hb_color_line_get_color_stops(line, 0u, gotVar.ptr, stops)
            val got = gotVar.value.toInt().coerceAtMost(capped)
            val list = List(got) { i ->
                val s = stops[i]
                ColorStop(
                    offset = s.offset,
                    isForeground = s.is_foreground != 0,
                    argb = hbColorToArgb(s.color),
                )
            }
            extend to list
        }
    }

    // ───── Callbacks (must be top-level / no captures for staticCFunction) ─────

    private val pushTransform = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            xx: Float, yx: Float,
            xy: Float, yy: Float,
            dx: Float, dy: Float,
            _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.pushTransform(xx, yx, xy, yy, dx, dy)
        Unit
    }

    private val popTransform = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?, paintData: COpaquePointer?, _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.popTransform()
        Unit
    }

    private val pushClipGlyph = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            glyph: UInt,
            _: CPointer<hb_font_t>?,
            _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.pushClipGlyph(glyph.toInt())
        Unit
    }

    private val pushClipRect = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            xMin: Float, yMin: Float, xMax: Float, yMax: Float,
            _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.pushClipRectangle(xMin, yMin, xMax, yMax)
        Unit
    }

    private val popClip = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?, paintData: COpaquePointer?, _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.popClip()
        Unit
    }

    private val color = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            isForeground: hb_bool_t,
            color: hb_color_t,
            _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.color(isForeground != 0, hbColorToArgb(color))
        Unit
    }

    private val image = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            _: COpaquePointer?,
            _: CPointer<hb_blob_t>?,
            _: UInt, _: UInt,                       // width, height
            _: UInt,                                // format tag
            _: Float,                               // slant
            _: CPointer<hb_glyph_extents_t>?,
            _: COpaquePointer?,
        ->
        // Bitmap / SVG image paint nodes are deferred. Returning false
        // tells HarfBuzz "not handled" so it skips this leaf.
        0
    }

    private val linearGradient = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            line: CPointer<hb_color_line_t>?,
            x0: Float, y0: Float,
            x1: Float, y1: Float,
            x2: Float, y2: Float,
            _: COpaquePointer?,
        ->
        val sink = sinkFromPaintData(paintData) ?: return@staticCFunction
        if (line == null) return@staticCFunction
        val (extend, stops) = readStops(line)
        sink.linearGradient(x0, y0, x1, y1, x2, y2, extend, stops)
    }

    private val radialGradient = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            line: CPointer<hb_color_line_t>?,
            x0: Float, y0: Float, r0: Float,
            x1: Float, y1: Float, r1: Float,
            _: COpaquePointer?,
        ->
        val sink = sinkFromPaintData(paintData) ?: return@staticCFunction
        if (line == null) return@staticCFunction
        val (extend, stops) = readStops(line)
        sink.radialGradient(x0, y0, r0, x1, y1, r1, extend, stops)
    }

    private val sweepGradient = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            line: CPointer<hb_color_line_t>?,
            x0: Float, y0: Float,
            startAngle: Float, endAngle: Float,
            _: COpaquePointer?,
        ->
        val sink = sinkFromPaintData(paintData) ?: return@staticCFunction
        if (line == null) return@staticCFunction
        val (extend, stops) = readStops(line)
        sink.sweepGradient(x0, y0, startAngle, endAngle, extend, stops)
    }

    private val pushGroup = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?, paintData: COpaquePointer?, _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.pushGroup()
        Unit
    }

    private val popGroup = staticCFunction {
            _: CPointer<hb_paint_funcs_t>?,
            paintData: COpaquePointer?,
            mode: hb_paint_composite_mode_t,
            _: COpaquePointer?,
        ->
        sinkFromPaintData(paintData)?.popGroup(CompositeMode.fromHbValue(mode.value.toInt()))
        Unit
    }

    /**
     * Lazily-initialised shared paint funcs object. Created once per
     * process; HarfBuzz refcounts it internally so leaking is harmless.
     */
    val shared: CPointer<hb_paint_funcs_t> by lazy {
        val funcs = hb_paint_funcs_create() ?: error("hb_paint_funcs_create returned null")

        hb_paint_funcs_set_push_transform_func(funcs, pushTransform, null, null)
        hb_paint_funcs_set_pop_transform_func(funcs, popTransform, null, null)
        hb_paint_funcs_set_push_clip_glyph_func(funcs, pushClipGlyph, null, null)
        hb_paint_funcs_set_push_clip_rectangle_func(funcs, pushClipRect, null, null)
        hb_paint_funcs_set_pop_clip_func(funcs, popClip, null, null)
        hb_paint_funcs_set_color_func(funcs, color, null, null)
        hb_paint_funcs_set_image_func(funcs, image, null, null)
        hb_paint_funcs_set_linear_gradient_func(funcs, linearGradient, null, null)
        hb_paint_funcs_set_radial_gradient_func(funcs, radialGradient, null, null)
        hb_paint_funcs_set_sweep_gradient_func(funcs, sweepGradient, null, null)
        hb_paint_funcs_set_push_group_func(funcs, pushGroup, null, null)
        hb_paint_funcs_set_pop_group_func(funcs, popGroup, null, null)

        hb_paint_funcs_make_immutable(funcs)
        funcs
    }
}
