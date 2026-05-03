package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds the same [MeasuredText] twice from identical inputs and asserts
 * the per-glyph caches contain the same glyph IDs and the same advance.
 * Catches accidental input-dependence (clock-based seeds, hash-set
 * iteration ordering) in the snapshot pipeline.
 *
 * Lives in `jvmTest` rather than `commonTest` because loading font bytes
 * in commonTest would require pulling Compose Resources into the test
 * source set just for fixture access - JVM `getResourceAsStream` is
 * trivially available and gives the same coverage. The snapshot
 * pipeline being asserted is in commonMain, so a JVM-side determinism
 * guarantee transfers to other targets that route through the same
 * code path.
 */
class MeasuredTextSnapshotTest {

    @Test
    fun `snapshot is deterministic across calls`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        val stack = HbFontStack(font)

        // Clear the process-wide MeasuredTextCache between builds so this
        // test actually exercises the uncached pipeline twice. Without
        // this, both calls would return the cached entry and the asserts
        // would degenerate to identity comparisons.
        clearMeasuredTextCacheForTest()
        val first = buildMeasuredText(
            text = "Hello, world",
            fontStack = stack,
            sizePx = 24f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )
        clearMeasuredTextCacheForTest()
        val second = buildMeasuredText(
            text = "Hello, world",
            fontStack = stack,
            sizePx = 24f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )

        assertEquals(first.advance, second.advance, "advance differs across calls")
        assertEquals(
            first.paragraph.runs.size,
            second.paragraph.runs.size,
            "run count differs",
        )
        assertTrue(
            first.paragraph.runs.zip(second.paragraph.runs).all { (a, b) ->
                a.glyphs.map { it.glyphId } == b.glyphs.map { it.glyphId }
            },
            "glyph ids differ across calls",
        )

        font.close()
        face.close()
    }
}
