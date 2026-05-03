package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stroke pass spec used by [ShapedTextBox]. Renders an outlined copy
 * of the text underneath the fill pass, so the visible glyph
 * silhouette is the union of stroke (around the outside) and fill
 * (inside).
 *
 * Either [color] or [brush] supplies the outline paint; if both are
 * non-default, [brush] wins. The default round cap / round join match
 * Andalusi's `StrokeValue` and look smoother than miter joins on
 * cursive scripts where strokes meet at sharp angles.
 */
@Immutable
public data class ShapedTextStroke(
    public val width: Dp,
    public val color: Color = Color.Unspecified,
    public val brush: Brush? = null,
    public val cap: StrokeCap = StrokeCap.Round,
    public val join: StrokeJoin = StrokeJoin.Round,
    public val miter: Float = Stroke.DefaultMiter,
) {
    public companion object {
        /** Convenience constructor with a flat colour stroke. */
        public fun solid(width: Dp, color: Color): ShapedTextStroke =
            ShapedTextStroke(width = width, color = color)

        /** Convenience constructor with a brush stroke. */
        public fun gradient(width: Dp, brush: Brush): ShapedTextStroke =
            ShapedTextStroke(width = width, brush = brush)
    }
}

/**
 * Default 1.dp stroke - thin enough to outline glyphs without
 * dominating fill, matches the default Andalusi UI uses for
 * "outlined text" toggles.
 */
public val DefaultShapedTextStrokeWidth: Dp = 1.dp
