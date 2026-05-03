package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.TestFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Layout-correctness tests against real fonts. Uses the bundled
 * Roboto Regular for Latin coverage. Arabic coverage opportunistically
 * loads a system font - the tests skip cleanly when none is found
 * (matches the pattern in `ArabicCorrectnessTest`).
 */
class ParagraphLayoutJvmTest {

    @Test
    fun empty_text_returns_empty_paragraph() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph("", sizePx = SIZE_PX, maxWidth = 200f)
            assertTrue(laid.isEmpty)
            assertEquals(0, laid.lineCount)
            assertEquals(0f, laid.height)
        }
    }

    @Test
    fun zero_max_width_returns_empty_paragraph() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph("Hello", sizePx = SIZE_PX, maxWidth = 0f)
            assertTrue(laid.isEmpty)
        }
    }

    @Test
    fun single_word_fits_in_one_line() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph("Hello", sizePx = SIZE_PX, maxWidth = 1000f)
            assertEquals(1, laid.lineCount)
            assertEquals(0 until 5, laid.lines[0].charRange)
            assertTrue(laid.width > 0f)
            assertTrue(laid.height > 0f)
        }
    }

    @Test
    fun long_paragraph_wraps_to_multiple_lines() = runBlocking {
        withRoboto { stack ->
            val text = "The quick brown fox jumps over the lazy dog"
            val laid = stack.layoutParagraph(text, sizePx = SIZE_PX, maxWidth = 200f)
            assertTrue(laid.lineCount > 1, "expected wrap, got ${laid.lineCount} lines")
            // Every line's advance must fit the budget. At 32pt Roboto
            // the longest single word ("jumps") is well under 200px so
            // no single-token forced overflow is expected.
            for (line in laid.lines) {
                assertTrue(line.advance <= 200f + 0.5f, "line overflowed: $line")
            }
            // Total of charRange covers the full text.
            val first = laid.lines.first().charRange.first
            val last = laid.lines.last().charRange.last
            assertEquals(0, first)
            assertEquals(text.length - 1, last)
        }
    }

    @Test
    fun hard_break_forces_new_line_even_when_room_remains() = runBlocking {
        withRoboto { stack ->
            val text = "a\nb"
            val laid = stack.layoutParagraph(text, sizePx = SIZE_PX, maxWidth = 1000f)
            assertEquals(2, laid.lineCount, "expected hard break to split, got ${laid.lineCount}")
        }
    }

    @Test
    fun consecutive_newlines_yield_empty_middle_lines() = runBlocking {
        withRoboto { stack ->
            val text = "a\n\nb"
            val laid = stack.layoutParagraph(text, sizePx = SIZE_PX, maxWidth = 1000f)
            assertEquals(3, laid.lineCount, "expected 3 lines for \"a\\n\\nb\", got ${laid.lineCount}")
        }
    }

    @Test
    fun line_top_advances_by_lineHeight_plus_lineSpacing() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph(
                "one two three four five six seven eight nine ten",
                sizePx = SIZE_PX,
                maxWidth = 80f,
                lineSpacing = 12f,
            )
            require(laid.lineCount >= 2)
            val expectedDelta = laid.lines[0].lineHeight + 12f
            val actualDelta = laid.lines[1].top - laid.lines[0].top
            assertTrue(
                kotlin.math.abs(actualDelta - expectedDelta) < 0.5f,
                "expected line spacing $expectedDelta, got $actualDelta",
            )
        }
    }

    @Test
    fun firstBaseline_equals_first_line_ascent() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph("Hello", sizePx = SIZE_PX, maxWidth = 1000f)
            assertEquals(laid.lines[0].ascent, laid.firstBaseline)
        }
    }

    @Test
    fun center_alignment_offsets_each_line_to_paragraph_center() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph(
                "Hello world",
                sizePx = SIZE_PX,
                maxWidth = 400f,
                alignment = ParagraphAlignment.Center,
            )
            val line = laid.lines.single()
            val expected = (400f - line.advance) / 2f
            assertTrue(
                kotlin.math.abs(line.xOffset - expected) < 0.5f,
                "expected center xOffset $expected, got ${line.xOffset}",
            )
        }
    }

    @Test
    fun right_alignment_pushes_line_to_right_edge() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph(
                "Hello",
                sizePx = SIZE_PX,
                maxWidth = 400f,
                alignment = ParagraphAlignment.Right,
            )
            val line = laid.lines.single()
            // Ink-based alignment: the line's rightmost VISIBLE pixel
            // (line.ink.right relative to the line origin) should land
            // at maxWidth.
            val expected = 400f - line.ink.right
            assertTrue(
                kotlin.math.abs(line.xOffset - expected) < 0.5f,
                "expected right xOffset $expected, got ${line.xOffset}",
            )
        }
    }

    @Test
    fun start_alignment_in_ltr_paragraph_pins_ink_to_left_edge() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph(
                "Hello",
                sizePx = SIZE_PX,
                maxWidth = 400f,
                alignment = ParagraphAlignment.Start,
            )
            val line = laid.lines.single()
            // Ink-based alignment: glyph[0] is offset by -ink.left so the
            // leftmost visible pixel lands at x=0. The advance box left
            // edge sits at -ink.left (typically a small negative).
            val expected = -line.ink.left
            assertTrue(
                kotlin.math.abs(line.xOffset - expected) < 0.5f,
                "expected start xOffset $expected, got ${line.xOffset}",
            )
        }
    }

    @Test
    fun height_equals_top_of_last_line_plus_line_height() = runBlocking {
        withRoboto { stack ->
            val laid = stack.layoutParagraph(
                "one two three four five six seven eight",
                sizePx = SIZE_PX,
                maxWidth = 80f,
                lineSpacing = 4f,
            )
            require(laid.lineCount >= 2)
            val last = laid.lines.last()
            // Last line's top + its lineHeight (no trailing spacing).
            val expected = last.top + last.lineHeight
            assertTrue(
                kotlin.math.abs(laid.height - expected) < 0.5f,
                "expected height $expected, got ${laid.height}",
            )
        }
    }

    @Test
    fun width_is_max_line_advance() = runBlocking {
        withRoboto { stack ->
            val text = "one two three four five six seven eight"
            val laid = stack.layoutParagraph(text, sizePx = SIZE_PX, maxWidth = 100f)
            require(laid.lineCount >= 2)
            val maxAdvance = laid.lines.maxOf { it.advance }
            assertEquals(maxAdvance, laid.width)
        }
    }

    @Test
    fun arabic_rtl_start_alignment_pushes_to_right_edge() = runBlocking {
        val arabicBytes = loadSystemArabicFont() ?: return@runBlocking
        HbFace.from { bytes(arabicBytes) }.use { face ->
            face.toFont().use { font ->
                val stack = HbFontStack(font)
                val laid = stack.layoutParagraph(
                    "نص عربي",
                    sizePx = SIZE_PX,
                    maxWidth = 400f,
                    alignment = ParagraphAlignment.Start,
                )
                val line = laid.lines.single()
                // Ink-based RTL Start: the line's rightmost VISIBLE pixel
                // (line.ink.right) snaps to maxWidth, so a negative-RSB
                // glyph (common in Arabic initial forms) doesn't visibly
                // overflow the box.
                val expected = 400f - line.ink.right
                assertTrue(
                    kotlin.math.abs(line.xOffset - expected) < 0.5f,
                    "expected RTL Start xOffset $expected, got ${line.xOffset}",
                )
                assertEquals(HbDirection.RTL, laid.baseDirection)
            }
        }
    }

    @Test
    fun arabic_wraps_at_word_boundaries() = runBlocking {
        val arabicBytes = loadSystemArabicFont() ?: return@runBlocking
        HbFace.from { bytes(arabicBytes) }.use { face ->
            face.toFont().use { font ->
                val stack = HbFontStack(font)
                val text = "نص عربي تجريبي طويل لاختبار التفاف الأسطر"
                val laid = stack.layoutParagraph(text, sizePx = 24f, maxWidth = 80f)
                assertTrue(laid.lineCount >= 2, "expected Arabic wrap, got ${laid.lineCount} lines")
                // Total char coverage spans the whole text.
                assertEquals(0, laid.lines.first().charRange.first)
                assertEquals(text.length - 1, laid.lines.last().charRange.last)
            }
        }
    }

    private fun loadSystemArabicFont(): ByteArray? = listOf(
        "/System/Library/Fonts/GeezaPro.ttc",
        "/System/Library/Fonts/Supplemental/Damascus.ttc",
        "/usr/share/fonts/opentype/noto/NotoNaskhArabic-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
    ).firstOrNull { File(it).exists() }?.let { File(it).readBytes() }

    private suspend inline fun withRoboto(block: (HbFontStack) -> Unit) {
        val bytes = TestFonts.robotoRegular()
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont().use { font ->
                block(HbFontStack(font))
            }
        }
    }

    private companion object {
        const val SIZE_PX = 32f
    }
}
