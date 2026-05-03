package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * iOS returns null and inherits the pure-Kotlin whitespace fallback in
 * [lineBreakOpportunities]. A real `CFStringTokenizer`-backed actual
 * is tracked as a future improvement; for v0 the fallback covers the
 * Latin / Arabic workloads consumers exercise on iOS today.
 */
internal actual fun lineBreakOpportunitiesPlatform(text: String): IntArray? = null
