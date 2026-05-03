package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage

/**
 * Process-wide LRU cache of fully-built [MeasuredText] values, keyed on
 * the layout-affecting inputs only - text, font identity, point size,
 * features, direction, language. Paint-only properties (color, alpha,
 * blend mode, decoration, shadow) never feed the key, so animating them
 * never invalidates the cache.
 *
 * Catches two real workloads that used to re-shape unnecessarily:
 *
 *  - **Recomposition stack churn.** A caller that builds an [HbFontStack]
 *    inline (without `remember`) but holds the inner [HbFont] in
 *    `remember(...)` gets a fresh stack reference each recomposition
 *    while the underlying font references stay stable. Without this
 *    cache, [buildMeasuredText] re-runs the full pipeline; with it,
 *    identical font references hit across distinct stack instances.
 *  - **Cross-component reuse.** A list of headers with the same string +
 *    font shapes once and serves the rest of the screen from the cache.
 *
 * Implementation mirrors [SvgBitmapCache]: a [LinkedHashMap] under FIFO
 * eviction, no locks. Eviction is FIFO rather than access-order because
 * `LinkedHashMap` access-order isn't portable to all KMP targets, and at
 * a 32-entry working set FIFO is indistinguishable from LRU for the
 * workloads this cache targets.
 *
 * Threading: [buildMeasuredText] runs inside `runShapingWork`, which on
 * JVM/Android/iOS pins to a single-threaded `harfbuzzDispatcher`. Worst-
 * case overlap (two coroutines suspending mid-build on the same key)
 * duplicates work for that key - acceptable, last writer wins.
 *
 * Memory: a [MeasuredText] for a typical UI label is on the order of a
 * few KB (paragraph runs + per-glyph path/paint maps the size of the
 * unique-glyph set). At [MAX_ENTRIES] = 32 this is bounded by the
 * largest paragraphs in flight. Callers under memory pressure can call
 * [clearMeasuredTextCacheForTest] (or, in the future, a public eviction
 * hook) to release everything.
 */
internal object MeasuredTextCache {
    private const val MAX_ENTRIES = 32

    private val entries = LinkedHashMap<MeasureKey, MeasuredText>()

    fun get(key: MeasureKey): MeasuredText? = entries[key]

    fun put(key: MeasureKey, value: MeasuredText) {
        if (!entries.containsKey(key) && entries.size >= MAX_ENTRIES) {
            val it = entries.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        entries[key] = value
    }

    fun clear() {
        entries.clear()
    }

    internal fun sizeForTest(): Int = entries.size
}

/** Test-only handle to drop cache state between cases. */
internal fun clearMeasuredTextCacheForTest() {
    MeasuredTextCache.clear()
}

/**
 * Layout-affecting key for [MeasuredTextCache]. Distinct from the
 * draw-time inputs (color/alpha/shadow/blend) which never feed the key
 * - that's the whole point.
 *
 * [fonts] preserves order so that two stacks with the same fonts in
 * different fallback orders don't collide. The list holds [HbFont]
 * references directly: equality is identity-based (`HbFont` doesn't
 * override `equals`), so two distinct font instances built from the
 * same face+pointSize but different variation axes - e.g.
 * `face.toFont(16f, listOf(Variation("wght", 400)))` vs
 * `face.toFont(16f, listOf(Variation("wght", 700)))` - produce
 * different keys and don't collide. The trade-off is that two
 * independently-constructed [HbFont]s with the same configuration
 * never share a cache entry, but the practical caller pattern
 * (`remember(font) { HbFontStack(font) }`) preserves the font
 * reference across recomposition, so the cache still hits when it
 * matters.
 */
internal data class MeasureKey(
    val text: String,
    val fonts: List<HbFont>,
    val sizePx: Float,
    val features: List<HbFeature>,
    val direction: HbDirection,
    val language: HbLanguage,
)

internal fun measureKeyOf(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
): MeasureKey = MeasureKey(
    text = text,
    fonts = fontStack.fonts,
    sizePx = sizePx,
    features = features,
    direction = direction,
    language = language,
)
