package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins [HbBuffer.setTextWithContext]: shaping a mid-word slice with the
 * whole string as pre/post context must reproduce the joining forms the
 * whole-word shape picks, while the same slice shaped standalone must
 * not (isolated/final forms at the cut).
 */
class BufferContextShapingTest {

    // A joined Arabic word: every boundary inside it is mid-join.
    private val word = "مرحبا"

    @Test
    fun `slice shaped with context matches the whole word's glyphs`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val font = face.toFont()
        try {
            val whole = shapeWith(font) { buf -> buf.text = word }
            // Split between HAH and BEH, a dual-joining pair, so the cut
            // really is mid-join (a right-join-only boundary like REH|HAH
            // would shape identically with or without context).
            val split = 3
            val head = shapeWith(font) { buf -> buf.setTextWithContext(word, 0, split) }
            val tail = shapeWith(font) { buf -> buf.setTextWithContext(word, split, word.length - split) }

            // RTL output: the tail's glyphs come visually first, so compare
            // glyph-id multisets and the concatenated advance, not order.
            val wholeIds = whole.glyphs.map { it.glyphId }.sorted()
            val contextIds = (head.glyphs + tail.glyphs).map { it.glyphId }.sorted()
            assertEquals(wholeIds, contextIds)
            assertEquals(whole.totalAdvance, head.totalAdvance + tail.totalAdvance, 0.01f)

            // Clusters are contextText-relative (absolute), not slice-relative.
            assertEquals(split, tail.glyphs.minOf { it.cluster })

            // The standalone (no-context) shape of the same slice differs,
            // proving the context is what preserved the joining forms.
            val headNoCtx = shapeWith(font) { buf -> buf.text = word.substring(0, split) }
            assertNotEquals(wholeIds, (headNoCtx.glyphs + tail.glyphs).map { it.glyphId }.sorted())
        } finally {
            font.close()
            face.close()
        }
    }

    private suspend fun shapeWith(font: HbFont, load: (HbBuffer) -> Unit): ShapedRun =
        HbBuffer().use { buf ->
            buf.reset()
            load(buf)
            buf.direction = HbDirection.RTL
            font.shape(buf, 48f)
        }
}
