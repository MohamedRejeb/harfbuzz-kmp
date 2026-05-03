package com.mohamedrejeb.harfbuzz.core.paragraph

import java.text.BreakIterator

/**
 * UAX #14 line-break opportunities via `java.text.BreakIterator`. ICU
 * powers this on every supported platform: Android ships ICU since
 * API 1, the OpenJDK uses ICU under the hood for the `BreakIterator`
 * service. We always request the root locale so this is a paragraph
 * shaping concern, not a UI-locale-formatting concern.
 */
internal actual fun lineBreakOpportunitiesPlatform(text: String): IntArray? {
    if (text.isEmpty()) return intArrayOf(0)
    val iterator = BreakIterator.getLineInstance(java.util.Locale.ROOT)
    iterator.setText(text)
    val breaks = ArrayList<Int>(8)
    var pos = iterator.first()
    while (pos != BreakIterator.DONE) {
        breaks.add(pos)
        pos = iterator.next()
    }
    // first() always returns 0 and the loop walks to text.length, so
    // breaks already starts at 0 and ends at text.length.
    return breaks.toIntArray()
}
