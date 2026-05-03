package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.shapeParagraph

/**
 * Lay out [text] across multiple visual lines under a [maxWidth]
 * constraint. Each line is shaped independently via
 * [HbFontStack.shapeParagraph]; line breaks are taken at the legal
 * positions returned by [lineBreakOpportunities]; horizontal
 * alignment is applied per-line via [alignment].
 *
 * Algorithm: greedy first-fit. For each cursor, walk the break
 * opportunities ahead and shape the candidate line; the longest
 * candidate that fits within [maxWidth] wins. If even the first
 * candidate overflows the budget, it is taken anyway (overflow is the
 * caller's problem to clip / ellipsise). Hard line breaks (LF, CR,
 * CRLF, NEL, U+2028, U+2029) terminate the line as soon as they
 * appear, regardless of width.
 *
 * `lineSpacing` is added between consecutive lines (not after the
 * last line). Per-line `lineHeight` is derived from the primary
 * font's `hExtents` (`ascender - descender + lineGap`). Multi-font
 * runs use the primary font's metrics for line stepping.
 *
 * Cost: shapes O(B) intermediate candidates per line where B is the
 * count of break opportunities the greedy walk probes. For typical
 * UI labels (a handful of words per line) this is a small constant;
 * long single-line paragraphs only shape once.
 */
public suspend fun HbFontStack.layoutParagraph(
    text: String,
    maxWidth: Float,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    baseDirection: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    lineSpacing: Float = 0f,
    justification: JustificationStrategy = JustificationStrategy.None,
): LaidOutParagraph {
    if (text.isEmpty() || maxWidth <= 0f) {
        return LaidOutParagraph.empty(baseDirection = baseDirection, alignment = alignment)
    }

    val breaks = lineBreakOpportunities(text)
    val rawLines = ArrayList<RawLine>(8)
    var cursor = 0
    var bIdx = nextBreakIndex(breaks, cursor)

    while (cursor < text.length) {
        val pick = pickLine(
            text = text,
            cursor = cursor,
            breaks = breaks,
            startIdx = bIdx,
            maxWidth = maxWidth,
            baseDirection = baseDirection,
            features = features,
            language = language,
        )
        rawLines.add(
            RawLine(
                start = cursor,
                end = pick.end,
                shape = pick.shape,
                shapedText = pick.shapedText,
                endedByHardBreak = pick.endedByHardBreak,
                justifyMapping = null,
            ),
        )
        cursor = pick.end
        bIdx = pick.nextProbeIdx
    }

    if (alignment == ParagraphAlignment.Justify && justification != JustificationStrategy.None) {
        applyJustification(
            text = text,
            rawLines = rawLines,
            maxWidth = maxWidth,
            baseDirection = baseDirection,
            features = features,
            language = language,
            justification = justification,
        )
    }

    val ext = primary.hExtents
    val ascent = ext?.ascender ?: (primary.pointSize * 0.8f)
    val descent = -(ext?.descender ?: -(primary.pointSize * 0.2f))
    val gap = ext?.lineGap ?: 0f
    val singleLineHeight = ascent + descent + gap

    val lines = ArrayList<LineLayout>(rawLines.size)
    var y = 0f
    var maxLineWidth = 0f
    val resolvedBase = rawLines.firstOrNull()?.shape?.baseDirection ?: baseDirection

    for (raw in rawLines) {
        val advance = raw.shape.totalAdvance
        val lineInk = if (raw.shape.ink.isEmpty) HbRect.EMPTY else raw.shape.ink
        val xOff = computeXOffset(advance, lineInk, maxWidth, alignment, resolvedBase)
        lines.add(
            LineLayout(
                paragraph = raw.shape,
                text = raw.shapedText,
                charRange = raw.start until raw.end,
                xOffset = xOff,
                top = y,
                baseline = y + ascent,
                ascent = ascent,
                descent = descent,
                lineGap = gap,
                lineHeight = singleLineHeight,
                advance = advance,
                ink = lineInk,
                logical = raw.shape.logical,
                originalToJustifiedIndex = raw.justifyMapping,
            ),
        )
        y += singleLineHeight + lineSpacing
        if (advance > maxLineWidth) maxLineWidth = advance
    }

    val height = if (lines.isNotEmpty()) lines.last().top + singleLineHeight else 0f

    return LaidOutParagraph(
        text = text,
        lines = lines,
        maxWidth = maxWidth,
        width = maxLineWidth,
        height = height,
        firstBaseline = if (lines.isNotEmpty()) lines.first().baseline else 0f,
        baseDirection = resolvedBase,
        alignment = alignment,
    )
}

private suspend fun HbFontStack.pickLine(
    text: String,
    cursor: Int,
    breaks: IntArray,
    startIdx: Int,
    maxWidth: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
): LinePick {
    var bestEnd = -1
    var bestShape: ShapedParagraph? = null
    var bestText: String? = null
    var probeIdx = startIdx

    var endedByHardBreak = false
    while (probeIdx < breaks.size) {
        val candidateEnd = breaks[probeIdx]
        if (candidateEnd <= cursor) {
            probeIdx++
            continue
        }
        val candidateText = text.substring(cursor, candidateEnd)
        // UAX #14: trailing whitespace at a line break is allowed to
        // overflow the line and must not count toward the visible advance.
        // Stripping it before shaping gives correct alignment maths
        // (Center / Right / End) and stops a line from reporting width
        // that includes invisible space chars.
        val visibleText = candidateText.trimEndForLineBreak()
        val candidateShape = shapeParagraph(visibleText, baseDirection, features, language)
        val containsHardBreak = candidateText.indexOfLast { isHardBreakChar(it) } >= 0

        val fits = candidateShape.totalAdvance <= maxWidth
        if (fits) {
            bestEnd = candidateEnd
            bestShape = candidateShape
            bestText = visibleText
            probeIdx++
            // Hard break in the candidate stops further extension: the
            // line MUST end here regardless of how much room is left.
            if (containsHardBreak) {
                endedByHardBreak = true
                break
            }
        } else if (bestEnd == -1) {
            // No previous fit and this candidate doesn't fit either.
            // Take it anyway so the layout makes forward progress;
            // overflow is the caller's problem.
            bestEnd = candidateEnd
            bestShape = candidateShape
            bestText = visibleText
            probeIdx++
            if (containsHardBreak) endedByHardBreak = true
            break
        } else {
            // Already have a fitting line; this candidate is too long.
            break
        }
    }

    if (bestEnd == -1 || bestShape == null || bestText == null) {
        // Defensive fall-through: no breaks left, consume to end of text.
        val end = text.length
        val visibleText = text.substring(cursor, end).trimEndForLineBreak()
        val shape = shapeParagraph(visibleText, baseDirection, features, language)
        return LinePick(end, shape, visibleText, breaks.size, endedByHardBreak = false)
    }
    return LinePick(bestEnd, bestShape, bestText, probeIdx, endedByHardBreak)
}

/**
 * Strip trailing whitespace and hard-break characters from a line slice
 * before measuring its visible advance. Mirrors UAX #14's recommendation
 * that trailing space at a break is "free" - it does not contribute to
 * the line's visible width.
 */
private fun String.trimEndForLineBreak(): String =
    this.trimEnd { ch -> ch == ' ' || ch == '\t' || isHardBreakChar(ch) }

private fun nextBreakIndex(breaks: IntArray, cursor: Int): Int {
    var i = 0
    while (i < breaks.size && breaks[i] <= cursor) i++
    return i
}

/**
 * Resolve the per-line `xOffset` so the line's visible **ink** lands at
 * the alignment's target position. Plain advance-based alignment leaves
 * the leftmost-visual glyph's positive LSB and the rightmost-visual
 * glyph's negative RSB visibly off-edge - especially on Arabic, where
 * those bearings are routinely 4-7px at typical UI sizes. Centering and
 * edge-snapping by the ink rect gives optically-balanced alignment for
 * every script without script-specific code.
 *
 * For lines with empty ink (whitespace only) we fall back to the advance
 * box so the line still occupies the expected slot.
 */
private fun computeXOffset(
    lineAdvance: Float,
    lineInk: HbRect,
    maxWidth: Float,
    alignment: ParagraphAlignment,
    baseDirection: HbDirection,
): Float {
    val (inkLeft, inkRight) = if (lineInk.isEmpty) {
        0f to lineAdvance
    } else {
        lineInk.left to lineInk.right
    }
    return when (alignment) {
        ParagraphAlignment.Start ->
            if (baseDirection == HbDirection.RTL) maxWidth - inkRight else -inkLeft
        ParagraphAlignment.End ->
            if (baseDirection == HbDirection.RTL) -inkLeft else maxWidth - inkRight
        ParagraphAlignment.Left -> -inkLeft
        ParagraphAlignment.Right -> maxWidth - inkRight
        ParagraphAlignment.Center -> (maxWidth - inkLeft - inkRight) / 2f
        // Width distribution happens earlier in `applyJustification`;
        // by this point the line either fills `maxWidth` (in which case
        // either Start branch lands at the same edge) or is the last /
        // hard-broken line and falls back to Start.
        ParagraphAlignment.Justify ->
            if (baseDirection == HbDirection.RTL) maxWidth - inkRight else -inkLeft
    }
}

internal fun isHardBreakChar(ch: Char): Boolean = when (ch) {
    '\n', '\r', '\u0085', '\u2028', '\u2029' -> true
    else -> false
}


private data class RawLine(
    val start: Int,
    val end: Int,
    val shape: ShapedParagraph,
    val shapedText: String,
    val endedByHardBreak: Boolean,
    val justifyMapping: IntArray?,
)

private data class LinePick(
    val end: Int,
    val shape: ShapedParagraph,
    val shapedText: String,
    val nextProbeIdx: Int,
    val endedByHardBreak: Boolean,
)

/**
 * Distribute extra width across the non-last, non-hard-broken lines of
 * [rawLines] so each one's re-shaped advance approaches [maxWidth].
 *
 * Strategy dispatch:
 *  - [JustificationStrategy.WordSpacing] - thin-space at every word
 *    boundary on every line (Compose's `BasicText` style).
 *  - [JustificationStrategy.Mixed] - Kashida on Arabic-content lines,
 *    thin-space on the rest. Chooses per-line so a paragraph that
 *    mixes scripts gets the right tool on each visual line.
 *
 * Pre-measures the Kashida and thin-space glyph widths once per
 * paragraph by shaping a single-character string. The font's Kashida
 * advance is what the per-line distribution divides into to decide how
 * many connectors to insert; running this measurement per line would
 * waste an O(L) shape pass for no reason.
 */
private suspend fun HbFontStack.applyJustification(
    text: String,
    rawLines: MutableList<RawLine>,
    maxWidth: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    justification: JustificationStrategy,
) {
    if (rawLines.size <= 1) return

    val needsKashidaWidth = justification == JustificationStrategy.Mixed
    val kashidaWidth = if (needsKashidaWidth) {
        shapeParagraph(ArabicTextUtils.KASHIDA.toString(), baseDirection, features, language).totalAdvance
    } else 0f
    val thinSpaceWidth = shapeParagraph(
        WordSpacingJustifier.THIN_SPACE.toString(),
        baseDirection, features, language,
    ).totalAdvance

    val lastIndex = rawLines.size - 1
    for (i in 0 until lastIndex) {
        val raw = rawLines[i]
        if (raw.endedByHardBreak) continue
        val current = raw.shape.totalAdvance
        if (current >= maxWidth) continue

        val lineText = text.substring(raw.start, raw.end).trimEndForLineBreak()
        if (lineText.isEmpty()) continue

        val justified = LineJustifier.justify(
            text = lineText,
            strategy = justification,
            currentWidth = current,
            targetWidth = maxWidth,
            kashidaGlyphWidth = kashidaWidth,
            thinSpaceWidth = thinSpaceWidth,
        )

        if (justified.justifiedText.length != lineText.length) {
            val newShape = shapeParagraph(justified.justifiedText, baseDirection, features, language)
            rawLines[i] = raw.copy(
                shape = newShape,
                shapedText = justified.justifiedText,
                justifyMapping = justified.originalToJustifiedIndex,
            )
        }
    }
}
