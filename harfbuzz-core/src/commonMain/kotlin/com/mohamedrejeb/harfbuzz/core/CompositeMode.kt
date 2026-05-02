package com.mohamedrejeb.harfbuzz.core

/**
 * COLR v1 group composite blend mode - mirrors HarfBuzz's
 * `hb_paint_composite_mode_t`. Each value corresponds to a named compositing
 * operator from the COLR specification.
 *
 * Most modes line up with W3C Compositing & Blending Level 1, which most
 * 2D graphics APIs (Skia, Cairo, Compose) implement directly. The HSL
 * modes are the exception - they ship as Skia `BlendMode` extensions but
 * have no Compose `BlendMode` enum entry on every platform; renderers can
 * fall back to [SrcOver] when an exact match isn't available.
 */
public enum class CompositeMode {
    Clear,
    Src,
    Dst,
    SrcOver,
    DstOver,
    SrcIn,
    DstIn,
    SrcOut,
    DstOut,
    SrcAtop,
    DstAtop,
    Xor,
    Plus,
    Screen,
    Overlay,
    Darken,
    Lighten,
    ColorDodge,
    ColorBurn,
    HardLight,
    SoftLight,
    Difference,
    Exclusion,
    Multiply,
    HslHue,
    HslSaturation,
    HslColor,
    HslLuminosity,
    ;

    public companion object {
        /**
         * Map a HarfBuzz `hb_paint_composite_mode_t` integer to the enum
         * (returns [SrcOver] for unknown values, matching HarfBuzz's own
         * default fallback).
         */
        public fun fromHbValue(value: Int): CompositeMode {
            val values = entries
            return if (value in values.indices) values[value] else SrcOver
        }
    }
}
