package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbFont

/**
 * Process-wide LRU of parsed glyph [Path] outlines, keyed on
 * `(font, glyphId, sizePx, flipY)`. Promotes the per-`MeasuredText`
 * parse cache previously held by [LazyGlyphPathMap] into a shared
 * singleton so two `MeasuredText` instances at the same
 * `(font, sizePx)` reuse parsed paths instead of each paying the SVG
 * to Compose [Path] decode (which on Android is the per-glyph hot spot:
 * SVG string -> `androidx.compose.ui.graphics.Path` -> `android.graphics.Path`
 * JNI bridge).
 *
 * **Cross-size reuse caveat.** Glyph SVG strings come from
 * `font.snapshotGlyphs(sizePx, ...)` which scales coordinates to the
 * requested pixel size. Different `sizePx` values produce different
 * SVG bytes and therefore different parsed paths, so this cache does
 * not deduplicate across font-size buckets. The structural fix for
 * cross-size reuse (parse once at design coordinates, scale at draw)
 * is left as a follow-up - see the perf plan.
 *
 * Implementation mirrors [MeasuredTextCache]: [LinkedHashMap] under
 * FIFO eviction, no locks. Glyph caches populate on the draw thread
 * inside `LazyGlyphPathMap.get`; Compose draw is single-threaded per
 * frame, so worst-case overlap duplicates work for one entry.
 */
internal object GlyphPathCache {
    private const val MAX_ENTRIES = 256

    internal data class Key(
        val font: HbFont,
        val glyphId: Int,
        val sizePx: Float,
        val flipY: Boolean,
    )

    private val entries = LinkedHashMap<Key, Path>()

    fun get(key: Key): Path? = entries[key]

    fun put(key: Key, path: Path) {
        if (!entries.containsKey(key) && entries.size >= MAX_ENTRIES) {
            val it = entries.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        entries[key] = path
    }

    fun clear() {
        entries.clear()
    }

    internal fun sizeForTest(): Int = entries.size
}

/** Test-only handle to drop cache state between cases. */
internal fun clearGlyphPathCacheForTest() {
    GlyphPathCache.clear()
}
