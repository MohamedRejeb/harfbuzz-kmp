package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The public per-line helpers for authored font runs: [sliceFontRuns]
 * (clip and rebase a paragraph's runs to a line range) and
 * [remapFontRuns] (project runs through a connector-insertion mapping).
 * Fonts are only identity tokens here.
 */
class FontRunSliceRemapTest {

    private lateinit var face: HbFace
    private lateinit var fontA: HbFont
    private lateinit var fontB: HbFont

    @BeforeTest
    fun setUp() = runBlocking {
        harfBuzzInit()
        face = HbFace.fromBytes(TestFonts.robotoRegular())
        fontA = face.toFont()
        fontB = face.toFont()
    }

    @AfterTest
    fun tearDown() {
        fontA.close()
        fontB.close()
        face.close()
    }

    @Test
    fun `slice clips and rebases to local coordinates`() {
        val runs = listOf(FontRun(2, 8, fontA), FontRun(10, 14, fontB))
        assertEquals(
            listOf(FontRun(0, 3, fontA)),
            sliceFontRuns(runs, 5, 9),
        )
        assertEquals(
            listOf(FontRun(0, 2, fontA), FontRun(4, 6, fontB)),
            sliceFontRuns(runs, 6, 12),
        )
    }

    @Test
    fun `slice applies last-wins normalization before clipping`() {
        val runs = listOf(FontRun(0, 10, fontA), FontRun(3, 6, fontB))
        assertEquals(
            listOf(FontRun(0, 2, fontB), FontRun(2, 4, fontA)),
            sliceFontRuns(runs, 4, 8),
        )
    }

    @Test
    fun `slice outside every run or empty range is empty`() {
        val runs = listOf(FontRun(0, 3, fontA))
        assertEquals(emptyList(), sliceFontRuns(runs, 5, 9))
        assertEquals(emptyList(), sliceFontRuns(runs, 2, 2))
        assertEquals(emptyList(), sliceFontRuns(emptyList(), 0, 9))
    }

    @Test
    fun `remap projects runs through an insertion mapping`() {
        // Original "abcde" to justified "abXcdYe": mapping[i] is the position
        // of original char i in the justified text.
        val mapping = intArrayOf(0, 1, 3, 4, 6)
        val runs = listOf(FontRun(1, 3, fontA), FontRun(3, 5, fontB))
        assertEquals(
            listOf(FontRun(1, 4, fontA), FontRun(4, 7, fontB)),
            remapFontRuns(runs, mapping, justifiedLength = 7),
        )
    }

    @Test
    fun `remap keeps a connector with the run that contains the preceding char`() {
        // Insertion between chars 1 and 2; run A ends at 2, so the connector
        // (justified index 2) belongs to A: A maps to [0, 3), B to [3, 5).
        val mapping = intArrayOf(0, 1, 3, 4)
        val runs = listOf(FontRun(0, 2, fontA), FontRun(2, 4, fontB))
        assertEquals(
            listOf(FontRun(0, 3, fontA), FontRun(3, 5, fontB)),
            remapFontRuns(runs, mapping, justifiedLength = 5),
        )
    }

    @Test
    fun `remap clamps out-of-range runs and drops empties`() {
        val mapping = intArrayOf(0, 1, 2)
        assertEquals(
            listOf(FontRun(1, 3, fontA)),
            remapFontRuns(listOf(FontRun(1, 99, fontA), FontRun(2, 2, fontB)), mapping, justifiedLength = 3),
        )
        assertEquals(emptyList(), remapFontRuns(emptyList(), mapping, justifiedLength = 3))
    }
}
