package com.mohamedrejeb.harfbuzz.compose.paragraph

import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Multi-line + alignment + letter spacing must keep every visual line
 * aligned to the same reference edge. A line's drawn content spans
 * `[xOffset + measured.ink.left, xOffset + measured.ink.right]` (the
 * renderer composes `measured` translated by `xOffset`). For Center the
 * per-line centers must match; for Right the right edges; for Left the
 * left edges. If core geometry (`xOffset`, from `shapeParagraph` +
 * advance-stretch) disagrees with the per-line `measured` (from
 * `buildMeasuredText` + letter-spacing / kashida), lines drift apart.
 */
class LetterSpacingAlignmentTest {

    private val handles = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        handles.reversed().forEach { it.close() }
        handles.clear()
    }

    @Test
    fun `latin center multiline control no letter spacing shares center`() = runBlocking {
        assertCommonEdge(LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Center, 0f, Edge.CENTER)
    }

    @Test
    fun `latin center multiline shares center with letter spacing`() = runBlocking {
        assertCommonEdge(LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Center, 8f, Edge.CENTER)
    }

    @Test
    fun `latin right multiline shares right edge with letter spacing`() = runBlocking {
        assertCommonEdge(LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Right, 8f, Edge.RIGHT)
    }

    @Test
    fun `latin left multiline shares left edge with letter spacing`() = runBlocking {
        assertCommonEdge(LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Left, 8f, Edge.LEFT)
    }

    @Test
    fun `arabic center multiline shares center with letter spacing`() = runBlocking {
        assertCommonEdge(ARABIC, TestFonts.notoNaskhArabicMedium(), ParagraphAlignment.Center, 8f, Edge.CENTER, HbDirection.RTL)
    }

    private enum class Edge { LEFT, CENTER, RIGHT }

    private fun edgeOf(line: MeasuredLine, edge: Edge): Float = when (edge) {
        Edge.LEFT -> line.xOffset + line.measured.ink.left
        Edge.RIGHT -> line.xOffset + line.measured.ink.right
        Edge.CENTER -> line.xOffset + (line.measured.ink.left + line.measured.ink.right) / 2f
    }

    private suspend fun assertCommonEdge(
        text: String,
        font: ByteArray,
        alignment: ParagraphAlignment,
        letterSpacing: Float,
        edge: Edge,
        direction: HbDirection = HbDirection.AUTO,
    ) {
        val p = layout(text, font, alignment, letterSpacing, direction)
        assertTrue(p.lineCount >= 2, "fixture must be multi-line; got ${p.lineCount}")
        val edges = p.lines.map { edgeOf(it, edge) }
        val spread = (edges.maxOrNull() ?: 0f) - (edges.minOrNull() ?: 0f)
        assertTrue(
            spread <= 1.0f,
            "lines should share $edge edge (spread=$spread) but drift:\n" + describe(p),
        )
    }

    private fun describe(p: MeasuredParagraph): String = buildString {
        append("paragraph width=${p.width} maxWidth=${p.maxWidth} baseDir=${p.baseDirection}\n")
        p.lines.forEachIndexed { i, l ->
            append(
                "  line$i xOff=${l.xOffset} coreAdv=${l.advance} " +
                    "coreInk=[${l.layout.ink.left}..${l.layout.ink.right}] " +
                    "mAdv=${l.measured.advance} mInk=[${l.measured.ink.left}..${l.measured.ink.right}] " +
                    "text='${l.layout.text}'\n",
            )
        }
    }

    private suspend fun layout(
        text: String,
        fontBytes: ByteArray,
        alignment: ParagraphAlignment,
        letterSpacing: Float,
        direction: HbDirection,
    ): MeasuredParagraph {
        harfBuzzInit()
        val face = HbFace.fromBytes(fontBytes)
        val font = face.toFont()
        handles.add(font)
        handles.add(face)
        return buildMeasuredParagraph(
            text = text,
            fontStack = HbFontStack(font),
            sizePx = 28f,
            maxWidth = 1000f,
            alignment = alignment,
            direction = direction,
            features = emptyList(),
            language = HbLanguage.AUTO,
            lineSpacing = 0f,
            justification = JustificationStrategy.None,
            letterSpacing = letterSpacing,
        )
    }

    private companion object {
        // Two hard-broken lines of very different widths so any per-line
        // alignment drift is obvious.
        const val LATIN = "Hi\nHello World Wide"
        const val ARABIC = "مر\nمرحبا بالعالم"
    }
}
