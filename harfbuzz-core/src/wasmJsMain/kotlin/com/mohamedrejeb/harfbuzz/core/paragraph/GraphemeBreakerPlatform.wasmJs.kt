package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Wasm returns null and inherits the pure-Kotlin codepoint fallback in
 * [graphemeBreakOpportunities]. A real
 * `Intl.Segmenter('', { granularity: 'grapheme' })`-backed actual is
 * tracked as a future improvement; for now the fallback keeps
 * surrogate pairs intact and is correct for the Latin / Arabic
 * workloads consumers exercise on the web today.
 */
internal actual fun graphemeBreakOpportunitiesPlatform(text: String): IntArray? = null
