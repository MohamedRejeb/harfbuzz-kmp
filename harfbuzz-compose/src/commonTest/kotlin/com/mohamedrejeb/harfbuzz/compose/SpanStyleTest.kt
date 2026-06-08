package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SpanStyleTest {
    @Test
    fun defaultValuesAreUnspecifiedAndNullBrush() {
        val style = SpanStyle()
        assertEquals(Color.Unspecified, style.color)
        assertNull(style.brush)
        // clearBrush opts in to brush reset; off by default so old
        // call sites keep their existing semantics.
        assertEquals(false, style.clearBrush)
    }

    @Test
    fun equalityByValue() {
        assertEquals(SpanStyle(color = Color.Red), SpanStyle(color = Color.Red))
    }

    @Test
    fun differentColorsAreUnequal() {
        assertNotEquals(SpanStyle(color = Color.Red), SpanStyle(color = Color.Blue))
    }

    @Test
    fun copyChangesOneAttribute() {
        val a = SpanStyle(color = Color.Red)
        val b = a.copy(brush = SolidColor(Color.Blue))
        assertEquals(Color.Red, b.color)
        assertNotEquals(null, b.brush)
    }
}
