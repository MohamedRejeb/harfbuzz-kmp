package com.mohamedrejeb.harfbuzz.core

/**
 * iOS leaves bidi resolution to the pure-Kotlin [BidiResolver]. CoreFoundation
 * exposes `CFAttributedString` BiDi via Cocoa's text engine, but reaching
 * it from Kotlin/Native requires significant cinterop wiring for a
 * marginal correctness win over what we already ship. Item I in the perf
 * parity plan revisits this if iOS profiling surfaces a bidi-bound
 * paragraph.
 */
internal actual fun resolveBidiPlatform(
    text: String,
    baseDirection: HbDirection,
): List<BidiRun>? = null
