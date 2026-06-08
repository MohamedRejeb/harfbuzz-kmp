package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph

/**
 * The paint a single glyph resolves to after a [PaintResolver] has
 * walked its cluster + the surrounding [SpanStyle] stack.
 */
internal data class ResolvedPaint(val color: Color, val brush: Brush?)

/**
 * Map every distinct cluster id in [paragraph] to its trailing edge:
 * the next-distinct cluster id in source order, or [textLength] for
 * the final cluster. Cluster ids are codepoint-indexed in logical
 * order regardless of run direction, so a single sorted scan over
 * every glyph produces the right answer for LTR and RTL alike.
 */
internal fun clusterEndArray(
    paragraph: ShapedParagraph,
    textLength: Int,
): Map<Int, Int> {
    if (paragraph.isEmpty || textLength == 0) return emptyMap()
    // HashSet + IntArray.sort() instead of sortedSetOf - the latter
    // resolves to a TreeSet which is not available on Kotlin/Wasm.
    val distinct = HashSet<Int>()
    for (run in paragraph.runs) {
        for (g in run.glyphs) distinct.add(g.cluster)
    }
    if (distinct.isEmpty()) return emptyMap()
    val sorted = distinct.toIntArray().also { it.sort() }
    val out = HashMap<Int, Int>(sorted.size)
    for (i in sorted.indices) {
        val cur = sorted[i]
        val end = if (i + 1 < sorted.size) sorted[i + 1] else textLength
        out[cur] = end
    }
    return out
}

/**
 * Per-glyph paint resolver. Holds the styled-text spans, the cluster
 * trailing-edge index from [clusterEndArray], the source / shaped
 * text the cluster ids reference (used for mark detection in the
 * mid-cluster-mark override), and the default paint to fall back to.
 * [resolve] is called once per glyph during the styled draw pass.
 *
 * [clusterText] should be the same string the cluster ids point into:
 * for a single-line draw that's `styledText.text`; for a per-line draw
 * inside a justified paragraph that's the per-line shaped text
 * (`LineLayout.text`), so cluster ids and combining-mark probes line
 * up after Kashida / thin-space insertion.
 */
internal class PaintResolver(
    private val spans: List<StyleRange>,
    private val clusterEnds: Map<Int, Int>,
    private val clusterText: String,
    private val defaultColor: Color,
    private val defaultBrush: Brush?,
) {
    fun resolve(
        clusterStart: Int,
        glyphIndexInCluster: Int,
        glyphsInCluster: Int,
    ): ResolvedPaint {
        // Cluster bounds: clusterEnds[clusterStart] is the next
        // distinct cluster's start (or text.length for the last).
        // Fallback to clusterStart + 1 keeps the heuristic well-
        // defined for glyphs whose cluster id was never registered
        // (defensive; should not happen for resolver output produced
        // from the same MeasuredText that supplied the index).
        val clusterEnd = clusterEnds[clusterStart] ?: (clusterStart + 1)

        // Map glyphs to source codepoints with a single rule that
        // collapses to all three cases (1:1, multi-glyph base,
        // merged-mark ligature):
        //
        //  - The base glyph (lowest index in source order, i.e. the
        //    last glyph in an RTL buffer) always maps to clusterStart.
        //  - Higher-index glyphs - marks above / below the base, plus
        //    extra body components some fonts emit - map to source
        //    positions starting from clusterEnd and walking backward.
        //
        // 1:1 (Arabic base + N marks, codeUnitWidth == glyphsInCluster):
        //   indexInCluster k maps to clusterStart + k.
        //
        // Multi-glyph base, codeUnitWidth < glyphsInCluster (BAA/NOON/FAA
        // body + dot in Noto Naskh): the [extra] lowest indices all
        // collapse onto clusterStart (body components of the leading
        // codepoint), the rest pick up the marks in source order.
        //
        // Merged-mark ligature, codeUnitWidth > glyphsInCluster (TAA +
        // SHADDA + KASRA shaped as [combined-mark, base], LAM-ALEF +
        // FATHA): the single mark glyph picks up the trailing source
        // codepoint, the base stays on clusterStart. Pure base ligatures
        // (LAM-ALEF, "fi") have only the base glyph and still resolve
        // to clusterStart (the leading codepoint's style), preserving
        // the existing "ligature inherits leading style" behaviour.
        val heuristicSource = if (glyphIndexInCluster == 0) {
            clusterStart
        } else {
            clusterEnd - (glyphsInCluster - glyphIndexInCluster)
        }

        // Mid-cluster-mark override: a base ligature spanning leading
        // AND trailing source codepoints with a combining mark in the
        // MIDDLE (LAM | FATHA | ALEF in Saudi Regular and similar
        // faces) absorbs the FATHA's source position into a single
        // cluster `[clusterStart, clusterEnd)`. The heuristic puts
        // the mark glyph at `clusterEnd - 1` (a non-mark codepoint),
        // so a span covering the actual mark codepoint never lights
        // up. Walk back from the heuristic source to the nearest
        // combining-mark codepoint INSIDE the cluster; if one exists,
        // route the glyph there. No walk-back when:
        //  - glyphIdx == 0 (the base maps to clusterStart unconditionally).
        //  - heuristicSource is already a mark (1:1 cluster, well-formed
        //    trailing-mark ligature - case 3 in PaintResolver's docstring).
        //  - the cluster has no mark before heuristicSource (BAA-dot at
        //    multi-glyph base case - the body extra collapses on
        //    clusterStart and we keep that behaviour).
        val sourceIndex = if (
            glyphIndexInCluster > 0 &&
            heuristicSource in (clusterStart + 1) until clusterEnd &&
            !isCombiningMarkAt(heuristicSource)
        ) {
            walkBackToCombiningMark(heuristicSource - 1, clusterStart) ?: heuristicSource
        } else {
            heuristicSource
        }

        var color = defaultColor
        var brush = defaultBrush
        // Walk spans in declaration order; per-attribute later-wins.
        // `clearBrush` drops the running brush BEFORE the same span's
        // brush re-set, so a span carrying both clearBrush and a brush
        // ends up with that brush (intuitive: "clear and replace");
        // a clearBrush-only span just unsets, leaving subsequent spans
        // free to re-set normally.
        for (s in spans) {
            if (sourceIndex < s.start || sourceIndex >= s.end) continue
            if (s.style.clearBrush) brush = null
            if (s.style.color != Color.Unspecified) color = s.style.color
            if (s.style.brush != null) brush = s.style.brush
        }
        return ResolvedPaint(color, brush)
    }

    /**
     * True when the codepoint that *starts* at [index] in [clusterText]
     * is a combining mark recognised by [isCombiningMarkCodepoint].
     * Returns false for low-surrogate positions (the codepoint sits at
     * the high surrogate one slot earlier) so a walk-back stepping by
     * code units skips them cleanly.
     */
    private fun isCombiningMarkAt(index: Int): Boolean {
        if (index < 0 || index >= clusterText.length) return false
        val ch = clusterText[index]
        if (ch.isLowSurrogate()) return false
        val cp = if (
            ch.isHighSurrogate() &&
            index + 1 < clusterText.length &&
            clusterText[index + 1].isLowSurrogate()
        ) {
            0x10000 + ((ch.code - 0xD800) shl 10) + (clusterText[index + 1].code - 0xDC00)
        } else {
            ch.code
        }
        return isCombiningMarkCodepoint(cp)
    }

    private fun walkBackToCombiningMark(from: Int, lowerBound: Int): Int? {
        var i = from
        while (i >= lowerBound) {
            if (isCombiningMarkAt(i)) return i
            i--
        }
        return null
    }
}

/**
 * Treat as a combining mark codepoint for the mid-cluster-mark
 * override in [PaintResolver]. Covers Arabic harakat / Quranic marks
 * (delegated to [isTashkeelCodepoint]) plus the BMP Combining
 * Diacritical Marks block (`U+0300..U+036F`) which catches Latin /
 * Greek / Cyrillic accented-letter ligatures with a mark sandwiched
 * inside. Other scripts can be added here as cases come up - the
 * predicate is the only place new ranges need to land.
 */
internal fun isCombiningMarkCodepoint(cp: Int): Boolean {
    if (isTashkeelCodepoint(cp)) return true
    return cp in 0x0300..0x036F
}
