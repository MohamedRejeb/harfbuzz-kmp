package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * Lay [measured]'s shaped paragraph along a circular arc by warping
 * every vertex of the combined glyph outline onto the arc analytically,
 * without building a Compose [Path] for the arc or invoking
 * `PathMeasure` on it.
 *
 * Visual semantics match [drawWarpedTextAlongPath] when the user's path
 * happens to be a circular arc; the win is performance: per-sample
 * placement is one cos/sin pair instead of a native `PathMeasure` call,
 * and the arc [Path] itself never has to be allocated. Worth choosing
 * whenever the caller already has the arc parameters in hand (radius,
 * start angle, sweep) - typical for animated arc-text previews where
 * geometry is recomputed every frame from a slider.
 *
 * Placement convention follows Compose's [Path.addArc]:
 *  - 0 deg sits on the +X axis; angles increase clockwise in screen-Y-down
 *    space (90 deg at +Y, 180 deg at -X, 270 deg at -Y).
 *  - Positive [sweepAngleDeg] sweeps clockwise; negative sweeps
 *    counter-clockwise. To put text on the inside of the circle,
 *    flip the sign of [sweepAngleDeg]: the tangent direction reverses
 *    with it, which puts ascenders toward the centre.
 *
 * Color glyphs follow the same fallback rule as [drawWarpedTextAlongPath]:
 * when [measured] has any color glyphs the call routes through
 * [drawTextAlongPath] with a freshly-built arc [Path] so emoji /
 * COLR / SVG-in-OT glyphs render correctly. Cursive-join smoothness is
 * the warp drawer's gain over rigid placement and does not apply to
 * color emoji anyway, so the fallback is the right rendering choice.
 *
 * [overflow] semantics match [drawWarpedTextAlongPath]:
 *  - [TextOnPathOverflow.Clip] drops samples whose distance falls
 *    outside `[0, arcLength]` (where `arcLength = |sweepAngleRad| ·
 *    radiusPx`).
 *  - [TextOnPathOverflow.Visible] extrapolates samples outside the
 *    arc through the analytical start / end tangents - the linear
 *    continuation of the circle at the endpoints.
 *  - [TextOnPathOverflow.Compress] scales `x` so the run fits the
 *    available arc length exactly; alignment is forced to Start.
 */
public fun DrawScope.drawArcText(
    measured: MeasuredText,
    center: Offset,
    radiusPx: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    color: Color = Color.Black,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    startOffset: Float = 0f,
    sampleStep: Float = 0.5f,
    shadow: Shadow? = null,
) {
    if (measured.isEmpty || radiusPx <= 0f || sweepAngleDeg == 0f) return

    if (measured.hasColorGlyphs) {
        val arcPath = Path().apply {
            addArc(
                oval = Rect(
                    left = center.x - radiusPx,
                    top = center.y - radiusPx,
                    right = center.x + radiusPx,
                    bottom = center.y + radiusPx,
                ),
                startAngleDegrees = startAngleDeg,
                sweepAngleDegrees = sweepAngleDeg,
            )
        }
        drawTextAlongPath(
            measured = measured,
            path = arcPath,
            color = color,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            startOffset = startOffset,
            side = side,
            alignment = alignment,
            overflow = overflow,
            autoFlip = false,
            forceForegroundColor = false,
            shadow = shadow,
        )
        return
    }

    val degToRad = PI.toFloat() / 180f
    val sweepAngleRad = sweepAngleDeg * degToRad
    val startAngleRad = startAngleDeg * degToRad
    val dirSign = sign(sweepAngleRad)
    val pathLength = abs(sweepAngleRad) * radiusPx
    if (pathLength <= 0f) return

    val baseline = buildBaselineGlyphPath(measured)
    val contours = samplePathContours(baseline, sampleStep)
    if (contours.isEmpty()) return

    val totalAdvance = measured.paragraph.totalAdvance
    val effectiveLen = pathLength - startOffset
    val spacingScale = if (
        overflow == TextOnPathOverflow.Compress &&
        totalAdvance > effectiveLen &&
        effectiveLen > 0f
    ) effectiveLen / totalAdvance else 1f

    val alignDelta = if (spacingScale < 1f) 0f else when (alignment) {
        TextOnPathAlignment.Start -> 0f
        TextOnPathAlignment.Center -> (pathLength - totalAdvance * spacingScale) / 2f
        TextOnPathAlignment.End -> pathLength - totalAdvance * spacingScale
    }
    val startDistance = startOffset + alignDelta
    val sideY = if (side == TextOnPathSide.Below) -1f else 1f

    val startPos: Offset
    val startTan: Offset
    val endPos: Offset
    val endTan: Offset
    if (overflow == TextOnPathOverflow.Visible) {
        val a0 = startAngleRad
        startPos = Offset(center.x + radiusPx * cos(a0), center.y + radiusPx * sin(a0))
        startTan = Offset(-sin(a0) * dirSign, cos(a0) * dirSign)
        val a1 = startAngleRad + sweepAngleRad
        endPos = Offset(center.x + radiusPx * cos(a1), center.y + radiusPx * sin(a1))
        endTan = Offset(-sin(a1) * dirSign, cos(a1) * dirSign)
    } else {
        startPos = Offset.Zero
        startTan = Offset(1f, 0f)
        endPos = Offset.Zero
        endTan = Offset(1f, 0f)
    }

    val warped = Path()
    for (contour in contours) {
        var first = true
        var i = 0
        while (i < contour.size) {
            val xLocal = contour[i]
            val yLocal = contour[i + 1]
            i += 2

            val dist = startDistance + xLocal * spacingScale
            val anchor: Offset
            val tangent: Offset
            when {
                dist in 0f..pathLength -> {
                    val a = startAngleRad + dirSign * dist / radiusPx
                    anchor = Offset(
                        center.x + radiusPx * cos(a),
                        center.y + radiusPx * sin(a),
                    )
                    tangent = Offset(-sin(a) * dirSign, cos(a) * dirSign)
                }
                overflow == TextOnPathOverflow.Visible && dist < 0f -> {
                    anchor = Offset(
                        startPos.x + startTan.x * dist,
                        startPos.y + startTan.y * dist,
                    )
                    tangent = startTan
                }
                overflow == TextOnPathOverflow.Visible && dist > pathLength -> {
                    val extra = dist - pathLength
                    anchor = Offset(
                        endPos.x + endTan.x * extra,
                        endPos.y + endTan.y * extra,
                    )
                    tangent = endTan
                }
                else -> continue
            }

            val perpX = -tangent.y * sideY
            val perpY = tangent.x * sideY
            val wx = anchor.x + yLocal * perpX
            val wy = anchor.y + yLocal * perpY

            if (first) {
                warped.moveTo(wx, wy)
                first = false
            } else {
                warped.lineTo(wx, wy)
            }
        }
        if (!first) warped.close()
    }

    if (shadow != null) {
        val shadowPaint = Paint().apply {
            this.color = shadow.color
            this.alpha = alpha
            this.blendMode = blendMode
            applyDrawStyleToPaint(this, style)
            configureShadowBlur(this, shadow.blurRadius)
        }
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(shadow.offset.x, shadow.offset.y)
            canvas.drawPath(warped, shadowPaint)
            canvas.restore()
        }
    }

    drawPath(
        path = warped,
        color = color,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
    )
}
