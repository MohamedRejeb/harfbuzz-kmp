package com.mohamedrejeb.harfbuzz.core

/**
 * A sink that receives the paint operations of a COLR v1 colored glyph,
 * mirroring HarfBuzz's `hb_paint_funcs_t` callbacks. Implement this to
 * render colored glyphs through any 2D graphics API.
 *
 * ## Operation order
 *
 * Calls form a tree: every `push…` is paired with a matching `pop…` in
 * LIFO order. Between `push` and `pop`, painting (color/gradient) calls
 * inside the active transform/clip apply.
 *
 * ## Solid color and gradients
 *
 * [color], [linearGradient], [radialGradient], and [sweepGradient] always
 * fill the *currently clipped region*. The clipping region is whatever
 * sequence of [pushClipGlyph] / [pushClipRectangle] is active above this
 * call; without a clip the entire glyph bounding box is filled. Renderers
 * that defer fills (like Skia) typically maintain a small stack of saved
 * draw layers around `pushGroup` / `popGroup`.
 *
 * ## Foreground color resolution
 *
 * When [color] reports `isForeground = true`, HarfBuzz substitutes the
 * caller-provided foreground color (the value passed as `foreground` to
 * [HbFont.paintGlyph]) into [argb] before this callback fires. Renderers
 * therefore *can* ignore the `isForeground` bit and just use [argb] -
 * the bit is exposed because some renderers (for instance, those that
 * later switch foregrounds without re-walking the paint tree) need the
 * distinction.
 *
 * ## Composite groups
 *
 * Each [pushGroup] starts a fresh isolated drawing layer; the matching
 * [popGroup] composites that layer onto the parent using the supplied
 * [CompositeMode]. This is how COLR v1 builds up multi-layer glyphs (a
 * background + masked foreground, for example).
 */
public interface HbPaintSink {

    /**
     * Concatenate a 2x3 affine transform onto the current matrix. The HB
     * convention is column-major:
     *
     * ```
     * | xx xy dx |
     * | yx yy dy |
     * |  0  0  1 |
     * ```
     */
    public fun pushTransform(
        xx: Float,
        yx: Float,
        xy: Float,
        yy: Float,
        dx: Float,
        dy: Float,
    )

    public fun popTransform()

    /** Push the outline of [glyphId] (in this font) as the active clip. */
    public fun pushClipGlyph(glyphId: Int)

    /** Push a rectangular clip in the current transform's coordinate space. */
    public fun pushClipRectangle(xMin: Float, yMin: Float, xMax: Float, yMax: Float)

    public fun popClip()

    /**
     * Fill the active clip with a solid color. See class docs for the
     * meaning of [isForeground].
     */
    public fun color(isForeground: Boolean, argb: Int)

    /**
     * Two-point linear gradient. The gradient direction is `(p1 - p0)`,
     * with `p2` defining the rotation axis (HB allows arbitrary 3-point
     * gradients; most COLR v1 gradients have `p2` perpendicular to
     * `p1 - p0` so simple shaders can ignore it).
     */
    public fun linearGradient(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    )

    /**
     * COLR v1 two-circle radial gradient (focal-point variant). Compose's
     * stock `Brush.radialGradient` is single-circle; renderers needing
     * exact COLR v1 fidelity should use a custom shader.
     */
    public fun radialGradient(
        x0: Float, y0: Float, r0: Float,
        x1: Float, y1: Float, r1: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    )

    /**
     * Sweep (conic) gradient, swept from [startAngle] to [endAngle] in
     * radians. HarfBuzz reports angles in the standard math convention
     * (counter-clockwise from positive X axis).
     */
    public fun sweepGradient(
        cx: Float, cy: Float,
        startAngle: Float, endAngle: Float,
        extend: GradientExtend,
        stops: List<ColorStop>,
    )

    /** Begin an isolated drawing layer; pair with [popGroup]. */
    public fun pushGroup()

    /** Composite the most recent group onto the parent using [mode]. */
    public fun popGroup(mode: CompositeMode)
}
