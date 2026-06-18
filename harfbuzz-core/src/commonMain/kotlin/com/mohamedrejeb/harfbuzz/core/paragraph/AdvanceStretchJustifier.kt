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
        // Content occupies pen-local [0, totalAdvance] for both LTR and RTL (RTL-ness
        // is in glyph order + alignment xOffset, not negative pen coords), so the
        // spacing extends the trailing (right/pen-end) edge for both. Mirroring it to
        // `ink.left` for RTL leaves `ink.right` stale and mis-positions the line.
        val newInk = if (paragraph.ink.isEmpty) {
            paragraph.ink
        } else {
            paragraph.ink.copy(right = paragraph.ink.right + totalDelta)
        }
        return paragraph.copy(
            runs = newRuns,
            totalAdvance = newTotal,
            ink = newInk,
            logical = paragraph.logical.copy(right = paragraph.logical.left + newTotal),
        )
    }

    /**
     * Fill [paragraph] to EXACTLY [targetWidthPx] by distributing the missing
     * width across elastic gaps. The exact-fill counterpart to [stretch], used
     * by paragraph justification:
     *
     *  - The total advance lands on [targetWidthPx] exactly — the floating-point
     *    remainder goes to the last elastic gap — so a justified line never
     *    overshoots the target (the bug where a short line grew past the longest
     *    line). [stretch], by contrast, leaves `ink` stale and is kept only for
     *    the arc `AdvanceStretchTo` path.
     *  - Width is distributed only into [elasticGlyphIndices] when provided: the
     *    global (visual, run-concatenated) indices of the glyphs whose trailing
     *    gap may grow — e.g. inter-word space glyphs for Latin word-justify.
     *    When `null` / empty it falls back to every cluster-final glyph except
     *    the line's last, matching [applyLetterSpacing]'s gap model (used for the
     *    sub-unit residual close after Kashida fill).
     *  - `totalAdvance`, `logical.right` and `ink` are all updated (trailing edge
     *    by the added width — LTR right, RTL left) so frame sizing reflects it.
     *
     * Returns [paragraph] unchanged when it is empty, [targetWidthPx] is
     * non-finite, the target is at/below the current advance (never shrink), or
     * there is no elastic gap to grow.
     */
    public fun fillToWidth(
        paragraph: ShapedParagraph,
        targetWidthPx: Float,
        elasticGlyphIndices: IntArray? = null,
    ): ShapedParagraph {
        if (paragraph.runs.isEmpty()) return paragraph
        if (!targetWidthPx.isFinite()) return paragraph
        val current = paragraph.totalAdvance
        val extra = targetWidthPx - current
        if (extra <= 0f) return paragraph

        val elastic: Set<Int> =
            if (elasticGlyphIndices != null && elasticGlyphIndices.isNotEmpty()) {
                elasticGlyphIndices.toHashSet()
            } else {
                clusterEndGlobalIndices(paragraph).toHashSet()
            }
        val lastElastic = elastic.maxOrNull() ?: return paragraph
        val perGap = extra / elastic.size

        var added = 0f
        var globalIndex = 0
        val newRuns = ArrayList<ShapedRun>(paragraph.runs.size)
        for (run in paragraph.runs) {
            if (run.isEmpty) {
                newRuns.add(run)
                continue
            }
            val newPositions = ArrayList<GlyphPosition>(run.glyphCount)
            var runDelta = 0f
            for (i in run.positions.indices) {
                val p = run.positions[i]
                if (globalIndex in elastic) {
                    // Last elastic gap absorbs the fp remainder so the sum is exact.
                    val add = if (globalIndex == lastElastic) extra - added else perGap
                    added += add
                    runDelta += add
                    newPositions.add(p.copy(xAdvance = p.xAdvance + add))
                } else {
                    newPositions.add(p)
                }
                globalIndex++
            }
            val newRunTotal = run.totalAdvance + runDelta
            newRuns.add(
                run.copy(
                    positions = newPositions,
                    totalAdvance = newRunTotal,
                    logical = run.logical.copy(right = run.logical.left + newRunTotal),
                ),
            )
        }

        // Both LTR and RTL content occupies pen-local [0, totalAdvance] (the
        // shaped glyphs advance left-to-right by xAdvance regardless of script —
        // RTL-ness lives in glyph order + the alignment xOffset, not in negative
        // pen coords; that's why `computeXOffset` right-aligns RTL via
        // `maxWidth - ink.right`). Filling extends the trailing (right/pen-end)
        // edge for both, so grow `ink.right`. Mirroring it to `ink.left` for RTL
        // leaves `ink.right` stale and mis-positions the line by `extra`.
        val newInk = if (paragraph.ink.isEmpty) {
            paragraph.ink
        } else {
            paragraph.ink.copy(right = paragraph.ink.right + extra)
        }
        return paragraph.copy(
            runs = newRuns,
            totalAdvance = targetWidthPx,
            ink = newInk,
            logical = paragraph.logical.copy(right = paragraph.logical.left + targetWidthPx),
        )
    }

    /**
     * Global (visual, run-concatenated) indices of every cluster-final glyph
     * except the paragraph's very last — the default elastic gaps for
     * [fillToWidth] when no explicit word-gap indices are passed. A glyph is
     * cluster-final when the next glyph starts a new cluster or it ends a run;
     * the global-last is excluded so the trailing edge stays put.
     */
    private fun clusterEndGlobalIndices(paragraph: ShapedParagraph): IntArray {
        val lastNonEmptyRun = paragraph.runs.indexOfLast { !it.isEmpty }
        val result = ArrayList<Int>()
        var globalIndex = 0
        for ((runIndex, run) in paragraph.runs.withIndex()) {
            val glyphs = run.glyphs
            for (i in glyphs.indices) {
                val isClusterEnd = i == glyphs.lastIndex || glyphs[i].cluster != glyphs[i + 1].cluster
                val isGlobalLast = runIndex == lastNonEmptyRun && i == glyphs.lastIndex
                if (isClusterEnd && !isGlobalLast) result.add(globalIndex)
                globalIndex++
            }
        }
        return result.toIntArray()
    }
}
