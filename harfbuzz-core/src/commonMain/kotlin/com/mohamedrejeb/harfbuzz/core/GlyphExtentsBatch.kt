package com.mohamedrejeb.harfbuzz.core

/**
 * Batched per-glyph extents fetch. Returns one entry per id in [glyphIds]
 * (parallel to input order); a `null` entry means HarfBuzz returned false
 * for that glyph (typically `.notdef` or composite-with-no-component
 * edge cases).
 *
 * Platforms that expose a native-side batch primitive (JVM/Android via
 * JNI) take the fast path through [tryGlyphExtentsBatchNative]: one
 * border-cross instead of `glyphIds.size`. Other platforms (iOS,
 * Wasm) fall back to looping [HbFont.glyphExtents]; invoking from
 * inside `runShapingWork { … }` still amortises the per-call
 * dispatcher hop.
 */
public suspend fun HbFont.glyphExtentsBatch(glyphIds: IntArray): List<GlyphExtents?> {
    if (glyphIds.isEmpty()) return emptyList()
    tryGlyphExtentsBatchNative(glyphIds)?.let { return it }
    val out = ArrayList<GlyphExtents?>(glyphIds.size)
    for (gid in glyphIds) {
        out.add(glyphExtents(gid))
    }
    return out
}

/**
 * Platform fast path for [glyphExtentsBatch]. When non-null, the
 * returned list replaces the loop body - every entry is parallel to
 * [glyphIds] and matches what the slow-path loop would produce.
 * Implementations return `null` to defer to the loop fallback.
 */
internal expect suspend fun HbFont.tryGlyphExtentsBatchNative(glyphIds: IntArray): List<GlyphExtents?>?
