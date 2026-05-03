package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Strategy dispatch for justifying one already-shaped line. Wraps the
 * [WordSpacingJustifier] / [KashidaJustifier] decision in a single entry
 * point so callers (single-line `ShapedText`, paragraph layout) do not
 * each repeat the same `when (strategy)` ladder.
 *
 * Returns [text] unchanged for [JustificationStrategy.None] and for the
 * usual identity bail-outs (target below current, zero glyph widths,
 * empty insertion sets) so the caller can safely re-shape only when the
 * returned string actually differs from the input.
 */
public object LineJustifier {

    /**
     * Insert kashida or thin-space glyphs into [text] per [strategy] so
     * that the re-shaped line approaches [targetWidth].
     *
     * - [WordSpacingJustifier.justifyLine] for [JustificationStrategy.WordSpacing]
     *   (also used for non-Arabic content under [JustificationStrategy.Mixed]).
     * - [KashidaJustifier.justifyArabicLine] for Arabic content under
     *   [JustificationStrategy.Mixed].
     *
     * [kashidaGlyphWidth] / [thinSpaceWidth] are the font's measured
     * advances for U+0640 and U+2009 respectively; pre-measure them
     * once per font and reuse across lines to keep the per-line cost at
     * O(text length).
     */
    public fun justify(
        text: String,
        strategy: JustificationStrategy,
        currentWidth: Float,
        targetWidth: Float,
        kashidaGlyphWidth: Float,
        thinSpaceWidth: Float,
    ): String = when (strategy) {
        JustificationStrategy.None -> text
        JustificationStrategy.WordSpacing -> WordSpacingJustifier.justifyLine(
            text = text,
            currentWidth = currentWidth,
            targetWidth = targetWidth,
            thinSpaceWidth = thinSpaceWidth,
        ).justifiedText
        JustificationStrategy.Mixed -> if (ArabicTextUtils.isArabicText(text)) {
            KashidaJustifier.justifyArabicLine(
                text = text,
                currentWidth = currentWidth,
                targetWidth = targetWidth,
                kashidaGlyphWidth = kashidaGlyphWidth,
            ).justifiedText
        } else {
            WordSpacingJustifier.justifyLine(
                text = text,
                currentWidth = currentWidth,
                targetWidth = targetWidth,
                thinSpaceWidth = thinSpaceWidth,
            ).justifiedText
        }
    }
}
