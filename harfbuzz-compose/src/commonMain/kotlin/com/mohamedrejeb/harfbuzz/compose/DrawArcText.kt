package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

    val key = ArcWarpKey(
        centerX = center.x,
        centerY = center.y,
        radiusPx = radiusPx,
        startAngleDeg = startAngleDeg,
        sweepAngleDeg = sweepAngleDeg,
        startOffset = startOffset,
        sampleStep = sampleStep,
        side = side,
        alignment = alignment,
        overflow = overflow,
    )
    val warped = measured.warpedArcPath(arc, key, sampleStep) ?: return

    if (shadow != null) {
        drawWarpedArcShadow(warped, shadow, alpha, style, blendMode)
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
            // Keep the shadow's own opacity (color.alpha) — assigning alpha alone drops it.
            this.alpha = alpha * shadow.color.alpha
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

/**
 * Layered arc draw: warp [measured] onto the arc ONCE, then paint
 * shadow, stroke, and fill from that single warped silhouette. Replaces
 * the caller pattern of issuing four separate [drawArcText] calls
 * (shadow-fill companion, shadow-stroke companion, stroke, fill) — each
 * of which re-warped — for text that has a stroke and/or drop shadow.
 *
 * Paint order matches a standard text stack: drop shadow at the bottom
 * (cast from the fill+stroke union so it stays solid AND reaches the
 * stroke's outer edge), then the stroke, then the fill on top.
 *
 * Fill and stroke each take either a solid [Color] or a [Brush] (brush
 * wins when non-null); a brush flows across the whole run as one body via
 * [drawCombinedBrushPath]. Leave [strokeWidth] at 0 (or both stroke
 * paints unset) for fill-only text.
 *
 * The warp is memoised on [measured] (see [MeasuredText.cachedArcWarpPath]),
 * so the three passes here share one warp and consecutive frames with
 * unchanged arc geometry reuse it too.
 *
 * Color glyphs (emoji / COLR / SVG) can't collapse into one silhouette,
 * so they fall back to per-pass [drawArcText] (the rare arc-emoji case);
 * fill/stroke brushes don't reach color glyphs there, matching
 * [drawArcText]'s existing color-glyph escape.
 */
public fun DrawScope.drawArcTextLayered(
    measured: MeasuredText,
    center: Offset,
    radiusPx: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    fillColor: Color = Color.Black,
    fillBrush: Brush? = null,
    fillAlpha: Float = 1f,
    strokeColor: Color = Color.Unspecified,
    strokeBrush: Brush? = null,
    strokeWidth: Float = 0f,
    strokeAlpha: Float = 1f,
    strokeCap: StrokeCap = StrokeCap.Round,
    strokeJoin: StrokeJoin = StrokeJoin.Round,
    shadow: Shadow? = null,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Center,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Compress,
    startOffset: Float = 0f,
    sampleStep: Float = 0.5f,
) {
    if (measured.isEmpty || radiusPx <= 0f || sweepAngleDeg == 0f) return

    val hasStroke = strokeWidth > 0f &&
        (strokeBrush != null || strokeColor != Color.Unspecified)
    val hasShadow = shadow != null && shadow.color != Color.Transparent

    // Color glyphs can't be a single warped silhouette — paint them with
    // the per-pass uniform drawArcText (stroke under fill, shadow on the
    // bottom-most pass). Rare on arcs; brushes don't reach color glyphs.
    if (measured.hasColorGlyphs) {
        if (hasStroke) {
            drawArcText(
                measured = measured, center = center, radiusPx = radiusPx,
                startAngleDeg = startAngleDeg, sweepAngleDeg = sweepAngleDeg,
                color = if (strokeColor == Color.Unspecified) Color.Black else strokeColor,
                alpha = strokeAlpha,
                style = Stroke(strokeWidth, cap = strokeCap, join = strokeJoin),
                blendMode = blendMode, side = side, alignment = alignment,
                overflow = overflow, startOffset = startOffset,
                sampleStep = sampleStep, shadow = shadow,
            )
        }
        drawArcText(
            measured = measured, center = center, radiusPx = radiusPx,
            startAngleDeg = startAngleDeg, sweepAngleDeg = sweepAngleDeg,
            color = fillColor, alpha = fillAlpha, style = Fill, blendMode = blendMode,
            side = side, alignment = alignment, overflow = overflow,
            startOffset = startOffset, sampleStep = sampleStep,
            shadow = if (hasStroke) null else shadow,
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
    val key = ArcWarpKey(
        centerX = center.x,
        centerY = center.y,
        radiusPx = radiusPx,
        startAngleDeg = startAngleDeg,
        sweepAngleDeg = sweepAngleDeg,
        startOffset = startOffset,
        sampleStep = sampleStep,
        side = side,
        alignment = alignment,
        overflow = overflow,
    )
    val warped = measured.warpedArcPath(arc, key, sampleStep) ?: return

    val strokeStyle = if (hasStroke) {
        Stroke(width = strokeWidth, cap = strokeCap, join = strokeJoin)
    } else null

    // Drop shadow from the fill+stroke union: the Fill blur keeps the
    // interior solid; the Stroke blur pushes the silhouette out to the
    // stroke's outer edge so a thick stroke's shadow isn't overrun.
    // Drawn at full alpha (the shadow's own opacity lives in its color).
    if (hasShadow && shadow != null) {
        drawWarpedArcShadow(warped, shadow, alpha = 1f, style = Fill, blendMode = blendMode)
        if (strokeStyle != null) {
            drawWarpedArcShadow(warped, shadow, alpha = 1f, style = strokeStyle, blendMode = blendMode)
        }
    }

    // Stroke beneath the fill.
    if (strokeStyle != null) {
        if (strokeBrush != null) {
            drawCombinedBrushPath(warped, strokeBrush, strokeAlpha, strokeStyle, blendMode)
        } else {
            drawPath(
                path = warped,
                color = strokeColor,
                alpha = strokeAlpha,
                style = strokeStyle,
                blendMode = blendMode,
            )
        }
    }

    // Fill on top.
    if (fillBrush != null) {
        drawCombinedBrushPath(warped, fillBrush, fillAlpha, Fill, blendMode)
    } else {
        drawPath(
            path = warped,
            color = fillColor,
            alpha = fillAlpha,
            style = Fill,
            blendMode = blendMode,
        )
    }
}

/**
 * Draw ONLY the arc drop shadow — the blurred, offset warped silhouette
 * under [shadow]'s color — without painting the glyphs. The arc twin of
 * [drawShapedTextShadow], for callers that paint fill and stroke in
 * separate passes and want one union shadow underneath (call once with
 * [Fill] for the solid body, once with a [Stroke] for the outer edge)
 * instead of routing each pass through [drawArcText] with a `shadow`
 * argument, which would re-warp and re-stamp the covered glyph silhouette.
 *
 * Reuses the per-frame warp cache ([MeasuredText.cachedArcWarpPath]), so
 * the shadow passes and the following fill/stroke passes (same arc
 * geometry) all share a single warp.
 *
 * Color glyphs contribute only what their monochrome base outline carries
 * (emoji without one cast no shadow), matching the rest of the warp path.
 */
public fun DrawScope.drawArcTextShadow(
    measured: MeasuredText,
    center: Offset,
    radiusPx: Float,
    startAngleDeg: Float,
    sweepAngleDeg: Float,
    shadow: Shadow,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Center,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Compress,
    startOffset: Float = 0f,
    sampleStep: Float = 0.5f,
) {
    if (measured.isEmpty || radiusPx <= 0f || sweepAngleDeg == 0f) return
    if (shadow.color == Color.Transparent) return

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
    val key = ArcWarpKey(
        centerX = center.x,
        centerY = center.y,
        radiusPx = radiusPx,
        startAngleDeg = startAngleDeg,
        sweepAngleDeg = sweepAngleDeg,
        startOffset = startOffset,
        sampleStep = sampleStep,
        side = side,
        alignment = alignment,
        overflow = overflow,
    )
    val warped = measured.warpedArcPath(arc, key, sampleStep) ?: return
    drawWarpedArcShadow(warped, shadow, alpha, style, blendMode)
}

/**
 * Stamp [warped] under a paint configured with [shadow]'s color, [alpha],
 * [blendMode], [style] (Fill/Stroke) and Gaussian blur, translated by
 * `shadow.offset`. Shared by [drawArcText], [drawArcTextLayered] and
 * [drawArcTextShadow].
 */
private fun DrawScope.drawWarpedArcShadow(
    warped: Path,
    shadow: Shadow,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
) {
    val shadowPaint = Paint().apply {
        this.color = shadow.color
        // Keep the shadow's own opacity (color.alpha) — assigning alpha alone drops it.
        this.alpha = alpha * shadow.color.alpha
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

/**
 * Warp [measured]'s baseline silhouette onto [arc], memoised on the
 * [MeasuredText] under [key] so repeated passes within a frame (shadow /
 * stroke / fill) and consecutive frames with unchanged geometry reuse the
 * path instead of re-sampling and re-warping. Returns null when the glyph
 * silhouette has no contours.
 */
private fun MeasuredText.warpedArcPath(
    arc: ArcWarpContext,
    key: ArcWarpKey,
    sampleStep: Float,
): Path? {
    cachedArcWarpPath?.let { if (cachedArcWarpKey == key) return it }
    val contours = contourSamples(sampleStep)
    if (contours.isEmpty()) {
        cachedArcWarpKey = null
        cachedArcWarpPath = null
        return null
    }
    val warped = warpContoursToArc(contours, arc)
    cachedArcWarpKey = key
    cachedArcWarpPath = warped
    return warped
}

/**
 * Value key for [MeasuredText.cachedArcWarpPath]. Captures every per-draw
 * input to [ArcWarpContext] / [warpContoursToArc]; the glyph contours and
 * total advance are intrinsic to the owning [MeasuredText] so they don't
 * need keying.
 */
internal data class ArcWarpKey(
    val centerX: Float,
    val centerY: Float,
    val radiusPx: Float,
    val startAngleDeg: Float,
    val sweepAngleDeg: Float,
    val startOffset: Float,
    val sampleStep: Float,
    val side: TextOnPathSide,
    val alignment: TextOnPathAlignment,
    val overflow: TextOnPathOverflow,
)

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
