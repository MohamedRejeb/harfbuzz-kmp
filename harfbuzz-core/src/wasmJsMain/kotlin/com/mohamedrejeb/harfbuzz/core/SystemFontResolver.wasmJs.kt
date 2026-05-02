package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm runs in a sandboxed browser context with no access to the user's
 * installed fonts. [SystemFallback.Match] is therefore a no-op here: the
 * resolver always returns `null`, and [HbFontStack] falls back to the
 * explicit chain in [HbFontStack.fallbacks] only.
 *
 * Web callers wanting multi-script support must bundle fallback fonts
 * with their app and pass them to [HbFontStack].
 */
internal actual fun createSystemFontResolver(match: SystemFallback.Match): SystemFontResolver? {
    @Suppress("UNUSED_PARAMETER") val unused = match
    return null
}
