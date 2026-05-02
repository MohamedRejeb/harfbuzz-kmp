package com.mohamedrejeb.harfbuzz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BidiResolverTest {

    private val resolver = BidiResolver()

    @Test
    fun `pure latin text is one LTR run`() {
        val runs = resolver.resolve("Hello World", HbDirection.LTR)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].level)
        assertEquals(0, runs[0].start)
        assertEquals(11, runs[0].end)
        assertTrue(!runs[0].isRtl)
    }

    @Test
    fun `pure arabic text is one RTL run with auto base`() {
        val text = "مرحبا"
        val runs = resolver.resolve(text)
        assertEquals(1, runs.size)
        assertTrue(runs[0].isRtl, "Arabic should resolve to RTL run")
        assertEquals(0, runs[0].start)
        assertEquals(text.length, runs[0].end)
    }

    @Test
    fun `mixed Latin+Arabic produces multiple runs`() {
        // "Hello مرحبا" - five Latin chars + space + five Arabic chars.
        val text = "Hello مرحبا"
        val runs = resolver.resolve(text, HbDirection.LTR)
        assertTrue(runs.size >= 2, "expected at least 2 runs, got ${runs.size}")
        assertTrue(runs.first().level == 0, "first run should be LTR (level 0)")
        assertTrue(runs.last().isRtl, "last run should be RTL")
    }

    @Test
    fun `arabic digits get AN class - visually right-to-left numerals`() {
        val text = "الكلمة ١٢٣"   // Arabic-Indic digits 0660-0669
        val runs = resolver.resolve(text)
        // The Arabic-Indic digits are AN class; in an RTL paragraph they end up at level 2.
        assertTrue(runs.any { it.level == 2 }, "expected an AN-resolved run with level 2")
    }

    @Test
    fun `european digits in arabic context get EN class`() {
        val text = "الكلمة 123"
        val runs = resolver.resolve(text)
        // ASCII digits inside an Arabic paragraph: EN, level 2.
        assertTrue(runs.any { it.level == 2 }, "expected level-2 EN run for ASCII digits in Arabic")
    }

    @Test
    fun `paragraph direction follows first strong character when AUTO`() {
        val arabicFirst = resolver.resolve("سلام Hello")
        val latinFirst = resolver.resolve("Hello سلام")
        // Paragraph level differs based on first strong char - visible in the
        // dominant base level.
        val arabicMaxLevel = arabicFirst.maxOf { it.level }
        val latinMaxLevel = latinFirst.maxOf { it.level }
        // In an RTL-base paragraph the LTR runs are level 2; in an LTR-base
        // paragraph the RTL runs are level 1. So the maximum level differs.
        assertTrue(arabicMaxLevel >= 2, "Arabic-base paragraph should have level-2 LTR runs")
        assertTrue(latinMaxLevel >= 1, "Latin-base paragraph should have level-1 RTL runs")
    }

    @Test
    fun `empty text returns empty run list`() {
        assertEquals(emptyList<BidiRun>(), resolver.resolve(""))
    }

    @Test
    fun `tashkeel marks attach to preceding base letter via NSM rule W1`() {
        // الـ + fatha (0x064E)
        val text = "اَل"
        val runs = resolver.resolve(text)
        // Whole thing should be a single RTL run - the NSM picks up the AL
        // class of the preceding char and gets resolved as R after W3.
        assertEquals(1, runs.size)
        assertTrue(runs[0].isRtl)
    }
}
