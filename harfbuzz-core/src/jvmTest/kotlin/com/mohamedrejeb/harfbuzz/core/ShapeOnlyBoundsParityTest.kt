package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ShapeOnlyBoundsParityTest {

    private suspend fun openRoboto(size: Float = 32f): HbFont =
        HbFace.fromBytes(TestFonts.robotoRegular()).toFont(size)

    @Test
    fun `shapeOnlyBounds matches shapeParagraph bounds for Latin`() = runBlocking {
        openRoboto().use { font ->
            val stack = HbFontStack(font)
            val text = "Hello, world"

            val result = stack.shapeOnlyBounds(text)
            val paragraph = stack.shapeParagraph(text)

            assertEquals(paragraph.totalAdvance, result.bounds.advance)
            assertEquals(paragraph.ink, result.bounds.ink)
            assertEquals(paragraph.logical, result.bounds.logical)
        }
    }

    @Test
    fun `metrics come from primary font hExtents`() = runBlocking {
        openRoboto().use { font ->
            val stack = HbFontStack(font)
            val ext = font.hExtents

            val bounds = stack.shapeOnlyBounds("hi").bounds

            if (ext != null) {
                assertEquals(ext.ascender, bounds.ascent)
                assertEquals(-ext.descender, bounds.descent)
                assertEquals(ext.lineGap, bounds.lineGap)
                assertEquals(ext.ascender, bounds.baseline)
            } else {
                // Fallback path used when font has no usable h-extents.
                assertEquals(font.pointSize * 0.8f, bounds.ascent)
                assertEquals(font.pointSize * 0.2f, bounds.descent)
                assertEquals(0f, bounds.lineGap)
            }
        }
    }

    @Test
    fun `empty text returns EMPTY bounds`() = runBlocking {
        openRoboto().use { font ->
            val stack = HbFontStack(font)
            val result = stack.shapeOnlyBounds("")
            assertEquals(0f, result.bounds.advance)
            assertEquals(HbRect.EMPTY, result.bounds.ink)
        }
    }
}
