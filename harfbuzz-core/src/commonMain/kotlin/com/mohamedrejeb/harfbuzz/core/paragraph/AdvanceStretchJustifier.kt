package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.GlyphPosition
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
}
