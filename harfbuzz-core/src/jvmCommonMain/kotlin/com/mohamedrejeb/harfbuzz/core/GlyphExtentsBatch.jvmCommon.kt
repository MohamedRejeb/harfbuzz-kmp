package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.withContext

/**
 * JVM/Android fast path: one JNI border-cross instead of `glyphIds.size`.
 * The bridge writes 4 floats per glyph; HarfBuzz's "no extents" return
 * is signalled by NaN in the xBearing slot (see
 * `KH_FN(fontGlyphExtentsBatch)` in `native/jni/harfbuzz_jni.cpp`).
 */
internal actual suspend fun HbFont.tryGlyphExtentsBatchNative(
    glyphIds: IntArray,
    sizePx: Float,
): List<GlyphExtents?>? = withContext(harfbuzzDispatcher) {
    check(!isClosed) { "hb object disposed" }
    HarfbuzzNative.fontSetPointSize(ptr, sizePx)
    val n = glyphIds.size
    val out = FloatArray(n * 4)
    HarfbuzzNative.fontGlyphExtentsBatch(ptr, glyphIds, out, n)
    List(n) { i ->
        val base = i * 4
        if (out[base].isNaN()) null
        else GlyphExtents(
            xBearing = out[base],
            yBearing = out[base + 1],
            width = out[base + 2],
            height = out[base + 3],
        )
    }
}
