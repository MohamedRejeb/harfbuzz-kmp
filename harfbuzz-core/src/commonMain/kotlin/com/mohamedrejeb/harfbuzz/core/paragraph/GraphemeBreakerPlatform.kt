package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Platform indirection for UAX #29 extended-grapheme-cluster
 * boundaries. Returns an array of codepoint indices where it is legal
 * to cut between graphemes, sorted ascending. The array MUST start
 * with `0` and end with `text.length`. Empty text returns
 * `intArrayOf(0)`.
 *
 * Returns `null` to signal that the caller should fall back to the
 * pure-Kotlin pipeline in [graphemeBreakOpportunities].
 *
 * JVM and Android delegate to
 * `java.text.BreakIterator.getCharacterInstance(Locale.ROOT)` which is
 * ICU-backed on both platforms. iOS and Wasm currently return null and
 * inherit the pure-Kotlin codepoint fallback; they can grow real
 * actuals later (`enumerateSubstrings(.byComposedCharacterSequences)`
 * on iOS, `Intl.Segmenter('', { granularity: 'grapheme' })` on Wasm)
 * without changing the public API.
 */
internal expect fun graphemeBreakOpportunitiesPlatform(text: String): IntArray?
