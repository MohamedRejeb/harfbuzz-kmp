package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import com.mohamedrejeb.harfbuzz.compose.drawShapedText

/**
 * Draw a previously-laid-out [paragraph] at [topLeft]. Walks every line in
 * order, applies the line's alignment-resolved [MeasuredLine.xOffset] +
 * [MeasuredLine.top], and delegates each line to the existing
 * [drawShapedText] pipeline so the same color-glyph ladder (SVG-in-OT,
 * COLR v1, COLR v0, monochrome) and the [shadow] / [style] /
 * [forceForegroundColor] options apply uniformly.
 */
public fun DrawScope.drawShapedParagraph(
    paragraph: MeasuredParagraph,
    topLeft: Offset = Offset.Zero,
    color: Color = Color.Black,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    if (paragraph.isEmpty) return

    for (line in paragraph.lines) {
        if (line.measured.isEmpty) continue
        val lineTopLeft = Offset(
            x = topLeft.x + line.xOffset,
            y = topLeft.y + line.top,
        )
        drawShapedText(
            measured = line.measured,
            topLeft = lineTopLeft,
            color = color,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            forceForegroundColor = forceForegroundColor,
            shadow = shadow,
        )
    }
}
