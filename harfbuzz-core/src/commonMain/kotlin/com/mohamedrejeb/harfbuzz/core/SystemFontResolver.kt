package com.mohamedrejeb.harfbuzz.core

/**
 * Platform indirection for [SystemFallback.Match]. Owns any system fonts
 * it loads on behalf of an [HbFontStack]; closing the stack closes the
 * resolver and releases every font it materialised.
 *
 * Implementations cache resolved `(codepoint → HbFont?)` mappings so
 * the same Arabic letter or emoji codepoint doesn't trigger a fresh
 * platform lookup on every call.
 *
 * Returns `null` from [fontFor] when the platform has no system font
 * covering [codepoint] under the configured style/language preferences,
 * or when no platform support is available at all (Wasm).
 */
internal interface SystemFontResolver : AutoCloseable {
    /**
     * Find a system font that covers [codepoint] at [pointSize], or
     * `null` if none is available. Returned [HbFont] instances are
     * owned by this resolver - do not close them directly; closing
     * the resolver closes them all.
     */
    suspend fun fontFor(codepoint: Int, pointSize: Float): HbFont?

    /**
     * Pre-resolve every codepoint in [codepoints] so the resolver's
     * cache (and the OS page cache for any system font it touches) is
     * warm by the time the first user-visible paragraph encounters
     * one of these scripts. Each `fontFor` call is the same one the
     * normal cover walk would make; results just stay in the resolver
     * cache instead of being returned.
     *
     * Default impl loops [fontFor] sequentially. Platforms that don't
     * benefit (iOS uses the CoreText cascade; Wasm has no system font
     * access) override to no-op.
     */
    suspend fun prewarm(codepoints: IntArray, pointSize: Float) {
        for (cp in codepoints) fontFor(cp, pointSize)
    }
}

/**
 * Construct the platform's [SystemFontResolver] for [match]. Returns
 * `null` on platforms that have no system-font access (Wasm) or cannot
 * support the requested options. The returned resolver is cold: it
 * doesn't enumerate fonts until [SystemFontResolver.fontFor] is called.
 */
internal expect fun createSystemFontResolver(match: SystemFallback.Match): SystemFontResolver?
