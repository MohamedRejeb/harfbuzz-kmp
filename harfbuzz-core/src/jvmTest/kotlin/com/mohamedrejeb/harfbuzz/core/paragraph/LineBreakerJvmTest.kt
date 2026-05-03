package com.mohamedrejeb.harfbuzz.core.paragraph

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-side: verifies the public [lineBreakOpportunities] routes through
 * the ICU-backed `java.text.BreakIterator` actual and inherits its
 * UAX #14 behaviour.
 */
class LineBreakerJvmTest {

    @Test
    fun empty_string_returns_single_zero() {
        assertContentEquals(intArrayOf(0), lineBreakOpportunities(""))
    }

    @Test
    fun starts_with_zero_and_ends_with_length() {
        val text = "The quick brown fox"
        val breaks = lineBreakOpportunities(text)
        assertEquals(0, breaks.first())
        assertEquals(text.length, breaks.last())
    }

    @Test
    fun result_is_strictly_ascending() {
        val text = "Hello, world! How are you today?"
        val breaks = lineBreakOpportunities(text)
        for (i in 1 until breaks.size) {
            assertTrue(
                breaks[i] > breaks[i - 1],
                "break[$i]=${breaks[i]} not > break[${i - 1}]=${breaks[i - 1]}",
            )
        }
    }

    @Test
    fun cjk_emits_per_character_breaks() {
        // ICU's UAX #14 emits a break opportunity between every CJK
        // character. The fallback would emit only [0, n]; this test
        // confirms we actually went through the platform actual.
        val text = "中文测试"
        val breaks = lineBreakOpportunities(text)
        // ICU should give breaks at every codepoint boundary (or close
        // to it) for CJK without spaces. Lower bound: more than the
        // fallback's 2 entries.
        assertTrue(breaks.size > 2, "expected multi-break CJK, got ${breaks.toList()}")
    }

    @Test
    fun newline_is_a_mandatory_break() {
        val text = "line1\nline2"
        val breaks = lineBreakOpportunities(text)
        // Position 6 (right after the newline) must be among the
        // returned break points.
        assertTrue(6 in breaks.toList(), "missing break after newline in ${breaks.toList()}")
    }

    @Test
    fun arabic_with_space_emits_break_after_space() {
        // "السلام عليكم" (12 chars in UTF-16). A break must exist at
        // index 7 (after the space at index 6).
        val text = "السلام عليكم"
        val breaks = lineBreakOpportunities(text)
        assertTrue(7 in breaks.toList(), "missing break after Arabic space in ${breaks.toList()}")
    }

    @Test
    fun hyphen_is_a_break_opportunity() {
        // ICU treats hyphen as an inter-word break opportunity (UAX #14
        // BA after BB). The fallback would not catch this.
        val text = "well-formed"
        val breaks = lineBreakOpportunities(text)
        assertTrue(5 in breaks.toList(), "missing break after hyphen in ${breaks.toList()}")
    }
}
