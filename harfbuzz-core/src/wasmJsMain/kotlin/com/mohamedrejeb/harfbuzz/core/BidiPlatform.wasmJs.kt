package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm leaves bidi resolution to the pure-Kotlin [BidiResolver]. The browser
 * exposes `Intl.Segmenter` for grapheme/word/sentence iteration but no
 * ready-to-use UAX#9 bidi API; falling back to the pure-Kotlin resolver
 * keeps the worker payload small and avoids a JS-bridge round trip.
 */
internal actual fun resolveBidiPlatform(
    text: String,
    baseDirection: HbDirection,
): List<BidiRun>? = null
