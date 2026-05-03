package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.mohamedrejeb.harfbuzz.compose.paragraph.MeasuredParagraph
import com.mohamedrejeb.harfbuzz.compose.paragraph.MeasuredParagraphLoad
import com.mohamedrejeb.harfbuzz.compose.paragraph.drawShapedParagraph
import com.mohamedrejeb.harfbuzz.compose.paragraph.rememberMeasuredParagraph
import kotlin.math.ceil

/**
 * One-stop wrapper around the layered "rounded background + outlined
 * text + filled text" pattern Andalusi renders today via
 * `TextDrawableLayer.draw`. Collapses background rect, stroked text,
 * and filled text into a single call sharing a [ShapedTextStyle].
 *
 * **Sizing.** Mirrors Compose's `Text` widget: the box self-sizes to
 * its content (paragraph width × paragraph height) and respects
 * incoming `Constraints`. With a bounded `maxWidth` from the parent,
 * the paragraph soft-wraps to that width; with `minWidth`, the box
 * grows to at least that wide so the background spans the full slot
 * even when the text is shorter. The paragraph itself always renders
 * at the box's top-left.
 *
 * **Render order, back to front.**
 *   1. [background] (if non-null) painted as a (possibly rounded)
 *      rect filling the *box bounds*. With `Modifier.fillMaxWidth()`
 *      the box stretches and the background follows.
 *   2. Stroke pass - if [stroke] is non-null, draws the same
 *      paragraph in outlined form with the stroke's paint.
 *   3. Fill pass - the regular filled paragraph, honouring
 *      [ShapedTextStyle.style] and [ShapedTextStyle.shadow].
 *
 * Shadow placement follows the visible silhouette: when [stroke] is
 * non-null, the shadow paints under the stroke pass (so it tracks
 * the outlined silhouette); otherwise it paints under the fill
 * pass.
 */
@Composable
public fun ShapedTextBox(
    text: String,
    style: ShapedTextStyle,
    modifier: Modifier = Modifier,
    background: Brush? = null,
    backgroundCornerRadius: CornerRadius = CornerRadius.Zero,
    stroke: ShapedTextStroke? = null,
    onTextLayout: ((MeasuredParagraph) -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier,
        // Relay the caller's min constraints to the inner layout. With
        // [Modifier.fillMaxWidth] / [Modifier.height] the user is
        // asking for a fixed slot size; without this the inner
        // layout's `lc.minWidth` stays 0 and the box collapses to
        // content size, leaving the background painting over only the
        // text region instead of the slot.
        propagateMinConstraints = true,
    ) {
        val maxWidthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.toFloat()
        } else {
            Float.POSITIVE_INFINITY
        }
        val paragraphLoad by rememberMeasuredParagraph(
            text = text,
            fontStack = style.fontStack,
            sizePx = style.fontSize,
            maxWidth = maxWidthPx,
            alignment = style.alignment,
            direction = style.direction,
            features = style.features,
            language = style.language,
            justification = style.justification,
        )
        val paragraph = (paragraphLoad as? MeasuredParagraphLoad.Ready)?.measured

        LaunchedEffect(paragraph) {
            if (paragraph != null) onTextLayout?.invoke(paragraph)
        }

        val density = LocalDensity.current
        val strokeWidthPx = stroke?.let { with(density) { it.width.toPx() } } ?: 0f

        Box(
            modifier = Modifier
                // drawBehind sits OUTSIDE the layout modifier in the
                // chain so it observes the slot size produced by
                // [layout] below. Reversing the order would draw at
                // the empty content's size (0x0).
                .drawBehind {
                    if (background != null) {
                        drawBackground(background, backgroundCornerRadius, size)
                    }
                    val p = paragraph ?: return@drawBehind
                    if (p.isEmpty) return@drawBehind

                    if (stroke != null) {
                        drawStrokePass(
                            paragraph = p,
                            stroke = stroke,
                            strokeWidthPx = strokeWidthPx,
                            features = style.features,
                            shadow = style.shadow,
                        )
                    }

                    drawFillPass(
                        paragraph = p,
                        style = style,
                        // Suppress duplicate shadow on the fill pass
                        // when the stroke pass already owns it.
                        suppressShadow = stroke != null,
                    )
                }
                .layout { measurable, lc ->
                    val contentWidth = if (paragraph == null || paragraph.isEmpty) 0
                    else ceil(paragraph.width).toInt().coerceAtLeast(0)
                    val contentHeight = if (paragraph == null || paragraph.isEmpty) 0
                    else ceil(paragraph.height).toInt().coerceAtLeast(0)
                    val width = contentWidth.coerceIn(lc.minWidth, lc.maxWidth)
                    val height = contentHeight.coerceIn(lc.minHeight, lc.maxHeight)
                    val placeable = measurable.measure(lc.copy(minWidth = 0, minHeight = 0))
                    layout(width, height) { placeable.place(0, 0) }
                },
            content = {},
        )
    }
}

private fun DrawScope.drawBackground(
    background: Brush,
    cornerRadius: CornerRadius,
    size: Size,
) {
    if (cornerRadius == CornerRadius.Zero) {
        drawRect(brush = background, size = size)
    } else {
        drawRoundRect(brush = background, size = size, cornerRadius = cornerRadius)
    }
}

private fun DrawScope.drawStrokePass(
    paragraph: MeasuredParagraph,
    stroke: ShapedTextStroke,
    strokeWidthPx: Float,
    features: List<com.mohamedrejeb.harfbuzz.core.HbFeature>,
    shadow: androidx.compose.ui.graphics.Shadow?,
) {
    val drawStyle = Stroke(
        width = strokeWidthPx,
        miter = stroke.miter,
        cap = stroke.cap,
        join = stroke.join,
    )
    // Stroke colour / brush should override every glyph's designed
    // paint - otherwise COLR v0 layers paint with their palette ink
    // and the user-provided stroke is invisible on coloured fonts.
    val forceForeground = stroke.brush != null || stroke.color.isSpecified
    val brush = stroke.brush
    if (brush != null) {
        drawShapedParagraph(
            paragraph = paragraph,
            brush = brush,
            topLeft = Offset.Zero,
            style = drawStyle,
            forceForegroundColor = forceForeground,
            shadow = shadow,
        )
    } else {
        drawShapedParagraph(
            paragraph = paragraph,
            color = stroke.color.takeOrElse { Color.Black },
            topLeft = Offset.Zero,
            style = drawStyle,
            forceForegroundColor = forceForeground,
            shadow = shadow,
        )
    }
}

private fun DrawScope.drawFillPass(
    paragraph: MeasuredParagraph,
    style: ShapedTextStyle,
    suppressShadow: Boolean,
) {
    val resolvedColor = style.color.takeOrElse { Color.Black }
    val shadow = if (suppressShadow) null else style.shadow
    val brush = style.brush
    if (brush != null) {
        drawShapedParagraph(
            paragraph = paragraph,
            brush = brush,
            topLeft = Offset.Zero,
            style = style.style,
            forceForegroundColor = style.forceForegroundColor,
            shadow = shadow,
        )
    } else {
        drawShapedParagraph(
            paragraph = paragraph,
            color = resolvedColor,
            topLeft = Offset.Zero,
            style = style.style,
            forceForegroundColor = style.forceForegroundColor,
            shadow = shadow,
        )
    }
}
