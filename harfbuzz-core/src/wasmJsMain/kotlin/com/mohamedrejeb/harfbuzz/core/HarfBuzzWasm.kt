@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * JS interop helpers for crossing the Kotlin/Wasm ↔ JS boundary.
 *
 * All HarfBuzz state lives inside the [HbWorker] singleton (see
 * `native/harfbuzzjs/hb-worker.js`); main-thread code never touches
 * `hb.wasm` directly. This file only retains the small generic helpers
 * needed to build/decode the JSON-shaped payloads sent across the
 * worker postMessage channel.
 */

// ─────────────────────────────────────────────────────────────────────────
// JsArray helpers
// ─────────────────────────────────────────────────────────────────────────

@JsFun("(arr) => arr.length")
internal external fun jsArrayLength(arr: JsAny): Int

@JsFun("(arr, i) => arr[i]")
internal external fun jsArrayGet(arr: JsAny, i: Int): JsAny

internal fun <T : JsAny> JsArray<T>.size(): Int = jsArrayLength(this)

@Suppress("UNCHECKED_CAST")
internal fun <T : JsAny> JsArray<T>.at(i: Int): T = jsArrayGet(this, i) as T

@JsFun("(arr, i) => arr[i]")
internal external fun jsArrayGetF32(arr: JsAny, i: Int): Float

@JsFun("(arr, i) => arr[i]")
internal external fun jsInt32ArrayGet(arr: JsAny, i: Int): Int

@JsFun("(arr) => arr.length")
internal external fun jsTypedArrayLength(arr: JsAny): Int

@JsFun("(arr, i) => arr[i] & 0xFF")
internal external fun jsUint8ArrayGet(arr: JsAny, i: Int): Int

@OptIn(UnsafeWasmMemoryApi::class)
internal fun jsUint8ArrayToByteArray(arr: JsAny): ByteArray {
    val n = jsTypedArrayLength(arr)
    if (n == 0) return EMPTY_BYTE_ARRAY
    // Bulk-copy via wasm linear memory: JS does one native `set()` from
    // the typed array into a view of wasm memory, and the Kotlin side
    // unpacks 4 bytes per `loadInt` instead of one byte per
    // `loadByte`. The init-lambda variant
    // (`ByteArray(n) { (ptr + it).loadByte() }`) crosses the closure
    // ABI per byte and is the dominant cost for kilobyte+ payloads;
    // chunking quarters the iteration count and amortises the wasm
    // load over four byte writes.
    //
    // The older Latin-1 string detour scaled to >1 s for 4 × 3 MB SVG
    // documents because of String allocation + UTF-16 traversal cost;
    // both are gone now.
    return withScopedMemoryAllocator { alloc ->
        val ptr = alloc.allocate(n)
        jsCopyUint8ArrayToWasmMemory(arr, ptr.address.toInt())
        val out = ByteArray(n)
        val whole = n ushr 2
        for (q in 0 until whole) {
            val w = (ptr + q * 4).loadInt()
            val o = q * 4
            // Wasm is little-endian: byte at addr+0 == low byte of the
            // i32 at addr. Int.toByte() truncates to the low 8 bits, so
            // no explicit `and 0xFF` is needed.
            out[o]     = w.toByte()
            out[o + 1] = (w ushr 8).toByte()
            out[o + 2] = (w ushr 16).toByte()
            out[o + 3] = (w ushr 24).toByte()
        }
        var i = whole * 4
        while (i < n) {
            out[i] = (ptr + i).loadByte()
            i++
        }
        out
    }
}

@JsFun(
    """
    (arr, dstAddr) => {
        const n = arr.length;
        if (n > 0) {
            new Uint8Array(wasmExports.memory.buffer, dstAddr, n).set(arr);
        }
    }
    """
)
private external fun jsCopyUint8ArrayToWasmMemory(arr: JsAny, dstAddr: Int)

private val EMPTY_BYTE_ARRAY = ByteArray(0)

// ─────────────────────────────────────────────────────────────────────────
// Kotlin → JS typed-array conversions
// ─────────────────────────────────────────────────────────────────────────

// Bulk-copy a Kotlin ByteArray into a fresh JS Uint8Array (with its own
// ArrayBuffer) by exposing a slice of Kotlin/Wasm linear memory to JS.
// Compared to the base64 path this drops two ~N-sized intermediate
// allocations + the encode/decode loops; it's the dominant byte-transfer
// cost for big fonts (e.g. NotoColorEmoji is ~25 MB). The scoped
// allocator releases the staging region as soon as the JS side has
// memcpy'd into its own buffer.
@OptIn(UnsafeWasmMemoryApi::class)
internal fun ByteArray.toJsUint8Array(): JsAny {
    if (isEmpty()) return jsCopyWasmMemoryToFreshUint8Array(0, 0)
    return withScopedMemoryAllocator { alloc ->
        val ptr = alloc.allocate(this.size)
        for (i in indices) (ptr + i).storeByte(this[i])
        jsCopyWasmMemoryToFreshUint8Array(ptr.address.toInt(), this.size)
    }
}

@JsFun(
    """
    (srcAddr, len) => {
        const buf = new ArrayBuffer(len);
        if (len > 0) {
            new Uint8Array(buf).set(
                new Uint8Array(wasmExports.memory.buffer, srcAddr, len)
            );
        }
        return new Uint8Array(buf);
    }
    """
)
internal external fun jsCopyWasmMemoryToFreshUint8Array(srcAddr: Int, len: Int): JsAny

// Bulk-copy a JS Int32Array straight into Kotlin/Wasm linear memory at
// [dstAddr]. Caller owns the wasm-side allocation (typically via
// `withScopedMemoryAllocator`) and is responsible for stride =
// `arr.length * 4`. Replaces N×`jsInt32ArrayGet` per array on the
// Kotlin side - the per-element JS→Wasm boundary is the dominant cost
// when decoding shape replies (3 ints/glyph, hundreds of glyphs per
// paragraph).
@JsFun(
    """
    (arr, dstAddr) => {
        const n = arr.length;
        if (n > 0) {
            new Int32Array(wasmExports.memory.buffer, dstAddr, n).set(arr);
        }
    }
    """
)
internal external fun jsCopyInt32ArrayToWasmMemory(arr: JsAny, dstAddr: Int)

@JsFun(
    """
    (arr, dstAddr) => {
        const n = arr.length;
        if (n > 0) {
            new Float32Array(wasmExports.memory.buffer, dstAddr, n).set(arr);
        }
    }
    """
)
internal external fun jsCopyFloat32ArrayToWasmMemory(arr: JsAny, dstAddr: Int)

@OptIn(UnsafeWasmMemoryApi::class)
internal fun jsInt32ArrayToIntArray(arr: JsAny): IntArray {
    val n = jsTypedArrayLength(arr)
    if (n == 0) return EMPTY_INT_ARRAY
    return withScopedMemoryAllocator { alloc ->
        val ptr = alloc.allocate(n * 4)
        jsCopyInt32ArrayToWasmMemory(arr, ptr.address.toInt())
        IntArray(n) { (ptr + it * 4).loadInt() }
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
internal fun jsFloat32ArrayToFloatArray(arr: JsAny): FloatArray {
    val n = jsTypedArrayLength(arr)
    if (n == 0) return EMPTY_FLOAT_ARRAY
    return withScopedMemoryAllocator { alloc ->
        val ptr = alloc.allocate(n * 4)
        jsCopyFloat32ArrayToWasmMemory(arr, ptr.address.toInt())
        FloatArray(n) { Float.fromBits((ptr + it * 4).loadInt()) }
    }
}

private val EMPTY_INT_ARRAY = IntArray(0)
private val EMPTY_FLOAT_ARRAY = FloatArray(0)

/**
 * Build a JS `Int32Array` from a Kotlin [IntArray]. We base64-encode the
 * underlying bytes because crossing the wasm boundary as a contiguous
 * typed array is faster than element-by-element JsArray conversion for
 * medium-large sizes.
 */
@JsFun(
    """
    (b64) => {
        const bin = atob(b64);
        const buf = new ArrayBuffer(bin.length);
        const bytes = new Uint8Array(buf);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new Int32Array(buf);
    }
    """
)
internal external fun base64ToInt32Array(b64: JsString): JsAny

@OptIn(ExperimentalEncodingApi::class)
internal fun IntArray.toJsInt32Array(): JsAny {
    val bytes = ByteArray(size * 4)
    for (i in indices) {
        val v = this[i]
        bytes[i * 4 + 0] = (v and 0xFF).toByte()
        bytes[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
    }
    return base64ToInt32Array(Base64.Default.encode(bytes).toJsString())
}

@JsFun(
    """
    (b64) => {
        const bin = atob(b64);
        const buf = new ArrayBuffer(bin.length);
        const bytes = new Uint8Array(buf);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new Float32Array(buf);
    }
    """
)
internal external fun base64ToFloat32Array(b64: JsString): JsAny

@OptIn(ExperimentalEncodingApi::class)
internal fun FloatArray.toJsFloat32Array(): JsAny {
    val bytes = ByteArray(size * 4)
    for (i in indices) {
        val bits = this[i].toRawBits()
        bytes[i * 4 + 0] = (bits and 0xFF).toByte()
        bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    return base64ToFloat32Array(Base64.Default.encode(bytes).toJsString())
}
