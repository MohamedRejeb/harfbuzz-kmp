package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import kotlin.test.Test
import kotlin.test.assertEquals

class PaintResolverTest {
    private val defaultBrush: Brush? = null
    private val defaultColor = Color.Black

    private fun resolverFor(
        clusterEnds: Map<Int, Int>,
        spans: List<StyleRange>,
        clusterText: String = "",
    ): PaintResolver = PaintResolver(
        spans = spans,
        clusterEnds = clusterEnds,
        clusterText = clusterText,
        defaultColor = defaultColor,
        defaultBrush = defaultBrush,
    )

    @Test
    fun noSpansResolvesToDefault() {
        val r = resolverFor(mapOf(0 to 1), spans = emptyList())
        assertEquals(
            ResolvedPaint(Color.Black, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun oneToOneClusterPicksGlyphsCodepoint() {
        // Cluster spans codepoints 0 and 1; 2 glyphs (Arabic base + 1 mark).
        // Span colors char 1 red, leaves char 0 default.
        val red = SpanStyle(color = Color.Red)
        val r = resolverFor(
            clusterEnds = mapOf(0 to 2, 2 to 3),
            spans = listOf(StyleRange(1, 2, red)),
        )
        // glyph 0 -> codepoint 0 (default)
        assertEquals(
            ResolvedPaint(Color.Black, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 2),
        )
        // glyph 1 -> codepoint 1 (red)
        assertEquals(
            ResolvedPaint(Color.Red, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 1, glyphsInCluster = 2),
        )
    }

    @Test
    fun ligatureFallsBackToLeadingCodepoint() {
        // LAM-ALEF: cluster spans 2 codepoints, produces 1 glyph.
        // Span colors char 1 red, but the leading codepoint (0) has
        // no override -> the glyph paints with the default.
        val red = SpanStyle(color = Color.Red)
        val r = resolverFor(
            clusterEnds = mapOf(0 to 2, 2 to 3),
            spans = listOf(StyleRange(1, 2, red)),
        )
        assertEquals(
            ResolvedPaint(Color.Black, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun ligatureUsesLeadingCodepointSpan() {
        // Same LAM-ALEF cluster, but the span covers char 0 instead.
        val red = SpanStyle(color = Color.Red)
        val r = resolverFor(
            clusterEnds = mapOf(0 to 2, 2 to 3),
            spans = listOf(StyleRange(0, 1, red)),
        )
        assertEquals(
            ResolvedPaint(Color.Red, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun surrogatePairFallsBackToLeadingCodeUnit() {
        // Emoji: 1 codepoint, 2 UTF-16 code units, 1 glyph.
        val red = SpanStyle(color = Color.Red)
        val r = resolverFor(
            clusterEnds = mapOf(0 to 2, 2 to 3),
            spans = listOf(StyleRange(0, 2, red)),
        )
        assertEquals(
            ResolvedPaint(Color.Red, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun perAttributeMergeFromTwoSpans() {
        // Span A sets only color; Span B sets only brush. Both should layer.
        val brushB = SolidColor(Color.Green)
        val spanA = SpanStyle(color = Color.Red)
        val spanB = SpanStyle(brush = brushB)
        val r = resolverFor(
            clusterEnds = mapOf(0 to 1),
            spans = listOf(StyleRange(0, 1, spanA), StyleRange(0, 1, spanB)),
        )
        assertEquals(
            ResolvedPaint(Color.Red, brushB),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun laterSpanOverridesEarlierForSameAttribute() {
        val r = resolverFor(
            clusterEnds = mapOf(0 to 1),
            spans = listOf(
                StyleRange(0, 1, SpanStyle(color = Color.Red)),
                StyleRange(0, 1, SpanStyle(color = Color.Blue)),
            ),
        )
        assertEquals(
            ResolvedPaint(Color.Blue, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun unspecifiedColorInSpanIsNoOp() {
        // First span sets color red; second span sets only brush
        // (color = Unspecified). The second span must NOT clear red.
        val brush = SolidColor(Color.Green)
        val r = resolverFor(
            clusterEnds = mapOf(0 to 1),
            spans = listOf(
                StyleRange(0, 1, SpanStyle(color = Color.Red)),
                StyleRange(0, 1, SpanStyle(brush = brush)),
            ),
        )
        assertEquals(
            ResolvedPaint(Color.Red, brush),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun spanOutOfRangeIsIgnored() {
        // Span starts at index 100 - past every cluster - should be a no-op.
        val r = resolverFor(
            clusterEnds = mapOf(0 to 1),
            spans = listOf(StyleRange(100, 200, SpanStyle(color = Color.Red))),
        )
        assertEquals(
            ResolvedPaint(Color.Black, null),
            r.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun clearBrushDropsOuterBrush() {
        // The composable carries a paragraph-wide brush; a per-glyph
        // span sets only color + clearBrush. Resolved paint must drop
        // the inherited brush so the bucket draw paints solid color
        // instead of the outer gradient.
        val outerBrush = SolidColor(Color.Green)
        val resolver = PaintResolver(
            spans = listOf(
                StyleRange(0, 1, SpanStyle(color = Color.Red, clearBrush = true)),
            ),
            clusterEnds = mapOf(0 to 1),
            clusterText = "",
            defaultColor = Color.Black,
            defaultBrush = outerBrush,
        )
        assertEquals(
            ResolvedPaint(Color.Red, null),
            resolver.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun clearBrushOnlySpanLeavesColorAlone() {
        // A span that ONLY clears brush (no color, no brush) drops
        // the inherited brush but leaves the running color at the
        // outer default - the span's own SpanStyle.color is
        // Unspecified, so per-attribute later-wins keeps the prior
        // (default) color.
        val outerBrush = SolidColor(Color.Green)
        val resolver = PaintResolver(
            spans = listOf(StyleRange(0, 1, SpanStyle(clearBrush = true))),
            clusterEnds = mapOf(0 to 1),
            clusterText = "",
            defaultColor = Color.Black,
            defaultBrush = outerBrush,
        )
        assertEquals(
            ResolvedPaint(Color.Black, null),
            resolver.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun laterBrushSpanReSetsAfterClearBrush() {
        // clearBrush in span 0 unsets the brush; span 1's brush
        // re-sets it. Per-attribute later-wins still applies.
        val outerBrush = SolidColor(Color.Green)
        val laterBrush = SolidColor(Color.Blue)
        val resolver = PaintResolver(
            spans = listOf(
                StyleRange(0, 1, SpanStyle(color = Color.Red, clearBrush = true)),
                StyleRange(0, 1, SpanStyle(brush = laterBrush)),
            ),
            clusterEnds = mapOf(0 to 1),
            clusterText = "",
            defaultColor = Color.Black,
            defaultBrush = outerBrush,
        )
        assertEquals(
            ResolvedPaint(Color.Red, laterBrush),
            resolver.resolve(clusterStart = 0, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }

    @Test
    fun clearBrushOnlyFiresInsideSpanRange() {
        // A clearBrush span covering [0, 1) must NOT clear the brush
        // for a glyph at clusterStart = 1. Source-index gating works
        // identically to color/brush setters.
        val outerBrush = SolidColor(Color.Green)
        val resolver = PaintResolver(
            spans = listOf(
                StyleRange(0, 1, SpanStyle(color = Color.Red, clearBrush = true)),
            ),
            clusterEnds = mapOf(0 to 1, 1 to 2),
            clusterText = "",
            defaultColor = Color.Black,
            defaultBrush = outerBrush,
        )
        assertEquals(
            ResolvedPaint(Color.Black, outerBrush),
            resolver.resolve(clusterStart = 1, glyphIndexInCluster = 0, glyphsInCluster = 1),
        )
    }
}
