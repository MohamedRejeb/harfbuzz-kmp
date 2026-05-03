package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlyphExtentsBatchTest {

    private suspend fun openFont(): HbFont =
        HbFace.fromBytes(TestFonts.robotoRegular()).toFont()

    @Test
    fun `batch returns one entry per requested glyph in same order`() = runBlocking {
        openFont().use { font ->
            val gid1 = font.glyphIdForCodepoint('a'.code)
            val gid2 = font.glyphIdForCodepoint('b'.code)
            val gid3 = font.glyphIdForCodepoint('c'.code)

            val batch = font.glyphExtentsBatch(intArrayOf(gid1, gid2, gid3), sizePx = SIZE_PX)

            assertEquals(3, batch.size)
            assertEquals(font.glyphExtents(gid1, sizePx = SIZE_PX), batch[0])
            assertEquals(font.glyphExtents(gid2, sizePx = SIZE_PX), batch[1])
            assertEquals(font.glyphExtents(gid3, sizePx = SIZE_PX), batch[2])
        }
    }

    @Test
    fun `batch preserves nulls for glyphs HB cannot measure`() = runBlocking {
        openFont().use { font ->
            // Glyph id well beyond the face's glyph count. hb_font_get_glyph_extents
            // returns false → glyphExtents returns null.
            val outOfRange = 999_999
            val batch = font.glyphExtentsBatch(intArrayOf(outOfRange), sizePx = SIZE_PX)

            assertEquals(1, batch.size)
            assertNull(batch[0])
        }
    }

    @Test
    fun `empty array returns empty list`() = runBlocking {
        openFont().use { font ->
            val batch = font.glyphExtentsBatch(intArrayOf(), sizePx = SIZE_PX)
            assertEquals(emptyList(), batch)
        }
    }

    private companion object {
        const val SIZE_PX = 32f
    }
}
