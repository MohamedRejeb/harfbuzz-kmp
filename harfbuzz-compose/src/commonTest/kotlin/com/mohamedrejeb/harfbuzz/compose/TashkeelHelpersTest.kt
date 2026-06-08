package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TashkeelHelpersTest {
    // withTashkeelColor sets clearBrush so a per-mark color paints
    // solid even when the outer call carries a Brush. Tests that
    // assert against the produced spans use the same value here.
    private val red = SpanStyle(color = Color.Red, clearBrush = true)

    @Test
    fun emptyTextReturnsSelf() {
        val s = StyledText("").withTashkeelColor(Color.Red)
        assertEquals("", s.text)
        assertTrue(s.spans.isEmpty())
    }

    @Test
    fun latinTextProducesNoSpans() {
        val s = StyledText("Hello world").withTashkeelColor(Color.Red)
        assertTrue(s.spans.isEmpty())
    }

    @Test
    fun fathaIsDetected() {
        // U+064E = ARABIC FATHA
        val s = StyledText("بَسم").withTashkeelColor(Color.Red)
        assertEquals(listOf(StyleRange(1, 2, red)), s.spans)
    }

    @Test
    fun multipleHarakatDetected() {
        // ب + FATHA + س + SUKUN + م + KASRA
        val s = StyledText("بَسْمِ").withTashkeelColor(Color.Red)
        assertEquals(3, s.spans.size)
        assertEquals(1, s.spans[0].start)
        assertEquals(3, s.spans[1].start)
        assertEquals(5, s.spans[2].start)
    }

    @Test
    fun tatweelIsExcluded() {
        val s = StyledText("بـس").withTashkeelColor(Color.Red)
        assertTrue(s.spans.isEmpty())
    }

    @Test
    fun daggerAlefDetected() {
        val s = StyledText("اٰ").withTashkeelColor(Color.Red)
        assertEquals(listOf(StyleRange(1, 2, red)), s.spans)
    }

    @Test
    fun quranicMarkDetected() {
        // U+06D6 = ARABIC SMALL HIGH LIGATURE SAD WITH LAM WITH ALEF MAKSURA
        val s = StyledText("اۖ").withTashkeelColor(Color.Red)
        assertEquals(listOf(StyleRange(1, 2, red)), s.spans)
    }

    @Test
    fun quranicMarkContinuationDetected() {
        // U+06DF, U+06E4, U+06E7 - representative samples from the
        // continuation, U+06DF-U+06E4, U+06E7-U+06E8 ranges.
        val s = StyledText("ا۟بۤتۧ").withTashkeelColor(Color.Red)
        assertEquals(3, s.spans.size)
    }

    @Test
    fun quranicLowMarkDetected() {
        // U+06EA = ARABIC EMPTY CENTRE LOW STOP
        val s = StyledText("ا۪").withTashkeelColor(Color.Red)
        assertEquals(listOf(StyleRange(1, 2, red)), s.spans)
    }

    @Test
    fun preservesExistingSpans() {
        val blue = SpanStyle(color = Color.Blue)
        val original = StyledText("بَسم", listOf(StyleRange(0, 1, blue)))
        val s = original.withTashkeelColor(Color.Red)
        assertEquals(2, s.spans.size)
        assertEquals(StyleRange(0, 1, blue), s.spans[0])
        assertEquals(StyleRange(1, 2, red), s.spans[1])
    }

    @Test
    fun brushHelperUsesBrush() {
        val brush = SolidColor(Color.Red)
        val s = StyledText("بَ").withTashkeelBrush(brush)
        assertEquals(1, s.spans.size)
        assertEquals(brush, s.spans[0].style.brush)
        assertEquals(Color.Unspecified, s.spans[0].style.color)
    }

    @Test
    fun colorHelperOptOutKeepsClearBrushOff() {
        // Opt-in to participating in the outer brush: produced spans
        // must NOT carry clearBrush, so the bucket draw still paints
        // tashkeel chars with the inherited brush.
        val s = StyledText("بَ").withTashkeelColor(Color.Red, participatesInOuterBrush = true)
        assertEquals(1, s.spans.size)
        assertEquals(Color.Red, s.spans[0].style.color)
        assertFalse(s.spans[0].style.clearBrush)
    }

    @Test
    fun isTashkeelCodepointBoundaries() {
        assertTrue(isTashkeelCodepoint(0x064B))     // start of harakat
        assertTrue(isTashkeelCodepoint(0x065F))     // end of harakat
        assertFalse(isTashkeelCodepoint(0x064A))    // just before
        assertFalse(isTashkeelCodepoint(0x0660))    // just after
        assertTrue(isTashkeelCodepoint(0x0670))     // dagger alef
        assertFalse(isTashkeelCodepoint(0x0640))    // tatweel - excluded
        assertTrue(isTashkeelCodepoint(0x06D6))     // start of quranic high
        assertTrue(isTashkeelCodepoint(0x06DC))
        assertFalse(isTashkeelCodepoint(0x06DD))    // gap
        assertFalse(isTashkeelCodepoint(0x06DE))    // gap
        assertTrue(isTashkeelCodepoint(0x06DF))     // continuation start
        assertTrue(isTashkeelCodepoint(0x06E4))
        assertFalse(isTashkeelCodepoint(0x06E5))    // gap
        assertFalse(isTashkeelCodepoint(0x06E6))    // gap
        assertTrue(isTashkeelCodepoint(0x06E7))
        assertTrue(isTashkeelCodepoint(0x06E8))
        assertFalse(isTashkeelCodepoint(0x06E9))    // gap
        assertTrue(isTashkeelCodepoint(0x06EA))
        assertTrue(isTashkeelCodepoint(0x06ED))
        assertFalse(isTashkeelCodepoint(0x06EE))    // just after
    }
}
