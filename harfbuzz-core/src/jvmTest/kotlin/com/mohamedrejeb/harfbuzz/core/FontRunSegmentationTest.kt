package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure segmentation math for authored font runs: clamping, last-wins
 * overlap resolution, adjacent-merge, and gap filling. Fonts are only
 * identity tokens here, so two instances of the same face suffice.
 */
class FontRunSegmentationTest {

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
    fun `empty input returns empty`() {
        assertEquals(emptyList(), normalizeFontRuns(emptyList(), 10))
        assertEquals(emptyList(), normalizeFontRuns(listOf(FontRun(0, 5, fontA)), 0))
    }

    @Test
    fun `out of range runs are clamped`() {
        val out = normalizeFontRuns(listOf(FontRun(-3, 99, fontA)), 5)
        assertEquals(listOf(FontRun(0, 5, fontA)), out)
    }

    @Test
    fun `inverted and empty ranges are dropped`() {
        assertEquals(
            emptyList(),
            normalizeFontRuns(listOf(FontRun(4, 2, fontA), FontRun(3, 3, fontB)), 10),
        )
    }

    @Test
    fun `overlap is last wins`() {
        val out = normalizeFontRuns(
            listOf(FontRun(0, 6, fontA), FontRun(2, 4, fontB)),
            10,
        )
        assertEquals(
            listOf(FontRun(0, 2, fontA), FontRun(2, 4, fontB), FontRun(4, 6, fontA)),
            out,
        )
    }

    @Test
    fun `later run fully covering earlier one wins entirely`() {
        val out = normalizeFontRuns(
            listOf(FontRun(2, 4, fontA), FontRun(0, 6, fontB)),
            10,
        )
        assertEquals(listOf(FontRun(0, 6, fontB)), out)
    }

    @Test
    fun `adjacent same font runs merge`() {
        val out = normalizeFontRuns(listOf(FontRun(0, 3, fontA), FontRun(3, 6, fontA)), 10)
        assertEquals(listOf(FontRun(0, 6, fontA)), out)
    }

    @Test
    fun `segmentRange fills gaps with null font and covers the range`() {
        val runs = normalizeFontRuns(listOf(FontRun(2, 4, fontA), FontRun(7, 9, fontB)), 12)
        val segs = segmentRange(0, 12, runs)
        assertEquals(
            listOf(
                FontSegment(0, 2, null),
                FontSegment(2, 4, fontA),
                FontSegment(4, 7, null),
                FontSegment(7, 9, fontB),
                FontSegment(9, 12, null),
            ),
            segs,
        )
        segs.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun `segmentRange clips runs to the queried range`() {
        val runs = normalizeFontRuns(listOf(FontRun(0, 10, fontA)), 10)
        assertEquals(listOf(FontSegment(3, 6, fontA)), segmentRange(3, 6, runs))
    }

    @Test
    fun `segmentRange with no runs is a single null segment`() {
        assertEquals(listOf(FontSegment(1, 4, null)), segmentRange(1, 4, emptyList()))
        assertTrue(segmentRange(4, 4, emptyList()).isEmpty())
    }
}
