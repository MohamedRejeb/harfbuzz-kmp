package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlin.math.ceil

/**
 * Multi-line counterpart to `ShapedText`. Lays [text] out under the
 * current `BoxWithConstraints.maxWidth` (or [maxWidthOverride] if
 * provided), applies [alignment] per line, and draws every line through
 * the same color-glyph pipeline as the single-line composable.
 *
 * Sizing: width matches the actual paragraph width (max line advance,
 * clamped to the constraint); height matches the bottom of the last line
 * (no trailing line-spacing). The composable does not yet enforce
 * `maxLines`, soft-wrap toggle, or overflow semantics - those land in a
 * follow-up.
 */
@Composable
public fun ShapedParagraphText(
    text: String,
    font: HbFont,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    direction: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    lineSpacing: Float = 0f,
    justification: JustificationStrategy = JustificationStrategy.None,
    forceForegroundColor: Boolean = false,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    maxWidthOverride: Float? = null,
    onTextLayout: ((MeasuredParagraph) -> Unit)? = null,
) {
    val stack = remember(font) { HbFontStack(font) }
    ShapedParagraphText(
        text = text,
        fontStack = stack,
        modifier = modifier,
        color = color,
        alignment = alignment,
        direction = direction,
        features = features,
        language = language,
        lineSpacing = lineSpacing,
        justification = justification,
        forceForegroundColor = forceForegroundColor,
        style = style,
        shadow = shadow,
        maxWidthOverride = maxWidthOverride,
        onTextLayout = onTextLayout,
    )
}

/**
 * Multi-font [ShapedParagraphText] - any cluster the primary font cannot
 * resolve falls back to [fontStack]'s configured fallbacks. Use this when
 * a paragraph mixes scripts (Latin + Arabic + emoji).
 */
@Composable
public fun ShapedParagraphText(
    text: String,
    fontStack: HbFontStack,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    direction: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    lineSpacing: Float = 0f,
    justification: JustificationStrategy = JustificationStrategy.None,
    forceForegroundColor: Boolean = false,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
    maxWidthOverride: Float? = null,
    onTextLayout: ((MeasuredParagraph) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = maxWidthOverride
            ?: this@BoxWithConstraints.constraints.maxWidth.toFloat()
        val loadState by rememberMeasuredParagraph(
            text = text,
            fontStack = fontStack,
            maxWidth = maxWidthPx,
            alignment = alignment,
            direction = direction,
            features = features,
            language = language,
            lineSpacing = lineSpacing,
            justification = justification,
        )
        val resolvedColor = color.takeOrElse { Color.Black }
        val measured: MeasuredParagraph? = (loadState as? MeasuredParagraphLoad.Ready)?.measured

        LaunchedEffect(measured) {
            if (measured != null) onTextLayout?.invoke(measured)
        }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val width = if (measured == null || measured.isEmpty) 0
                    else min(constraints.maxWidth, ceil(measured.width).toInt().coerceAtLeast(0))
                    val height = if (measured == null || measured.isEmpty) 0
                    else ceil(measured.height).toInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(width, height) {
                        placeable.place(0, 0)
                    }
                }
                .drawBehind {
                    if (measured != null) {
                        drawShapedParagraph(
                            paragraph = measured,
                            color = resolvedColor,
                            style = style,
                            forceForegroundColor = forceForegroundColor,
                            shadow = shadow,
                        )
                    }
                },
            content = {},
        )
    }
}

private fun min(a: Int, b: Int): Int = if (a < b) a else b
