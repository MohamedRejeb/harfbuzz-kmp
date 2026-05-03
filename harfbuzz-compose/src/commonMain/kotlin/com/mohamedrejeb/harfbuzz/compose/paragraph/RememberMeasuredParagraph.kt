package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.LaidOutParagraph
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import com.mohamedrejeb.harfbuzz.core.paragraph.layoutParagraph
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lay [text] across multiple visual lines under [maxWidth] and package
 * the result + per-line [com.mohamedrejeb.harfbuzz.compose.MeasuredText]
 * caches into a [MeasuredParagraph] safe to retain across recompositions.
 *
 * Returns a [State] of [MeasuredParagraphLoad]. The build runs off-main,
 * so the initial value is [MeasuredParagraphLoad.Loading] until layout
 * completes.
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
): State<MeasuredParagraphLoad> {
    return produceState<MeasuredParagraphLoad>(
        initialValue = MeasuredParagraphLoad.Loading,
        text, fontStack, sizePx, maxWidth, alignment, direction, features, language, lineSpacing, justification,
    ) {
        value = MeasuredParagraphLoad.Loading
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

internal suspend fun buildMeasuredParagraph(
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
    )

    val lines = layout.lines.map { line ->
        // Re-shape per line through the same per-glyph-cache pipeline that
        // `buildMeasuredText` populates for `drawShapedText`. The shaped
        // text comes straight from the line's `paragraph` (already
        // justified by core if applicable), so no second-rate divergence
        // between layout and render.
        val lineSourceText = line.text
        val measured = buildMeasuredText(
            text = lineSourceText,
            fontStack = fontStack,
            sizePx = sizePx,
            features = features,
            direction = layout.baseDirection,
            language = language,
        )
        MeasuredLine(measured = measured, layout = line)
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

