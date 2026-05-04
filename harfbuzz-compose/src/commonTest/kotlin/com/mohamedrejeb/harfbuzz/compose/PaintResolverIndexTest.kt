package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.GlyphInfo
import com.mohamedrejeb.harfbuzz.core.GlyphPosition
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.HbScript
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.ShapedRun
import kotlin.test.Test
import kotlin.test.assertEquals

class PaintResolverIndexTest {
    private fun glyph(cluster: Int): GlyphInfo = GlyphInfo(glyphId = 1, cluster = cluster, flags = 0)
    private val pos = GlyphPosition(xAdvance = 10f, yAdvance = 0f, xOffset = 0f, yOffset = 0f)

    private fun runOf(direction: HbDirection, vararg clusters: Int): ShapedRun = ShapedRun(
        glyphs = clusters.map(::glyph),
        positions = clusters.map { pos },
        direction = direction,
        script = HbScript.UNKNOWN,
        totalAdvance = 10f * clusters.size,
        ink = HbRect.EMPTY,
        logical = HbRect.EMPTY,
    )

    @Test
    fun clusterEndsForLtrSingleRun() {
        // 4 chars, clusters 0, 1, 2, 3 (one glyph each)
        val paragraph = ShapedParagraph(
            runs = listOf(runOf(HbDirection.LTR, 0, 1, 2, 3)),
            baseDirection = HbDirection.LTR,
            totalAdvance = 40f,
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
        val ends = clusterEndArray(paragraph, textLength = 4)
        // For each cluster id (the leading codepoint), the next cluster's start.
        // 0 -> 1, 1 -> 2, 2 -> 3, 3 -> 4 (text.length)
        assertEquals(mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4), ends)
    }

    @Test
    fun clusterEndsCollapsesRepeatedClusterIds() {
        // Arabic-style cluster: base + 2 marks all share cluster=0; next char at 3
        val paragraph = ShapedParagraph(
            runs = listOf(runOf(HbDirection.LTR, 0, 0, 0, 3)),
            baseDirection = HbDirection.LTR,
            totalAdvance = 40f,
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
        val ends = clusterEndArray(paragraph, textLength = 4)
        // Distinct cluster ids: 0 and 3. End of cluster 0 = start of cluster 3.
        assertEquals(mapOf(0 to 3, 3 to 4), ends)
    }

    @Test
    fun clusterEndsForRtlSingleRun() {
        // RTL run: glyph order is visual (right-to-left), but cluster ids
        // are still codepoint-indexed in logical order. Same expected map.
        val paragraph = ShapedParagraph(
            runs = listOf(runOf(HbDirection.RTL, 3, 2, 1, 0)),
            baseDirection = HbDirection.RTL,
            totalAdvance = 40f,
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
        val ends = clusterEndArray(paragraph, textLength = 4)
        assertEquals(mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4), ends)
    }

    @Test
    fun emptyParagraphReturnsEmptyMap() {
        val paragraph = ShapedParagraph.EMPTY
        assertEquals(emptyMap(), clusterEndArray(paragraph, textLength = 0))
    }
}
