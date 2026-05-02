package com.mohamedrejeb.harfbuzz.core

/**
 * Receives the outline of a glyph as a sequence of path commands -
 * the Kotlin counterpart of HarfBuzz's `hb_draw_funcs_t` callbacks.
 *
 * Coordinates are in font units; HarfBuzz's y-axis points up. Implementations
 * that target Compose / Skia / Canvas typically flip the y component during
 * conversion. Glyph paths are emitted with a separate `move-to` per contour.
 */
public interface HbPathSink {
    public fun moveTo(x: Float, y: Float)
    public fun lineTo(x: Float, y: Float)
    public fun quadTo(c1x: Float, c1y: Float, x: Float, y: Float)
    public fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float)
    public fun close()
}
