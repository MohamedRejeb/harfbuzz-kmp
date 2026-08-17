package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Authored font runs on [HbFontStack.shapeParagraph]: forced fonts per
 * range, fallback inside an authored range, joining preservation across
 * authored boundaries, fast-path gating, and the disposed-font contract.
 */
class HbFontStackFontRunsTest {

    @Test
    fun `authored run forces its font for the range`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, notoB, _ ->
            val text = "مرحبا بالعالم"
            val stack = HbFontStack(noto)
            val p = stack.shapeParagraph(
                text, 48f, HbDirection.RTL,
                fontRuns = listOf(FontRun(0, 5, notoB)),
            )
            assertTrue(p.runs.isNotEmpty())
            for (run in p.runs) {
                for (g in run.glyphs) {
                    val expected = if (g.cluster < 5) notoB else noto
                    assertEquals(expected, run.font, "cluster ${g.cluster}")
                }
            }
        }
    }

    @Test
    fun `authored boundary inside a joined Arabic word preserves letter forms`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, notoB, _ ->
            val word = "مرحبا"
            val stack = HbFontStack(noto)
            val whole = stack.shapeParagraph(word, 48f, HbDirection.RTL)
            // Boundary between HAH and BEH, a dual-joining pair: the cut is
            // mid-join, so without buffer context the forms would change.
            val split = stack.shapeParagraph(
                word, 48f, HbDirection.RTL,
                fontRuns = listOf(FontRun(0, 3, notoB)),
            )
            assertEquals(
                whole.runs.flatMap { r -> r.glyphs.map { it.glyphId } }.sorted(),
                split.runs.flatMap { r -> r.glyphs.map { it.glyphId } }.sorted(),
            )
            assertEquals(whole.totalAdvance, split.totalAdvance, 0.01f)
        }
    }

    @Test
    fun `fallback applies inside an authored run`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, _, roboto ->
            val text = "مرحبا"
            val stack = HbFontStack(noto)
            // Roboto has no Arabic coverage: the authored range must fall
            // back through the stack and come out shaped by noto, notdef-free.
            val p = stack.shapeParagraph(
                text, 48f, HbDirection.RTL,
                fontRuns = listOf(FontRun(0, text.length, roboto)),
            )
            assertTrue(p.runs.isNotEmpty())
            for (run in p.runs) {
                assertEquals(noto, run.font)
                assertTrue(run.glyphs.none { it.glyphId == 0 })
            }
        }
    }

    @Test
    fun `boring fast path is bypassed when authored runs are present`() = runBlocking {
        harfBuzzInit()
        withFonts { _, _, roboto ->
            val face = HbFace.fromBytes(TestFonts.robotoRegular())
            val robotoB = face.toFont()
            try {
                val stack = HbFontStack(roboto)
                val p = stack.shapeParagraph(
                    "Hello", 48f,
                    fontRuns = listOf(FontRun(2, 4, robotoB)),
                )
                val fonts = p.runs.map { it.font }
                assertTrue(robotoB in fonts, "authored font must appear in output runs, got $fonts")
                assertEquals(
                    listOf(0, 1, 2, 3, 4),
                    p.runs.flatMap { r -> r.glyphs.map { it.cluster } }.sorted(),
                )
            } finally {
                robotoB.close()
                face.close()
            }
        }
    }

    @Test
    fun `disposed authored font fails with CancellationException`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, notoB, _ ->
            notoB.close()
            val stack = HbFontStack(noto)
            assertFailsWith<CancellationException> {
                stack.shapeParagraph(
                    "مرحبا", 48f, HbDirection.RTL,
                    fontRuns = listOf(FontRun(0, 2, notoB)),
                )
            }
        }
    }

    @Test
    fun `empty fontRuns is identical to the legacy path`() = runBlocking {
        harfBuzzInit()
        withFonts { noto, _, roboto ->
            val text = "Hello مرحبا"
            val stack = HbFontStack(roboto, listOf(noto))
            val legacy = stack.shapeParagraph(text, 48f)
            val explicit = stack.shapeParagraph(text, 48f, fontRuns = emptyList())
            assertEquals(legacy, explicit)
        }
    }

    private suspend fun withFonts(block: suspend (noto: HbFont, notoB: HbFont, roboto: HbFont) -> Unit) {
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val robotoFace = HbFace.fromBytes(TestFonts.robotoRegular())
        val noto = notoFace.toFont()
        val notoB = notoFace.toFont()
        val roboto = robotoFace.toFont()
        try {
            block(noto, notoB, roboto)
        } finally {
            runCatching { noto.close() }
            runCatching { notoB.close() }
            runCatching { roboto.close() }
            notoFace.close()
            robotoFace.close()
        }
    }
}
