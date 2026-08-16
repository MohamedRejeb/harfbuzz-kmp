package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Authored font runs at the [buildMeasuredText] level: cache-key
 * separation, per-range font attribution in the measured output, and
 * original-text index semantics on mixed-font shapes.
 */
class FontRunsMeasuredTextTest {

    @Test
    fun `cache separates shapes with and without authored runs`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNotoAndSaudi { noto, saudi ->
            val stack = HbFontStack(noto)
            val text = "مرحبا بالعالم"
            val plain = buildMeasuredText(text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO)
            val runs = listOf(FontRun(0, 5, saudi))
            val authored = buildMeasuredText(text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO, runs)
            assertNotSame(plain, authored)
            // Identical inputs hit their own entries.
            assertSame(plain, buildMeasuredText(text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO))
            assertSame(
                authored,
                buildMeasuredText(text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO, runs),
            )
        }
    }

    @Test
    fun `authored range is shaped under the authored font`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNotoAndSaudi { noto, saudi ->
            val stack = HbFontStack(noto)
            val text = "مرحبا بالعالم"
            val plain = buildMeasuredText(text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO)
            val authored = buildMeasuredText(
                text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO,
                listOf(FontRun(0, 5, saudi)),
            )
            for (run in authored.paragraph.runs) {
                for (g in run.glyphs) {
                    val expected = if (g.cluster < 5) saudi else noto
                    assertEquals(expected, run.font, "cluster ${g.cluster}")
                }
            }
            // Different faces have different advances for the same word.
            assertNotEquals(plain.advance, authored.advance)
        }
    }

    @Test
    fun `index accessors stay in original coordinates with authored runs`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNotoAndSaudi { noto, saudi ->
            val robotoFace = HbFace.fromBytes(TestFonts.robotoRegular())
            val roboto = robotoFace.toFont()
            try {
                val text = "Hello مرحبا"
                val arabicStart = text.indexOf('م')
                val stack = HbFontStack(roboto, listOf(noto))
                val measured = buildMeasuredText(
                    text, stack, 48f, emptyList(), HbDirection.AUTO, HbLanguage.AUTO,
                    listOf(FontRun(arabicStart, text.length, saudi)),
                )
                assertEquals(text.length, measured.textLength)

                var advanceSum = 0f
                for (i in 0 until measured.textLength) {
                    advanceSum += measured.advanceWidthOf(i)
                    val cluster = measured.clusterAt(i)
                    if (cluster != null) {
                        assertTrue(cluster in 0 until measured.textLength, "clusterAt($i) = $cluster")
                    }
                }
                assertTrue(
                    abs(advanceSum - measured.advance) < 0.1f,
                    "advance sum $advanceSum vs total ${measured.advance}",
                )
                // LTR base: the trailing caret edge is the full advance.
                assertEquals(measured.advance, measured.horizontalPositionOf(measured.textLength))
                // Every in-range index answers without throwing.
                for (i in 0..measured.textLength) {
                    measured.horizontalPositionOf(i)
                }
            } finally {
                roboto.close()
                robotoFace.close()
            }
        }
    }

    @Test
    fun `max metrics cover every contributing font while base metrics stay primary`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNotoAndSaudi { noto, saudi ->
            val text = "مرحبا بالعالم"
            val stack = HbFontStack(noto)
            val notoExt = requireNotNull(noto.hExtents(48f))
            val saudiExt = requireNotNull(saudi.hExtents(48f))

            val single = buildMeasuredText(text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO)
            assertEquals(single.ascent, single.maxAscent)
            assertEquals(single.descent, single.maxDescent)

            val mixed = buildMeasuredText(
                text, stack, 48f, emptyList(), HbDirection.RTL, HbLanguage.AUTO,
                listOf(FontRun(0, 5, saudi)),
            )
            assertEquals(notoExt.ascender, mixed.ascent, 0.01f)
            assertEquals(maxOf(notoExt.ascender, saudiExt.ascender), mixed.maxAscent, 0.01f)
            assertEquals(maxOf(-notoExt.descender, -saudiExt.descender), mixed.maxDescent, 0.01f)
            assertTrue(mixed.maxAscent >= mixed.ascent)
            assertTrue(mixed.maxDescent >= mixed.descent)
        }
    }

    @Test
    fun `empty text reports base font metrics`() = runBlocking {
        harfBuzzInit()
        clearMeasuredTextCacheForTest()
        withNotoAndSaudi { noto, _ ->
            val stack = HbFontStack(noto)
            val ext = requireNotNull(noto.hExtents(48f))
            val measured = buildMeasuredText("", stack, 48f, emptyList(), HbDirection.AUTO, HbLanguage.AUTO)
            assertTrue(measured.isEmpty)
            assertEquals(0f, measured.advance)
            assertEquals(0, measured.textLength)
            assertEquals(ext.ascender, measured.ascent, 0.01f)
            assertEquals(-ext.descender, measured.descent, 0.01f)
            assertEquals(ext.lineGap, measured.lineGap, 0.01f)
            assertEquals(measured.ascent, measured.maxAscent)
            assertEquals(measured.descent, measured.maxDescent)
        }
    }

    private suspend fun withNotoAndSaudi(block: suspend (noto: HbFont, saudi: HbFont) -> Unit) {
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val saudiFace = HbFace.fromBytes(TestFonts.saudiRegular())
        val noto = notoFace.toFont()
        val saudi = saudiFace.toFont()
        try {
            block(noto, saudi)
        } finally {
            noto.close()
            saudi.close()
            notoFace.close()
            saudiFace.close()
        }
    }
}
