package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Return the codepoint indices where [text] may legally line-break,
 * sorted ascending. The result always starts with `0` and ends with
 * `text.length`; intermediate entries are positions where the line
 * MAY end (the next line would start there).
 *
 * Example: `"Hello world"` returns `[0, 6, 11]` - line one can be
 * `"Hello "` (chars `0..5`) with line two starting at index `6`.
 *
 * On JVM and Android this routes through `java.text.BreakIterator`
 * (UAX #14 / ICU-backed). On iOS and Wasm it falls through to a
 * pure-Kotlin whitespace-based pipeline: every Unicode whitespace run
 * yields a break after it, plus mandatory breaks at hard line
 * terminators. The fallback is correct for the common Latin /
 * Arabic / Cyrillic case and acceptable for CJK (where every
 * codepoint is a break opportunity in real UAX #14, but consumers
 * usually wrap CJK by character anyway).
 *
 * Empty text returns `intArrayOf(0)`.
 */
public fun lineBreakOpportunities(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    return lineBreakOpportunitiesPlatform(text) ?: lineBreakOpportunitiesFallback(text)
}

/**
 * Pure-Kotlin UAX #14 lite: break after each run of whitespace and
 * after each hard line terminator. Always emits `0` and `text.length`.
 *
 * This is intentionally minimal: it covers space-delimited scripts
 * (Latin, Cyrillic, Greek, Arabic) but not CJK character-by-character
 * breaking. Targets that route through the platform actual ignore
 * this fallback entirely.
 */
internal fun lineBreakOpportunitiesFallback(text: String): IntArray {
    val breaks = ArrayList<Int>(8)
    breaks.add(0)
    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]
        if (isHardBreak(ch)) {
            // Mandatory break after the line terminator. Handle CRLF
            // as a single break point.
            val next = if (ch == '\r' && i + 1 < n && text[i + 1] == '\n') i + 2 else i + 1
            if (breaks.last() != next) breaks.add(next)
            i = next
            continue
        }
        if (ch.isWhitespace()) {
            // Walk the whitespace run; the break opportunity is right
            // after the last whitespace character.
            var j = i + 1
            while (j < n && text[j].isWhitespace() && !isHardBreak(text[j])) j++
            if (breaks.last() != j) breaks.add(j)
            i = j
            continue
        }
        i++
    }
    if (breaks.last() != n) breaks.add(n)
    return breaks.toIntArray()
}

private fun isHardBreak(ch: Char): Boolean = when (ch) {
    '\n', '\r', '\u0085', '\u2028', '\u2029' -> true
    else -> false
}
