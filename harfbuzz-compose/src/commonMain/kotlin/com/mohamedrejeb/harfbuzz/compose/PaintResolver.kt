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
    val distinct = sortedSetOf<Int>()
    for (run in paragraph.runs) {
        for (g in run.glyphs) distinct.add(g.cluster)
    }
    if (distinct.isEmpty()) return emptyMap()
    val out = HashMap<Int, Int>(distinct.size)
    val sorted = distinct.toIntArray()
    for (i in sorted.indices) {
        val cur = sorted[i]
        val end = if (i + 1 < sorted.size) sorted[i + 1] else textLength
        out[cur] = end
    }
    return out
}
