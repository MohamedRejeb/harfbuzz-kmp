package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Wasm returns null and inherits the pure-Kotlin whitespace fallback
 * in [lineBreakOpportunities]. `Intl.Segmenter` does not yet ship a
 * `line` granularity in any browser, so a real UAX #14 actual would
 * need an embedded ICU build. The fallback covers the Latin / Arabic
 * workloads consumers exercise on the web today.
 */
internal actual fun lineBreakOpportunitiesPlatform(text: String): IntArray? = null
