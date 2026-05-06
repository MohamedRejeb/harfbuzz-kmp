package com.mohamedrejeb.harfbuzz.core.paragraph

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-Kotlin grapheme fallback contract. Runs on every target so we
 * know the fallback behaves the same regardless of which platform is
 * active and unaffected by a platform actual when one is present.
 */
class GraphemeBreakerFallbackTest {

    @Test
    fun empty_text_returns_single_zero() {
        assertContentEquals(intArrayOf(0), graphemeBreakOpportunitiesFallback(""))
    }

    @Test
    fun ascii_emits_break_after_each_codepoint() {
        // "abc" - boundaries at 0, 1, 2, 3.
        assertContentEquals(intArrayOf(0, 1, 2, 3), graphemeBreakOpportunitiesFallback("abc"))
    }

    @Test
    fun supplementary_codepoint_keeps_surrogate_pair_together() {
        // U+1F600 (grinning face) encodes as one surrogate pair
        // (high + low). The fallback must NOT cut between the two -
        // only at codepoint boundaries.
        val emoji = "😀"
        // 2 code units, one codepoint -> boundaries at 0 and 2 only.
        assertContentEquals(intArrayOf(0, 2), graphemeBreakOpportunitiesFallback(emoji))
    }

    @Test
    fun mixed_bmp_and_supplementary_emits_codepoint_boundaries() {
        // "a" + grinning face + "b" = "a😀b" (4 code units).
        // Boundaries: 0, 1 (after a), 3 (after pair), 4 (after b).
        val text = "a😀b"
        assertContentEquals(intArrayOf(0, 1, 3, 4), graphemeBreakOpportunitiesFallback(text))
    }

    @Test
    fun starts_with_zero_and_ends_with_length() {
        val text = "Hello"
        val out = graphemeBreakOpportunitiesFallback(text)
        assertEquals(0, out.first())
        assertEquals(text.length, out.last())
    }

    @Test
    fun result_is_strictly_ascending() {
        val text = "abc 😀 xyz"
        val out = graphemeBreakOpportunitiesFallback(text)
        for (i in 1 until out.size) {
            assertTrue(
                out[i] > out[i - 1],
                "boundary[$i]=${out[i]} not > boundary[${i - 1}]=${out[i - 1]}",
            )
        }
    }

    @Test
    fun arabic_letters_each_get_their_own_boundary() {
        // Arabic letters in the BMP - one boundary per code unit.
        val text = "نص" // "نص"
        assertContentEquals(intArrayOf(0, 1, 2), graphemeBreakOpportunitiesFallback(text))
    }
}
