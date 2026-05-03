package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.paragraph.WordSpacingJustifier.THIN_SPACE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordSpacingJustifierTest {

    @Test
    fun identity_when_no_room_to_grow() {
        val text = "Hello world"
        val r = WordSpacingJustifier.justifyLine(text, 200f, 100f, 5f)
        assertEquals(text, r.justifiedText)
    }

    @Test
    fun identity_when_thin_space_width_non_positive() {
        val text = "Hello world"
        assertEquals(text, WordSpacingJustifier.justifyLine(text, 100f, 200f, 0f).justifiedText)
        assertEquals(text, WordSpacingJustifier.justifyLine(text, 100f, 200f, -1f).justifiedText)
    }

    @Test
    fun identity_when_no_word_boundaries() {
        val text = "Hello"
        val r = WordSpacingJustifier.justifyLine(text, 50f, 200f, 5f)
        assertEquals(text, r.justifiedText)
    }

    @Test
    fun inserts_thin_spaces_at_word_boundaries() {
        // 2 spaces, extra=20, thin=5 -> 4 thin spaces total.
        val r = WordSpacingJustifier.justifyLine("a b c", 30f, 50f, 5f)
        assertEquals(4, r.justifiedText.count { it == THIN_SPACE })
    }

    @Test
    fun distributes_evenly_then_remainder_first() {
        // 3 spaces (in "a b c d"), extra=10, thin=4 -> floor(2.5) = 2.
        // 2 thin spaces across 3 word boundaries -> [1, 1, 0].
        val r = WordSpacingJustifier.justifyLine("a b c d", 40f, 50f, 4f)
        assertEquals(2, r.justifiedText.count { it == THIN_SPACE })
    }

    @Test
    fun mapping_is_monotonic() {
        val r = WordSpacingJustifier.justifyLine("a b c", 30f, 60f, 5f)
        for (i in 1 until r.originalToJustifiedIndex.size) {
            assertTrue(r.originalToJustifiedIndex[i] > r.originalToJustifiedIndex[i - 1])
        }
    }

    @Test
    fun mapping_size_matches_original_length() {
        val text = "Hello world"
        val r = WordSpacingJustifier.justifyLine(text, 50f, 80f, 5f)
        assertEquals(text.length, r.originalToJustifiedIndex.size)
    }

    @Test
    fun preserves_original_chars_in_order() {
        val text = "The quick brown fox"
        val r = WordSpacingJustifier.justifyLine(text, 100f, 200f, 5f)
        assertEquals(text, r.justifiedText.filter { it != THIN_SPACE })
    }

    @Test
    fun distribute_helper_evenly_when_total_divides_slots() {
        assertContentEquals(intArrayOf(2, 2, 2), WordSpacingJustifier.distribute(6, 3))
    }

    @Test
    fun distribute_helper_remainder_first() {
        assertContentEquals(intArrayOf(2, 1, 1), WordSpacingJustifier.distribute(4, 3))
    }
}
