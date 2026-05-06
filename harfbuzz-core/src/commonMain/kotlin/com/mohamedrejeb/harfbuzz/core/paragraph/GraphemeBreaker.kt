package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Return the codepoint indices where [text] may legally cut between
 * graphemes (extended grapheme clusters per UAX #29), sorted
 * ascending. The result always starts with `0` and ends with
 * `text.length`; intermediate entries are positions where a
 * grapheme-level cut is allowed.
 *
 * Used by [layoutParagraph] when [WordBreak.BreakWord] or
 * [WordBreak.AnyChar] needs to split a word that is wider than
 * `maxWidth`. Cutting at a grapheme boundary keeps combining marks,
 * non-BMP characters, and ZWJ emoji sequences intact - cutting between
 * codepoints would split them and produce visual garbage.
 *
 * On JVM and Android this routes through
 * `java.text.BreakIterator.getCharacterInstance` (ICU-backed). On iOS
 * and Wasm it falls through to a pure-Kotlin codepoint walker that
 * keeps surrogate pairs together but does not yet detect combining-
 * mark or ZWJ-emoji boundaries; the fallback is correct for the
 * Latin / Arabic / CJK workloads consumers exercise on those targets
 * today and can grow real actuals later without changing this API.
 *
 * Empty text returns `intArrayOf(0)`.
 */
public fun graphemeBreakOpportunities(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    return graphemeBreakOpportunitiesPlatform(text) ?: graphemeBreakOpportunitiesFallback(text)
}

/**
 * Pure-Kotlin grapheme-break fallback: every codepoint boundary is a
 * cut, keeping UTF-16 surrogate pairs intact. Combining marks, ZWJ
 * sequences, and regional-indicator pairs are NOT detected - those
 * cases need the platform actual. Always emits `0` and `text.length`.
 */
internal fun graphemeBreakOpportunitiesFallback(text: String): IntArray {
    val n = text.length
    // Worst case is one boundary per code unit (no surrogates), plus the
    // implicit 0 and n. ArrayList re-grows on overflow, so the lower
    // bound below is just a sizing hint.
    val out = ArrayList<Int>(n + 1)
    out.add(0)
    var i = 0
    while (i < n) {
        val ch = text[i]
        i = if (ch.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) i + 2 else i + 1
        if (out.last() != i) out.add(i)
    }
    if (out.last() != n) out.add(n)
    return out.toIntArray()
}
