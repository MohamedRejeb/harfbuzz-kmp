package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * How [layoutParagraph] handles a "word" (a span between two
 * consecutive UAX #14 line-break opportunities) that is wider than the
 * paragraph's `maxWidth` constraint.
 *
 * | Mode        | Wide word fits in budget                  | Wide word too long for budget                                     |
 * |-------------|-------------------------------------------|-------------------------------------------------------------------|
 * | [Phrase]    | break only at line-break opportunities    | place the whole word on its own line, **overflowing** the budget  |
 * | [BreakWord] | break only at line-break opportunities    | cut the word at the largest grapheme boundary that still fits     |
 * | [AnyChar]   | every grapheme is a break opportunity     | cut the word at the largest grapheme boundary that still fits     |
 *
 * [Phrase] (the default) preserves the v0 behaviour: a single token
 * longer than `maxWidth` is rendered uncut and overflows. [BreakWord]
 * matches the typical UI-text policy (Compose's default,
 * `StaticLayout` on Android) - words break at word boundaries when
 * they fit, and the engine falls back to grapheme-level cuts only when
 * a single word would otherwise overflow. [AnyChar] is for CJK-style
 * layouts where every grapheme is a legal break point.
 *
 * Mid-word cuts pick the largest grapheme boundary whose prefix advance
 * still fits the budget, derived from the already-shaped overflow
 * line's per-cluster cumulative advance. The chosen prefix is then
 * re-shaped once for rendering. Cost over [Phrase]: at most one extra
 * shape per overflowing line plus an O(graphemes-in-the-cut-span)
 * scan; no work is added to lines that already fit at a word boundary.
 *
 * In Arabic (and other cursive scripts) splitting a word changes the
 * joining forms at the cut: the last letter of the prefix takes its
 * isolated / final form, and the first letter of the next line takes
 * its initial form. This is the same behaviour Compose / Skia exhibit
 * and is acceptable for fitting overflowing words; consumers that need
 * pixel-perfect joining should pin [Phrase] and accept the overflow.
 */
public sealed class WordBreak {
    /** Break only at UAX #14 line-break opportunities; allow overflow. */
    public data object Phrase : WordBreak()

    /** Like [Phrase] but cut overflowing words at a grapheme boundary. */
    public data object BreakWord : WordBreak()

    /** Every grapheme is a break opportunity (CJK-style). */
    public data object AnyChar : WordBreak()
}
