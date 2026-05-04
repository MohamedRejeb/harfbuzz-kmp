package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyledTextBuilderTest {
    private val red = SpanStyle(color = Color.Red)
    private val blue = SpanStyle(color = Color.Blue)

    @Test
    fun emptyBuilderProducesEmptyText() {
        val s = buildStyledText { }
        assertEquals("", s.text)
        assertTrue(s.spans.isEmpty())
    }

    @Test
    fun appendBuildsTextWithoutSpans() {
        val s = buildStyledText {
            append("Hello ")
            append("World")
        }
        assertEquals("Hello World", s.text)
        assertTrue(s.spans.isEmpty())
    }

    @Test
    fun appendWithStyleAddsRange() {
        val s = buildStyledText {
            append("Hello ")
            append("World", red)
        }
        assertEquals("Hello World", s.text)
        assertEquals(listOf(StyleRange(6, 11, red)), s.spans)
    }

    @Test
    fun addStyleAddsRange() {
        val s = buildStyledText("Hello") {
            addStyle(red, 0, 5)
        }
        assertEquals("Hello", s.text)
        assertEquals(listOf(StyleRange(0, 5, red)), s.spans)
    }

    @Test
    fun withStyleAppliesToAppendsInsideBlock() {
        val s = buildStyledText {
            append("plain ")
            withStyle(red) { append("red") }
            append(" plain")
        }
        assertEquals("plain red plain", s.text)
        assertEquals(listOf(StyleRange(6, 9, red)), s.spans)
    }

    @Test
    fun nestedWithStyleStacks() {
        val s = buildStyledText {
            withStyle(red) {
                append("red")
                withStyle(blue) { append("blue") }
                append("red")
            }
        }
        assertEquals("redbluered", s.text)
        assertEquals(
            listOf(
                StyleRange(0, 3, red),
                StyleRange(3, 7, blue),
                StyleRange(7, 10, red),
            ),
            s.spans,
        )
    }

    @Test
    fun twoArgBuilderPrefillsTextAndAddStyleWorks() {
        val s = buildStyledText("Hello World") {
            addStyle(red, 0, 5)
        }
        assertEquals("Hello World", s.text)
        assertEquals(listOf(StyleRange(0, 5, red)), s.spans)
    }
}
