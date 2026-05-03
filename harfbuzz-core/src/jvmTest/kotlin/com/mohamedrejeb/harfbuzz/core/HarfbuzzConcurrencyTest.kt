package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fires four parallel `shapeParagraph` calls against the same [HbFont] and
 * asserts every result is byte-identical. Validates that the JVM
 * single-thread `harfbuzz-bg` dispatcher serialises calls correctly - a
 * regression here (a wrong dispatcher, a leaky lock, or shared mutable
 * buffer state) would surface as different glyph IDs or mismatched
 * advances between calls.
 *
 * Intentionally stays on a small Latin string: the goal is to stress
 * concurrency safety, not shaping correctness. Other tests cover Arabic
 * etc. on the correctness axis.
 */
class HarfbuzzConcurrencyTest {

    @Test
    fun `four parallel shapeParagraph calls produce identical output`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val results = (1..4)
                .map { async { font.shapeParagraph("Hello, world", sizePx = 24f) } }
                .awaitAll()
            val first = results.first()
            for (other in results.drop(1)) {
                assertEquals(
                    first.totalAdvance,
                    other.totalAdvance,
                    "totalAdvance differs across parallel shapeParagraph calls",
                )
                assertEquals(
                    first.runs.flatMap { run -> run.glyphs.map { it.glyphId } },
                    other.runs.flatMap { run -> run.glyphs.map { it.glyphId } },
                    "glyph IDs differ across parallel shapeParagraph calls",
                )
            }
        } finally {
            font.close()
            face.close()
        }
    }
}
