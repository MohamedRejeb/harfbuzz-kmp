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
 * trailing-edge index from [clusterEndArray], and the default paint
 * to fall back to. [resolve] is called once per glyph during the
 * styled draw pass.
 */
internal class PaintResolver(
    private val spans: List<StyleRange>,
    private val clusterEnds: Map<Int, Int>,
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
        val sourceIndex = if (glyphIndexInCluster == 0) {
            clusterStart
        } else {
            clusterEnd - (glyphsInCluster - glyphIndexInCluster)
        }

        var color = defaultColor
        var brush = defaultBrush
        // Walk spans in declaration order; per-attribute later-wins.
        for (s in spans) {
            if (sourceIndex < s.start || sourceIndex >= s.end) continue
            if (s.style.color != Color.Unspecified) color = s.style.color
            if (s.style.brush != null) brush = s.style.brush
        }
        return ResolvedPaint(color, brush)
    }
}
