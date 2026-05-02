package com.mohamedrejeb.harfbuzz.core

/**
 * Platform indirection for the UAX#9 bidirectional algorithm. Returns
 * the platform-resolved runs in **logical** order, or `null` to signal
 * that the caller should fall back to [BidiResolver]'s pure-Kotlin
 * pipeline.
 *
 * JVM + Android route through `java.text.Bidi` (ICU-backed in the JDK
 * and on Android) - fast, correct, includes paired-bracket pairing
 * (UAX#9 N0), explicit embedding controls, and L1-L4 reordering.
 *
 * iOS + Wasm return `null` and inherit the pure-Kotlin path. Kotlin/Native
 * doesn't ship a Bidi binding for free; the cinterop+CoreFoundation
 * detour costs more than our pragmatic resolver delivers, so we keep
 * the pure-Kotlin path there until profiling justifies otherwise.
 */
internal expect fun resolveBidiPlatform(
    text: String,
    baseDirection: HbDirection,
): List<BidiRun>?
