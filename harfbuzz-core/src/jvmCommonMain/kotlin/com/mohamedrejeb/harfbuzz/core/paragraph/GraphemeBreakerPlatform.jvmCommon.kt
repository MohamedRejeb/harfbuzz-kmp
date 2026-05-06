package com.mohamedrejeb.harfbuzz.core.paragraph

import java.text.BreakIterator

/**
 * UAX #29 extended-grapheme-cluster boundaries via
 * `java.text.BreakIterator`. ICU powers this on every supported
 * platform: Android ships ICU since API 1, the OpenJDK uses ICU under
 * the hood for the `BreakIterator` service. The root locale is used
 * because grapheme clustering is a shaping concern, not a
 * UI-locale-formatting concern.
 */
internal actual fun graphemeBreakOpportunitiesPlatform(text: String): IntArray? {
    if (text.isEmpty()) return intArrayOf(0)
    val iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    iterator.setText(text)
    val out = ArrayList<Int>(text.length + 1)
    var pos = iterator.first()
    while (pos != BreakIterator.DONE) {
        out.add(pos)
        pos = iterator.next()
    }
    return out.toIntArray()
}
