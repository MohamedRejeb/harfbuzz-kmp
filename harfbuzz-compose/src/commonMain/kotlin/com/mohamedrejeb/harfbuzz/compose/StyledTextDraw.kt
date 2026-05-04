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
 * Implemented progressively across plan tasks - this revision is the
 * skeleton; full per-glyph batching arrives next.
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
    // Skeleton: defer to the uniform-paint path. Replaced by the
    // group-by-paint implementation in Task 9.
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
