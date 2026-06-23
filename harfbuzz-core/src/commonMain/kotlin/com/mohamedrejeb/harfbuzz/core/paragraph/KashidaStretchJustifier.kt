package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.ShapedRun

/**
 * Continuous Kashida (tatweel, U+0640) justifier.
 *
 * Where [KashidaJustifier] inserts a *whole number* of tatweel glyphs — so a
 * line's width can only land on integer multiples of one tatweel advance — this
 * stretches the tatweel glyphs **already present** in a shaped line to an
 * arbitrary, fractional width, so the line lands on [targetWidthPx] exactly and
 * varies continuously with it.
 *
 * **Identifying the tatweel glyphs:** by their *source character*, not their
 * glyph id. A glyph is treated as a kashida when its cluster points at a U+0640
 * in [shapedText] and it carries advance. Matching the nominal U+0640 glyph id
 * fails on fonts whose GSUB substitutes inserted kashida into contextual or
 * ligated forms (common in decorative/display Arabic fonts); the source
 * character survives substitution. The advance check excludes zero-advance
 * tashkeel marks that may share a kashida's cluster.
 *
 * Each matched glyph gets a uniform horizontal scale `s`: its `xAdvance` becomes
 * `xAdvance * s` and its [com.mohamedrejeb.harfbuzz.core.GlyphPosition.xScale]
 * becomes `s` so the renderer stretches the glyph outline to fill the new
 * advance. `s` may be `> 1` (stretch) or `< 1` (shrink an overshooting tatweel
 * to a sub-tatweel target), so the line lands on the target exactly either way.
 *
 * Bail-outs (return [paragraph] unchanged, same instance):
 *  - the paragraph is empty,
 *  - [targetWidthPx] is non-finite,
 *  - there are no kashida glyphs to stretch (incl. fonts that don't shape
 *    U+0640 to a real glyph — caller should gate on capability),
 *  - the required scale is non-finite, non-positive, or already `1`.
 */
public object KashidaStretchJustifier {

    private const val KASHIDA = 'ـ'

    public fun stretchToWidth(
        paragraph: ShapedParagraph,
        targetWidthPx: Float,
        shapedText: String,
    ): ShapedParagraph {
        if (paragraph.runs.isEmpty()) return paragraph
        if (!targetWidthPx.isFinite()) return paragraph
        val current = paragraph.totalAdvance

        // Total natural advance contributed by kashida glyphs across all runs.
        var tatweelAdvance = 0f
        for (run in paragraph.runs) {
            for (i in run.glyphs.indices) {
                if (run.isKashida(i, shapedText)) tatweelAdvance += run.positions[i].xAdvance
            }
        }
        if (tatweelAdvance <= 0f) return paragraph

        // Solve `nonTatweel + s * tatweelAdvance = target` for the uniform scale.
        // `s` may be < 1 (shrink) or > 1 (stretch); both land on the target
        // exactly. A non-positive `s` means the target is at/below the
        // non-tatweel content and cannot be reached by any tatweel width.
        val nonTatweel = current - tatweelAdvance
        val scale = (targetWidthPx - nonTatweel) / tatweelAdvance
        if (!scale.isFinite() || scale <= 0f || scale == 1f) return paragraph

        val newRuns = ArrayList<ShapedRun>(paragraph.runs.size)
        for (run in paragraph.runs) {
            if (run.isEmpty) {
                newRuns.add(run)
                continue
            }
            var runDelta = 0f
            var newPositions: ArrayList<com.mohamedrejeb.harfbuzz.core.GlyphPosition>? = null
            for (i in run.glyphs.indices) {
                if (!run.isKashida(i, shapedText)) continue
                if (newPositions == null) newPositions = ArrayList(run.positions)
                val p = run.positions[i]
                val newAdvance = p.xAdvance * scale
                runDelta += newAdvance - p.xAdvance
                newPositions[i] = p.copy(xAdvance = newAdvance, xScale = scale)
            }
            if (newPositions == null || runDelta == 0f) {
                newRuns.add(run)
            } else {
                val newRunTotal = run.totalAdvance + runDelta
                newRuns.add(
                    run.copy(
                        positions = newPositions,
                        totalAdvance = newRunTotal,
                        logical = run.logical.copy(right = run.logical.left + newRunTotal),
                    ),
                )
            }
        }

        // Land the paragraph box on the target exactly so justified lines match.
        val growth = targetWidthPx - current
        val newInk = if (paragraph.ink.isEmpty) {
            paragraph.ink
        } else {
            paragraph.ink.copy(right = paragraph.ink.right + growth)
        }
        return paragraph.copy(
            runs = newRuns,
            totalAdvance = targetWidthPx,
            ink = newInk,
            logical = paragraph.logical.copy(right = paragraph.logical.left + targetWidthPx),
        )
    }

    /**
     * A glyph is a kashida to stretch when it carries advance (excludes
     * zero-advance tashkeel marks) and its cluster's source character in
     * [shapedText] is the tatweel U+0640.
     */
    private fun ShapedRun.isKashida(i: Int, shapedText: String): Boolean {
        if (positions[i].xAdvance <= 0f) return false
        return shapedText.getOrNull(glyphs[i].cluster) == KASHIDA
    }
}
