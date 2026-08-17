package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredText
import com.mohamedrejeb.harfbuzz.compose.withLetterSpacing
import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.remapFontRuns
import com.mohamedrejeb.harfbuzz.core.sliceFontRuns
import com.mohamedrejeb.harfbuzz.core.paragraph.ArabicTextUtils
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.LaidOutParagraph
import com.mohamedrejeb.harfbuzz.core.paragraph.LineJustifier
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import com.mohamedrejeb.harfbuzz.core.paragraph.WordBreak
import com.mohamedrejeb.harfbuzz.core.paragraph.WordSpacingJustifier
import com.mohamedrejeb.harfbuzz.core.paragraph.layoutParagraph
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lay [text] across multiple visual lines under [maxWidth] and package
 * the result + per-line [com.mohamedrejeb.harfbuzz.compose.MeasuredText]
 * caches into a [MeasuredParagraph] safe to retain across recompositions.
 *
 * Returns a [State] of [MeasuredParagraphLoad]. The build runs off-main.
 * The initial value is [MeasuredParagraphLoad.Loading] until the first
 * layout completes; subsequent input changes keep the previous
 * [MeasuredParagraphLoad.Ready] visible while the new layout runs
 * (stale-while-revalidate), so animating size, maxWidth, features etc.
 * does not blank the rendered paragraph between frames.
 */
@Composable
public fun rememberMeasuredParagraph(
    text: String,
    font: HbFont,
    sizePx: Float,
    maxWidth: Float,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    direction: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    lineSpacing: Float = 0f,
    justification: JustificationStrategy = JustificationStrategy.None,
    wordBreak: WordBreak = WordBreak.Phrase,
    letterSpacing: Float = 0f,
    fontRuns: List<FontRun> = emptyList(),
): State<MeasuredParagraphLoad> {
    val stack = remember(font) { HbFontStack(font) }
    return rememberMeasuredParagraph(
        text = text,
        fontStack = stack,
        sizePx = sizePx,
        maxWidth = maxWidth,
        alignment = alignment,
        direction = direction,
        features = features,
        language = language,
        lineSpacing = lineSpacing,
        justification = justification,
        wordBreak = wordBreak,
        letterSpacing = letterSpacing,
        fontRuns = fontRuns,
    )
}

/**
 * Multi-font overload of [rememberMeasuredParagraph]. Routes layout
 * through [HbFontStack.layoutParagraph]; per-line shape work uses the
 * same fallback chain so mixed-script paragraphs (Latin + Arabic + emoji)
 * render correctly even when no single font covers everything.
 */
@Composable
public fun rememberMeasuredParagraph(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    maxWidth: Float,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    direction: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    lineSpacing: Float = 0f,
    justification: JustificationStrategy = JustificationStrategy.None,
    wordBreak: WordBreak = WordBreak.Phrase,
    letterSpacing: Float = 0f,
    fontRuns: List<FontRun> = emptyList(),
): State<MeasuredParagraphLoad> {
    return produceState<MeasuredParagraphLoad>(
        initialValue = MeasuredParagraphLoad.Loading,
        text, fontStack, sizePx, maxWidth, alignment, direction,
        features, language, lineSpacing, justification, wordBreak, letterSpacing, fontRuns,
    ) {
        // Deliberately do NOT reset to Loading here: keep the previous
        // Ready value visible while the new layout runs so size /
        // maxWidth / feature animation does not blank the paragraph.
        try {
            val measured = buildMeasuredParagraph(
                text = text,
                fontStack = fontStack,
                sizePx = sizePx,
                maxWidth = maxWidth,
                alignment = alignment,
                direction = direction,
                features = features,
                language = language,
                lineSpacing = lineSpacing,
                justification = justification,
                wordBreak = wordBreak,
                letterSpacing = letterSpacing,
                fontRuns = fontRuns,
            )
            value = MeasuredParagraphLoad.Ready(measured)
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: Throwable) {
            if (isStaleHbHandle(cause)) {
                return@produceState
            }
            println("[kotlin-harfbuzz] buildMeasuredParagraph failed: $cause")
            cause.printStackTrace()
            value = MeasuredParagraphLoad.Failed(cause)
        }
    }
}

private fun isStaleHbHandle(cause: Throwable): Boolean =
    cause is IllegalStateException && cause.message == "hb object disposed"

/**
 * @param fontRuns Authored font assignments over ranges of [text]
 *   (paragraph-text UTF-16 offsets; clamp and last-wins semantics).
 *   Line breaking measures candidates with the runs applied, and each
 *   line's [MeasuredText] is shaped with the runs sliced to its range
 *   (via [sliceFontRuns], projected through [remapFontRuns] on
 *   justified lines), so per-line glyph caches, cache keys, and
 *   effective metrics ([MeasuredText.maxAscent] / `maxDescent`) all
 *   reflect the mixed fonts. Line stepping keeps using the primary
 *   font's metrics. The caller retains ownership of the fonts.
 */
public suspend fun buildMeasuredParagraph(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    maxWidth: Float,
    alignment: ParagraphAlignment,
    direction: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    lineSpacing: Float,
    justification: JustificationStrategy,
    wordBreak: WordBreak = WordBreak.Phrase,
    letterSpacing: Float = 0f,
    fontRuns: List<FontRun> = emptyList(),
): MeasuredParagraph {
    if (text.isEmpty() || maxWidth <= 0f) return MeasuredParagraph.empty(fontStack)

    val layout: LaidOutParagraph = fontStack.layoutParagraph(
        text = text,
        sizePx = sizePx,
        maxWidth = maxWidth,
        alignment = alignment,
        baseDirection = direction,
        features = features,
        language = language,
        lineSpacing = lineSpacing,
        justification = justification,
        wordBreak = wordBreak,
        letterSpacing = letterSpacing,
        fontRuns = fontRuns,
    )

    val lines = layout.lines.map { line ->
        // Slice the paragraph's authored runs to this line's range, in
        // line-local coordinates; justified lines project the slice
        // through the connector-insertion mapping so the re-measure
        // shapes the same glyphs the layout produced.
        val lineRuns = if (fontRuns.isEmpty()) {
            emptyList()
        } else {
            val sliced = sliceFontRuns(fontRuns, line.charRange.first, line.charRange.last + 1)
            line.originalToJustifiedIndex?.let { m ->
                remapFontRuns(sliced, m, line.text.length)
            } ?: sliced
        }
        // Re-shape per line through the same per-glyph-cache pipeline that
        // `buildMeasuredText` populates for `drawShapedText`. The shaped
        // text comes straight from the line's `paragraph` (already
        // justified by core if applicable), so no second-rate divergence
        // between layout and render. Letter spacing is baked in here so the
        // rendered glyphs match the letter-spaced advances `layoutParagraph`
        // already used for line breaking and geometry.
        val shape = measureParagraphLine(
            lineText = line.text,
            fontStack = fontStack,
            sizePx = sizePx,
            features = features,
            // Each line's own (paragraph) direction, not the whole text's, so a
            // hard-wrapped line in the other script keeps its reading order.
            direction = line.paragraph.baseDirection,
            language = language,
            letterSpacing = letterSpacing,
            targetAdvance = line.advance,
            fontRuns = lineRuns,
        )
        val measured = shape.measured
        // Letter spacing on Arabic widens the line by inserting Kashida
        // connectors; surface the widened text and mapping on the line,
        // exactly like the justification path, so the measured shape's
        // cluster ids, span slicing, and char accessors all agree on one
        // text space.
        val lineWithShapedText = if (shape.insertionMapping != null) {
            line.copy(
                text = shape.shapedText,
                originalToJustifiedIndex = composeInsertionMappings(
                    line.originalToJustifiedIndex,
                    shape.insertionMapping,
                ),
            )
        } else {
            line
        }
        // Re-derive the alignment offset from the ACTUAL measured ink. Core's
        // `layoutParagraph` computed `xOffset` from the advance-stretch
        // geometry, which matches the render for Latin but diverges for Arabic
        // (rendered as Kashida). Aligning to the rendered ink keeps every line
        // on the same edge. Zero spacing keeps core's offset untouched.
        val resolvedLine = if (letterSpacing != 0f && !measured.isEmpty) {
            lineWithShapedText.copy(
                xOffset = alignedXOffset(measured, layout.maxWidth, layout.alignment, line.paragraph.baseDirection),
            )
        } else {
            lineWithShapedText
        }
        MeasuredLine(measured = measured, layout = resolvedLine)
    }

    return MeasuredParagraph(
        text = text,
        lines = lines,
        maxWidth = layout.maxWidth,
        width = layout.width,
        height = layout.height,
        firstBaseline = layout.firstBaseline,
        baseDirection = layout.baseDirection,
        alignment = layout.alignment,
        fontStack = fontStack,
    )
}

/**
 * Per-line alignment offset derived from the rendered [measured] ink — the
 * Compose-side mirror of `ParagraphLayout.computeXOffset`, used to re-align a
 * letter-spaced / Kashida-justified line to the same edge as its neighbours.
 * Whitespace-only lines fall back to the advance box.
 */
private fun alignedXOffset(
    measured: MeasuredText,
    maxWidth: Float,
    alignment: ParagraphAlignment,
    baseDirection: HbDirection,
): Float {
    val ink = measured.ink
    val left = if (ink.isEmpty) 0f else ink.left
    val right = if (ink.isEmpty) measured.advance else ink.right
    return when (alignment) {
        ParagraphAlignment.Start -> if (baseDirection == HbDirection.RTL) maxWidth - right else -left
        ParagraphAlignment.End -> if (baseDirection == HbDirection.RTL) -left else maxWidth - right
        ParagraphAlignment.Left -> -left
        ParagraphAlignment.Right -> maxWidth - right
        ParagraphAlignment.Center -> (maxWidth - left - right) / 2f
        ParagraphAlignment.Justify -> if (baseDirection == HbDirection.RTL) maxWidth - right else -left
    }
}

/**
 * Per-line measure result: the shaped [measured] plus the text its
 * cluster ids address and, when positive letter spacing widened an
 * Arabic line by inserting Kashida connectors, the source-to-widened
 * index mapping. Non-null [insertionMapping] means [shapedText] differs
 * from the source line and must replace [LineLayout.text] with the
 * mapping composed onto [LineLayout.originalToJustifiedIndex], the same
 * bookkeeping core justification uses.
 */
private class MeasuredLineShape(
    val measured: MeasuredText,
    val shapedText: String,
    val insertionMapping: IntArray?,
)

private suspend fun measureParagraphLine(
    lineText: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
    letterSpacing: Float,
    targetAdvance: Float,
    fontRuns: List<FontRun> = emptyList(),
): MeasuredLineShape {
    if (letterSpacing == 0f) {
        return MeasuredLineShape(
            measured = buildMeasuredText(lineText, fontStack, sizePx, features, direction, language, fontRuns),
            shapedText = lineText,
            insertionMapping = null,
        )
    }
    if (letterSpacing > 0f && ArabicTextUtils.isArabicText(lineText)) {
        return measureKashidaSpacedLine(
            lineText, fontStack, sizePx, features, direction, language, targetAdvance, fontRuns,
        )
    }
    return MeasuredLineShape(
        measured = buildMeasuredText(lineText, fontStack, sizePx, features, direction, language, fontRuns)
            .withLetterSpacing(letterSpacing),
        shapedText = lineText,
        insertionMapping = null,
    )
}

/**
 * Positive letter spacing on Arabic content elongates via Kashida up to
 * [targetAdvance] (the letter-spaced advance the layout already used),
 * so cursive joins stretch instead of opening gaps. The widened text is
 * shaped directly, so the returned measured's cluster ids and
 * [MeasuredText.textLength] both live in the widened text's space; the
 * caller surfaces [MeasuredLineShape.shapedText] and the insertion
 * mapping on the line.
 */
private suspend fun measureKashidaSpacedLine(
    lineText: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
    targetAdvance: Float,
    fontRuns: List<FontRun>,
): MeasuredLineShape {
    val initial = buildMeasuredText(lineText, fontStack, sizePx, features, direction, language, fontRuns)
    if (
        lineText.isEmpty() ||
        !targetAdvance.isFinite() ||
        targetAdvance <= 0f ||
        initial.advance >= targetAdvance
    ) {
        return MeasuredLineShape(initial, lineText, null)
    }
    val kashidaWidth = fontStack.shapeParagraph(
        ArabicTextUtils.KASHIDA.toString(), sizePx, direction, features, language,
    ).totalAdvance
    val thinSpaceWidth = fontStack.shapeParagraph(
        WordSpacingJustifier.THIN_SPACE.toString(), sizePx, direction, features, language,
    ).totalAdvance
    val justified = LineJustifier.justify(
        text = lineText,
        strategy = JustificationStrategy.KashidaTo(targetAdvance),
        currentWidth = initial.advance,
        targetWidth = targetAdvance,
        kashidaGlyphWidth = kashidaWidth,
        thinSpaceWidth = thinSpaceWidth,
    )
    val mapping = justified.originalToJustifiedIndex
        ?: return MeasuredLineShape(initial, lineText, null)
    val widenedRuns = remapFontRuns(fontRuns, mapping, justified.justifiedText.length)
    val measured = buildMeasuredText(
        justified.justifiedText, fontStack, sizePx, features, direction, language, widenedRuns,
    )
    return MeasuredLineShape(measured, justified.justifiedText, mapping)
}

/**
 * Compose a core-justification mapping with a later letter-spacing
 * insertion mapping: entry `i` of the trimmed source line maps first
 * into the justified text, then into the connector-widened text.
 */
private fun composeInsertionMappings(first: IntArray?, second: IntArray): IntArray =
    if (first == null) second else IntArray(first.size) { second[first[it]] }

