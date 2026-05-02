package com.mohamedrejeb.harfbuzz.core

/**
 * One stop along a COLR v1 gradient - see `hb_color_stop_t`.
 *
 * @property offset Normalised position of the stop along the gradient
 *   (0.0 .. 1.0 inside the visible range; out-of-range stops are valid and
 *   determine extrapolation behaviour together with [GradientExtend]).
 * @property isForeground When true, the stop's color is the caller-provided
 *   foreground (passed to [HbFont.paintGlyph]) rather than [argb].
 *   Renderers should still use [argb] when this is false.
 * @property argb 32-bit packed `0xAARRGGBB`. When [isForeground] is true,
 *   this holds the foreground color resolved at the time `paintGlyph` was
 *   called - so renderers can ignore [isForeground] and just use [argb] if
 *   they don't want to special-case the foreground.
 */
public data class ColorStop(
    public val offset: Float,
    public val isForeground: Boolean,
    public val argb: Int,
)

/**
 * COLR v1 gradient extend mode - what happens outside the gradient's
 * defined stop range. Mirrors HarfBuzz's `hb_paint_extend_t`.
 */
public enum class GradientExtend {
    /** Clamp to the nearest stop. */
    Pad,

    /** Tile the gradient back to back. */
    Repeat,

    /** Tile the gradient with every other repetition reversed. */
    Reflect,
    ;

    public companion object {
        public fun fromHbValue(value: Int): GradientExtend = when (value) {
            0 -> Pad
            1 -> Repeat
            2 -> Reflect
            else -> Pad
        }
    }
}
