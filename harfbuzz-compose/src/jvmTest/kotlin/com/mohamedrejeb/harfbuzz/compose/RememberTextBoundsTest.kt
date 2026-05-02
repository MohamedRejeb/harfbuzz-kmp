package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Rect
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.MeasuredBounds
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.shapeOnlyBounds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct coverage for the new bounds-only Compose surface. The
 * Composable functions themselves can't be exercised via `setContent`
 * because this project doesn't ship a `compose.ui.test:ui-test-junit4`
 * dependency - `MeasuredTextSnapshotTest` follows the same JVM-only
 * `runBlocking` pattern.
 *
 * What this test covers:
 *  - `MeasuredBoundsLoad` sealed interface variants compile and
 *    pattern-match correctly.
 *  - `composeInk` / `composeLogical` extensions correctly project an
 *    `HbRect` to a Compose `Rect` (load-bearing for end-user
 *    ergonomics).
 *  - `shapeOnlyBounds` (the core function the Composables wrap) yields
 *    a non-empty Ready state when given non-trivial input - sanity
 *    that the wiring still produces usable bounds at this layer.
 */
class RememberTextBoundsTest {

    @Test
    fun `composeInk and composeLogical project HbRect to Compose Rect`() {
        val bounds = MeasuredBounds(
            ink = HbRect(left = 1f, top = 2f, right = 3f, bottom = 4f),
            logical = HbRect(left = 5f, top = 6f, right = 7f, bottom = 8f),
            advance = 10f,
            ascent = 12f,
            descent = 4f,
            lineGap = 2f,
            baseline = 12f,
        )

        assertEquals(Rect(1f, 2f, 3f, 4f), bounds.composeInk)
        assertEquals(Rect(5f, 6f, 7f, 8f), bounds.composeLogical)
    }

    @Test
    fun `Ready holds bounds and paragraph fields`() {
        val bounds = MeasuredBounds.EMPTY
        val paragraph = ShapedParagraph.EMPTY
        val ready: MeasuredBoundsLoad = MeasuredBoundsLoad.Ready(bounds, paragraph)

        assertTrue(ready is MeasuredBoundsLoad.Ready)
        assertEquals(bounds, ready.bounds)
        assertEquals(paragraph, ready.paragraph)
    }

    @Test
    fun `Failed carries cause`() {
        val cause = IllegalStateException("simulated failure")
        val failed: MeasuredBoundsLoad = MeasuredBoundsLoad.Failed(cause)

        assertTrue(failed is MeasuredBoundsLoad.Failed)
        assertEquals(cause, failed.cause)
    }

    @Test
    fun `Loading is a singleton`() {
        val a: MeasuredBoundsLoad = MeasuredBoundsLoad.Loading
        val b: MeasuredBoundsLoad = MeasuredBoundsLoad.Loading
        assertTrue(a === b)
    }

    @Test
    fun `shapeOnlyBounds wiring produces non-empty bounds for non-trivial text`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont(24f)
        try {
            val stack = HbFontStack(font)
            val result = stack.shapeOnlyBounds("Hello, world")
            assertTrue(result.bounds.advance > 0f, "expected positive advance, got ${result.bounds.advance}")
            assertEquals(result.paragraph.totalAdvance, result.bounds.advance)
        } finally {
            font.close()
            face.close()
        }
    }
}
