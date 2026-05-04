package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle

/**
 * Styled draw path. Resolves paint per glyph via [PaintResolver] and
 * groups monochrome silhouettes by resolved paint so each distinct
 * paint becomes one combined-path fill at the end.
 *
 * Per-glyph batching is not yet implemented; this revision delegates
 * to the uniform-paint path with the outer color/brush.
 */
internal fun DrawScope.drawShapedTextStyledInternal(
    measured: MeasuredText,
    styledText: StyledText,
    topLeft: Offset,
    color: Color,
    brush: Brush?,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
    forceForegroundColor: Boolean,
    shadow: Shadow?,
) {
    drawShapedTextInternal(
        measured = measured,
        topLeft = topLeft,
        color = color,
        brush = brush,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
        forceForegroundColor = forceForegroundColor,
        shadow = shadow,
        spacingScale = 1f,
    )
}
