package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mohamedrejeb.harfbuzz.compose.drawCombinedBrushPath
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.compose.drawShapedTextInternal

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

/**
 * Brush variant of [drawShapedParagraph]. Walks every line through the
 * same color-glyph ladder as the solid-color overload but routes
 * monochrome silhouettes (and COLR v0 foreground-color layers) into a
 * single combined [Path] spanning the whole paragraph; the brush is
 * applied once at the end, so a [Brush.linearGradient] flows from the
 * paragraph's leading edge through every wrapped line to its trailing
 * edge instead of repeating per-line.
 *
 * Designed-color glyphs (SVG-in-OT, COLR v1, COLR v0 palette layers)
 * keep their authored colors so emoji and SVG-painted fonts (e.g. Aref
 * Ruqaa Ink) render unchanged. Pass [forceForegroundColor] = true to
 * route those glyphs through the silhouette outline as well, which
 * makes the brush paint every glyph in the paragraph (including emoji
 * silhouettes).
 *
 * [shadow] is drawn per-line under the main render - matching the
 * single-line `drawShapedText(brush)` convention - so each wrapped
 * line gets its own shadow at its own offset.
 */
public fun DrawScope.drawShapedParagraph(
    paragraph: MeasuredParagraph,
    brush: Brush,
    topLeft: Offset = Offset.Zero,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    if (paragraph.isEmpty) return

    // One combined path holds every line's monochrome silhouette so a
    // gradient brush spans the full paragraph. Walking lines into a
    // shared accumulator avoids re-allocating the path per line and
    // makes per-line bounding-box drift impossible.
    val combinedBrushPath = Path()

    for (line in paragraph.lines) {
        if (line.measured.isEmpty) continue
        val lineTopLeft = Offset(
            x = topLeft.x + line.xOffset,
            y = topLeft.y + line.top,
        )
        drawShapedTextInternal(
            measured = line.measured,
            topLeft = lineTopLeft,
            color = Color.Black,
            brush = brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            forceForegroundColor = forceForegroundColor,
            shadow = shadow,
            spacingScale = 1f,
            externalBrushPath = combinedBrushPath,
        )
    }

    drawCombinedBrushPath(
        path = combinedBrushPath,
        brush = brush,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
    )
}

/**
 * Multi-line counterpart of `drawShapedTextWithImageFill`. Uses
 * [MeasuredParagraph.silhouettePath] (cached on the paragraph) as a
 * clip mask and renders [image] inside it. The default [dstSize]
 * stretches the image across the paragraph's logical box so colour
 * regions span every line; pass an explicit [dstSize] / [dstOffset]
 * to control how the image maps over the silhouette.
 */
public fun DrawScope.drawShapedParagraphWithImageFill(
    paragraph: MeasuredParagraph,
    image: ImageBitmap,
    topLeft: Offset = Offset.Zero,
    dstOffset: IntOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
    dstSize: IntSize = IntSize(image.width, image.height),
    srcOffset: IntOffset = IntOffset.Zero,
    srcSize: IntSize = IntSize(image.width, image.height),
    alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    filterQuality: FilterQuality = FilterQuality.High,
) {
    if (paragraph.isEmpty) return
    val silhouette = paragraph.silhouettePath
    val translated = if (topLeft == Offset.Zero) {
        silhouette
    } else {
        Path().also { it.addPath(silhouette, topLeft) }
    }
    clipPath(translated) {
        drawImage(
            image = image,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = dstOffset,
            dstSize = dstSize,
            alpha = alpha,
            blendMode = blendMode,
            filterQuality = filterQuality,
        )
    }
}
