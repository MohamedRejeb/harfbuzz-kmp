package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SliceLineSpansTest {
    private val red = SpanStyle(color = Color.Red)
    private val blue = SpanStyle(color = Color.Blue)

    @Test
    fun spanFullyInsideLine() {
        val out = sliceLineSpans(
            spans = listOf(StyleRange(12, 18, red)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        // 12..18 paragraph -> 2..8 line-local
        assertEquals(listOf(StyleRange(2, 8, red)), out)
    }

    @Test
    fun spanFullyOutsideLineIsDropped() {
        val out = sliceLineSpans(
            spans = listOf(StyleRange(50, 60, red)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        assertEquals(emptyList(), out)
    }

    @Test
    fun spanClippedAtLeadingEdge() {
        val out = sliceLineSpans(
            spans = listOf(StyleRange(5, 15, red)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        // 5..15 paragraph clipped to 10..15 -> 0..5 line-local
        assertEquals(listOf(StyleRange(0, 5, red)), out)
    }

    @Test
    fun spanClippedAtTrailingEdge() {
        val out = sliceLineSpans(
            spans = listOf(StyleRange(15, 25, red)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        // 15..25 clipped to 15..20 -> 5..10 line-local
        assertEquals(listOf(StyleRange(5, 10, red)), out)
    }

    @Test
    fun spanExactlyAtLineBoundaryIsExcluded() {
        // span ends at lineStart -> excluded (end is exclusive).
        val out = sliceLineSpans(
            spans = listOf(StyleRange(0, 10, red)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        assertEquals(emptyList(), out)
    }

    @Test
    fun spanAtTrailingBoundaryIsIncluded() {
        // span starts at lineEnd-1, ends at lineEnd.
        val out = sliceLineSpans(
            spans = listOf(StyleRange(19, 20, red)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        assertEquals(listOf(StyleRange(9, 10, red)), out)
    }

    @Test
    fun multipleSpansPreserveDeclarationOrder() {
        val out = sliceLineSpans(
            spans = listOf(StyleRange(11, 12, red), StyleRange(15, 16, blue)),
            lineStart = 10, lineEnd = 20,
            originalToJustifiedIndex = null,
            justifiedTextLength = 10,
        )
        assertEquals(
            listOf(StyleRange(1, 2, red), StyleRange(5, 6, blue)),
            out,
        )
    }

    @Test
    fun justificationTranslatesIndices() {
        // Line was justified: line-local original index k maps to
        // justified index mapping[k]. Original line is 5 chars, but
        // justifier inserted Kashida so the justified line is 8 chars.
        // mapping = [0, 1, 3, 5, 7] - chars 2, 3, 4 each got pushed
        // out by an inserted Kashida.
        val mapping = intArrayOf(0, 1, 3, 5, 7)
        val out = sliceLineSpans(
            spans = listOf(StyleRange(11, 14, red)), // paragraph 11..14
            lineStart = 10, lineEnd = 15,
            originalToJustifiedIndex = mapping,
            justifiedTextLength = 8,
        )
        // Local: 1..4. Translated: mapping[1]..mapping[4] = 1..7.
        assertEquals(listOf(StyleRange(1, 7, red)), out)
    }

    @Test
    fun justificationEndAtLineLastUsesJustifiedTextLength() {
        // localEnd == mapping.size means the span reached the line's
        // trailing edge; we use justifiedTextLength as the upper bound
        // since mapping[mapping.size] would be out of range.
        val mapping = intArrayOf(0, 1, 3, 5, 7)
        val out = sliceLineSpans(
            spans = listOf(StyleRange(10, 15, red)),
            lineStart = 10, lineEnd = 15,
            originalToJustifiedIndex = mapping,
            justifiedTextLength = 8,
        )
        // Local: 0..5. localEnd=5 == mapping.size -> end = 8.
        assertEquals(listOf(StyleRange(0, 8, red)), out)
    }
}
