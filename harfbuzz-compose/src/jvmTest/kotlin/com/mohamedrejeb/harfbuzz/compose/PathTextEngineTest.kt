package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.shapeOnlyBounds
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun horizontalLine(length: Float): Path = Path().apply {
    moveTo(0f, 0f)
    lineTo(length, 0f)
}

private fun verticalLine(length: Float): Path = Path().apply {
    moveTo(0f, 0f)
    lineTo(0f, length)
}

class PathTextEngineTest {

    @Test
    fun `placements on horizontal line have zero rotation`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val result = stack.shapeOnlyBounds("abc", sizePx = 32f)
            val path = horizontalLine(1000f)

            val placements = collectPlacements(
                paragraph = result.paragraph,
                primaryFont = font,
                path = path,
                startOffset = 0f,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Clip,
                autoFlip = false,
            )

            assertTrue(placements.isNotEmpty(), "placements should be produced")
            for (p in placements) {
                assertTrue(abs(p.angleRadians) < 0.0001f, "angle should be 0, was ${p.angleRadians}")
                assertTrue(abs(p.position.y) < 0.0001f, "y should be 0, was ${p.position.y}")
            }
        } finally {
            font.close()
            face.close()
        }
    }

    @Test
    fun `placements on vertical line are rotated 90 degrees`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val result = stack.shapeOnlyBounds("abc", sizePx = 32f)
            val path = verticalLine(1000f)

            val placements = collectPlacements(
                paragraph = result.paragraph,
                primaryFont = font,
                path = path,
                startOffset = 0f,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Clip,
                autoFlip = false,
            )

            val expected = (PI / 2.0).toFloat()
            for (p in placements) {
                assertTrue(
                    abs(p.angleRadians - expected) < 0.0001f,
                    "angle should be ~PI/2, was ${p.angleRadians}",
                )
            }
        } finally {
            font.close()
            face.close()
        }
    }

    @Test
    fun `Clip overflow drops glyphs whose center exceeds path length`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val text = "this text is much longer than the path is going to be"
            val result = stack.shapeOnlyBounds(text, sizePx = 32f)
            val path = horizontalLine(50f) // very short

            val placements = collectPlacements(
                paragraph = result.paragraph,
                primaryFont = font,
                path = path,
                startOffset = 0f,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Clip,
                autoFlip = false,
            )

            val fullGlyphCount = result.paragraph.runs.sumOf { it.glyphCount }
            assertTrue(
                placements.size < fullGlyphCount,
                "Clip should drop overflow glyphs (got ${placements.size} of $fullGlyphCount)",
            )
        } finally {
            font.close()
            face.close()
        }
    }

    @Test
    fun `Compress mode places every glyph within path bounds`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val text = "wider than path"
            val result = stack.shapeOnlyBounds(text, sizePx = 32f)
            val pathLen = 80f
            val path = horizontalLine(pathLen)

            val placements = collectPlacements(
                paragraph = result.paragraph,
                primaryFont = font,
                path = path,
                startOffset = 0f,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Compress,
                autoFlip = false,
            )

            val fullGlyphCount = result.paragraph.runs.sumOf { it.glyphCount }
            assertEquals(
                fullGlyphCount,
                placements.size,
                "Compress should place every glyph (no clipping)",
            )
            for (p in placements) {
                assertTrue(
                    p.position.x in 0f..pathLen + 0.5f,
                    "x ${p.position.x} should be within [0, $pathLen]",
                )
            }
        } finally {
            font.close()
            face.close()
        }
    }
}

/**
 * Test helper that drives the engine's walk and collects every emitted
 * placement. Wraps the public engine entry point that Task 6 introduces.
 */
private fun collectPlacements(
    paragraph: ShapedParagraph,
    primaryFont: HbFont,
    path: Path,
    startOffset: Float,
    alignment: TextOnPathAlignment,
    overflow: TextOnPathOverflow,
    autoFlip: Boolean,
): List<PathGlyphPlacement> {
    val acc = mutableListOf<PathGlyphPlacement>()
    walkPathText(
        paragraph = paragraph,
        primaryFont = primaryFont,
        path = path,
        startOffset = startOffset,
        side = TextOnPathSide.Above,
        alignment = alignment,
        overflow = overflow,
        autoFlip = autoFlip,
    ) { placement -> acc += placement }
    return acc
}
