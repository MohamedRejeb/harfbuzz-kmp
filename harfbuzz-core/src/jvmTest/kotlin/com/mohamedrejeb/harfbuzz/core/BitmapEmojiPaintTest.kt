package com.mohamedrejeb.harfbuzz.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the bitmap-glyph paint pipeline: native `paintImage`
 * recorder → wire buffer → [PaintBufferParser] → [RecordedPaintOp.Image].
 *
 * Uses Apple Color Emoji (sbix PNG strikes) as the on-disk bitmap emoji
 * font - same `hb_ot_color_has_png` + `PAINT_IMAGE` path Android's
 * CBDT/CBLC emoji fonts (Samsung, NotoColorEmojiLegacy) take. Skips on
 * machines without the font instead of failing.
 */
class BitmapEmojiPaintTest {

    @Test
    fun `sbix emoji font reports png color and paints an image op`() = runBlocking {
        val file = File("/System/Library/Fonts/Apple Color Emoji.ttc")
        assumeTrue("Apple Color Emoji not present on this machine", file.exists())

        val face = HbFace.from { path(file.absolutePath) }
        try {
            assertTrue(face.hasColorPng(), "sbix font must report hasColorPng")

            val font = face.toFont()
            val gid = font.glyphIdForCodepoint(0x1F600) // 😀
            assertTrue(gid != 0, "font must cover U+1F600")

            val sink = RecordingPaintSink()
            font.paintGlyph(glyphId = gid, sizePx = 128f, sink = sink)

            val imageOp = sink.ops.filterIsInstance<RecordedPaintOp.Image>().firstOrNull()
            assertNotNull(imageOp, "paint walk must emit an image op for a bitmap glyph")

            val png = imageOp.image.data
            assertTrue(
                png.size > 8 &&
                    png[1] == 'P'.code.toByte() &&
                    png[2] == 'N'.code.toByte() &&
                    png[3] == 'G'.code.toByte(),
                "image payload must be a PNG stream",
            )
            val extents = imageOp.image.extents
            assertNotNull(extents, "bitmap glyph must carry placement extents")
            assertTrue(extents.width > 0f, "extents width must be positive")
            assertTrue(extents.height < 0f, "hb extents height is negative (Y-up)")

            font.close()
        } finally {
            face.close()
        }
    }
}
