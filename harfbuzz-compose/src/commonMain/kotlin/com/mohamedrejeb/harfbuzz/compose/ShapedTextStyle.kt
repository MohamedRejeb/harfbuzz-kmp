package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.HbVariation
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment

/**
 * Bundle of paint + layout properties applied to a piece of shaped
 * text. Mirrors what call sites would otherwise pass argument-by-
 * argument to [ShapedText] / [ShapedTextBox], so a single style
 * instance can drive many widgets ("body" / "heading" / "caption")
 * without repeating the full paint stack at each use.
 *
 * [HbFont] is sizeless: a single face-derived font can render at any
 * size, and [fontSize] selects the per-style render size. Mirrors
 * Compose's `TextStyle.fontSize` convention - one font, many sizes
 * via `style.copy(fontSize = ...)`.
 */
@Immutable
public data class ShapedTextStyle(
    /**
     * Font stack used for shaping. A single [HbFont] caller can use
     * the top-level [ShapedTextStyle] factory taking [HbFont] which
     * wraps it in a one-element stack.
     */
    public val fontStack: HbFontStack,
    /**
     * Pixel size to shape and render at. With sizeless [HbFont]s the
     * size lives on the style, mirroring Compose's `TextStyle.fontSize`
     * convention - one font, many sizes.
     */
    public val fontSize: Float,
    /**
     * Solid fill colour for monochrome glyphs and COLR v0 foreground-
     * colour layers. Defaults to [Color.Unspecified] so call sites
     * can fall back to widget-level defaults; SVG bitmaps and COLR
     * paint trees keep their authored colours regardless.
     */
    public val color: Color = Color.Unspecified,
    /**
     * Optional [Brush] (linear / radial / sweep gradient, image
     * shader) spanning the full silhouette. Takes precedence over
     * [color] for monochrome glyphs and COLR v0 foreground-colour
     * layers.
     */
    public val brush: Brush? = null,
    /** Horizontal alignment within the slot's available width. */
    public val alignment: ParagraphAlignment = ParagraphAlignment.Start,
    /**
     * Justification strategy used when [alignment] is
     * [ParagraphAlignment.Justify]; [JustificationStrategy.None] (the
     * default) makes Justify behave like Start.
     */
    public val justification: JustificationStrategy = JustificationStrategy.None,
    /** Single-line overflow handling. Ignored by paragraph composables. */
    public val overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    /** OpenType feature toggles applied at shape time. */
    public val features: List<HbFeature> = emptyList(),
    /**
     * Base direction. [HbDirection.AUTO] (the default) uses
     * first-strong detection on the input.
     */
    public val direction: HbDirection = HbDirection.AUTO,
    /** BCP-47 language hint used by the shaper. */
    public val language: HbLanguage = HbLanguage.AUTO,
    /**
     * Compose `DrawStyle` applied to glyphs. Use [Fill] for filled
     * glyphs (the default) or
     * [androidx.compose.ui.graphics.drawscope.Stroke] for outlined
     * glyphs. For both fill and stroke in one composition pair this
     * with [ShapedTextBox]'s `stroke` argument.
     */
    public val style: DrawStyle = Fill,
    /**
     * Optional drop shadow. Applied to the silhouette (not to per-
     * glyph SVG / COLR paint trees) so colour fonts shadow as a
     * single cut-out, mirroring Compose's `BasicText` convention.
     */
    public val shadow: Shadow? = null,
    /**
     * For COLR v0 colour fonts: when `true`, every glyph paints with
     * [color] / [brush] instead of the font's palette colours.
     */
    public val forceForegroundColor: Boolean = false,
)

/**
 * Build a [ShapedTextStyle] from a single [HbFont]. Wraps the font in
 * a one-element [HbFontStack] so paragraph layout (which always walks
 * a stack) sees the same surface as multi-font callers.
 */
public fun ShapedTextStyle(
    font: HbFont,
    fontSize: Float,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    forceForegroundColor: Boolean = false,
): ShapedTextStyle = ShapedTextStyle(
    fontStack = HbFontStack(font),
    fontSize = fontSize,
    color = color,
    brush = brush,
    alignment = alignment,
    justification = justification,
    overflow = overflow,
    features = features,
    direction = direction,
    language = language,
    style = style,
    shadow = shadow,
    forceForegroundColor = forceForegroundColor,
)

/**
 * Build a [ShapedTextStyle] directly from an [HbFace] at [fontSize],
 * minting the underlying [HbFont] for you. Equivalent to calling
 * [HbFace.toFont] and then the [HbFont]-based factory above; this
 * suspend overload exists so simple call sites don't have to manage
 * the intermediate font handle themselves.
 *
 * The minted [HbFont] is owned by the resulting style's [HbFontStack]
 * and shares the lifetime of the face's resources - close the face
 * (or recreate the style) when you're done.
 */
public suspend fun ShapedTextStyle(
    face: HbFace,
    fontSize: Float,
    variations: List<HbVariation> = emptyList(),
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    forceForegroundColor: Boolean = false,
): ShapedTextStyle = ShapedTextStyle(
    font = if (variations.isEmpty()) face.toFont() else face.toFont(variations),
    fontSize = fontSize,
    color = color,
    brush = brush,
    alignment = alignment,
    justification = justification,
    overflow = overflow,
    features = features,
    direction = direction,
    language = language,
    style = style,
    shadow = shadow,
    forceForegroundColor = forceForegroundColor,
)
