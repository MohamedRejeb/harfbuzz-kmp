package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * How a line of text is filled to its target width when alignment is
 * [ParagraphAlignment.Justify]. Applies to both single-line shaping
 * (`ShapedText` with a fixed width) and multi-line paragraphs - each
 * eligible line is widened by inserting extra glyphs (or extra inter-
 * word spaces) until its measured advance approaches the target width.
 *
 *  - [None]       Justify falls back to Start - no width distribution.
 *                 Useful as a default that opts out of justification
 *                 without changing the alignment field.
 *  - [WordSpacing] Insert U+2009 thin spaces at every word boundary,
 *                 regardless of script. Mirrors what Compose's
 *                 `BasicText` `TextAlign.Justify` does for Latin and
 *                 produces script-uniform output.
 *  - [Mixed]      Use Kashida (U+0640) on Arabic-content lines and thin
 *                 spaces elsewhere. Highest-quality result for mixed
 *                 Arabic / Latin content because the Arabic letter
 *                 joins stretch instead of the inter-word gaps.
 *  - [KashidaTo]  Single-line variant carrying an explicit target
 *                 width (typically the arc length for `ArcText`).
 *                 Same behaviour as [Mixed] otherwise; the embedded
 *                 width overrides the caller's `maxWidth` so callers
 *                 can keep `maxWidth` infinite (no wrap) while still
 *                 driving Kashida insertion.
 *  - [AdvanceStretchTo] Single-line, post-shape per-glyph advance
 *                 stretch. Widens every inter-glyph gap uniformly
 *                 (no text insertion, no re-shape) so the line's
 *                 total advance reaches the embedded target. The
 *                 script-symmetric counterpart to [KashidaTo] for
 *                 non-Arabic text on `ArcText`, where the only fill
 *                 mechanism for Latin / mixed runs is letter-spacing.
 *
 * Justification is skipped when:
 *  - The paragraph's last line (multi-line) or a line whose un-justified
 *    advance already meets / exceeds the target width.
 *  - A line ended by a hard break (LF, CR, CRLF, NEL, U+2028, U+2029).
 */
public sealed interface JustificationStrategy {

    /** Justify is a no-op; the paragraph aligns like [ParagraphAlignment.Start]. */
    public data object None : JustificationStrategy

    /** Distribute thin spaces across word boundaries on every line. */
    public data object WordSpacing : JustificationStrategy

    /** Kashida for Arabic lines, thin-space word-spacing for the rest. */
    public data object Mixed : JustificationStrategy

    /**
     * Kashida-justify a single line to exactly [targetWidthPx], ignoring
     * any `maxWidth` argument the caller passed for wrap purposes.
     * Designed for `ArcText`, where the target width is the arc length
     * and there is no wrap. In a paragraph context this falls back to
     * [Mixed] semantics against the paragraph's `maxWidth`.
     */
    public data class KashidaTo(public val targetWidthPx: Float) : JustificationStrategy

    /**
     * Stretch the line's per-glyph advances post-shape so the total
     * advance reaches [targetWidthPx]. Inserts no characters and
     * does not re-shape; the extra width is distributed uniformly
     * across inter-glyph gaps. Designed for `ArcText` where the
     * caller wants Latin or mixed-script text to fill the arc the
     * way [KashidaTo] fills it for Arabic.
     *
     * Has no effect when [targetWidthPx] is at or below the
     * un-justified advance, when the line has fewer than two
     * glyphs, or when [targetWidthPx] is non-finite or non-positive.
     */
    public data class AdvanceStretchTo(public val targetWidthPx: Float) : JustificationStrategy
}
