package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.GlyphPosition
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.ShapedRun

/**
 * Post-shape advance-stretch justifier. Counterpart to
 * [KashidaJustifier] / [WordSpacingJustifier], but instead of inserting
 * characters into the source text and re-shaping, it widens each
 * shaped glyph's `xAdvance` so the paragraph's total advance reaches a
 * target width. The output preserves glyph identity: same glyph ids,
 * same `xOffset` / `yOffset` / `yAdvance`, only the inter-glyph gaps
 * grow.
 *
 * The extra width is distributed uniformly across all inter-glyph
 * gaps. With `n` glyphs and a delta of `target - current`, every gap
 * (the trailing edge of glyph `i` to the leading edge of glyph `i+1`)
 * receives `delta / (n - 1)` extra pixels. Equivalent to applying a
 * uniform letter-spacing of that magnitude.
 *
 * The trailing edge of the last glyph is left unchanged so the line's
 * total advance equals the target exactly (no phantom space after the
 * last visible glyph).
 *
 * Bail-outs (return the input unchanged):
 *  - paragraph is empty,
 *  - target is non-finite, non-positive, or at/below the current
 *    `totalAdvance`,
 *  - the paragraph has fewer than two visible glyphs (no gap to
 *    distribute into).
 */
public object AdvanceStretchJustifier {

    /**
     * Stretch [paragraph]'s per-glyph advances so its `totalAdvance`
     * reaches [targetWidthPx]. Returns [paragraph] unchanged when no
     * stretch is applicable.
     */
    public fun stretch(paragraph: ShapedParagraph, targetWidthPx: Float): ShapedParagraph {
        if (paragraph.runs.isEmpty()) return paragraph
        if (!targetWidthPx.isFinite() || targetWidthPx <= 0f) return paragraph
        val current = paragraph.totalAdvance
        if (targetWidthPx <= current) return paragraph

        val totalGlyphs = paragraph.runs.sumOf { it.glyphCount }
        if (totalGlyphs < 2) return paragraph

        val extraPerGap = (targetWidthPx - current) / (totalGlyphs - 1)
        if (extraPerGap <= 0f) return paragraph

        var glyphsSeen = 0
        val newRuns = ArrayList<ShapedRun>(paragraph.runs.size)
        for (run in paragraph.runs) {
            if (run.isEmpty) {
                newRuns.add(run)
                continue
            }
            val gapsInRun = if (glyphsSeen + run.glyphCount >= totalGlyphs) {
                run.glyphCount - 1
            } else {
                run.glyphCount
            }
            newRuns.add(stretchRun(run, extraPerGap, gapsInRun))
            glyphsSeen += run.glyphCount
        }

        return paragraph.copy(
            runs = newRuns,
            totalAdvance = targetWidthPx,
            logical = paragraph.logical.copy(right = paragraph.logical.left + targetWidthPx),
        )
    }

    /**
     * Public hook for callers that already split runs themselves
     * (e.g. tests). Adds [extraPerGap] to the `xAdvance` of the first
     * [gapsInRun] glyphs in [run] and recomputes `totalAdvance`.
     */
    public fun stretchRun(run: ShapedRun, extraPerGap: Float, gapsInRun: Int): ShapedRun {
        if (run.isEmpty || extraPerGap <= 0f || gapsInRun <= 0) return run
        val safeGaps = gapsInRun.coerceAtMost(run.glyphCount)
        val newPositions = ArrayList<GlyphPosition>(run.glyphCount)
        for (i in run.positions.indices) {
            val p = run.positions[i]
            if (i < safeGaps) {
                newPositions.add(p.copy(xAdvance = p.xAdvance + extraPerGap))
            } else {
                newPositions.add(p)
            }
        }
        val newTotal = run.totalAdvance + extraPerGap * safeGaps
        return run.copy(
            positions = newPositions,
            totalAdvance = newTotal,
            logical = run.logical.copy(right = run.logical.left + newTotal),
        )
    }

    /**
     * Default floor for negative letter-spacing: a tightened cluster's
     * trailing advance is never reduced below this fraction of its original
     * advance, so glyphs can't fully collapse onto each other (Arabic joins,
     * narrow Latin). Only ever hit by extreme negative values relative to a
     * narrow glyph — letter-spacing deltas are normally small versus advance.
     */
    public const val DEFAULT_MIN_CLUSTER_ADVANCE_FRACTION: Float = 0.1f

    /**
     * Apply uniform letter-spacing to [paragraph]. [perGapPx] is the iOS-style
     * per-glyph `characterSpacing`: the total width added to the line is
     * `perGapPx * glyphCount` (matching CoreText's kern / CTLineCreateJustified
     * total of `characterSpacing * glyphCount`). Positive widens (tracking);
     * negative tightens. Unlike [stretch] — which distributes a fixed total to
     * reach a target width and only ever widens — this is a signed delta.
     *
     * That total is spread evenly across the inter-**cluster** gaps, not per
     * glyph: consecutive glyphs sharing a `cluster` index (decomposed marks,
     * ligature components) count as one unit, so the spacing lands between
     * graphemes and never inside a mark stack. Run boundaries split clusters.
     * The paragraph's last cluster gets no trailing delta, so its leading edge
     * shifts by the full total — keeping `ink` and `totalAdvance` in step.
     *
     * Negative tightening is floored per cluster at [minClusterAdvanceFraction]
     * of the cluster's original trailing advance.
     *
     * Bail-outs (return [paragraph] unchanged): [perGapPx] is `0` or
     * non-finite, the paragraph is empty, it has fewer than two clusters, or
     * the net delta rounds to zero.
     *
     * Recomputes `totalAdvance` and `logical.right`, and approximates the new
     * `ink` by shifting the trailing edge (LTR) / leading edge (RTL) by the net
     * advance delta — exact for the dominant LTR tracking case.
     */
    public fun applyLetterSpacing(
        paragraph: ShapedParagraph,
        perGapPx: Float,
        minClusterAdvanceFraction: Float = DEFAULT_MIN_CLUSTER_ADVANCE_FRACTION,
    ): ShapedParagraph {
        if (perGapPx == 0f || !perGapPx.isFinite()) return paragraph
        if (paragraph.runs.isEmpty()) return paragraph
        val lastNonEmptyRun = paragraph.runs.indexOfLast { !it.isEmpty }
        if (lastNonEmptyRun < 0) return paragraph

        var glyphCount = 0
        var clusterCount = 0
        for (run in paragraph.runs) {
            val glyphs = run.glyphs
            glyphCount += glyphs.size
            for (i in glyphs.indices) {
                if (i == glyphs.lastIndex || glyphs[i].cluster != glyphs[i + 1].cluster) clusterCount++
            }
        }
        val gaps = clusterCount - 1
        if (gaps < 1) return paragraph

        // iOS parity: the total width added to a line is `perGapPx * glyphCount`
        // (CoreText's `characterSpacing * glyphCount`, applied via kern /
        // CTLineCreateJustifiedLine). Spread that total across the inter-cluster
        // gaps so marks/ligature components stay put.
        val perGapDelta = perGapPx * glyphCount / gaps

        var totalDelta = 0f
        val newRuns = ArrayList<ShapedRun>(paragraph.runs.size)
        for ((runIndex, run) in paragraph.runs.withIndex()) {
            if (run.isEmpty) {
                newRuns.add(run)
                continue
            }
            val glyphs = run.glyphs
            val newPositions = ArrayList<GlyphPosition>(run.glyphCount)
            var runDelta = 0f
            for (i in run.positions.indices) {
                val p = run.positions[i]
                val isClusterEnd = i == glyphs.lastIndex || glyphs[i].cluster != glyphs[i + 1].cluster
                val isGlobalLast = runIndex == lastNonEmptyRun && i == glyphs.lastIndex
                if (isClusterEnd && !isGlobalLast) {
                    val target = p.xAdvance + perGapDelta
                    val floored = if (perGapDelta < 0f) {
                        maxOf(target, p.xAdvance * minClusterAdvanceFraction)
                    } else {
                        target
                    }
                    runDelta += floored - p.xAdvance
                    newPositions.add(p.copy(xAdvance = floored))
                } else {
                    newPositions.add(p)
                }
            }
            val newRunTotal = run.totalAdvance + runDelta
            totalDelta += runDelta
            newRuns.add(
                run.copy(
                    positions = newPositions,
                    totalAdvance = newRunTotal,
                    logical = run.logical.copy(right = run.logical.left + newRunTotal),
                ),
            )
        }

        if (totalDelta == 0f) return paragraph

        val newTotal = paragraph.totalAdvance + totalDelta
        val newInk = when {
            paragraph.ink.isEmpty -> paragraph.ink
            paragraph.baseDirection == HbDirection.RTL ->
                paragraph.ink.copy(left = paragraph.ink.left - totalDelta)
            else -> paragraph.ink.copy(right = paragraph.ink.right + totalDelta)
        }
        return paragraph.copy(
            runs = newRuns,
            totalAdvance = newTotal,
            ink = newInk,
            logical = paragraph.logical.copy(right = paragraph.logical.left + newTotal),
        )
    }
}
