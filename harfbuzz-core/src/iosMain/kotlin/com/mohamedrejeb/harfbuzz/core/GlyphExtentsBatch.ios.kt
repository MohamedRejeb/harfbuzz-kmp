package com.mohamedrejeb.harfbuzz.core

/**
 * iOS has no border-cross to amortise - cinterop calls compile down to a
 * direct C function call. Returning null here defers to the commonMain
 * loop, which is already the optimal path on this platform.
 */
internal actual suspend fun HbFont.tryGlyphExtentsBatchNative(
    glyphIds: IntArray,
): List<GlyphExtents?>? = null
