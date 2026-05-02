package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbFace

/**
 * Process-wide cache of parsed [HbFace] keyed by `bytes.contentHashCode()`.
 * A font-size animation that re-runs `Res.readBytes(...)` and friends
 * gets the same parsed face on every call instead of re-parsing.
 *
 * **Lifecycle.** Cached faces are owned by this cache, not by the caller
 * - they live for the process lifetime (or until [clear]). Callers that
 * obtain a face via [get] must NOT close it. The compose-layer
 * `rememberHbFont` / `rememberHbFace` wire-ups skip the disposal
 * `face.close()` accordingly. Direct [HbFace.fromBytes] callers
 * outside the compose layer are unaffected - they still get an owned
 * face with the usual close semantics.
 *
 * **Eviction.** Bounded at [MAX_ENTRIES] with FIFO discard. Evicted
 * entries are dropped from the map but **not closed** - another
 * caller might still hold the reference. The native handle leaks
 * until process exit; with the cap at 16 and typical real-app face
 * counts well under that, the leak is bounded by a handful of small
 * native handles. If memory pressure ever surfaces, switch to
 * weak-ref semantics here.
 *
 * **Threading.** Reads + writes serialise via [HarfbuzzDispatcher]
 * elsewhere in the codebase (the compose remember chains all hop
 * through `runShapingWork`); the in-cache state isn't externally
 * synchronised. Worst-case overlap: two coroutines miss for the same
 * key concurrently and both parse - last writer wins, the loser's
 * face leaks until its caller holds no more references and GC
 * eventually collects it. Acceptable trade-off vs. carrying a
 * `Mutex` through every face load.
 *
 * **Key fingerprint.** Bytes are fingerprinted by their `size` plus a
 * hash of the first [PREFIX_BYTES] bytes - the OpenType file header
 * and table directory live in the first ~256 bytes, so this discriminates
 * distinct fonts reliably without paying O(n) per cache lookup. A full
 * `bytes.contentHashCode()` over a typical 150 KB font is ~150 µs on
 * JVM, more than the bare `HbFace.fromBytes` parse it's trying to
 * amortise; the prefix-only fingerprint is O(1) at sub-microsecond cost.
 *
 * Collision risk: 2^32 fingerprint space crossed with prefix-only
 * sampling; in practice every font ships a unique 4-byte sfnt version
 * + table directory, so two distinct fonts colliding here is
 * vanishingly improbable. If it ever surfaces as wrong glyphs, switch
 * to SHA-256 of the prefix or extend [PREFIX_BYTES].
 */
internal object HbFaceCache {
    private const val MAX_ENTRIES = 16

    /**
     * Number of leading bytes hashed for the cache fingerprint. 256 is
     * enough to cover the OpenType header (12 B) plus a populated
     * `numTables × 16 B` table directory (typical fonts: 10–15 tables).
     */
    private const val PREFIX_BYTES = 256

    /**
     * Key combines a cheap content fingerprint and `faceIndex` so a
     * `.ttc` collection can cache its individual faces independently.
     */
    private data class Key(val fingerprint: Long, val faceIndex: Int)

    private val entries = LinkedHashMap<Key, HbFace>()

    /**
     * Return the cached [HbFace] for [bytes] / [faceIndex] or parse +
     * cache + return on first miss. Cached faces are read-only from
     * the caller's perspective - never call [HbFace.close] on them.
     */
    suspend fun get(bytes: ByteArray, faceIndex: Int = 0): HbFace {
        val key = Key(fingerprintOf(bytes), faceIndex)
        entries[key]?.let { cached ->
            if (!cached.isClosed) return cached
            // Defensive: a cached face was closed externally (shouldn't
            // happen if the contract is followed). Drop the stale entry
            // and re-parse below.
            entries.remove(key)
        }
        val face = HbFace.fromBytes(bytes, faceIndex)
        if (entries.size >= MAX_ENTRIES) {
            val it = entries.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        entries[key] = face
        return face
    }

    /**
     * Drop every cached face. Doesn't close the underlying handles -
     * see the class doc for why. Intended for tests; production code
     * relies on process-lifetime caching.
     */
    fun clear() {
        entries.clear()
    }

    internal fun sizeForTest(): Int = entries.size

    /**
     * Cheap content fingerprint: `(size, hash of first PREFIX_BYTES)`.
     * Packed into a Long so the cache key is one allocation per get.
     */
    private fun fingerprintOf(bytes: ByteArray): Long {
        val size = bytes.size
        val limit = if (size < PREFIX_BYTES) size else PREFIX_BYTES
        var h = 1
        var i = 0
        while (i < limit) {
            h = 31 * h + bytes[i].toInt()
            i++
        }
        return (size.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
    }
}

/** Test-only handle to drop cache state between cases. */
internal fun clearHbFaceCacheForTest() {
    HbFaceCache.clear()
}
