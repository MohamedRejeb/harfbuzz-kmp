package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import kotlin.math.ceil
import kotlin.math.max

/**
 * Render a single piece of text with one font, one color, one feature set.
 * v1's "I just want correct Arabic" composable. The full `BasicText` parity
 * (per-span styling, full TextStyle support) ships as v1.1's `ShapedBasicText`.
 */
@Composable
public fun ShapedText(
    text: String,
    font: HbFont,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    /**
     * For COLR v0 color fonts: by default each glyph paints with the
     * palette colors baked into the font (so e.g. Aref Ruqaa Ink renders
     * in its designed inks regardless of [color]). Set this to `true` to
     * suppress that and use [color] for every glyph.
     */
    forceForegroundColor: Boolean = false,
    onTextLayout: ((MeasuredText) -> Unit)? = null,
) {
    val stack = remember(font) { HbFontStack(font) }
    ShapedText(
        text = text,
        fontStack = stack,
        modifier = modifier,
        color = color,
        features = features,
        direction = direction,
        softWrap = softWrap,
        overflow = overflow,
        maxLines = maxLines,
        forceForegroundColor = forceForegroundColor,
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
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    forceForegroundColor: Boolean = false,
    onTextLayout: ((MeasuredText) -> Unit)? = null,
) {
    val loadState by rememberMeasuredText(text, fontStack, features, direction)
    val resolvedColor = color.takeOrElse { Color.Black }
    val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured

    // Notify the caller whenever a fresh Ready measurement lands.
    LaunchedEffect(measured) {
        if (measured != null) onTextLayout?.invoke(measured)
    }

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val width = if (measured == null || measured.isEmpty) 0
                else min(constraints.maxWidth, ceil(measured.advance).toInt().coerceAtLeast(0))
                val height = if (measured == null || measured.isEmpty) 0
                else ceil(measured.lineHeight).toInt().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(width, height) {
                    placeable.place(0, 0)
                }
            }
            .drawBehind {
                if (measured != null) {
                    drawShapedText(measured, color = resolvedColor, forceForegroundColor = forceForegroundColor)
                }
            },
        content = {},
    )
    @Suppress("UNUSED_VARIABLE")
    val unused = listOf(softWrap, overflow, maxLines, IntSize.Zero)  // wired in v1.1's ShapedBasicText
}

private fun min(a: Int, b: Int): Int = if (a < b) a else b
