package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.paragraph.ArabicTextUtils.KASHIDA
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [KashidaJustifier]. Covers distribution, identity
 * bail-outs, insertion behaviour, mapping invariants, and the
 * punctuation / digit join-detection regressions.
 */
class KashidaJustifierTest {

    // distributeKashidas

    @Test
    fun distribute_evenly_when_total_divides_slots() {
        val r = KashidaJustifier.distributeKashidas(6, 3)
        assertContentEquals(intArrayOf(2, 2, 2), r)
    }

    @Test
    fun distribute_remainder_to_earlier_slots() {
        val r = KashidaJustifier.distributeKashidas(7, 3)
        assertContentEquals(intArrayOf(3, 2, 2), r)
    }

    @Test
    fun distribute_more_slots_than_total() {
        val r = KashidaJustifier.distributeKashidas(2, 5)
        assertContentEquals(intArrayOf(1, 1, 0, 0, 0), r)
    }

    @Test
    fun distribute_zero_total() {
        val r = KashidaJustifier.distributeKashidas(0, 3)
        assertContentEquals(intArrayOf(0, 0, 0), r)
    }

    @Test
    fun distribute_zero_slots() {
        val r = KashidaJustifier.distributeKashidas(5, 0)
        assertEquals(0, r.size)
    }

    // Identity / guard cases

    @Test
    fun identity_when_target_below_current() {
        val text = "نص عربي"
        val r = KashidaJustifier.justifyArabicLine(text, 200f, 100f, 10f)
        assertEquals(text, r.justifiedText)
        assertContentEquals(IntArray(text.length) { it }, r.originalToJustifiedIndex)
    }

    @Test
    fun identity_for_non_arabic_text() {
        val text = "Hello world"
        val r = KashidaJustifier.justifyArabicLine(text, 100f, 200f, 10f)
        assertEquals(text, r.justifiedText)
    }

    @Test
    fun identity_when_kashida_width_non_positive() {
        val text = "نص عربي"
        assertEquals(text, KashidaJustifier.justifyArabicLine(text, 100f, 200f, 0f).justifiedText)
        assertEquals(text, KashidaJustifier.justifyArabicLine(text, 100f, 200f, -5f).justifiedText)
    }

    @Test
    fun identity_when_no_insertion_points() {
        // اد - both right-join-only, no place to put a Kashida.
        val text = "اد"
        val r = KashidaJustifier.justifyArabicLine(text, 50f, 100f, 10f)
        assertEquals(text, r.justifiedText)
    }

    // Insertion behaviour

    @Test
    fun inserts_kashida_when_room_to_grow() {
        // "بت" - one insertion point. extra=50, kashida=10 -> 5 inserted.
        val r = KashidaJustifier.justifyArabicLine("بت", 50f, 100f, 10f)
        assertEquals(5, r.justifiedText.count { it == KASHIDA })
        assertTrue(r.justifiedText.startsWith('ب'))
        assertTrue(r.justifiedText.endsWith('ت'))
    }

    @Test
    fun floors_kashida_count_to_avoid_overshoot() {
        // extra=15, kashida=10 -> floor(1.5) = 1.
        val r = KashidaJustifier.justifyArabicLine("بت", 85f, 100f, 10f)
        assertEquals(1, r.justifiedText.count { it == KASHIDA })
    }

    @Test
    fun at_least_one_kashida_when_any_room() {
        // extra=1 - smaller than kashida width but the floor protects 1.
        val r = KashidaJustifier.justifyArabicLine("بت", 99f, 100f, 10f)
        assertEquals(1, r.justifiedText.count { it == KASHIDA })
    }

    @Test
    fun distributes_evenly_across_multiple_points() {
        // كتب - Kaf-Ta-Ba: two valid insertion points.
        val r = KashidaJustifier.justifyArabicLine("كتب", 60f, 100f, 10f)
        assertEquals(4, r.justifiedText.count { it == KASHIDA })
    }

    @Test
    fun preserves_original_chars_in_order() {
        val text = "نص عربي تجريبي"
        val r = KashidaJustifier.justifyArabicLine(text, 100f, 300f, 10f)
        assertEquals(text, r.justifiedText.filter { it != KASHIDA })
    }

    @Test
    fun preserves_diacritics() {
        // بَتَ - Ba + Fatha + Ta + Fatha. Kashida should land between
        // base letters; diacritics don't break the join chain.
        val text = "بَتَ"
        val r = KashidaJustifier.justifyArabicLine(text, 50f, 100f, 10f)
        assertTrue(r.justifiedText.contains(KASHIDA))
        assertEquals(text, r.justifiedText.filter { it != KASHIDA })
    }

    // Mapping

    @Test
    fun mapping_size_equals_original_length() {
        val text = "نص"
        val r = KashidaJustifier.justifyArabicLine(text, 50f, 100f, 10f)
        assertEquals(text.length, r.originalToJustifiedIndex.size)
    }

    @Test
    fun mapping_is_strictly_monotonic() {
        val r = KashidaJustifier.justifyArabicLine("كتبت", 50f, 200f, 10f)
        for (i in 1 until r.originalToJustifiedIndex.size) {
            assertTrue(r.originalToJustifiedIndex[i] > r.originalToJustifiedIndex[i - 1])
        }
    }

    @Test
    fun mapping_preserves_original_chars_at_their_indices() {
        val text = "بت"
        val r = KashidaJustifier.justifyArabicLine(text, 80f, 100f, 10f)
        for (i in text.indices) {
            assertEquals(text[i], r.justifiedText[r.originalToJustifiedIndex[i]])
        }
    }

    // Punctuation safety: joinsToNext must reject punctuation and digits

    @Test
    fun no_kashida_after_arabic_comma() {
        // "ب،ب" - Ba, Arabic comma, Ba. The comma must NOT count as a
        // joining letter, so no insertion point exists.
        val pts = ArabicTextUtils.findKashidaInsertionPoints("ب،ب")
        assertEquals(0, pts.size)
    }

    @Test
    fun no_kashida_after_arabic_indic_digit() {
        val pts = ArabicTextUtils.findKashidaInsertionPoints("ب٠ب")
        assertEquals(0, pts.size)
    }

    @Test
    fun lam_alef_ligature_not_broken() {
        // "لا" - Lam + Alef. Kashida between them would break the
        // mandatory ligature; the scan must skip this position.
        val pts = ArabicTextUtils.findKashidaInsertionPoints("لا")
        assertEquals(0, pts.size)
    }

    @Test
    fun high_priority_letters_get_extras_first() {
        // بهب - Ba (high) -> Haa -> Ba. Two insertion points; first is
        // priority 2, second is priority 1. With 3 Kashidas total, the
        // distribution should be [2, 1] - high priority first.
        val r = KashidaJustifier.justifyArabicLine("بهب", 70f, 100f, 10f)
        val total = r.justifiedText.count { it == KASHIDA }
        assertEquals(3, total)
        // Count Kashidas before the Haa vs after the Haa.
        val haaIdx = r.justifiedText.indexOf('ه')
        val ba2Idx = r.justifiedText.lastIndexOf('ب')
        val firstSlot = r.justifiedText.substring(0, haaIdx).count { it == KASHIDA }
        val secondSlot = r.justifiedText.substring(haaIdx + 1, ba2Idx).count { it == KASHIDA }
        assertTrue(firstSlot >= secondSlot, "high-pri slot $firstSlot should be >= low-pri $secondSlot")
    }
}
