package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.shapeOnlyBounds
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct coverage for `computePathTextBounds` (the suspend helper that
 * powers `rememberPathTextBounds`). The Composable wrapper itself is
 * untestable in this project - we don't ship `compose.ui.test:ui-test-junit4`
 * - so the parity-with-shapeOnlyBounds and Ready/Loading/Failed variants
 * are checked via the helper and the sealed interface.
 */
class RememberPathTextBoundsTest {

    @Test
    fun `Ready holds bounds`() {
        val bounds = PathTextBounds(ink = Rect(0f, 0f, 10f, 10f), advance = 100f, pathLength = 200f)
        val ready: PathTextBoundsLoad = PathTextBoundsLoad.Ready(bounds)
        assertTrue(ready is PathTextBoundsLoad.Ready)
        assertEquals(bounds, ready.bounds)
    }

    @Test
    fun `Failed carries cause`() {
        val cause = IllegalStateException("simulated")
        val failed: PathTextBoundsLoad = PathTextBoundsLoad.Failed(cause)
        assertTrue(failed is PathTextBoundsLoad.Failed)
        assertEquals(cause, failed.cause)
    }

    @Test
    fun `Loading is a singleton`() {
        val a: PathTextBoundsLoad = PathTextBoundsLoad.Loading
        val b: PathTextBoundsLoad = PathTextBoundsLoad.Loading
        assertTrue(a === b)
    }

    @Test
    fun `bounds on empty paragraph yields Rect Zero`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val shaped = stack.shapeOnlyBounds("", sizePx = 32f)
            val path = Path().apply { moveTo(0f, 0f); lineTo(1000f, 0f) }
            val bounds = computePathTextBounds(
                paragraph = shaped.paragraph,
                primaryFont = font,
                sizePx = 32f,
                path = path,
                startOffset = 0f,
                side = TextOnPathSide.Above,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Clip,
                autoFlip = false,
            )
            assertEquals(Rect.Zero, bounds.ink, "empty text should yield Rect.Zero ink")
            assertEquals(0f, bounds.advance, "empty text should have zero advance")
        } finally {
            font.close(); face.close()
        }
    }

    @Test
    fun `bounds on horizontal line have non-empty ink and matching advance`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val shaped = stack.shapeOnlyBounds("Hello", sizePx = 32f)
            val path = Path().apply { moveTo(0f, 0f); lineTo(1000f, 0f) }
            val bounds = computePathTextBounds(
                paragraph = shaped.paragraph,
                primaryFont = font,
                sizePx = 32f,
                path = path,
                startOffset = 0f,
                side = TextOnPathSide.Above,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Clip,
                autoFlip = false,
            )
            assertTrue(bounds.advance > 0f, "advance should be positive: ${bounds.advance}")
            assertTrue(bounds.ink.width > 0f, "ink width should be positive: ${bounds.ink.width}")
            assertTrue(bounds.pathLength in 999f..1001f,
                "pathLength should be ~1000: ${bounds.pathLength}")
        } finally {
            font.close(); face.close()
        }
    }

    @Test
    fun `bounds on horizontal line approximate shapeOnlyBounds ink width`() = runBlocking {
        // Parity invariant from spec §3.2: text laid along a horizontal
        // line should have ~the same ink.width as the linear ink box,
        // since the per-glyph rotation is identity for tan = (1, 0).
        // Slack accounts for HB-extent vs path-bound sub-pixel differences.
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val text = "Hello"
            val shaped = stack.shapeOnlyBounds(text, sizePx = 32f)
            val path = Path().apply { moveTo(0f, 0f); lineTo(1000f, 0f) }
            val pathBounds = computePathTextBounds(
                paragraph = shaped.paragraph,
                primaryFont = font,
                sizePx = 32f,
                path = path,
                startOffset = 0f,
                side = TextOnPathSide.Above,
                alignment = TextOnPathAlignment.Start,
                overflow = TextOnPathOverflow.Clip,
                autoFlip = false,
            )

            val linearWidth = shaped.bounds.ink.right - shaped.bounds.ink.left
            val pathWidth = pathBounds.ink.width
            val diff = abs(pathWidth - linearWidth)
            assertTrue(diff < 5f,
                "path ink width $pathWidth should match linear $linearWidth within 5px (diff=$diff)")
        } finally {
            font.close(); face.close()
        }
    }
}
