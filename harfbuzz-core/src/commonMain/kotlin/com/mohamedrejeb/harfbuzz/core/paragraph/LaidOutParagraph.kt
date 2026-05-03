package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph

/**
 * Result of laying [text] across multiple visual lines under a width
 * constraint. Each line carries its own shape result, alignment-applied
 * X offset, and Y position so the renderer can walk lines in order
 * without re-doing layout work.
 *
 * `width` is the actual paragraph width (max line advance), `height`
 * is the bottom of the last line (no trailing line spacing). Together
 * they bound the inked area; callers wanting the constraint width
 * should use [maxWidth] instead.
 *
 * Empty text or a non-positive [maxWidth] still produce a valid empty
 * `LaidOutParagraph` so callers don't need null-checks.
 */
public data class LaidOutParagraph(
    public val text: String,
    public val lines: List<LineLayout>,
    public val maxWidth: Float,
    public val width: Float,
    public val height: Float,
    public val firstBaseline: Float,
    public val baseDirection: HbDirection,
    public val alignment: ParagraphAlignment,
) {
    public val isEmpty: Boolean get() = lines.isEmpty() || lines.all { it.paragraph.isEmpty }
    public val lineCount: Int get() = lines.size

    public companion object {
        public fun empty(
            baseDirection: HbDirection = HbDirection.LTR,
            alignment: ParagraphAlignment = ParagraphAlignment.Start,
        ): LaidOutParagraph = LaidOutParagraph(
            text = "",
            lines = emptyList(),
            maxWidth = 0f,
            width = 0f,
            height = 0f,
            firstBaseline = 0f,
            baseDirection = baseDirection,
            alignment = alignment,
        )
    }
}

/**
 * One visual line within a [LaidOutParagraph].
 *
 * `paragraph` is the shape result for [charRange] (a single visual
 * line treated as its own one-line "paragraph"). `xOffset` is the
 * alignment-applied left position relative to the [LaidOutParagraph]
 * origin; `top` is the Y of the line's logical box top relative to
 * the paragraph origin.
 *
 * `ascent` is positive (distance from baseline up to the box top).
 * `descent` is positive (distance from baseline down to the box
 * bottom). This matches typical UI typography APIs and differs from
 * HarfBuzz's Y-up [com.mohamedrejeb.harfbuzz.core.FontExtents] sign
 * convention; the layout function flips the sign once at the boundary.
 */
public data class LineLayout(
    public val paragraph: ShapedParagraph,
    /**
     * The exact text content the line was shaped with. Equals the
     * source slice (`text.substring(charRange)`) trimmed of trailing
     * whitespace + hard-break chars. When the paragraph was justified
     * via [JustificationStrategy], this contains the Kashida (U+0640)
     * / thin-space (U+2009) characters the justifier inserted - so
     * downstream renderers can re-shape the *same* glyphs the layout
     * measured and avoid drift.
     */
    public val text: String,
    public val charRange: IntRange,
    public val xOffset: Float,
    public val top: Float,
    public val baseline: Float,
    public val ascent: Float,
    public val descent: Float,
    public val lineGap: Float,
    public val lineHeight: Float,
    public val advance: Float,
    public val ink: HbRect,
    public val logical: HbRect,
    /**
     * Per-character mapping from the trimmed source line into [text]
     * after justification: index `i` of the original (trimmed) line
     * lands at position `originalToJustifiedIndex[i]` of the justified
     * [text]. `null` means identity - no insertions happened on this
     * line, so the original-side indices already match the shaped
     * indices and callers can skip the indirection without paying for
     * an `IntArray(n) { it }` allocation. When non-null,
     * `originalToJustifiedIndex.size` is the trimmed source line
     * length (which can be shorter than [charRange] when trailing
     * whitespace / hard-break characters were stripped before shaping).
     */
    public val originalToJustifiedIndex: IntArray? = null,
)
