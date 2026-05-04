package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyledTextTest {
    @Test
    fun defaultsToEmptySpans() {
        val s = StyledText("hello")
        assertEquals("hello", s.text)
        assertTrue(s.spans.isEmpty())
    }

    @Test
    fun lengthMatchesTextLength() {
        assertEquals(5, StyledText("hello").length)
        assertEquals(0, StyledText("").length)
    }

    @Test
    fun emptyConstantHasNoTextOrSpans() {
        assertEquals("", StyledText.EMPTY.text)
        assertTrue(StyledText.EMPTY.spans.isEmpty())
    }

    @Test
    fun spansArePreserved() {
        val red = SpanStyle(color = Color.Red)
        val s = StyledText("hello", listOf(StyleRange(0, 5, red)))
        assertEquals(1, s.spans.size)
        assertEquals(red, s.spans[0].style)
    }
}
