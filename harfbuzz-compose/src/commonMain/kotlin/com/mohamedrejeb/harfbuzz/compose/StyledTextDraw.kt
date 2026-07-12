package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import com.mohamedrejeb.harfbuzz.core.HbDirection

/**
 * Styled draw path. Resolves paint per glyph via [PaintResolver] and
 * groups monochrome silhouettes by resolved paint so each distinct
 * paint becomes one combined-path fill at the end. Color glyphs
 * (COLR v0 / v1, SVG-in-OT) paint per-glyph in place with the
 * default outer paint.
 */
internal fun DrawScope.drawShapedTextStyledInternal(
    measured: MeasuredText,
    styledText: StyledText,
    topLeft: Offset,
    color: Color,
    brush: Brush?,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
    forceForegroundColor: Boolean,
    shadow: Shadow?,
    glyphEffects: GlyphEffectProvider? = null,
) {
    if (measured.isEmpty) return

    // Shadow uses the silhouette under the default paint; per-span
    // shadow is out of scope. Reuses the existing shadow pass so a
    // styled draw shadows identically to a uniform draw.
    if (shadow != null) {
        val baselineY = topLeft.y + measured.baseline
        drawShadowPass(
            measured = measured,
            penX = topLeft.x + shadow.offset.x,
            penY = baselineY + shadow.offset.y,
            shadow = shadow,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            spacingScale = 1f,
            glyphEffects = glyphEffects,
        )
    }

    val resolver = PaintResolver(
        spans = styledText.spans,
        clusterEnds = clusterEndArray(measured.paragraph, measured.textLength),
        clusterText = styledText.text,
        defaultColor = color,
        defaultBrush = brush,
    )

    // One combined path per distinct ResolvedPaint. Iteration order is
    // insertion order (LinkedHashMap default), so bucket painting also
    // happens in first-seen order. With per-glyph effects the key grows
    // a quantized-alpha dimension (a union cannot vary alpha per glyph),
    // held in a separate map so the static path allocates nothing new.
    val pathBuckets = LinkedHashMap<ResolvedPaint, androidx.compose.ui.graphics.Path>()
    val effectPathBuckets = if (glyphEffects != null) {
        LinkedHashMap<StyledEffectBucketKey, androidx.compose.ui.graphics.Path>()
    } else null

    val baselineY = topLeft.y + measured.baseline
    var penX = topLeft.x
    var penY = baselineY

    val allRuns = measured.paragraph.runs
    for (runIndex in allRuns.indices) {
        val run = allRuns[runIndex]
        val runFont = run.font ?: measured.font
        val caches = measured.runCachesFor(runFont, forceForegroundColor, style)

        // Precompute per-glyph cluster size + source-order index in cluster.
        // For RTL runs HarfBuzz emits cluster glyphs in reverse source order
        // (mark before base for an Arabic base+mark cluster), so the buffer
        // index has to be flipped to recover the source-order position the
        // 1:1 case in PaintResolver expects.
        val glyphsInCluster = IntArray(run.glyphs.size)
        val indexInCluster = IntArray(run.glyphs.size)
        val isRtl = run.direction == HbDirection.RTL
        run {
            val counts = HashMap<Int, Int>(run.glyphs.size)
            for (g in run.glyphs) counts[g.cluster] = (counts[g.cluster] ?: 0) + 1
            val seen = HashMap<Int, Int>(counts.size)
            for (i in run.glyphs.indices) {
                val cl = run.glyphs[i].cluster
                val total = counts.getValue(cl)
                glyphsInCluster[i] = total
                val bufferIndex = seen.getOrElse(cl) { 0 }
                indexInCluster[i] = if (isRtl) total - 1 - bufferIndex else bufferIndex
                seen[cl] = bufferIndex + 1
            }
        }

        var localX = penX
        var localY = penY
        for (i in run.glyphs.indices) {
            val gid = run.glyphs[i].glyphId
            val pos = run.positions[i]
            val cl = run.glyphs[i].cluster

            val effect = glyphEffects?.effectFor(runIndex, i, cl)
            if (effect != null && effect.alpha <= 0f) {
                localX += pos.xAdvance
                localY += pos.yAdvance
                continue
            }

            val drawX = localX + pos.xOffset
            val drawY = localY - pos.yOffset

            val resolved = resolver.resolve(
                clusterStart = cl,
                glyphIndexInCluster = indexInCluster[i],
                glyphsInCluster = glyphsInCluster[i],
            )

            // Color-glyph precedence mirrors drawOneGlyphAtOrigin:
            // SVG > COLR v1 paint tree > COLR v0 palette layers > silhouette.
            val svgRender = if (caches.useSvg) caches.svgCache?.get(gid) else null
            val paintTree = if (svgRender == null && caches.useV1Paint) {
                caches.paintTrees?.get(gid)
            } else null
            val v0Layers = if (svgRender == null && paintTree == null && caches.useV0Layers) {
                caches.colorLayers?.get(gid)
            } else null
            val isColorGlyph = svgRender != null ||
                (paintTree != null && paintTree.isNotEmpty()) ||
                (v0Layers != null && v0Layers.isNotEmpty())

            if (isColorGlyph) {
                // Color glyph - paint per-glyph in place. Foreground-color
                // layers (COLR v0 foreground / forceForegroundColor) take the
                // resolved per-glyph color. Per-span brushes are not applied
                // to color glyphs; the resolved color is used instead, or the
                // outer default if no color was set.
                val foreground = when {
                    resolved.color != Color.Unspecified -> resolved.color
                    else -> color
                }
                withGlyphEffectTransform(effect, drawX, drawY) {
                    drawOneGlyphAtOrigin(
                        runFont = runFont,
                        sizePx = measured.sizePx,
                        caches = caches,
                        glyphId = gid,
                        color = foreground,
                        alpha = if (effect == null) alpha else alpha * effect.alpha,
                        style = style,
                        blendMode = blendMode,
                    )
                }
            } else {
                // Monochrome: append to the resolved-paint bucket.
                val flipped = caches.flippedPaths[gid]
                if (flipped != null) {
                    if (effectPathBuckets != null) {
                        val bucketAlpha = quantizeGlyphEffectAlpha(effect?.alpha ?: 1f)
                        if (bucketAlpha > 0f) {
                            val key = StyledEffectBucketKey(resolved, bucketAlpha)
                            val path = effectPathBuckets.getOrPut(key) {
                                androidx.compose.ui.graphics.Path()
                            }
                            appendGlyphPathWithEffect(path, flipped, drawX, drawY, effect)
                        }
                    } else {
                        val path = pathBuckets.getOrPut(resolved) {
                            androidx.compose.ui.graphics.Path()
                        }
                        path.addPath(flipped, Offset(drawX, drawY))
                    }
                }
            }

            localX += pos.xAdvance
            localY += pos.yAdvance
        }
        penX = localX
        penY = localY
    }

    // Bases (default paint) form the bottom layer; per-span overrides
    // paint on top. HarfBuzz emits cluster glyphs as mark-then-base in
    // RTL buffer order, so iterating in insertion order paints marks
    // first - any base whose path geometrically overlaps the mark
    // (Arabic letters with deep descenders such as BAA / NOON colliding
    // with a below-baseline KASRA) then overpaints the colored mark
    // and hides it. Painting the default bucket first reverses that.
    val defaultPaint = ResolvedPaint(color, brush)
    if (effectPathBuckets != null) {
        for ((key, path) in effectPathBuckets) {
            if (key.paint != defaultPaint) continue
            drawResolvedBucket(key.paint, path, alpha * key.alpha, style, blendMode)
        }
        for ((key, path) in effectPathBuckets) {
            if (key.paint == defaultPaint) continue
            drawResolvedBucket(key.paint, path, alpha * key.alpha, style, blendMode)
        }
        return
    }
    pathBuckets[defaultPaint]?.let { defaultPath ->
        drawResolvedBucket(defaultPaint, defaultPath, alpha, style, blendMode)
    }
    for ((resolved, path) in pathBuckets) {
        if (resolved == defaultPaint) continue
        drawResolvedBucket(resolved, path, alpha, style, blendMode)
    }
}

/**
 * Bucket key for the styled draw under per-glyph effects: the resolved
 * per-glyph paint plus the quantized effect alpha, so each (paint, alpha)
 * pair becomes one combined-path draw.
 */
private data class StyledEffectBucketKey(
    val paint: ResolvedPaint,
    val alpha: Float,
)

private fun DrawScope.drawResolvedBucket(
    resolved: ResolvedPaint,
    path: androidx.compose.ui.graphics.Path,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
) {
    if (resolved.brush != null) {
        // drawCombinedBrushPath already collapses subpaths to a unified
        // silhouette when stroking, so per-bucket strokes outline cleanly.
        drawCombinedBrushPath(
            path = path,
            brush = resolved.brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
        )
    } else {
        // Stroke path: collapse the bucket's per-glyph subpaths to a
        // unified silhouette so the stroked per-paint colour outlines
        // its glyph cluster as one contour. Fill ignores the duplicate
        // overlap (union of fills equals per-glyph fill).
        val effectivePath = if (style is Stroke) path.toUnifiedSilhouette() else path
        drawPath(
            path = effectivePath,
            color = if (resolved.color == Color.Unspecified) Color.Black else resolved.color,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
        )
    }
}

/**
 * Project paragraph-level [spans] onto a single line covering chars
 * `[lineStart, lineEnd)` of the source text, returning ranges in
 * line-local coordinates ready to feed into a per-line [PaintResolver].
 *
 * When [originalToJustifiedIndex] is non-null the line was widened
 * by Kashida / thin-space insertion; line-local original indices
 * are translated through the mapping so spans line up with the
 * shaped clusters the per-line draw works against.
 */
internal fun sliceLineSpans(
    spans: List<StyleRange>,
    lineStart: Int,
    lineEnd: Int,
    originalToJustifiedIndex: IntArray?,
    justifiedTextLength: Int,
): List<StyleRange> {
    if (spans.isEmpty()) return emptyList()
    val out = ArrayList<StyleRange>(spans.size)
    for (s in spans) {
        if (s.end <= lineStart || s.start >= lineEnd) continue
        val localStart = (maxOf(s.start, lineStart) - lineStart).coerceAtLeast(0)
        val localEnd = (minOf(s.end, lineEnd) - lineStart).coerceAtLeast(localStart)
        val (finalStart, finalEnd) = if (originalToJustifiedIndex == null) {
            localStart to localEnd
        } else {
            val mapping = originalToJustifiedIndex
            val fs = if (localStart >= mapping.size) justifiedTextLength else mapping[localStart]
            val fe = if (localEnd >= mapping.size) justifiedTextLength else mapping[localEnd]
            fs to fe
        }
        if (finalEnd >= finalStart) {
            out.add(StyleRange(finalStart, finalEnd, s.style))
        }
    }
    return out
}
