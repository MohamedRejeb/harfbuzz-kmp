package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlin.math.ceil

/**
 * Render a single piece of text with one font, one color, one feature set.
 *
 * Single-line composable: shapes [text] once and lays it out within the
 * parent's `maxWidth` constraint. For multi-line wrapping use
 * `ShapedParagraphText`.
 *
 * - [alignment] positions the line within the available width. Reuses
 *   [ParagraphAlignment] so single-line and paragraph call sites pick
 *   from the same enum (Start/End/Left/Right/Center/Justify).
 * - [justification] picks how a [ParagraphAlignment.Justify] line is
 *   widened (Kashida vs thin-space). Ignored for non-Justify alignments.
 *   Justify with [JustificationStrategy.None] falls back to Start.
 * - [overflow] decides what happens when the shaped advance exceeds the
 *   available width. [ShapedTextOverflow.Clip] (default) clips to the
 *   bounds; [ShapedTextOverflow.Visible] lets glyphs paint outside;
 *   [ShapedTextOverflow.Compress] scales per-glyph spacing so the line
 *   fits the slot exactly (glyphs keep their size, spacing shrinks).
 */
@Composable
public fun ShapedText(
    text: String,
    font: HbFont,
    sizePx: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    /**
     * Optional [Brush] (e.g. [Brush.linearGradient]) that paints
     * monochrome glyph silhouettes and COLR v0 foreground-color layers.
     * When non-null this takes precedence over [color] for those glyphs;
     * SVG-in-OT bitmaps and COLR v1 paint trees and COLR v0 palette
     * layers continue to render with their font-designed colors so emoji
     * and SVG-painted fonts (e.g. Aref Ruqaa Ink) keep their authored
     * appearance. The brush spans the line's combined silhouette path,
     * so a gradient flows from the line's leading edge to its trailing
     * edge rather than repeating per glyph.
     */
    brush: Brush? = null,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    /**
     * For COLR v0 color fonts: by default each glyph paints with the
     * palette colors baked into the font (so e.g. Aref Ruqaa Ink renders
     * in its designed inks regardless of [color]). Set this to `true` to
     * suppress that and use [color] (or [brush]) for every glyph.
     */
    forceForegroundColor: Boolean = false,
    /**
     * Render glyphs as filled shapes (default) or stroked outlines. A
     * non-Fill style routes every glyph to the silhouette outline, so a
     * stroke applies to the actual glyph shape regardless of color
     * tables (SVG bitmaps and COLR paint trees fall through).
     */
    style: DrawStyle = Fill,
    /**
     * Optional drop shadow with offset, blur radius, and color. Renders
     * underneath the main text. The shadow follows [style] (a stroked
     * text gets a stroked shadow) and uses the silhouette only for color
     * glyphs - same convention as Compose's `BasicText` shadow.
     */
    shadow: Shadow? = null,
    onTextLayout: ((MeasuredText) -> Unit)? = null,
) {
    val stack = remember(font) { HbFontStack(font) }
    ShapedText(
        text = text,
        fontStack = stack,
        sizePx = sizePx,
        modifier = modifier,
        color = color,
        brush = brush,
        features = features,
        direction = direction,
        overflow = overflow,
        alignment = alignment,
        justification = justification,
        forceForegroundColor = forceForegroundColor,
        style = style,
        shadow = shadow,
        onTextLayout = onTextLayout,
    )
}

/**
 * Multi-font [ShapedText] - same composable but routes shaping through
 * [fontStack], so any cluster the primary font cannot resolve falls back
 * to the configured fallbacks in order. Use this when a single string
 * mixes scripts (Latin + Arabic + emoji) that no individual font covers.
 */
@Composable
public fun ShapedText(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    forceForegroundColor: Boolean = false,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    onTextLayout: ((MeasuredText) -> Unit)? = null,
) {
    val justifyOn = alignment == ParagraphAlignment.Justify &&
        justification != JustificationStrategy.None

    if (justifyOn) {
        // Justify needs the slot width before shaping, so wrap in
        // BoxWithConstraints to read it. The user's modifier sits on
        // the outer wrapper.
        BoxWithConstraints(modifier = modifier) {
            val maxWidthPx = if (constraints.hasBoundedWidth)
                constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY
            ShapedTextBody(
                text = text,
                fontStack = fontStack,
                sizePx = sizePx,
                modifier = Modifier,
                color = color,
                brush = brush,
                features = features,
                direction = direction,
                overflow = overflow,
                alignment = alignment,
                justification = justification,
                justifyMaxWidth = maxWidthPx,
                forceForegroundColor = forceForegroundColor,
                style = style,
                shadow = shadow,
                onTextLayout = onTextLayout,
            )
        }
    } else {
        ShapedTextBody(
            text = text,
            fontStack = fontStack,
            sizePx = sizePx,
            modifier = modifier,
            color = color,
            brush = brush,
            features = features,
            direction = direction,
            overflow = overflow,
            alignment = alignment,
            justification = justification,
            justifyMaxWidth = Float.POSITIVE_INFINITY,
            forceForegroundColor = forceForegroundColor,
            style = style,
            shadow = shadow,
            onTextLayout = onTextLayout,
        )
    }
}

/**
 * Styled-text variant of [ShapedText]. Same shaping as the [String]
 * overload above; per-character paint comes from [text]'s spans via
 * the cluster-position heuristic. The composable's [color] / [brush]
 * are the **default** paint for chars no span covers.
 *
 * For empty [StyledText.spans] this is byte-identical to the
 * [String] overload (the styled draw path dispatches back to the
 * uniform-paint internal in that case).
 */
@Composable
public fun ShapedText(
    text: StyledText,
    font: HbFont,
    sizePx: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    forceForegroundColor: Boolean = false,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    onTextLayout: ((MeasuredText) -> Unit)? = null,
) {
    val stack = remember(font) { HbFontStack(font) }
    ShapedText(
        text = text,
        fontStack = stack,
        sizePx = sizePx,
        modifier = modifier,
        color = color,
        brush = brush,
        features = features,
        direction = direction,
        overflow = overflow,
        alignment = alignment,
        justification = justification,
        forceForegroundColor = forceForegroundColor,
        style = style,
        shadow = shadow,
        onTextLayout = onTextLayout,
    )
}

/**
 * Multi-font [ShapedText] taking [StyledText]. Routes shaping
 * through [fontStack] (so any cluster the primary font cannot
 * resolve falls back to the configured fallbacks in order); per-
 * character paint comes from [text]'s spans.
 */
@Composable
public fun ShapedText(
    text: StyledText,
    fontStack: HbFontStack,
    sizePx: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    forceForegroundColor: Boolean = false,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    onTextLayout: ((MeasuredText) -> Unit)? = null,
) {
    val justifyOn = alignment == ParagraphAlignment.Justify &&
        justification != JustificationStrategy.None

    if (justifyOn) {
        BoxWithConstraints(modifier = modifier) {
            val maxWidthPx = if (constraints.hasBoundedWidth)
                constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY
            ShapedTextStyledBody(
                text = text,
                fontStack = fontStack,
                sizePx = sizePx,
                modifier = Modifier,
                color = color,
                brush = brush,
                features = features,
                direction = direction,
                overflow = overflow,
                alignment = alignment,
                justification = justification,
                justifyMaxWidth = maxWidthPx,
                forceForegroundColor = forceForegroundColor,
                style = style,
                shadow = shadow,
                onTextLayout = onTextLayout,
            )
        }
    } else {
        ShapedTextStyledBody(
            text = text,
            fontStack = fontStack,
            sizePx = sizePx,
            modifier = modifier,
            color = color,
            brush = brush,
            features = features,
            direction = direction,
            overflow = overflow,
            alignment = alignment,
            justification = justification,
            justifyMaxWidth = Float.POSITIVE_INFINITY,
            forceForegroundColor = forceForegroundColor,
            style = style,
            shadow = shadow,
            onTextLayout = onTextLayout,
        )
    }
}

@Composable
private fun ShapedTextStyledBody(
    text: StyledText,
    fontStack: HbFontStack,
    sizePx: Float,
    modifier: Modifier,
    color: Color,
    brush: Brush?,
    features: List<HbFeature>,
    direction: HbDirection,
    overflow: ShapedTextOverflow,
    alignment: ParagraphAlignment,
    justification: JustificationStrategy,
    justifyMaxWidth: Float,
    forceForegroundColor: Boolean,
    style: DrawStyle,
    shadow: Shadow?,
    onTextLayout: ((MeasuredText) -> Unit)?,
) {
    val loadState by rememberMeasuredText(
        text = text.text,
        fontStack = fontStack,
        sizePx = sizePx,
        features = features,
        direction = direction,
        maxWidth = justifyMaxWidth,
        justification = justification,
    )
    val resolvedColor = color.takeOrElse { Color.Black }
    val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured

    LaunchedEffect(measured) {
        if (measured != null) onTextLayout?.invoke(measured)
    }

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                if (measured == null || measured.isEmpty) {
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    return@layout layout(0, 0) { placeable.place(0, 0) }
                }
                val advancePx = ceil(measured.advance).toInt().coerceAtLeast(0)
                val naturalExtentPx = ceil(maxOf(measured.advance, measured.ink.right))
                    .toInt().coerceAtLeast(0)
                val width = when {
                    overflow == ShapedTextOverflow.Visible ->
                        minOf(constraints.maxWidth, advancePx)
                    overflow == ShapedTextOverflow.Compress &&
                        constraints.hasBoundedWidth &&
                        naturalExtentPx > constraints.maxWidth ->
                        constraints.maxWidth
                    constraints.hasBoundedWidth && alignment != ParagraphAlignment.Start ->
                        constraints.maxWidth
                    else -> minOf(constraints.maxWidth, advancePx)
                }
                val height = ceil(measured.lineHeight).toInt().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(width, height) { placeable.place(0, 0) }
            }
            .drawBehind {
                if (measured != null) {
                    drawShapedText(
                        measured = measured,
                        styledText = text,
                        color = resolvedColor,
                        brush = brush,
                        style = style,
                        forceForegroundColor = forceForegroundColor,
                        shadow = shadow,
                    )
                }
            },
        content = {},
    )
}

@Composable
private fun ShapedTextBody(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    modifier: Modifier,
    color: Color,
    brush: Brush?,
    features: List<HbFeature>,
    direction: HbDirection,
    overflow: ShapedTextOverflow,
    alignment: ParagraphAlignment,
    justification: JustificationStrategy,
    justifyMaxWidth: Float,
    forceForegroundColor: Boolean,
    style: DrawStyle,
    shadow: Shadow?,
    onTextLayout: ((MeasuredText) -> Unit)?,
) {
    val loadState by rememberMeasuredText(
        text = text,
        fontStack = fontStack,
        sizePx = sizePx,
        features = features,
        direction = direction,
        maxWidth = justifyMaxWidth,
        justification = justification,
    )
    val resolvedColor = color.takeOrElse { Color.Black }
    val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured

    LaunchedEffect(measured) {
        if (measured != null) onTextLayout?.invoke(measured)
    }

    // The DrawScope `size` inside drawBehind reflects the inner element's
    // measured size (an empty Box, so 0x0 here), not what `Modifier.layout`
    // reports to the parent. Mirror the chosen slot width to a state so
    // drawBehind has a real number to align / clip against.
    val slotWidth = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                if (measured == null || measured.isEmpty) {
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    slotWidth.floatValue = 0f
                    return@layout layout(0, 0) { placeable.place(0, 0) }
                }
                val advancePx = ceil(measured.advance).toInt().coerceAtLeast(0)
                // Cursive scripts (Arabic) can have ink extending past
                // the pen advance, so check both for the Compress
                // overflow trigger.
                val naturalExtentPx = ceil(
                    maxOf(measured.advance, measured.ink.right),
                ).toInt().coerceAtLeast(0)
                val width = when {
                    overflow == ShapedTextOverflow.Visible ->
                        minOf(constraints.maxWidth, advancePx)
                    overflow == ShapedTextOverflow.Compress &&
                        constraints.hasBoundedWidth &&
                        naturalExtentPx > constraints.maxWidth ->
                        // Compress always fills the slot - the line is
                        // squeezed to fit so the reported size matches
                        // the slot, not the natural advance.
                        constraints.maxWidth
                    constraints.hasBoundedWidth && alignment != ParagraphAlignment.Start ->
                        constraints.maxWidth
                    else -> minOf(constraints.maxWidth, advancePx)
                }
                val height = ceil(measured.lineHeight).toInt().coerceAtLeast(0)
                slotWidth.floatValue = width.toFloat()
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(width, height) { placeable.place(0, 0) }
            }
            .drawBehind {
                if (measured != null) {
                    // Default Start with the box sized to the line's
                    // advance is the legacy code path: pen at (0,0), no
                    // alignment offset, no clip. The layout block above
                    // shrinks the slot to `ceil(advance)` for Start, so
                    // any residual slack is at most 1px of rounding -
                    // routing that through the alignment-aware overload
                    // would shift RTL glyphs right by the slack amount
                    // (visible as a 1px AA jitter on Arabic). Only take
                    // this fast path when the line actually fits the
                    // slot - if it overflows, the new overload owns the
                    // clip (or visible spill, depending on [overflow]).
                    val isLegacyStart = alignment == ParagraphAlignment.Start &&
                        slotWidth.floatValue >= measured.advance &&
                        slotWidth.floatValue <= measured.advance + 1f
                    if (isLegacyStart) {
                        if (brush != null) {
                            drawShapedText(
                                measured = measured,
                                brush = brush,
                                style = style,
                                forceForegroundColor = forceForegroundColor,
                                shadow = shadow,
                            )
                        } else {
                            drawShapedText(
                                measured = measured,
                                color = resolvedColor,
                                style = style,
                                forceForegroundColor = forceForegroundColor,
                                shadow = shadow,
                            )
                        }
                    } else {
                        if (brush != null) {
                            drawShapedText(
                                measured = measured,
                                availableWidth = slotWidth.floatValue,
                                brush = brush,
                                alignment = alignment,
                                direction = measured.paragraph.baseDirection,
                                overflow = overflow,
                                style = style,
                                forceForegroundColor = forceForegroundColor,
                                shadow = shadow,
                            )
                        } else {
                            drawShapedText(
                                measured = measured,
                                availableWidth = slotWidth.floatValue,
                                alignment = alignment,
                                direction = measured.paragraph.baseDirection,
                                overflow = overflow,
                                color = resolvedColor,
                                style = style,
                                forceForegroundColor = forceForegroundColor,
                                shadow = shadow,
                            )
                        }
                    }
                }
            },
        content = {},
    )
}
