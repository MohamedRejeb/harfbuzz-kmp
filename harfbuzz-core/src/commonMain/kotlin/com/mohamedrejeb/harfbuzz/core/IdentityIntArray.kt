package com.mohamedrejeb.harfbuzz.core

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
    private val entries = LinkedHashMap<Int, IntArray>()

    fun get(length: Int): IntArray {
        if (length <= 0) return EMPTY
        entries[length]?.let { return it }
        if (entries.size >= MAX_ENTRIES) {
            val it = entries.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        val arr = IntArray(length) { it }
        entries[length] = arr
        return arr
    }

    internal fun sizeForTest(): Int = entries.size

    internal fun clearForTest() {
        entries.clear()
    }
}

/** Convenience wrapper - replaces `IntArray(n) { it }` at every paragraph build site. */
internal fun identityIntArray(length: Int): IntArray = IdentityIntArrayCache.get(length)
