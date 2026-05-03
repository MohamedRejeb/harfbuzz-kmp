package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Platform indirection for UAX #14 line-break opportunities. Returns
 * an array of codepoint indices where the line is allowed to break,
 * sorted ascending. The array MUST start with `0` and end with
 * `text.length`. Empty text returns `intArrayOf(0)`.
 *
 * Returns `null` to signal that the caller should fall back to the
 * pure-Kotlin pipeline in [lineBreakOpportunities].
 *
 * JVM and Android delegate to `java.text.BreakIterator.getLineInstance()`
 * which is ICU-backed on both platforms (always available since API 1
 * on Android and on every JVM). iOS and Wasm currently return null
 * and inherit the pure-Kotlin whitespace fallback; they can grow real
 * actuals later (CFStringTokenizer / Intl.Segmenter once the line
 * granularity ships) without changing the public API.
 */
internal expect fun lineBreakOpportunitiesPlatform(text: String): IntArray?
