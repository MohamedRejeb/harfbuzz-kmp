package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/**
 * Lay out [measured]'s shaped paragraph along [path] by **warping every
 * vertex of the combined glyph outline onto the path**, instead of
 * placing each glyph as a rigid rotated rectangle the way
 * [drawTextAlongPath] does.
 *
 * Why two drawers: per-glyph rigid placement rotates each glyph by its
 * own tangent angle and translates to its midpoint. On a tight curve
 * the connecting tail of an Arabic letter and the connecting head of
 * the next letter sit at slightly different rotation angles, which
 * shatters cursive joins (the letters look isolated / broken).
 * [drawWarpedTextAlongPath] avoids this: it builds one combined Path
 * from the shaped run, samples every sub-contour, and remaps each
 * `(x, y)` sample onto the user's path so cursive strokes stay
 * continuous across glyph boundaries.
 *
 * Color glyphs (COLR v0 / v1 paint trees, SVG-in-OT bitmaps) cannot
 * be expressed as a single monochrome outline path that the warp
 * algorithm operates on. When [measured]'s font stack contains any
 * color glyphs (`measured.hasColorGlyphs == true`) this function
 * silently delegates to [drawTextAlongPath], so callers do not have
 * to special-case emoji / decorative-color fonts. Cursive-join
 * smoothness is the warp drawer's gain over the rigid drawer and
 * does not apply to color emoji anyway, so the fallback is the
 * correct rendering choice in that case.
 *
 * Algorithm sketch:
 *  1. Build a flat baseline Path containing every glyph's outline,
 *     translated by its accumulated pen position. Y = 0 is the
 *     baseline; ascenders have negative y in Compose y-down coords.
 *  2. Sample each sub-contour of the baseline Path at [sampleStep]
 *     pixel intervals. Multi-contour iteration is needed because
 *     Arabic medial forms and Latin O / D / e have holes.
 *  3. For every sample `(x, y)`, the position along the user's path
 *     is `dist = startOffset + alignDelta + x * spacingScale`. The
 *     warped position is `userPath.getPosition(dist)` offset
 *     perpendicular to the local tangent by `y` (sign flipped for
 *     [TextOnPathSide.Below]).
 *  4. Build the warped Path and draw it once. Holes survive because
 *     contour boundaries are preserved.
 *
 * [overflow] semantics match [drawTextAlongPath]:
 *  - [TextOnPathOverflow.Clip] drops samples with `dist` outside the
 *    path.
 *  - [TextOnPathOverflow.Visible] extrapolates through the start /
 *    end tangents linearly so glyphs continue past the path ends.
 *  - [TextOnPathOverflow.Compress] scales `x` so the run fits the
 *    available path length exactly (alignment is forced to Start).
 *
 * [shadow] stamps a Gaussian-blurred copy of the warped Path
 * underneath, translated by `shadow.offset` in screen space. The
 * shadow is the warped-outline silhouette, so it tracks the text
 * curvature naturally.
 */
public fun DrawScope.drawWarpedTextAlongPath(
    measured: MeasuredText,
    path: Path,
    color: Color = Color.Black,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    startOffset: Float = 0f,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    sampleStep: Float = 0.5f,
    shadow: Shadow? = null,
) {
    if (measured.isEmpty) return

    if (measured.hasColorGlyphs) {
        drawTextAlongPath(
            measured = measured,
            path = path,
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

    val pathMeasure = PathMeasure().apply { setPath(path, forceClosed = false) }
    val pathLength = pathMeasure.length
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
        startPos = pathMeasure.getPosition(0f)
        startTan = pathMeasure.getTangent(0f)
        endPos = pathMeasure.getPosition(pathLength)
        endTan = pathMeasure.getTangent(pathLength)
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
                    anchor = pathMeasure.getPosition(dist)
                    tangent = pathMeasure.getTangent(dist)
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

/**
 * Combine every glyph in [measured] into a single flat-baseline [Path].
 * Pen origin is `(0, 0)`; baseline is `y = 0`; ascenders extend into
 * negative y in Compose y-down coordinates because the per-glyph
 * outlines come from the cached flipped paths per font (already flipped
 * from HarfBuzz y-up). HarfBuzz `xOffset` / `yOffset` are applied
 * per-glyph; advances accumulate `xAdvance`.
 *
 * Public so external pipelines can implement their own warp drawers
 * (e.g. an arc-specific drawer that pre-bakes the warped path inside
 * a layout snapshot and reuses it across frames). Library-supplied
 * drawers built on top of this primitive: [drawWarpedTextAlongPath]
 * (generic path) and [drawArcText] (analytical circular arc).
 *
 * Glyphs whose outlines are missing (typically because the font has a
 * color paint tree but no monochrome silhouette) still advance the pen
 * so the placement of subsequent glyphs is correct. They simply do
 * not contribute to the path - which is the right behaviour for warp
 * drawers, which are monochrome-only by design.
 */
public fun buildBaselineGlyphPath(measured: MeasuredText): Path {
    val src = Path()
    var penX = 0f
    for (run in measured.paragraph.runs) {
        val font = run.font ?: measured.font
        val flipped = measured.flippedPathsByFont[font]
        for (i in run.glyphs.indices) {
            val pos = run.positions[i]
            val gp = flipped?.get(run.glyphs[i].glyphId)
            if (gp != null) {
                src.addPath(gp, offset = Offset(penX + pos.xOffset, -pos.yOffset))
            }
            penX += pos.xAdvance
        }
    }
    return src
}
