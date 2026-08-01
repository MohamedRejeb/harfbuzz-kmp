package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.TestFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Behaviour tests for the [WordBreak] policy on
 * [HbFontStack.layoutParagraph]. Uses bundled Roboto for Latin and an
 * opportunistically-loaded Arabic system font (skips cleanly when not
 * present, matching the convention used elsewhere in this module).
 */
class ParagraphLayoutWordBreakJvmTest {

    @Test
    fun phrase_keeps_long_word_on_one_overflowing_line() = runBlocking {
        withRoboto { stack ->
            // "Supercalifragilistic" is far wider than 40px at 32pt
            // Roboto. Phrase must keep it on a single line and report
            // an advance > 40 (overflow is the caller's problem).
            val text = "Supercalifragilistic"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 40f,
                wordBreak = WordBreak.Phrase,
            )
            assertEquals(1, laid.lineCount, "Phrase should not split a single-token overflow")
            assertTrue(
                laid.lines[0].advance > 40f,
                "expected overflow line, got advance=${laid.lines[0].advance}",
            )
            assertEquals(0 until text.length, laid.lines[0].charRange)
        }
    }

    @Test
    fun breakWord_splits_long_word_into_multiple_lines() = runBlocking {
        withRoboto { stack ->
            val text = "Supercalifragilistic"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 80f,
                wordBreak = WordBreak.BreakWord,
            )
            assertTrue(laid.lineCount > 1, "expected mid-word split, got ${laid.lineCount} lines")
            // Every line's advance must fit the budget (give a 0.5px
            // tolerance for joining-form drift).
            for (line in laid.lines) {
                assertTrue(
                    line.advance <= 80f + 0.5f,
                    "line overflow: advance=${line.advance}, line=$line",
                )
            }
            // Coverage: lines partition the full text contiguously.
            assertEquals(0, laid.lines.first().charRange.first)
            assertEquals(text.length - 1, laid.lines.last().charRange.last)
        }
    }

    @Test
    fun breakWord_no_split_when_word_fits_at_boundary() = runBlocking {
        withRoboto { stack ->
            // Short words fit individually on each line at this width
            // -> BreakWord must behave identically to Phrase: word
            // boundaries only, no mid-word cuts.
            val text = "one two three four"
            val phrase = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 200f,
                wordBreak = WordBreak.Phrase,
            )
            val breakWord = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 200f,
                wordBreak = WordBreak.BreakWord,
            )
            assertEquals(phrase.lineCount, breakWord.lineCount)
            for (i in 0 until phrase.lineCount) {
                assertEquals(
                    phrase.lines[i].charRange,
                    breakWord.lines[i].charRange,
                    "line $i charRange differs between Phrase and BreakWord",
                )
            }
        }
    }

    @Test
    fun breakWord_preserves_word_boundary_split_for_mixed_text() = runBlocking {
        withRoboto { stack ->
            // Mixed: short fitting words, then a single long token.
            // First N lines split at spaces; the long token splits
            // mid-word.
            val text = "hi Supercalifragilistic"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 80f,
                wordBreak = WordBreak.BreakWord,
            )
            assertTrue(laid.lineCount >= 2, "expected at least two lines")
            for (line in laid.lines) {
                assertTrue(
                    line.advance <= 80f + 0.5f,
                    "line overflow: advance=${line.advance}",
                )
            }
        }
    }

    @Test
    fun anyChar_breaks_at_every_grapheme_when_budget_is_tiny() = runBlocking {
        withRoboto { stack ->
            // 24px at 32pt Roboto comfortably fits one Latin glyph but
            // not two. AnyChar should produce one line per char.
            val text = "abcd"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 24f,
                wordBreak = WordBreak.AnyChar,
            )
            assertEquals(text.length, laid.lineCount, "expected one line per char")
            for (line in laid.lines) {
                assertTrue(line.advance <= 24f + 0.5f, "line overflow at $line")
            }
        }
    }

    @Test
    fun anyChar_takes_full_text_when_it_fits() = runBlocking {
        withRoboto { stack ->
            val text = "Hello"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 1000f,
                wordBreak = WordBreak.AnyChar,
            )
            assertEquals(1, laid.lineCount)
            assertEquals(0 until text.length, laid.lines[0].charRange)
        }
    }

    @Test
    fun anyChar_respects_hard_breaks() = runBlocking {
        withRoboto { stack ->
            val text = "ab\ncd"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 1000f,
                wordBreak = WordBreak.AnyChar,
            )
            // The hard break at index 2 must split the paragraph even
            // when room remains. Line 0 covers "ab\n" (the LF is
            // consumed at the line end like Phrase / BreakWord do);
            // line 1 starts at index 3.
            assertEquals(2, laid.lineCount)
            assertEquals(0, laid.lines[0].charRange.first)
            assertEquals(3, laid.lines[1].charRange.first)
        }
    }

    @Test
    fun anyChar_handles_crlf_as_single_break() = runBlocking {
        withRoboto { stack ->
            val text = "ab\r\ncd"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 1000f,
                wordBreak = WordBreak.AnyChar,
            )
            assertEquals(2, laid.lineCount, "CRLF must collapse into one line break")
            assertEquals(4, laid.lines[1].charRange.first, "second line must start past CRLF pair")
        }
    }

    @Test
    fun forward_progress_when_single_grapheme_overflows() = runBlocking {
        withRoboto { stack ->
            // 40pt sizePx with 4px maxWidth: even one Latin glyph is
            // wider than the budget. Layout MUST still terminate -
            // every line takes at least one grapheme.
            val text = "abc"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = 40f,
                maxWidth = 4f,
                wordBreak = WordBreak.BreakWord,
            )
            assertEquals(text.length, laid.lineCount, "one grapheme per line under starvation")
            assertEquals(0, laid.lines.first().charRange.first)
            assertEquals(text.length - 1, laid.lines.last().charRange.last)
        }
    }

    @Test
    fun breakWord_nan_max_width_returns_empty_like_non_positive() = runBlocking {
        withRoboto { stack ->
            // NaN fails every budget comparison, which used to march a
            // whitespace-only candidate into the grapheme bisect with
            // empty text and crash on `boundaries[1]` (length=1;
            // index=1). NaN must short-circuit like maxWidth <= 0.
            val laid = stack.layoutParagraph(
                text = "a b",
                sizePx = SIZE_PX,
                maxWidth = Float.NaN,
                wordBreak = WordBreak.BreakWord,
            )
            assertEquals(0, laid.lineCount)
        }
    }

    @Test
    fun breakWord_nan_letter_spacing_terminates_without_crash() = runBlocking {
        withRoboto { stack ->
            // NaN letter spacing poisons the effective advance (0 +
            // NaN * 0 = NaN) even with a valid budget, so a
            // whitespace-only candidate never "fits". It must be
            // consumed as-is instead of reaching the bisect.
            val text = "a b"
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 200f,
                wordBreak = WordBreak.BreakWord,
                letterSpacing = Float.NaN,
            )
            assertTrue(laid.lineCount >= 1)
            assertEquals(0, laid.lines.first().charRange.first)
            assertEquals(text.length - 1, laid.lines.last().charRange.last)
        }
    }

    @Test
    fun anyChar_nan_letter_spacing_with_trailing_spaces_terminates() = runBlocking {
        withRoboto { stack ->
            // AnyChar's slice for a trailing-whitespace tail trims to
            // an empty visible text - same empty-bisect crash path as
            // BreakWord when the advance check is poisoned.
            val text = "ab "
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 200f,
                wordBreak = WordBreak.AnyChar,
                letterSpacing = Float.NaN,
            )
            assertTrue(laid.lineCount >= 1)
            assertEquals(text.length - 1, laid.lines.last().charRange.last)
        }
    }

    @Test
    fun breakWord_does_not_split_supplementary_codepoint_pair() = runBlocking {
        withRoboto { stack ->
            // The grinning-face emoji (U+1F600) is two UTF-16 code
            // units. A grapheme-aware bisect must never cut between
            // them. Robot does not have a glyph for U+1F600 - on the
            // .notdef path the advance is small but still exists, so
            // the boundary scan completes without splitting the pair.
            val text = "ab😀cd"  // "ab😀cd" - 6 UTF-16 code units, 5 graphemes
            val laid = stack.layoutParagraph(
                text = text,
                sizePx = SIZE_PX,
                maxWidth = 24f,
                wordBreak = WordBreak.BreakWord,
            )
            // Charranges must never start or end inside the surrogate
            // pair (indices 2..3): the pair always sits whole on one
            // line.
            for (line in laid.lines) {
                val startsMidPair = line.charRange.first == 3
                val endsMidPair = line.charRange.last == 2
                assertTrue(
                    !startsMidPair && !endsMidPair,
                    "split inside surrogate pair: range=${line.charRange}",
                )
            }
        }
    }

    @Test
    fun arabic_break_word_cuts_at_grapheme_without_crash() = runBlocking {
        val arabicBytes = loadSystemArabicFont() ?: return@runBlocking
        HbFace.from { bytes(arabicBytes) }.use { face ->
            face.toFont().use { font ->
                val stack = HbFontStack(font)
                // A long Arabic word: BreakWord must cut it at a
                // grapheme boundary. Joining forms differ at the cut
                // (initial / medial / final), so allow a small overflow
                // tolerance for the re-shaped advance vs. the bisect's
                // cumulative-advance estimate.
                val text = "اللاختبار"
                val laid = stack.layoutParagraph(
                    text = text,
                    sizePx = 32f,
                    maxWidth = 50f,
                    wordBreak = WordBreak.BreakWord,
                )
                assertTrue(laid.lineCount >= 2, "expected Arabic mid-word split")
                // Total coverage: lines partition the full text.
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
