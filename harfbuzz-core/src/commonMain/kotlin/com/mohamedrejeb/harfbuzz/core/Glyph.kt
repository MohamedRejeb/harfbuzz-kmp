package com.mohamedrejeb.harfbuzz.core

/**
 * One shaped glyph - an entry from `hb_buffer_get_glyph_infos`. [cluster] maps
 * back to the originating UTF-16 character index; multiple glyphs can share a
 * cluster (decomposed marks, ligatures the other way), and the same cluster
 * value can appear non-contiguously in RTL runs.
 */
public data class GlyphInfo(
    public val glyphId: Int,
    public val cluster: Int,
    public val flags: Int,
)

/**
 * Per-glyph positioning - an entry from `hb_buffer_get_glyph_positions`.
 * Values are in pixel space when shaping was called with a positive
 * `sizePx`; otherwise they are in font design units.
 *
 * [xScale] is a render-time horizontal scale applied to this glyph's
 * outline, anchored at the glyph's pen origin (x = 0), independent of
 * shaping. It defaults to `1f` (no scaling) so every existing shaping
 * path is unaffected. It exists for continuous Kashida (tatweel, U+0640)
 * elongation: a single tatweel glyph can be stretched horizontally to an
 * arbitrary, fractional width instead of inserting whole tatweel glyphs.
 * When set, [xAdvance] must already reflect the scaled width
 * (`naturalAdvance * xScale`) so pen advancement and total-advance sums
 * stay consistent with what is drawn.
 */
public data class GlyphPosition(
    public val xAdvance: Float,
    public val yAdvance: Float,
    public val xOffset: Float,
    public val yOffset: Float,
    public val xScale: Float = 1f,
)

/**
 * Tight ink bounding box for a single glyph - from `hb_font_get_glyph_extents`.
 * Note: HarfBuzz's y-axis points up while most 2D graphics APIs use y-down,
 * so consumers may need to flip [yBearing] when drawing.
 */
public data class GlyphExtents(
    public val xBearing: Float,
    public val yBearing: Float,
    public val width: Float,
    public val height: Float,
)

/**
 * Font-wide horizontal metrics - from `hb_font_get_h_extents`. Values are
 * already scaled to pixel space (consistent with the rest of the API).
 *
 * Sign convention follows HarfBuzz / typographic Y-up:
 *  • [ascender]  > 0  - distance from baseline up to the recommended top.
 *  • [descender] < 0  - distance from baseline down to the recommended
 *                       bottom (negative because the bottom is below
 *                       baseline in Y-up). Take `-descender` for a positive
 *                       below-baseline depth in screen Y-down terms.
 *  • [lineGap]  >= 0  - extra vertical space the font designer recommends
 *                       between consecutive lines.
 *
 * Total recommended line height = `ascender - descender + lineGap`.
 */
public data class FontExtents(
    public val ascender: Float,
    public val descender: Float,
    public val lineGap: Float,
) {
    /** Convenience: `ascender - descender + lineGap` in screen pixels. */
    public val lineHeight: Float get() = ascender - descender + lineGap
}

/**
 * One layer of a color glyph (COLR/CPAL). A color glyph is composed of
 * several base glyphs stacked in order, each painted with [argb] from the
 * font's palette - when [argb] is null, the layer uses the caller-provided
 * foreground color (HarfBuzz's `HB_OT_COLOR_PALETTE_COLOR_FOREGROUND`).
 */
public data class ColorLayer(
    public val glyphId: Int,
    /**
     * 32-bit packed ARGB color from the font's default palette, or `null`
     * to use the caller's foreground color. Use the standard `0xAARRGGBB`
     * layout (alpha in the high byte).
     */
    public val argb: Int?,
)
