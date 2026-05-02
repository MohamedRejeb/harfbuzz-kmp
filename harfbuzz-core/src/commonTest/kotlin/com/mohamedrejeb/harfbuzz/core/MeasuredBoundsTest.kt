package com.mohamedrejeb.harfbuzz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeasuredBoundsTest {

    @Test
    fun `EMPTY constant is empty and has zero metrics`() {
        val b = MeasuredBounds.EMPTY
        assertTrue(b.isEmpty)
        assertEquals(0f, b.advance)
        assertEquals(0f, b.ascent)
        assertEquals(0f, b.descent)
        assertEquals(0f, b.lineGap)
        assertEquals(0f, b.baseline)
        assertEquals(0f, b.lineHeight)
    }

    @Test
    fun `lineHeight equals ascent plus descent plus lineGap`() {
        val b = MeasuredBounds(
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            advance = 100f,
            ascent = 12f, descent = 4f, lineGap = 2f, baseline = 12f,
        )
        assertEquals(18f, b.lineHeight)
        assertFalse(b.isEmpty)
    }
}
