package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.runtime.Immutable
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.paragraph.LineLayout
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment

/**
 * Compose-side bundle of a multi-line paragraph laid out under a width
 * constraint. Each entry in [lines] carries its own [MeasuredText] (so the
 * existing per-glyph caches and the full color-glyph render ladder are
 * reused) plus the [LineLayout] record from `layoutParagraph` describing
 * where the line sits inside the paragraph's logical box.
 *
 * Created by [rememberMeasuredParagraph]; safe to retain across
 * recompositions and frames.
 */
@Immutable
public class MeasuredParagraph internal constructor(
    public val text: String,
    public val lines: List<MeasuredLine>,
    public val maxWidth: Float,
    public val width: Float,
    public val height: Float,
    public val firstBaseline: Float,
    public val baseDirection: HbDirection,
    public val alignment: ParagraphAlignment,
    public val fontStack: HbFontStack,
) {
    public val isEmpty: Boolean get() = lines.isEmpty() || lines.all { it.measured.isEmpty }
    public val lineCount: Int get() = lines.size

    public companion object {
        public fun empty(fontStack: HbFontStack): MeasuredParagraph = MeasuredParagraph(
            text = "",
            lines = emptyList(),
            maxWidth = 0f,
            width = 0f,
            height = 0f,
            firstBaseline = 0f,
            baseDirection = HbDirection.LTR,
            alignment = ParagraphAlignment.Start,
            fontStack = fontStack,
        )
    }
}

/**
 * One visual line within a [MeasuredParagraph]. Pairs the line's own
 * [MeasuredText] (per-glyph caches keyed by font, fully renderable) with
 * the [LineLayout] record that places it inside the paragraph: alignment-
 * applied [LineLayout.xOffset], top-of-box [LineLayout.top], baseline,
 * ascent / descent, and the original [LineLayout.charRange] in the source
 * text.
 */
@Immutable
public class MeasuredLine internal constructor(
    public val measured: MeasuredText,
    public val layout: LineLayout,
) {
    public val xOffset: Float get() = layout.xOffset
    public val top: Float get() = layout.top
    public val baseline: Float get() = layout.baseline
    public val advance: Float get() = layout.advance
    public val charRange: IntRange get() = layout.charRange
    public val ascent: Float get() = layout.ascent
    public val descent: Float get() = layout.descent
    public val lineHeight: Float get() = layout.lineHeight
}
