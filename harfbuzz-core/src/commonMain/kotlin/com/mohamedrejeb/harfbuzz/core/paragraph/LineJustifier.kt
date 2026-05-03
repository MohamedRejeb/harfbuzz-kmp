package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Result of justifying one line. [justifiedText] is the line text after
 * inserting connectors (Kashida / thin-space) - or the original `text`
 * unchanged when nothing was inserted. [originalToJustifiedIndex] is the
 * per-character mapping from the original line text into [justifiedText];
 * `null` means identity (no insertions happened) so callers can skip the
 * indirection without paying for an `IntArray(n) { it }` allocation on
 * every un-justified line.
 *
 * When non-null, [originalToJustifiedIndex].size equals the original
 * line's length and each entry is the position of that character in
 * [justifiedText]. The mapping is monotonically increasing, so callers
 * can binary-search it to translate a justified-side index back to the
 * original-side cluster origin.
 */
public class LineJustificationResult internal constructor(
    public val justifiedText: String,
    public val originalToJustifiedIndex: IntArray?,
)

/**
 * Strategy dispatch for justifying one already-shaped line. Wraps the
 * [WordSpacingJustifier] / [KashidaJustifier] decision in a single entry
 * point so callers (single-line `ShapedText`, paragraph layout) do not
 * each repeat the same `when (strategy)` ladder.
 *
 * Returns an identity result (text unchanged, mapping null) for
 * [JustificationStrategy.None] and for the usual identity bail-outs
 * (target below current, zero glyph widths, empty insertion sets) so
 * the caller can safely re-shape only when [LineJustificationResult.justifiedText]
 * differs from the input.
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
    ): LineJustificationResult = when (strategy) {
        JustificationStrategy.None -> identityResult(text)
        JustificationStrategy.WordSpacing -> {
            val r = WordSpacingJustifier.justifyLine(
                text = text,
                currentWidth = currentWidth,
                targetWidth = targetWidth,
                thinSpaceWidth = thinSpaceWidth,
            )
            buildResult(text, r.justifiedText, r.originalToJustifiedIndex)
        }
        JustificationStrategy.Mixed, is JustificationStrategy.KashidaTo -> {
            if (ArabicTextUtils.isArabicText(text)) {
                val r = KashidaJustifier.justifyArabicLine(
                    text = text,
                    currentWidth = currentWidth,
                    targetWidth = targetWidth,
                    kashidaGlyphWidth = kashidaGlyphWidth,
                )
                buildResult(text, r.justifiedText, r.originalToJustifiedIndex)
            } else {
                val r = WordSpacingJustifier.justifyLine(
                    text = text,
                    currentWidth = currentWidth,
                    targetWidth = targetWidth,
                    thinSpaceWidth = thinSpaceWidth,
                )
                buildResult(text, r.justifiedText, r.originalToJustifiedIndex)
            }
        }
    }

    /**
     * Collapse the underlying justifier's identity case (length
     * unchanged) into a null-mapping [LineJustificationResult] so
     * downstream callers can short-circuit on `mapping == null` and
     * skip the per-char indirection on every un-changed line. The
     * underlying justifiers always insert and never remove, so an
     * unchanged length means no characters were added.
     */
    private fun buildResult(
        originalText: String,
        justifiedText: String,
        mapping: IntArray,
    ): LineJustificationResult =
        if (justifiedText.length == originalText.length) identityResult(originalText)
        else LineJustificationResult(justifiedText, mapping)

    private fun identityResult(text: String): LineJustificationResult =
        LineJustificationResult(text, null)
}
