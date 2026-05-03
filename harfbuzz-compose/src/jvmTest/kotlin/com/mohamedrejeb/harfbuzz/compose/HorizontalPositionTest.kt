package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 4 (char ↔ cluster mapping) coverage on [MeasuredText].
 *
 *  - Latin LTR: per-character cumulative advance with the trailing edge
 *    landing at the paragraph's total advance.
 *  - Arabic RTL: cluster's leading edge is its right side, so the X
 *    decreases monotonically as `charIndex` advances.
 *  - Combining mark inside a cluster: only the cluster's leading
 *    codepoint owns the advance; the mark itself reports `0f`.
 *  - Surrogate pair: the lead surrogate carries the cluster's full
 *    advance, the trail surrogate reports `0f`.
 */
class HorizontalPositionTest {

    private val openHandles = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        openHandles.reversed().forEach { it.close() }
        openHandles.clear()
    }

    @Test
    fun `latin ltr positions are cumulative and end at total advance`() = runBlocking {
        val measured = measure(text = "Hello", fontBytes = TestFonts.robotoRegular())

        // Roboto shapes "Hello" as 5 single-glyph clusters.
        assertEquals(5, measured.textLength)
        assertEquals(0f, measured.horizontalPositionOf(0))

        var running = 0f
        for (i in 0 until measured.textLength) {
            assertEquals(
                running,
                measured.horizontalPositionOf(i),
                tolerance = 1e-3f,
                message = "leading edge of char $i",
            )
            running += measured.advanceWidthOf(i)
        }
        assertEquals(
            measured.advance,
            measured.horizontalPositionOf(measured.textLength),
            tolerance = 1e-3f,
            message = "trailing edge equals advance",
        )
        assertEquals(
            measured.advance,
            running,
            tolerance = 1e-3f,
            message = "summed advances equal advance",
        )
    }

    @Test
    fun `arabic rtl leading edge is the right side and decreases with charIndex`() = runBlocking {
        val measured = measure(
            text = "كلمة",
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            direction = HbDirection.RTL,
        )

        assertEquals(HbDirection.RTL, measured.paragraph.baseDirection)
        assertEquals(4, measured.textLength)

        // Leading edge (right side in canvas) of char 0 is the
        // paragraph's right end = total advance.
        assertEquals(
            measured.advance,
            measured.horizontalPositionOf(0),
            tolerance = 1e-3f,
            message = "char 0 (logical first, visually rightmost) leads at advance",
        )
        // Trailing edge of the last cluster in an RTL paragraph is the
        // paragraph's left end = 0.
        assertEquals(
            0f,
            measured.horizontalPositionOf(measured.textLength),
            tolerance = 1e-3f,
            message = "trailing edge of RTL paragraph is 0",
        )

        // Positions must be strictly decreasing (each cluster has a
        // non-zero advance for these three single-glyph clusters).
        var previous = measured.horizontalPositionOf(0)
        for (i in 1..measured.textLength) {
            val current = measured.horizontalPositionOf(i)
            assertTrue(
                current < previous,
                "RTL position should decrease: pos($i)=$current, pos(${i - 1})=$previous",
            )
            previous = current
        }

        // Sum of per-char advances still equals total advance.
        var summed = 0f
        for (i in 0 until measured.textLength) summed += measured.advanceWidthOf(i)
        assertEquals(measured.advance, summed, tolerance = 1e-3f)
    }

    @Test
    fun `combining mark inside arabic cluster reports zero advance`() = runBlocking {
        // "بَ" - U+0628 (BEH) + U+064E (FATHA, a vowel-mark combining
        // diacritic). HarfBuzz typically clusters them together with
        // FATHA's advance attributed to BEH (cluster start at index 0).
        val measured = measure(
            text = "بَ",
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            direction = HbDirection.RTL,
        )
        assertEquals(2, measured.textLength)

        val cluster0 = measured.clusterAt(0)
        val cluster1 = measured.clusterAt(1)
        assertNotNull(cluster0)
        assertNotNull(cluster1)
        assertEquals(
            cluster0,
            cluster1,
            "FATHA must share a cluster with its base BEH",
        )

        val baseAdvance = measured.advanceWidthOf(0)
        val markAdvance = measured.advanceWidthOf(1)
        assertEquals(0f, markAdvance, tolerance = 1e-3f, message = "FATHA advance must be 0")
        assertTrue(baseAdvance > 0f, "BEH must own the cluster's advance")
        assertEquals(
            measured.advance,
            baseAdvance,
            tolerance = 1e-3f,
            message = "single cluster's advance equals total advance",
        )

        // Both codepoints share the cluster, so they share the leading
        // edge.
        assertEquals(
            measured.horizontalPositionOf(0),
            measured.horizontalPositionOf(1),
            tolerance = 1e-3f,
            message = "cluster-internal codepoints share the leading edge",
        )
    }

    @Test
    fun `surrogate pair attributes advance to lead surrogate`() = runBlocking {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A is non-BMP, encoded as a
        // surrogate pair (D835 DC00) in UTF-16. HarfBuzz clusters them
        // together with the cluster id pointing at the high surrogate.
        // Roboto's `glyf` covers Mathematical Alphanumeric Symbols, so
        // shaping yields a single ligature glyph spanning both code
        // units.
        val mathBoldA = "𝐀"
        val measured = measure(text = mathBoldA, fontBytes = TestFonts.robotoRegular())
        // If the fixture font is missing the codepoint and falls
        // through to .notdef, give a clear error rather than a noisy
        // assertion failure later.
        assertTrue(
            measured.advance > 0f,
            "Roboto should cover U+1D400; got zero advance, font fixture changed?",
        )

        assertEquals(2, measured.textLength)
        assertEquals(0, measured.clusterAt(0))
        assertEquals(0, measured.clusterAt(1), "trail surrogate inherits cluster from lead")

        assertEquals(
            measured.advance,
            measured.advanceWidthOf(0),
            tolerance = 1e-3f,
            message = "lead surrogate carries the full advance",
        )
        assertEquals(
            0f,
            measured.advanceWidthOf(1),
            tolerance = 1e-3f,
            message = "trail surrogate reports zero advance",
        )

        // Both code units share the cluster's leading edge.
        assertEquals(
            measured.horizontalPositionOf(0),
            measured.horizontalPositionOf(1),
            tolerance = 1e-3f,
        )
        assertEquals(
            measured.advance,
            measured.horizontalPositionOf(measured.textLength),
            tolerance = 1e-3f,
        )
    }

    @Test
    fun `clusterAt returns null outside text range`() = runBlocking {
        val measured = measure(text = "ab", fontBytes = TestFonts.robotoRegular())
        assertEquals(0, measured.clusterAt(0))
        assertEquals(1, measured.clusterAt(1))
        assertNull(measured.clusterAt(2))
        assertNull(measured.clusterAt(-1))
        assertNull(measured.clusterAt(100))
    }

    @Test
    fun `horizontalPositionOf rejects out of range indices`() = runBlocking {
        val measured = measure(text = "ab", fontBytes = TestFonts.robotoRegular())
        // [0, textLength] is in range; [textLength + 1, ...) and
        // negative indices throw.
        measured.horizontalPositionOf(0)
        measured.horizontalPositionOf(measured.textLength)
        assertFails { measured.horizontalPositionOf(-1) }
        assertFails { measured.horizontalPositionOf(measured.textLength + 1) }
    }

    @Test
    fun `advanceWidthOf rejects the trailing edge index`() = runBlocking {
        val measured = measure(text = "ab", fontBytes = TestFonts.robotoRegular())
        measured.advanceWidthOf(0)
        measured.advanceWidthOf(measured.textLength - 1)
        assertFails { measured.advanceWidthOf(measured.textLength) }
        assertFails { measured.advanceWidthOf(-1) }
    }

    @Test
    fun `empty MeasuredText reports zero textLength`() = runBlocking {
        val measured = measure(text = "", fontBytes = TestFonts.robotoRegular())
        assertEquals(0, measured.textLength)
        assertNull(measured.clusterAt(0))
        assertEquals(0f, measured.horizontalPositionOf(0), tolerance = 1e-3f)
    }

    @Test
    fun `justified arabic single line keeps original-text indices`() = runBlocking {
        val text = "نص عربي تجريبي للاختبار"
        val unjustified = measure(
            text = text,
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            direction = HbDirection.RTL,
        )
        val targetWidth = unjustified.advance + 60f
        val justified = measureWithJustify(
            text = text,
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            direction = HbDirection.RTL,
            maxWidth = targetWidth,
            justification = JustificationStrategy.Mixed,
        )

        // textLength stays in the original-text coordinate space even
        // though Kashida insertions widened the shaped run.
        assertEquals(
            text.length,
            justified.textLength,
            "justified textLength must reflect original length",
        )
        assertTrue(
            justified.advance > unjustified.advance,
            "justification did not fire; advance ${justified.advance} <= unjustified ${unjustified.advance}",
        )

        // RTL leading edge of charIndex 0 lives at the right of the
        // line; trailing edge (charIndex == textLength) at the left.
        assertEquals(
            justified.advance,
            justified.horizontalPositionOf(0),
            tolerance = 1e-3f,
        )
        assertEquals(
            0f,
            justified.horizontalPositionOf(justified.textLength),
            tolerance = 1e-3f,
        )

        // Positions stay non-strictly-decreasing across original chars
        // (the trim suffix or cluster-internal codepoints can repeat
        // but never increase).
        var prev = justified.horizontalPositionOf(0)
        for (i in 1..justified.textLength) {
            val current = justified.horizontalPositionOf(i)
            assertTrue(
                current <= prev + 1e-3f,
                "RTL position must not increase: pos($i)=$current pos(${i - 1})=$prev",
            )
            prev = current
        }

        // clusterAt returns original-space indices (every cluster id
        // must be a valid original-char offset). Summing per-original
        // advances equals the un-justified advance (Kashida-only
        // shaped clusters are not attributed to any original char).
        var summed = 0f
        for (i in 0 until justified.textLength) {
            val cluster = justified.clusterAt(i)
            assertNotNull(cluster, "cluster missing at original index $i")
            assertTrue(
                cluster in 0 until justified.textLength,
                "cluster $cluster outside original range at index $i",
            )
            summed += justified.advanceWidthOf(i)
        }
        assertEquals(
            unjustified.advance,
            summed,
            tolerance = 1e-3f,
            message = "summed original-char advances must equal un-justified advance",
        )
    }

    @Test
    fun `justification with no slack returns identity-mapped result`() = runBlocking {
        // When the un-justified advance already meets the target width
        // there is nothing to insert; buildMeasuredTextWithJustify must
        // bail out and return the original shape (no mapping attached).
        val text = "نص عربي"
        val initial = measure(
            text = text,
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            direction = HbDirection.RTL,
        )
        val justified = measureWithJustify(
            text = text,
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            direction = HbDirection.RTL,
            maxWidth = initial.advance, // no slack
            justification = JustificationStrategy.Mixed,
        )

        assertEquals(text.length, justified.textLength)
        assertEquals(initial.advance, justified.advance, tolerance = 1e-3f)
        // Positions must match the un-justified shape exactly.
        for (i in 0..text.length) {
            assertEquals(
                initial.horizontalPositionOf(i),
                justified.horizontalPositionOf(i),
                tolerance = 1e-3f,
                message = "no-slack pos parity at $i",
            )
        }
    }

    private suspend fun measure(
        text: String,
        fontBytes: ByteArray,
        sizePx: Float = 24f,
        direction: HbDirection = HbDirection.AUTO,
    ): MeasuredText {
        harfBuzzInit()
        val face = HbFace.fromBytes(fontBytes)
        val font = face.toFont()
        openHandles.add(font)
        openHandles.add(face)
        val stack = HbFontStack(font)
        clearMeasuredTextCacheForTest()
        return buildMeasuredText(
            text = text,
            fontStack = stack,
            sizePx = sizePx,
            features = emptyList(),
            direction = direction,
            language = HbLanguage.AUTO,
        )
    }

    private suspend fun measureWithJustify(
        text: String,
        fontBytes: ByteArray,
        sizePx: Float = 24f,
        direction: HbDirection = HbDirection.AUTO,
        maxWidth: Float,
        justification: JustificationStrategy,
    ): MeasuredText {
        harfBuzzInit()
        val face = HbFace.fromBytes(fontBytes)
        val font = face.toFont()
        openHandles.add(font)
        openHandles.add(face)
        val stack = HbFontStack(font)
        clearMeasuredTextCacheForTest()
        return buildMeasuredTextWithJustify(
            text = text,
            fontStack = stack,
            sizePx = sizePx,
            features = emptyList(),
            direction = direction,
            language = HbLanguage.AUTO,
            maxWidth = maxWidth,
            justification = justification,
        )
    }
}

private fun assertEquals(
    expected: Float,
    actual: Float,
    tolerance: Float,
    message: String? = null,
) {
    if (abs(expected - actual) > tolerance) {
        val prefix = if (message != null) "$message: " else ""
        fail("${prefix}expected=$expected actual=$actual tolerance=$tolerance")
    }
}

private inline fun assertFails(block: () -> Unit) {
    try {
        block()
    } catch (_: Throwable) {
        return
    }
    fail("expected an exception, none thrown")
}
