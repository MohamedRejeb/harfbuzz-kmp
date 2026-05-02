package com.mohamedrejeb.harfbuzz.core

import java.text.Bidi

/**
 * JVM + Android implementation of [resolveBidiPlatform], routed through
 * `java.text.Bidi` - ICU-backed on the JDK (icu4j upstream), libcore-Bidi
 * on Android (also ICU-backed). Both run the full UAX#9 algorithm
 * including paired-bracket pairing (N0) and explicit embedding controls
 * that our pure-Kotlin resolver omits.
 *
 * Empty text returns `emptyList()` to match the existing
 * [BidiResolver.resolve] contract - `java.text.Bidi` produces a single
 * base-level run for empty input which would surprise downstream
 * shaping code that special-cases the empty paragraph.
 */
internal actual fun resolveBidiPlatform(
    text: String,
    baseDirection: HbDirection,
): List<BidiRun>? {
    if (text.isEmpty()) return emptyList()
    val flag = when (baseDirection) {
        HbDirection.LTR -> Bidi.DIRECTION_LEFT_TO_RIGHT
        HbDirection.RTL -> Bidi.DIRECTION_RIGHT_TO_LEFT
        // AUTO + the vertical TTB/BTT directions all fall through to
        // the LTR-default heuristic. Paragraph direction is inferred
        // from the first strong character; if none, assume LTR - same
        // pragma as the pure-Kotlin resolver's `else -> 0` branch.
        HbDirection.AUTO,
        HbDirection.TTB,
        HbDirection.BTT -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
    }
    val bidi = Bidi(text, flag)
    val n = bidi.runCount
    if (n == 0) return emptyList()
    val out = ArrayList<BidiRun>(n)
    for (i in 0 until n) {
        out += BidiRun(
            start = bidi.getRunStart(i),
            end = bidi.getRunLimit(i),
            level = bidi.getRunLevel(i),
        )
    }
    return out
}
