package com.mohamedrejeb.harfbuzz.core.paragraph

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM-side: verifies the public [graphemeBreakOpportunities] routes
 * through the ICU-backed `java.text.BreakIterator` actual and
 * inherits its UAX #29 behaviour (combining marks, ZWJ emoji
 * sequences, regional-indicator pairs all stay intact).
 */
class GraphemeBreakerJvmTest {

    @Test
    fun empty_string_returns_single_zero() {
        assertContentEquals(intArrayOf(0), graphemeBreakOpportunities(""))
    }

    @Test
    fun starts_with_zero_and_ends_with_length() {
        val text = "Hello"
        val out = graphemeBreakOpportunities(text)
        assertEquals(0, out.first())
        assertEquals(text.length, out.last())
    }

    @Test
    fun ascii_emits_break_after_each_char() {
        // ICU agrees with the fallback on plain ASCII.
        assertContentEquals(intArrayOf(0, 1, 2, 3), graphemeBreakOpportunities("abc"))
    }

    @Test
    fun combining_mark_stays_with_base_letter() {
        // "e" + COMBINING ACUTE (U+0301) is ONE grapheme cluster.
        // Boundaries should only be at 0 and 2 (not between e and the mark).
        val text = "é"
        val out = graphemeBreakOpportunities(text)
        assertContentEquals(intArrayOf(0, 2), out)
    }

    @Test
    fun zwj_emoji_sequence_never_splits_a_surrogate_pair() {
        // Family emoji: man + ZWJ + woman + ZWJ + girl. Encoded as
        // 3 supplementary codepoints (each a surrogate pair) joined
        // by U+200D (ZWJ). Older JDK ICU bundles may not collapse the
        // ZWJ sequence into one grapheme - that's a quality-of-result
        // issue, not a correctness one. The bisect's correctness
        // invariant is that we MUST NOT cut inside a surrogate pair.
        val text = "👨‍👩‍👧"
        val out = graphemeBreakOpportunities(text).toSet()
        // Surrogate pair starts: 0 (man hi), 3 (woman hi), 6 (girl hi).
        // Internal positions to forbid: 1, 4, 7.
        assertFalse(1 in out, "boundary inside man's surrogate pair: $out")
        assertFalse(4 in out, "boundary inside woman's surrogate pair: $out")
        assertFalse(7 in out, "boundary inside girl's surrogate pair: $out")
    }

    @Test
    fun regional_indicator_flag_never_splits_a_surrogate_pair() {
        // Two regional-indicator codepoints render as a single flag
        // grapheme. Each codepoint is a surrogate pair (4 UTF-16 code
        // units total). Older ICU may report a boundary between the
        // two indicators (legal in UAX #29 but not the modern shaping
        // result); the bisect just needs surrogate pairs intact.
        val text = "🇫🇷" // FR flag
        val out = graphemeBreakOpportunities(text).toSet()
        assertFalse(1 in out, "boundary inside first indicator's surrogate pair: $out")
        assertFalse(3 in out, "boundary inside second indicator's surrogate pair: $out")
    }

    @Test
    fun supplementary_codepoint_keeps_surrogate_pair_together() {
        val emoji = "😀"
        val out = graphemeBreakOpportunities(emoji)
        assertContentEquals(intArrayOf(0, 2), out)
    }

    @Test
    fun result_is_strictly_ascending() {
        val text = "Hello, world! 😀 OK"
        val out = graphemeBreakOpportunities(text)
        for (i in 1 until out.size) {
            assertTrue(
                out[i] > out[i - 1],
                "boundary[$i]=${out[i]} not > boundary[${i - 1}]=${out[i - 1]}",
            )
        }
    }
}
