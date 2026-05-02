package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbFace

/**
 * Process-wide cache of rasterised OT-SVG glyph bitmaps, keyed by
 * `(face, glyphId, pointSize)`. The same emoji glyph used across
 * many paragraphs is rasterised once instead of N times - the
 * Skia SVGDOM parse + paint that dominates SVG-glyph build time
 * (~tens of ms per glyph for hand-tuned color fonts) becomes a
 * map lookup on the second and subsequent paragraphs.
 *
 * Cache misses store the [SvgGlyphRender] (or `null` if the glyph
 * is empty / failed to render) so a malformed subtree is only
 * attempted once.
 *
 * Eviction: bounded by [MAX_ENTRIES] with FIFO discard. Pure-Kotlin
 * [LinkedHashMap] doesn't support access-order across all KMP
 * targets, and FIFO under steady-state usage with a working set
 * smaller than the cap is indistinguishable from LRU. The cap is
 * sized for ~1024 distinct emoji glyphs at typical UI sizes -
 * generous enough to cover NotoColorEmoji's full glyph table for
 * a single point size.
 *
 * Threading: this slow path runs on the HarfBuzz background
 * dispatcher (`harfbuzz-bg` thread on JVM/Android; parallelism-1
 * on iOS). Wasm reaches its own worker fast path before this code
 * runs, so the cache is single-threaded in practice and uses no
 * locks. Worst-case overlap (two coroutines suspending mid-render
 * on the same key) duplicates one raster - acceptable.
 *
 * Memory: a 16 px-em-half emoji bitmap is ~48×48 RGBA8 (~9 KB).
 * At [MAX_ENTRIES] = 1024 the worst-case is ~9 MB. Callers under
 * memory pressure can call [clear] to drop everything; on Android
 * a `ComponentCallbacks2.onTrimMemory` hook in the consumer app
 * is the natural place.
 */
internal object SvgBitmapCache {
    private const val MAX_ENTRIES = 1024

    private data class Key(
        val face: HbFace,
        val glyphId: Int,
        val pointSize: Float,
    )

    private val entries = LinkedHashMap<Key, SvgGlyphRender?>()

    suspend fun getOrRasterize(
        face: HbFace,
        glyphId: Int,
        svgBytes: ByteArray,
        pointSize: Float,
        upem: Int,
    ): SvgGlyphRender? {
        val key = Key(face, glyphId, pointSize)
        // containsKey rather than `entries[key] ?: ...` so a cached null
        // (failed-to-parse subtree) is treated as a hit and not retried.
        if (entries.containsKey(key)) return entries[key]

        val rendered = renderSvgGlyph(svgBytes = svgBytes, pointSize = pointSize, upem = upem)
        if (entries.size >= MAX_ENTRIES) {
            val it = entries.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        entries[key] = rendered
        return rendered
    }

    fun clear() {
        entries.clear()
    }
}
