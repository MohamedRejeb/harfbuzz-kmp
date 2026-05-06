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
 * candidate that fits within [maxWidth] wins. When even the smallest
 * legal candidate overflows the budget, [wordBreak] decides what
 * happens: [WordBreak.Phrase] takes the overflow as-is (the v0
 * default), [WordBreak.BreakWord] cuts the offending word at the
 * largest grapheme boundary that still fits, and [WordBreak.AnyChar]
 * treats every grapheme as a break opportunity to begin with. Hard
 * line breaks (LF, CR, CRLF, NEL, U+2028, U+2029) terminate the line
 * as soon as they appear, regardless of width.
 *
 * `lineSpacing` is added between consecutive lines (not after the
 * last line). Per-line `lineHeight` is derived from the primary
 * font's `hExtents` (`ascender - descender + lineGap`). Multi-font
 * runs use the primary font's metrics for line stepping.
 *
 * Cost: [WordBreak.Phrase] and [WordBreak.BreakWord] shape O(B)
 * intermediate candidates per line where B is the count of legal
 * break opportunities the greedy walk probes (a small constant for
 * typical UI labels). [WordBreak.BreakWord] adds at most one extra
 * shape per overflowing line plus an O(graphemes-in-the-cut-span)
 * scan over the already-shaped overflow's per-cluster cumulative
 * advance - no extra work on lines that fit at a word boundary.
 * [WordBreak.AnyChar] shapes once per line up to a hard break or a
 * bounded character cap, then bisects in O(graphemes-per-line); 1-2
 * shapes per line vs. one-per-grapheme greedy.
 */
public suspend fun HbFontStack.layoutParagraph(
    text: String,
    sizePx: Float,
    maxWidth: Float,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    baseDirection: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    lineSpacing: Float = 0f,
    justification: JustificationStrategy = JustificationStrategy.None,
    wordBreak: WordBreak = WordBreak.Phrase,
): LaidOutParagraph {
    if (text.isEmpty() || maxWidth <= 0f) {
        return LaidOutParagraph.empty(baseDirection = baseDirection, alignment = alignment)
    }

    val breaks = lineBreakOpportunities(text)
    val rawLines = ArrayList<RawLine>(8)
    var cursor = 0
    var bIdx = nextBreakIndex(breaks, cursor)

    while (cursor < text.length) {
        val pick = if (wordBreak == WordBreak.AnyChar) {
            pickLineAnyChar(
                text = text,
                sizePx = sizePx,
                cursor = cursor,
                maxWidth = maxWidth,
                baseDirection = baseDirection,
                features = features,
                language = language,
            )
        } else {
            pickLine(
                text = text,
                sizePx = sizePx,
                cursor = cursor,
                breaks = breaks,
                startIdx = bIdx,
                maxWidth = maxWidth,
                baseDirection = baseDirection,
                features = features,
                language = language,
                wordBreak = wordBreak,
            )
        }
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
        bIdx = if (pick.nextProbeIdx >= 0) pick.nextProbeIdx else nextBreakIndex(breaks, cursor)
    }

    if (alignment == ParagraphAlignment.Justify && justification != JustificationStrategy.None) {
        applyJustification(
            text = text,
            sizePx = sizePx,
            rawLines = rawLines,
            maxWidth = maxWidth,
            baseDirection = baseDirection,
            features = features,
            language = language,
            justification = justification,
        )
    }

    val ext = primary.hExtents(sizePx)
    val ascent = ext?.ascender ?: (sizePx * 0.8f)
    val descent = -(ext?.descender ?: -(sizePx * 0.2f))
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
    sizePx: Float,
    cursor: Int,
    breaks: IntArray,
    startIdx: Int,
    maxWidth: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    wordBreak: WordBreak,
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
        val candidateShape = shapeParagraph(visibleText, sizePx, baseDirection, features, language)
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
            // Phrase: take the overflow as-is so the layout makes
            // forward progress (caller clips / ellipsises). BreakWord:
            // bisect within this candidate at a grapheme boundary so
            // the prefix fits the budget; the next line resumes from
            // the cut point.
            if (wordBreak == WordBreak.BreakWord) {
                return bisectByGrapheme(
                    text = text,
                    sizePx = sizePx,
                    cursor = cursor,
                    overflowShape = candidateShape,
                    overflowText = visibleText,
                    maxWidth = maxWidth,
                    baseDirection = baseDirection,
                    features = features,
                    language = language,
                )
            }
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
        val shape = shapeParagraph(visibleText, sizePx, baseDirection, features, language)
        return LinePick(end, shape, visibleText, breaks.size, endedByHardBreak = false)
    }
    return LinePick(bestEnd, bestShape, bestText, probeIdx, endedByHardBreak)
}

/**
 * AnyChar fast path: shape one capped slice per line, then bisect by
 * grapheme using the slice's per-cluster cumulative advance. Caps
 * shape work at [ANY_CHAR_SHAPE_CAP] characters so a long
 * single-script paragraph (CJK without hard breaks) does not pay
 * O(text.length) per line.
 *
 * The slice covers `[cursor, min(cursor + ANY_CHAR_SHAPE_CAP,
 * nextHardBreakBoundary, text.length))`. If the whole slice fits the
 * budget we take it; otherwise the bisect picks the largest grapheme
 * prefix that fits and re-shapes it. Hard-break detection inside the
 * slice is kept faithful to the existing `pickLine` semantics: a
 * trailing line terminator (LF, CR, CRLF, NEL, U+2028,
 * U+2029) ends the line even when room remains.
 */
private suspend fun HbFontStack.pickLineAnyChar(
    text: String,
    sizePx: Float,
    cursor: Int,
    maxWidth: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
): LinePick {
    val capEnd = (cursor + ANY_CHAR_SHAPE_CAP).coerceAtMost(text.length)
    // Stop the shape at the first hard break inside the cap window; we
    // include the hard-break char itself (and the following `\n` for a
    // CR-LF pair) so the line consumes it like Phrase / BreakWord do.
    val sliceEnd = nextHardBreakBoundary(text, cursor, capEnd)

    val sliceText = text.substring(cursor, sliceEnd)
    val visibleText = sliceText.trimEndForLineBreak()
    val sliceShape = shapeParagraph(visibleText, sizePx, baseDirection, features, language)
    val containsHardBreak = sliceText.indexOfLast { isHardBreakChar(it) } >= 0

    if (sliceShape.totalAdvance <= maxWidth) {
        return LinePick(
            end = sliceEnd,
            shape = sliceShape,
            shapedText = visibleText,
            nextProbeIdx = -1,
            endedByHardBreak = containsHardBreak,
        )
    }

    return bisectByGrapheme(
        text = text,
        sizePx = sizePx,
        cursor = cursor,
        overflowShape = sliceShape,
        overflowText = visibleText,
        maxWidth = maxWidth,
        baseDirection = baseDirection,
        features = features,
        language = language,
    )
}

/**
 * Cut the line at the largest grapheme boundary inside [overflowText]
 * whose prefix advance still fits [maxWidth], using
 * [overflowShape]'s per-cluster cumulative advance to score
 * boundaries without re-shaping each candidate. Re-shapes the chosen
 * prefix once for the line's render so glyph data is correct.
 *
 * The cumulative-advance estimate is exact for non-cursive scripts.
 * In Arabic the chosen prefix's joining forms differ from those in
 * the connected word, so the re-shaped advance can differ from the
 * estimate by a few pixels at the cut - acceptable for a fitting
 * heuristic; consumers needing pixel-perfect joining should pin
 * [WordBreak.Phrase] and accept the overflow.
 *
 * Always makes forward progress: when even the first grapheme's
 * advance exceeds the budget the bisect still cuts at that grapheme
 * so the outer loop never stalls.
 */
private suspend fun HbFontStack.bisectByGrapheme(
    text: String,
    sizePx: Float,
    cursor: Int,
    overflowShape: ShapedParagraph,
    overflowText: String,
    maxWidth: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
): LinePick {
    val cumulative = perIndexCumulativeAdvance(overflowShape, overflowText.length)
    val boundaries = graphemeBreakOpportunities(overflowText)

    // Walk from largest to smallest grapheme boundary; first one whose
    // cumulative advance fits the budget wins. Boundaries are sorted
    // ascending so this is at most |boundaries| comparisons in the
    // worst case (single tall grapheme).
    var chosenLocal = -1
    for (i in boundaries.size - 1 downTo 1) {
        val b = boundaries[i]
        if (cumulative[b] <= maxWidth) {
            chosenLocal = b
            break
        }
    }

    // Forward progress: take the first grapheme even if it overflows.
    // Bisect is only invoked on a non-empty overflow (totalAdvance >
    // maxWidth implies at least one glyph), so boundaries always
    // contains a positive entry past `0`.
    if (chosenLocal < 0) chosenLocal = boundaries[1]

    val prefixEnd = cursor + chosenLocal
    val prefixSrc = text.substring(cursor, prefixEnd)
    val prefixVisible = prefixSrc.trimEndForLineBreak()
    val prefixShape = if (chosenLocal == overflowText.length) {
        // The whole shaped overflow won; reuse it instead of paying
        // for an identical re-shape.
        overflowShape
    } else {
        shapeParagraph(prefixVisible, sizePx, baseDirection, features, language)
    }

    return LinePick(
        end = prefixEnd,
        shape = prefixShape,
        shapedText = prefixVisible,
        nextProbeIdx = -1,
        endedByHardBreak = false,
    )
}

/**
 * Build a `[textLen + 1]` array where `out[b]` is the cumulative
 * advance of all glyphs whose cluster is in `[0, b)` - i.e. the
 * shape's idea of how wide the prefix `text[0, b)` is. Used by
 * [bisectByGrapheme] to score grapheme boundaries without
 * per-candidate re-shaping.
 *
 * Multi-glyph clusters (decomposed marks) sum into the cluster's
 * leading source index; `out[textLen]` always equals the shape's
 * `totalAdvance`. The estimate is conservative for ligature clusters
 * that cross a grapheme boundary - rare in Latin and harmless for
 * the bisect's cut decision.
 */
private fun perIndexCumulativeAdvance(shape: ShapedParagraph, textLen: Int): FloatArray {
    val out = FloatArray(textLen + 1)
    for (run in shape.runs) {
        val glyphs = run.glyphs
        val positions = run.positions
        for (i in glyphs.indices) {
            val cluster = glyphs[i].cluster
            if (cluster in 0 until textLen) {
                out[cluster] += positions[i].xAdvance
            }
        }
    }
    var sum = 0f
    for (i in 0..textLen) {
        val v = out[i]
        out[i] = sum
        sum += v
    }
    return out
}

/**
 * Index just past the first hard-break sequence in `[cursor, cap)`,
 * or [cap] when none is present. CR-LF pairs collapse to a single
 * boundary so the line consumes both code units in one step.
 */
private fun nextHardBreakBoundary(text: String, cursor: Int, cap: Int): Int {
    var i = cursor
    while (i < cap) {
        val ch = text[i]
        if (isHardBreakChar(ch)) {
            return if (ch == '\r' && i + 1 < cap && text[i + 1] == '\n') i + 2 else i + 1
        }
        i++
    }
    return cap
}

/**
 * Per-line shape budget for [WordBreak.AnyChar]. Bounds worst-case
 * shape work on long single-script paragraphs (CJK without hard
 * breaks); typical UI lines (under any realistic `maxWidth` /
 * `sizePx` combination) fit in fewer characters than this and never
 * trigger the cap.
 */
private const val ANY_CHAR_SHAPE_CAP: Int = 4096

/**
 * Strip trailing whitespace and hard-break characters from a line slice
 * before measuring its visible advance. Mirrors UAX #14's recommendation
 * that trailing space at a break is "free" - it does not contribute to
 * the line's visible width.
 */
private fun String.trimEndForLineBreak(): String =
    this.trimEnd { ch -> ch == ' ' || ch == '\t' || isHardBreakChar(ch) }

/**
 * Smallest `i` in `[0, breaks.size]` with `breaks[i] > cursor`.
 * Binary-searches the strictly-ascending `breaks` array so the
 * post-bisect re-derive path stays O(log breaks.size) per line, not
 * O(breaks.size).
 */
private fun nextBreakIndex(breaks: IntArray, cursor: Int): Int {
    var lo = 0
    var hi = breaks.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (breaks[mid] <= cursor) lo = mid + 1 else hi = mid
    }
    return lo
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
    sizePx: Float,
    rawLines: MutableList<RawLine>,
    maxWidth: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    justification: JustificationStrategy,
) {
    if (rawLines.size <= 1) return

    val needsKashidaWidth = justification == JustificationStrategy.Mixed ||
        justification is JustificationStrategy.KashidaTo
    val kashidaWidth = if (needsKashidaWidth) {
        shapeParagraph(ArabicTextUtils.KASHIDA.toString(), sizePx, baseDirection, features, language).totalAdvance
    } else 0f
    val thinSpaceWidth = shapeParagraph(
        WordSpacingJustifier.THIN_SPACE.toString(),
        sizePx, baseDirection, features, language,
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
            val newShape = shapeParagraph(justified.justifiedText, sizePx, baseDirection, features, language)
            rawLines[i] = raw.copy(
                shape = newShape,
                shapedText = justified.justifiedText,
                justifyMapping = justified.originalToJustifiedIndex,
            )
        }
    }
}
