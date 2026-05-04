package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StyleRangeTest {
    private val red = SpanStyle(color = Color.Red)

    @Test
    fun acceptsValidRange() {
        val r = StyleRange(0, 5, red)
        assertEquals(0, r.start)
        assertEquals(5, r.end)
        assertEquals(red, r.style)
    }

    @Test
    fun acceptsEmptyRange() {
        val r = StyleRange(3, 3, red)
        assertEquals(3, r.start)
        assertEquals(3, r.end)
    }

    @Test
    fun rejectsNegativeStart() {
        assertFailsWith<IllegalArgumentException> { StyleRange(-1, 5, red) }
    }

    @Test
    fun rejectsStartGreaterThanEnd() {
        assertFailsWith<IllegalArgumentException> { StyleRange(5, 3, red) }
    }
}
