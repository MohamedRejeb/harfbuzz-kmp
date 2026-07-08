package com.mohamedrejeb.harfbuzz.core

import kotlin.concurrent.Volatile

/**
 * Shared identity `IntArray(n) { it }` cache.
 *
 * `ShapedParagraph.logicalToVisual` and `visualToLogical` are populated
 * with `IntArray(n) { it }` for every shape call today (the BiDi visual
 * reorder maps are not yet wired up). Until then the arrays are
 * write-only identity tables, but Kotlin allocates and fills them on
 * every paragraph build (~10 KB per 2 500-char paragraph, × 2 maps).
 *
 * Routing those allocations through this cache makes paragraphs of the
 * same length share a single underlying `IntArray`, so a scrolling list
 * full of similar-length labels stops thrashing the GC. The cached
 * arrays are immutable in spirit - callers must NOT write into them.
 * (None do today; the public `ShapedParagraph` getters return the same
 * reference and downstream code only reads.)
 *
 * Bounded at [MAX_ENTRIES] with FIFO eviction so a workload that hits
 * many distinct paragraph lengths doesn't pin unbounded native memory.
 * Evicted entries are dropped from the map but remain valid for any
 * paragraph that still holds a reference - re-using a freshly allocated
 * array on the next request for that length is fine, identity arrays
 * compare equal regardless of identity.
 */
internal object IdentityIntArrayCache {
    private const val MAX_ENTRIES = 64
    private val EMPTY = IntArray(0)

    /**
     * Copy-on-write snapshot. Consumers shape paragraphs from multiple
     * threads concurrently, so this global cache cannot assume the
     * single-threaded shaping dispatcher (seen in production as
     * ConcurrentModificationException from [get]). Readers only touch
     * the immutable published snapshot; a writer copies, mutates the
     * copy, and republishes. Racing writers lose at worst one cache
     * entry (a redundant allocation next call) — never a corrupt map.
     */
    @Volatile
    private var entries: LinkedHashMap<Int, IntArray> = LinkedHashMap()

    fun get(length: Int): IntArray {
        if (length <= 0) return EMPTY
        val snapshot = entries
        snapshot[length]?.let { return it }
        val next = LinkedHashMap(snapshot)
        if (next.size >= MAX_ENTRIES) {
            val it = next.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        val arr = IntArray(length) { it }
        next[length] = arr
        entries = next
        return arr
    }

    internal fun sizeForTest(): Int = entries.size

    internal fun clearForTest() {
        entries = LinkedHashMap()
    }
}

/** Convenience wrapper - replaces `IntArray(n) { it }` at every paragraph build site. */
internal fun identityIntArray(length: Int): IntArray = IdentityIntArrayCache.get(length)
