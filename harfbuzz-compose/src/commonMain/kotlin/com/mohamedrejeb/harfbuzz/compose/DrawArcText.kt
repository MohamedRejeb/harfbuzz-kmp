package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.mohamedrejeb.harfbuzz.core.HbDirection
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

    val arc = ArcWarpContext.computeOrNull(
        center = center,
        radiusPx = radiusPx,
        startAngleDeg = startAngleDeg,
        sweepAngleDeg = sweepAngleDeg,
        startOffset = startOffset,
        side = side,
        alignment = alignment,
        overflow = overflow,
        totalAdvance = measured.paragraph.totalAdvance,
    ) ?: return

    val contours = measured.contourSamples(sampleStep)
    if (contours.isEmpty()) return

    val warped = warpContoursToArc(contours, arc)

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
 * Styled-text variant of [drawArcText]. Each glyph's outline is
 * bucketed by the paint resolved from [styledText]'s spans (cluster
 * heuristic identical to [drawShapedText]'s styled path), then each
 * bucket's silhouette is sampled and warped onto the analytical arc
 * independently. The result is one combined warped path per
 * resolved paint, drawn in declaration order with the default paint
 * underneath.
 *
 * The chars in [styledText].text MUST equal the source string that
 * produced [measured]; cluster ids would otherwise not line up with
 * span ranges.
 *
 * Color glyphs (COLR v0 / v1, SVG-in-OT) take the per-glyph rotated
 * fallback through [drawTextAlongPath] - the same color-glyph escape
 * the uniform-color [drawArcText] uses. Per-span coloring is **not**
 * applied to color glyphs on the arc today; pin a monochrome font if
 * span colors must reach every glyph.
 *
 * When [styledText].spans is empty AND [brush] is null this delegates
 * to the uniform-color [drawArcText] so the no-spans path is
 * byte-identical to the existing call.
 */
public fun DrawScope.drawArcText(
    measured: MeasuredText,
    styledText: StyledText,
    center: Offset,
    radiusPx: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    color: Color = Color.Black,
    brush: Brush? = null,
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
    if (styledText.spans.isEmpty() && brush == null) {
        drawArcText(
            measured = measured,
            center = center,
            radiusPx = radiusPx,
            startAngleDeg = startAngleDeg,
            sweepAngleDeg = sweepAngleDeg,
            color = color,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            side = side,
            alignment = alignment,
            overflow = overflow,
            startOffset = startOffset,
            sampleStep = sampleStep,
            shadow = shadow,
        )
        return
    }

    if (measured.isEmpty || radiusPx <= 0f || sweepAngleDeg == 0f) return

    if (measured.hasColorGlyphs) {
        // Color-glyph escape: build the arc Path and route through
        // drawTextAlongPath using the outer default paint. Spans
        // don't reach color glyphs through this escape; drawArcText
        // (uniform color) does the same when emoji are present.
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

    val arc = ArcWarpContext.computeOrNull(
        center = center,
        radiusPx = radiusPx,
        startAngleDeg = startAngleDeg,
        sweepAngleDeg = sweepAngleDeg,
        startOffset = startOffset,
        side = side,
        alignment = alignment,
        overflow = overflow,
        totalAdvance = measured.paragraph.totalAdvance,
    ) ?: return

    val resolver = PaintResolver(
        spans = styledText.spans,
        clusterEnds = clusterEndArray(measured.paragraph, measured.textLength),
        clusterText = styledText.text,
        defaultColor = color,
        defaultBrush = brush,
    )

    // Bucket per-glyph silhouettes by resolved paint, in baseline
    // (y = 0) coordinates. Mirrors drawShapedTextStyledInternal's
    // bucketing pre-pass; the only difference is pen origin (baseline
    // here, baseline-shifted there).
    val bucketedBaselines = LinkedHashMap<ResolvedPaint, Path>()
    var penX = 0f
    for (run in measured.paragraph.runs) {
        val runFont = run.font ?: measured.font
        val flippedPaths = measured.flippedPathsByFont[runFont] ?: emptyMap()
        val isRtl = run.direction == HbDirection.RTL
        val (glyphsInCluster, indexInCluster) = clusterIndexingForRun(run.glyphs, isRtl)
        for (i in run.glyphs.indices) {
            val gid = run.glyphs[i].glyphId
            val pos = run.positions[i]
            val cl = run.glyphs[i].cluster
            val resolved = resolver.resolve(
                clusterStart = cl,
                glyphIndexInCluster = indexInCluster[i],
                glyphsInCluster = glyphsInCluster[i],
            )
            val gp = flippedPaths[gid]
            if (gp != null) {
                val bucket = bucketedBaselines.getOrPut(resolved) { Path() }
                bucket.addPath(gp, Offset(penX + pos.xOffset, -pos.yOffset))
            }
            penX += pos.xAdvance
        }
    }

    if (bucketedBaselines.isEmpty()) return

    // Warp each bucket independently so per-paint contours stay
    // grouped. Sampling per bucket costs an extra PathMeasure walk
    // per distinct paint, which is bounded by the number of styled
    // spans hitting the line (typically 1-2 plus the default).
    val warpedBuckets = LinkedHashMap<ResolvedPaint, Path>(bucketedBaselines.size)
    for ((paint, baselinePath) in bucketedBaselines) {
        val contours = samplePathContours(baselinePath, sampleStep)
        if (contours.isEmpty()) continue
        warpedBuckets[paint] = warpContoursToArc(contours, arc)
    }

    if (shadow != null) {
        val shadowPaint = Paint().apply {
            this.color = shadow.color
            this.alpha = alpha
            this.blendMode = blendMode
            applyDrawStyleToPaint(this, style)
            configureShadowBlur(this, shadow.blurRadius)
        }
        // Single shadow pass under the union of every bucket, matching
        // drawShapedTextStyledInternal's "shadow uses the silhouette
        // under the default paint" rule. Faster than per-bucket
        // shadow passes and avoids overlapping shadows compounding
        // alpha.
        val unionWarped = Path().apply {
            for (warped in warpedBuckets.values) addPath(warped)
        }
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(shadow.offset.x, shadow.offset.y)
            canvas.drawPath(unionWarped, shadowPaint)
            canvas.restore()
        }
    }

    // Default paint goes underneath every per-span override - same
    // ordering rule as drawShapedTextStyledInternal so per-span
    // overlays don't get hidden by base glyphs whose silhouette
    // overlaps the marked codepoint.
    val defaultPaint = ResolvedPaint(color, brush)
    warpedBuckets[defaultPaint]?.let { warped ->
        drawArcBucket(warped, defaultPaint, alpha, style, blendMode)
    }
    for ((paint, warped) in warpedBuckets) {
        if (paint == defaultPaint) continue
        drawArcBucket(warped, paint, alpha, style, blendMode)
    }
}

/** Pre-computed scalar inputs the per-sample warp loop needs. */
private class ArcWarpContext(
    val centerX: Float,
    val centerY: Float,
    val radiusPx: Float,
    val pathLength: Float,
    val startAngleRad: Float,
    val dirSign: Float,
    val spacingScale: Float,
    val startDistance: Float,
    val sideY: Float,
    val overflow: TextOnPathOverflow,
    val startPos: Offset,
    val startTan: Offset,
    val endPos: Offset,
    val endTan: Offset,
) {
    companion object {
        fun computeOrNull(
            center: Offset,
            radiusPx: Float,
            startAngleDeg: Float,
            sweepAngleDeg: Float,
            startOffset: Float,
            side: TextOnPathSide,
            alignment: TextOnPathAlignment,
            overflow: TextOnPathOverflow,
            totalAdvance: Float,
        ): ArcWarpContext? {
            val degToRad = PI.toFloat() / 180f
            val sweepAngleRad = sweepAngleDeg * degToRad
            val startAngleRad = startAngleDeg * degToRad
            val dirSign = sign(sweepAngleRad)
            val pathLength = abs(sweepAngleRad) * radiusPx
            if (pathLength <= 0f) return null

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

            return ArcWarpContext(
                centerX = center.x,
                centerY = center.y,
                radiusPx = radiusPx,
                pathLength = pathLength,
                startAngleRad = startAngleRad,
                dirSign = dirSign,
                spacingScale = spacingScale,
                startDistance = startDistance,
                sideY = sideY,
                overflow = overflow,
                startPos = startPos,
                startTan = startTan,
                endPos = endPos,
                endTan = endTan,
            )
        }
    }
}

/**
 * Walk every [contours] entry and warp each `(x, y)` sample onto the
 * arc described by [arc], emitting a single closed subpath per
 * contour into the returned [Path]. Shared between the uniform-color
 * and styled [drawArcText] overloads so geometry stays one
 * implementation.
 */
private fun warpContoursToArc(contours: List<FloatArray>, arc: ArcWarpContext): Path {
    val warped = Path()
    for (contour in contours) {
        var first = true
        var i = 0
        while (i < contour.size) {
            val xLocal = contour[i]
            val yLocal = contour[i + 1]
            i += 2

            val dist = arc.startDistance + xLocal * arc.spacingScale
            val anchor: Offset
            val tangent: Offset
            when {
                dist in 0f..arc.pathLength -> {
                    val a = arc.startAngleRad + arc.dirSign * dist / arc.radiusPx
                    anchor = Offset(
                        arc.centerX + arc.radiusPx * cos(a),
                        arc.centerY + arc.radiusPx * sin(a),
                    )
                    tangent = Offset(-sin(a) * arc.dirSign, cos(a) * arc.dirSign)
                }
                arc.overflow == TextOnPathOverflow.Visible && dist < 0f -> {
                    anchor = Offset(
                        arc.startPos.x + arc.startTan.x * dist,
                        arc.startPos.y + arc.startTan.y * dist,
                    )
                    tangent = arc.startTan
                }
                arc.overflow == TextOnPathOverflow.Visible && dist > arc.pathLength -> {
                    val extra = dist - arc.pathLength
                    anchor = Offset(
                        arc.endPos.x + arc.endTan.x * extra,
                        arc.endPos.y + arc.endTan.y * extra,
                    )
                    tangent = arc.endTan
                }
                else -> continue
            }

            val perpX = -tangent.y * arc.sideY
            val perpY = tangent.x * arc.sideY
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
    return warped
}

/**
 * Paint one bucket's already-warped silhouette. Mirrors the dispatch
 * inside `drawShapedTextStyledInternal`: brush buckets route through
 * [drawCombinedBrushPath] so a gradient flows across the bucket as
 * one body; color buckets fall back to the unified silhouette under
 * stroke (so per-bucket strokes outline cleanly without per-glyph
 * inner edges).
 */
private fun DrawScope.drawArcBucket(
    warped: Path,
    paint: ResolvedPaint,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
) {
    if (paint.brush != null) {
        drawCombinedBrushPath(
            path = warped,
            brush = paint.brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
        )
    } else {
        val effectivePath = if (style is Stroke) warped.toUnifiedSilhouette() else warped
        drawPath(
            path = effectivePath,
            color = if (paint.color == Color.Unspecified) Color.Black else paint.color,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
        )
    }
}

/**
 * Pre-pass identical to the inline block in
 * `drawShapedTextStyledInternal`: for each glyph in [glyphs],
 * compute its `glyphsInCluster` and `indexInCluster` (with the RTL
 * buffer-order flip) so the per-glyph paint resolver call uses the
 * same source-position model on the arc as it does on a flat
 * baseline.
 */
private fun clusterIndexingForRun(
    glyphs: List<com.mohamedrejeb.harfbuzz.core.GlyphInfo>,
    isRtl: Boolean,
): Pair<IntArray, IntArray> {
    val total = IntArray(glyphs.size)
    val indexInCluster = IntArray(glyphs.size)
    val counts = HashMap<Int, Int>(glyphs.size)
    for (g in glyphs) counts[g.cluster] = (counts[g.cluster] ?: 0) + 1
    val seen = HashMap<Int, Int>(counts.size)
    for (i in glyphs.indices) {
        val cl = glyphs[i].cluster
        val totalForCluster = counts.getValue(cl)
        total[i] = totalForCluster
        val bufferIndex = seen.getOrElse(cl) { 0 }
        indexInCluster[i] = if (isRtl) totalForCluster - 1 - bufferIndex else bufferIndex
        seen[cl] = bufferIndex + 1
    }
    return total to indexInCluster
}
