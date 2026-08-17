package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredText
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredTextWithJustify
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
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import com.mohamedrejeb.harfbuzz.core.paragraph.WordBreak
import com.mohamedrejeb.harfbuzz.core.paragraph.layoutParagraph
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
        val measured = measureParagraphLine(
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
        // Re-derive the alignment offset from the ACTUAL measured ink. Core's
        // `layoutParagraph` computed `xOffset` from the advance-stretch
        // geometry, which matches the render for Latin but diverges for Arabic
        // (rendered as Kashida). Aligning to the rendered ink keeps every line
        // on the same edge. Zero spacing keeps core's offset untouched.
        val resolvedLine = if (letterSpacing != 0f && !measured.isEmpty) {
            line.copy(
                xOffset = alignedXOffset(measured, layout.maxWidth, layout.alignment, line.paragraph.baseDirection),
            )
        } else {
            line
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
 * Shape one paragraph line with [letterSpacing] baked in to match the
 * letter-spaced advance ([targetAdvance]) `layoutParagraph` already used for
 * line breaking and geometry:
 *
 *  - `0` spacing → plain shape.
 *  - Positive spacing on Arabic content → Kashida (tatweel) elongation up to
 *    [targetAdvance], so cursive joins stretch instead of opening gaps.
 *  - Otherwise (Latin / mixed tracking, or any negative spacing) → uniform
 *    per-cluster advance delta via [withLetterSpacing].
 */
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
): MeasuredText {
    if (letterSpacing == 0f) {
        return buildMeasuredText(lineText, fontStack, sizePx, features, direction, language, fontRuns)
    }
    if (letterSpacing > 0f && ArabicTextUtils.isArabicText(lineText)) {
        return buildMeasuredTextWithJustify(
            text = lineText,
            fontStack = fontStack,
            sizePx = sizePx,
            features = features,
            direction = direction,
            language = language,
            maxWidth = targetAdvance,
            justification = JustificationStrategy.KashidaTo(targetAdvance),
            fontRuns = fontRuns,
        )
    }
    return buildMeasuredText(lineText, fontStack, sizePx, features, direction, language, fontRuns)
        .withLetterSpacing(letterSpacing)
}

