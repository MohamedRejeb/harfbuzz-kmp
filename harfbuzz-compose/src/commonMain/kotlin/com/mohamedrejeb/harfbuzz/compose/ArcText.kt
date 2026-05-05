package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack

/**
 * Lays text along a circular arc of [radius]. Thin wrapper over
 * [TextOnPath] / [drawTextAlongPath]: builds the corresponding
 * circular path and maps the arc-flavoured enums to their
 * path-text equivalents.
 *
 * [overflow] controls what happens when the shaped text doesn't
 * match the circle's circumference. The default [TextOnPathOverflow.Clip]
 * drops glyphs whose midpoint lies outside the path. Pass
 * [TextOnPathOverflow.Compress] to shrink per-glyph spacing so an
 * over-long line fits exactly. To stretch a short line into the
 * full ring, justify it via
 * [com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy.KashidaTo]
 * (Arabic) or
 * [com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy.AdvanceStretchTo]
 * (Latin / mixed) before handing the [MeasuredText] to the
 * `(measured, ...)` overload.
 */
@Composable
public fun ArcText(
    text: String,
    font: HbFont,
    sizePx: Float,
    radius: Dp,
    modifier: Modifier = Modifier,
    startAngle: Float = 0f,
    direction: ArcDirection = ArcDirection.Clockwise,
    side: ArcSide = ArcSide.Outside,
    color: Color = Color.Black,
    alignment: ArcAlignment = ArcAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    features: List<HbFeature> = emptyList(),
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
) {
    val loadState by rememberMeasuredText(text, font, sizePx, features)
    val measured = (loadState as? MeasuredTextLoad.Ready)?.measured

    Box(
        modifier = modifier.drawBehind {
            if (measured == null || measured.isEmpty) return@drawBehind
            val rPx = radius.toPx()
            val arcPath = buildArcPath(
                size = size,
                radiusPx = rPx,
                startAngleDeg = startAngle,
                direction = direction,
                side = side,
            )
            drawTextAlongPath(
                measured = measured,
                path = arcPath,
                color = color,
                style = style,
                alignment = when (alignment) {
                    ArcAlignment.Start -> TextOnPathAlignment.Start
                    ArcAlignment.Center -> TextOnPathAlignment.Center
                    ArcAlignment.End -> TextOnPathAlignment.End
                },
                overflow = overflow,
                autoFlip = false,
                shadow = shadow,
            )
        },
        content = {},
    )
}

@Composable
public fun ArcText(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    radius: Dp,
    modifier: Modifier = Modifier,
    startAngle: Float = 0f,
    direction: ArcDirection = ArcDirection.Clockwise,
    side: ArcSide = ArcSide.Outside,
    color: Color = Color.Black,
    alignment: ArcAlignment = ArcAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    features: List<HbFeature> = emptyList(),
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
) {
    val loadState by rememberMeasuredText(text, fontStack, sizePx, features)
    val measured = (loadState as? MeasuredTextLoad.Ready)?.measured

    Box(
        modifier = modifier.drawBehind {
            if (measured == null || measured.isEmpty) return@drawBehind
            val rPx = radius.toPx()
            val arcPath = buildArcPath(
                size = size,
                radiusPx = rPx,
                startAngleDeg = startAngle,
                direction = direction,
                side = side,
            )
            drawTextAlongPath(
                measured = measured,
                path = arcPath,
                color = color,
                style = style,
                alignment = when (alignment) {
                    ArcAlignment.Start -> TextOnPathAlignment.Start
                    ArcAlignment.Center -> TextOnPathAlignment.Center
                    ArcAlignment.End -> TextOnPathAlignment.End
                },
                overflow = overflow,
                autoFlip = false,
                shadow = shadow,
            )
        },
        content = {},
    )
}

/**
 * Render a pre-shaped [measured] text along an arc of [radius].
 * Identical placement and draw rules as the `(text, font, ...)` and
 * `(text, fontStack, ...)` overloads, but skips the internal
 * [rememberMeasuredText] call so callers can pre-justify the run.
 *
 * The intended use case is arc Kashida (Arabic) or arc advance-stretch
 * (Latin / mixed): shape `text` with
 * [com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy.KashidaTo]
 * or
 * [com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy.AdvanceStretchTo]
 * targeting the arc length, then hand the resulting [MeasuredText]
 * straight to this overload. The text is drawn at whatever advance the
 * justified shape produced; widening the input is the caller's job.
 */
@Composable
public fun ArcText(
    measured: MeasuredText,
    radius: Dp,
    modifier: Modifier = Modifier,
    startAngle: Float = 0f,
    direction: ArcDirection = ArcDirection.Clockwise,
    side: ArcSide = ArcSide.Outside,
    color: Color = Color.Black,
    alignment: ArcAlignment = ArcAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    style: DrawStyle = Fill,
    shadow: Shadow? = null,
) {
    Box(
        modifier = modifier.drawBehind {
            if (measured.isEmpty) return@drawBehind
            val rPx = radius.toPx()
            val arcPath = buildArcPath(
                size = size,
                radiusPx = rPx,
                startAngleDeg = startAngle,
                direction = direction,
                side = side,
            )
            drawTextAlongPath(
                measured = measured,
                path = arcPath,
                color = color,
                style = style,
                alignment = when (alignment) {
                    ArcAlignment.Start -> TextOnPathAlignment.Start
                    ArcAlignment.Center -> TextOnPathAlignment.Center
                    ArcAlignment.End -> TextOnPathAlignment.End
                },
                overflow = overflow,
                autoFlip = false,
                shadow = shadow,
            )
        },
        content = {},
    )
}

/**
 * Build a full-circle path of [radiusPx], centred in [size]. Sweep
 * direction follows [direction]; [side] reverses it again so "Inside"
 * text rides the inner edge of the circle (letters keep their natural
 * shape - the path itself runs the opposite way, which puts ascenders
 * toward the centre instead of mirroring the glyphs).
 */
private fun buildArcPath(
    size: Size,
    radiusPx: Float,
    startAngleDeg: Float,
    direction: ArcDirection,
    side: ArcSide,
): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val rect = Rect(
        left = cx - radiusPx, top = cy - radiusPx,
        right = cx + radiusPx, bottom = cy + radiusPx,
    )
    val directionSign = when (direction) {
        ArcDirection.Clockwise -> 1f
        ArcDirection.CounterClockwise -> -1f
    }
    val sideSign = when (side) {
        ArcSide.Outside -> 1f
        ArcSide.Inside -> -1f
    }
    val sweep = 360f * directionSign * sideSign
    return Path().apply { addArc(rect, startAngleDeg, sweep) }
}

@Immutable
public enum class ArcDirection { Clockwise, CounterClockwise }

@Immutable
public enum class ArcSide { Outside, Inside }

@Immutable
public enum class ArcAlignment { Start, Center, End }
