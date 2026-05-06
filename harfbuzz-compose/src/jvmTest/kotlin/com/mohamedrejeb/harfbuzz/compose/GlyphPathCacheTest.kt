package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pin the cross-`MeasuredText` glyph-path reuse contract: two builds at
 * the same `(font, sizePx, glyphId)` must hand back the same parsed
 * Compose [androidx.compose.ui.graphics.Path] instance, so the
 * SVG-string -> `Path` decode happens once.
 *
 * Both builds clear [MeasuredTextCache] between calls so the regression
 * surface is the per-glyph path cache, not the higher-level shape cache.
 */
class GlyphPathCacheTest {

    @Test
    fun `parsed glyph paths reused across MeasuredText instances at same font and size`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        val stack = HbFontStack(font)

        clearMeasuredTextCacheForTest()
        clearGlyphPathCacheForTest()

        val first = buildMeasuredText(
            text = "Hello",
            fontStack = stack,
            sizePx = 24f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )
        clearMeasuredTextCacheForTest()
        val second = buildMeasuredText(
            text = "Help",
            fontStack = stack,
            sizePx = 24f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )

        // 'H', 'e', 'l' are shared between the two strings; their parsed
        // paths must be the very same Path instance after the second
        // build hits the global cache.
        val firstFlipped = first.flippedPathsByFont.values.first()
        val secondFlipped = second.flippedPathsByFont.values.first()
        val firstGids = first.paragraph.runs.flatMap { run -> run.glyphs.map { it.glyphId } }
        val secondGids = second.paragraph.runs.flatMap { run -> run.glyphs.map { it.glyphId } }
        val sharedGids = firstGids.toSet().intersect(secondGids.toSet())

        assertTrue(sharedGids.isNotEmpty(), "expected at least one shared glyph between Hello and Help")
        for (gid in sharedGids) {
            val a = firstFlipped[gid]
            val b = secondFlipped[gid]
            requireNotNull(a) { "first build missing parsed path for shared glyph $gid" }
            requireNotNull(b) { "second build missing parsed path for shared glyph $gid" }
            assertSame(a, b, "shared glyph $gid: parsed paths must be identity-equal")
        }

        font.close()
        face.close()
    }

    @Test
    fun `different sizePx buckets get distinct parsed paths`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        val stack = HbFontStack(font)

        clearMeasuredTextCacheForTest()
        clearGlyphPathCacheForTest()

        val small = buildMeasuredText(
            text = "Hi",
            fontStack = stack,
            sizePx = 16f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )
        clearMeasuredTextCacheForTest()
        val large = buildMeasuredText(
            text = "Hi",
            fontStack = stack,
            sizePx = 24f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )

        // Same glyph at different sizePx must NOT share a Path - the
        // SVG strings come from snapshotGlyphs(sizePx) and are scaled to
        // pixel space. Sharing here would render the wrong size.
        val smallFlipped = small.flippedPathsByFont.values.first()
        val largeFlipped = large.flippedPathsByFont.values.first()
        val gid = small.paragraph.runs.first().glyphs.first().glyphId
        val a = smallFlipped[gid]
        val b = largeFlipped[gid]
        requireNotNull(a)
        requireNotNull(b)
        assertTrue(a !== b, "different sizePx must produce distinct parsed paths for the same glyph")

        font.close()
        face.close()
    }
}
