package com.mohamedrejeb.harfbuzz.compose.paragraph

import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * Phase 4.3 coverage on [MeasuredParagraph.horizontalPositionOf] /
 * [MeasuredParagraph.advanceWidthOf] / [MeasuredParagraph.clusterAt] /
 * [MeasuredParagraph.lineForCharIndex].
 *
 *  - Multi-line Latin LTR: paragraph dispatch finds the right line and
 *    delegates per-char queries to it.
 *  - Multi-line Arabic RTL: positions land in the right line and decrease
 *    monotonically inside each line.
 *  - Justified line: the
 *    [com.mohamedrejeb.harfbuzz.core.paragraph.LineLayout.originalToJustifiedIndex]
 *    mapping translates original char indices through inserted Kashidas /
 *    thin-spaces so the paragraph-level accessors land at the cluster the
 *    *original* char belongs to. Identity-mapped lines must still produce
 *    consistent results.
 *  - Trailing edge / out-of-range / empty paragraph follow [MeasuredText]
 *    semantics.
 */
class MeasuredParagraphCharAccessorsTest {

    private val openHandles = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        openHandles.reversed().forEach { it.close() }
        openHandles.clear()
    }

    @Test
    fun `multi-line latin ltr dispatches each charIndex to its line`() = runBlocking {
        // Wraps to roughly two lines under a tight maxWidth budget.
        val paragraph = layout(
            text = "Hello world",
            fontBytes = TestFonts.robotoRegular(),
            maxWidth = 80f,
        )
        assertTrue(paragraph.lineCount >= 2, "test fixture must wrap")

        for (lineIdx in 0 until paragraph.lineCount) {
            val line = paragraph.lines[lineIdx]
            val firstCharOfLine = line.charRange.first
            val expected = line.xOffset + line.measured.horizontalPositionOf(0)
            val actual = paragraph.horizontalPositionOf(firstCharOfLine)
            assertCloseTo(
                expected,
                actual,
                message = "leading edge of line $lineIdx",
            )
            assertEquals(
                lineIdx,
                paragraph.lineForCharIndex(firstCharOfLine),
                message = "line dispatch for char ${firstCharOfLine}",
            )
        }

        // charIndex == text.length resolves to last line's trailing edge.
        val last = paragraph.lines.last()
        val expectedTrailing = last.xOffset + last.measured.horizontalPositionOf(last.measured.textLength)
        assertCloseTo(
            expectedTrailing,
            paragraph.horizontalPositionOf(paragraph.text.length),
            message = "paragraph trailing edge",
        )
        assertEquals(paragraph.lineCount - 1, paragraph.lineForCharIndex(paragraph.text.length))
    }

    @Test
    fun `arabic rtl line keeps decreasing position semantics`() = runBlocking {
        val paragraph = layout(
            text = "نص عربي تجريبي للاختبار",
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            maxWidth = 220f,
            direction = HbDirection.RTL,
        )
        assertEquals(HbDirection.RTL, paragraph.baseDirection)
        assertTrue(paragraph.lineCount >= 1)

        for (lineIdx in 0 until paragraph.lineCount) {
            val line = paragraph.lines[lineIdx]
            val firstChar = line.charRange.first
            val lastChar = firstChar + line.originalLineLength() - 1
            if (lastChar <= firstChar) continue
            val leading = paragraph.horizontalPositionOf(firstChar)
            val later = paragraph.horizontalPositionOf(lastChar)
            assertTrue(
                leading > later,
                "line $lineIdx: RTL position should decrease (leading=$leading, later=$later)",
            )
        }
    }

    @Test
    fun `justified arabic line maps original indices through inserted kashidas`() = runBlocking {
        // Mixed strategy on Arabic content elongates joins via Kashida.
        // Long enough to wrap into three soft-broken lines so at least
        // one non-last, non-hard-broken line picks up insertions.
        // applyJustification skips the last line and any line ended by
        // a hard break - using `\n` here would zero out justification.
        val text = "نص عربي تجريبي للاختبار اختبار عرض النصوص بالعربية اختبار"
        val justified = layout(
            text = text,
            fontBytes = TestFonts.notoNaskhArabicMedium(),
            maxWidth = 160f,
            direction = HbDirection.RTL,
            alignment = ParagraphAlignment.Justify,
            justification = JustificationStrategy.Mixed,
        )

        val justifiedLine = justified.lines.firstOrNull { it.originalToJustifiedIndexCopy() != null }
        assertNotNull(
            justifiedLine,
            "expected at least one Kashida-justified line; got ${justified.lineCount} lines",
        )
        val mapping = justifiedLine.originalToJustifiedIndexCopy()!!
        // Insertions strictly grow the justified line, so the last entry
        // must point past the source's last char.
        assertTrue(
            mapping.last() >= mapping.size - 1,
            "mapping must be non-decreasing and reach into the justified text",
        )

        // For every original char on the justified line, the paragraph
        // accessor must agree with manually translating through the
        // mapping into the line's `MeasuredText`.
        val firstOriginalChar = justifiedLine.charRange.first
        for (originalLocal in 0 until mapping.size) {
            val paragraphIdx = firstOriginalChar + originalLocal
            val justifiedLocal = mapping[originalLocal]
            val expected = justifiedLine.xOffset +
                justifiedLine.measured.horizontalPositionOf(justifiedLocal)
            assertCloseTo(
                expected,
                justified.horizontalPositionOf(paragraphIdx),
                message = "paragraph horizontalPositionOf($paragraphIdx) on justified line",
            )
            // clusterAt must report the original-side cluster start, not
            // the justified-side glyph index. For single-glyph clusters
            // this is the same original char.
            val cluster = justified.clusterAt(paragraphIdx)
            assertNotNull(cluster, "cluster missing for original char $paragraphIdx")
            assertTrue(
                cluster in justifiedLine.charRange,
                "cluster $cluster must stay inside line range ${justifiedLine.charRange}",
            )
        }
    }

    @Test
    fun `unjustified line uses identity mapping with no allocations`() = runBlocking {
        // No mapping array on a non-justified line - paragraph accessor
        // must still match the line's `MeasuredText` accessor exactly.
        val paragraph = layout(
            text = "Hello world",
            fontBytes = TestFonts.robotoRegular(),
            maxWidth = 200f,
        )
        for (line in paragraph.lines) {
            assertNull(
                line.originalToJustifiedIndexCopy(),
                "unjustified line must have null mapping",
            )
            for (i in 0 until line.originalLineLength()) {
                val pIdx = line.charRange.first + i
                assertCloseTo(
                    line.xOffset + line.measured.horizontalPositionOf(i),
                    paragraph.horizontalPositionOf(pIdx),
                    message = "para vs line at $pIdx",
                )
            }
        }
    }

    @Test
    fun `clusterAt returns null outside text range and on stripped trim suffix`() = runBlocking {
        val paragraph = layout(
            text = "Hello\nWorld",
            fontBytes = TestFonts.robotoRegular(),
            maxWidth = 1000f,
        )
        // The newline ends line 0; if the layout strips the LF before
        // shaping, querying that index must report null (no cluster).
        assertEquals(2, paragraph.lineCount)
        val line0 = paragraph.lines[0]
        val maybeStrippedIdx = line0.charRange.first + line0.originalLineLength()
        if (maybeStrippedIdx in line0.charRange) {
            assertNull(
                paragraph.clusterAt(maybeStrippedIdx),
                "stripped trim suffix has no cluster",
            )
        }
        // Out-of-range indices still return null without throwing.
        assertNull(paragraph.clusterAt(-1))
        assertNull(paragraph.clusterAt(paragraph.text.length))
        assertNull(paragraph.clusterAt(paragraph.text.length + 5))
    }

    @Test
    fun `empty paragraph reports neutral defaults`() = runBlocking {
        val paragraph = layout(
            text = "",
            fontBytes = TestFonts.robotoRegular(),
            maxWidth = 100f,
        )
        assertTrue(paragraph.isEmpty)
        assertEquals(0f, paragraph.horizontalPositionOf(0), tolerance = 1e-3f)
        assertEquals(-1, paragraph.lineForCharIndex(0))
        assertNull(paragraph.clusterAt(0))
    }

    @Test
    fun `out-of-range horizontalPositionOf throws`() = runBlocking {
        val paragraph = layout(
            text = "ab",
            fontBytes = TestFonts.robotoRegular(),
            maxWidth = 100f,
        )
        paragraph.horizontalPositionOf(0)
        paragraph.horizontalPositionOf(paragraph.text.length)
        assertFails { paragraph.horizontalPositionOf(-1) }
        assertFails { paragraph.horizontalPositionOf(paragraph.text.length + 1) }
    }

    private fun MeasuredLine.originalLineLength(): Int =
        layout.originalToJustifiedIndex?.size ?: measured.textLength

    /**
     * Convenience for tests: read the line's mapping array off the
     * underlying `LineLayout` (the `MeasuredLine` field is `internal`,
     * but `LineLayout.originalToJustifiedIndex` is public).
     */
    private fun MeasuredLine.originalToJustifiedIndexCopy(): IntArray? =
        layout.originalToJustifiedIndex

    private suspend fun layout(
        text: String,
        fontBytes: ByteArray,
        maxWidth: Float,
        sizePx: Float = 24f,
        direction: HbDirection = HbDirection.AUTO,
        alignment: ParagraphAlignment = ParagraphAlignment.Start,
        justification: JustificationStrategy = JustificationStrategy.None,
    ): MeasuredParagraph {
        harfBuzzInit()
        val face = HbFace.fromBytes(fontBytes)
        val font = face.toFont()
        openHandles.add(font)
        openHandles.add(face)
        val stack = HbFontStack(font)
        return buildMeasuredParagraph(
            text = text,
            fontStack = stack,
            sizePx = sizePx,
            maxWidth = maxWidth,
            alignment = alignment,
            direction = direction,
            features = emptyList(),
            language = com.mohamedrejeb.harfbuzz.core.HbLanguage.AUTO,
            lineSpacing = 0f,
            justification = justification,
        )
    }
}

private fun assertCloseTo(
    expected: Float,
    actual: Float,
    tolerance: Float = 1e-3f,
    message: String? = null,
) {
    if (abs(expected - actual) > tolerance) {
        val prefix = if (message != null) "$message: " else ""
        fail("${prefix}expected=$expected actual=$actual tolerance=$tolerance")
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
