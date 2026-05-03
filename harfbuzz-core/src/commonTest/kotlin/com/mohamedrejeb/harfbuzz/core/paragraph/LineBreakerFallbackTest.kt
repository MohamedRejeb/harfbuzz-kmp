package com.mohamedrejeb.harfbuzz.core.paragraph

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pure-Kotlin fallback contract. Runs on every target so we know the
 * fallback behaves the same regardless of which platform is active
 * and unaffected by the platform actual when it is.
 */
class LineBreakerFallbackTest {

    @Test
    fun empty_text_returns_single_zero() {
        assertContentEquals(intArrayOf(0), lineBreakOpportunitiesFallback(""))
    }

    @Test
    fun no_whitespace_returns_start_and_end_only() {
        // "Helloworld" has no break opportunities except start and end.
        assertContentEquals(intArrayOf(0, 10), lineBreakOpportunitiesFallback("Helloworld"))
    }

    @Test
    fun single_space_emits_break_after_run() {
        // "Hello world" - break after the space, between 'o' and 'w'.
        assertContentEquals(intArrayOf(0, 6, 11), lineBreakOpportunitiesFallback("Hello world"))
    }

    @Test
    fun multiple_spaces_collapse_to_one_break() {
        // "Hello   world" - one break after the three-space run.
        assertContentEquals(intArrayOf(0, 8, 13), lineBreakOpportunitiesFallback("Hello   world"))
    }

    @Test
    fun trailing_whitespace_emits_break_at_end() {
        // "Hello " - break after the space, which IS the end of the string.
        assertContentEquals(intArrayOf(0, 6), lineBreakOpportunitiesFallback("Hello "))
    }

    @Test
    fun newline_emits_mandatory_break() {
        // "Hello\nworld" - break right after the newline.
        assertContentEquals(intArrayOf(0, 6, 11), lineBreakOpportunitiesFallback("Hello\nworld"))
    }

    @Test
    fun crlf_collapses_to_single_break_point() {
        // "Hi\r\nthere" - CR + LF treated as one break, not two.
        assertContentEquals(intArrayOf(0, 4, 9), lineBreakOpportunitiesFallback("Hi\r\nthere"))
    }

    @Test
    fun unicode_paragraph_separator_breaks() {
        // U+2029 (paragraph separator) is a hard break.
        val text = "A B"
        assertContentEquals(intArrayOf(0, 2, 3), lineBreakOpportunitiesFallback(text))
    }

    @Test
    fun arabic_with_spaces_breaks_after_each_word() {
        // "نص عربي" - one break after the space at index 2 (length 7).
        val text = "نص عربي"
        val breaks = lineBreakOpportunitiesFallback(text)
        // Codepoint 0..1 is the first word + space; break at 3, end at 7.
        assertContentEquals(intArrayOf(0, 3, 7), breaks)
    }

    @Test
    fun cjk_without_spaces_has_no_intermediate_breaks() {
        // CJK without spaces: fallback only emits start + end. Real UAX
        // #14 would emit a break between every character; we accept the
        // looser fallback as a v0 limitation.
        val text = "中文测试"
        assertContentEquals(intArrayOf(0, 4), lineBreakOpportunitiesFallback(text))
    }

    @Test
    fun multiple_newlines_yield_multiple_breaks() {
        // "a\n\nb" - two consecutive newlines, two break points.
        val breaks = lineBreakOpportunitiesFallback("a\n\nb")
        assertContentEquals(intArrayOf(0, 2, 3, 4), breaks)
    }

    @Test
    fun result_is_strictly_ascending() {
        val text = "The quick brown fox jumps over the lazy dog"
        val breaks = lineBreakOpportunitiesFallback(text)
        for (i in 1 until breaks.size) {
            assertEquals(true, breaks[i] > breaks[i - 1], "break[$i]=${breaks[i]} not > break[${i - 1}]=${breaks[i - 1]}")
        }
    }
}
