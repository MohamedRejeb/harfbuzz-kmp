@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * Wasm SVG-in-OT rasteriser using the browser's native SVG pipeline via
 * `createImageBitmap(Blob)`. The Promise body runs in Chromium's image
 * decode service (off the JS main thread), so awaiting it doesn't pin
 * main - the only main-thread work left here is wrapping the SVG bytes
 * in an outer `<svg width=… height=… viewBox=…>` (so the browser
 * rasterises at exactly the size we need) plus a ~30 KB pixel copy out
 * of an `OffscreenCanvas` 2D context into a Skia [Bitmap] (since
 * Compose's [androidx.compose.ui.graphics.ImageBitmap] on Wasm is
 * skiko-backed).
 *
 * Replaces the skiko `SVGDOM` path used on JVM/iOS - that path was the
 * dominant cost in the post-build longtask (~230 ms per glyph for
 * NotoColorEmoji) because skiko's SVG parser is pure-Kotlin-on-Wasm
 * and the actual raster runs on the only thread that has GL state.
 *
 * The Skia [Bitmap] is configured as `RGBA_8888` UNPREMUL to match
 * Canvas2D `getImageData` byte order exactly - no per-pixel byte
 * swizzle or premultiplication needed in Kotlin.
 */
internal actual suspend fun renderSvgGlyph(
    svgBytes: ByteArray,
    pointSize: Float,
    upem: Int,
    glyphHalfEm: Float,
): SvgGlyphRender? {
    if (svgBytes.isEmpty() || pointSize <= 0f || upem <= 0) return null
    val sidePx = max(1, ceil(pointSize * 2f * glyphHalfEm).toInt())
    val designSpan = upem.toFloat() * glyphHalfEm

    // The worker-sliced bytes start with the slicer's static SVG header
    // (no width/height/viewBox); the worker-fallback "full document"
    // path may carry a viewBox we want to override anyway because
    // OT-SVG paint coords are in design units regardless of any embedded
    // viewport. Either way, replacing the outer <svg ...> with one we
    // build here is the correct knob.
    val wrapped = rebuildSvgWithViewport(svgBytes, sidePx, designSpan) ?: return null

    val jsBitmap = jsCreateImageBitmapFromSvg(wrapped) ?: return null
    val pixels = try {
        jsExtractPixelsFromImageBitmap(jsBitmap, sidePx, sidePx) ?: return null
    } finally {
        jsCloseImageBitmap(jsBitmap)
    }

    val info = ImageInfo(
        colorInfo = ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
        width = sidePx,
        height = sidePx,
    )
    val bitmap = Bitmap()
    if (!bitmap.installPixels(info, pixels, sidePx * 4)) {
        bitmap.close()
        return null
    }

    return SvgGlyphRender(
        bitmap = bitmap.asComposeImageBitmap(),
        designOffsetX = -designSpan,
        designOffsetY = -designSpan,
        designWidth = designSpan * 2f,
        designHeight = designSpan * 2f,
    )
}

// ─── SVG header rebuild ───────────────────────────────────────────────

private val SVG_CLOSE_TAG_BYTES: ByteArray = "</svg>".encodeToByteArray()

/**
 * Drop the original `<svg ...>` opening tag (and matching closing
 * `</svg>`) and rewrap the inner content in a fresh `<svg>` whose
 * width/height match our target raster size and whose viewBox is in
 * design-unit coordinates centered on the glyph origin. This is the
 * exact transform skiko's [SvgGlyphRenderer.skiko] applies via canvas
 * translate + scale.
 */
private fun rebuildSvgWithViewport(
    svgBytes: ByteArray,
    sidePx: Int,
    designSpan: Float,
): ByteArray? {
    val openEnd = indexOfByte(svgBytes, '>'.code.toByte(), 0)
    if (openEnd < 0) return null

    val closeStart = svgBytes.size - SVG_CLOSE_TAG_BYTES.size
    if (closeStart <= openEnd) return null
    for (i in SVG_CLOSE_TAG_BYTES.indices) {
        if (svgBytes[closeStart + i] != SVG_CLOSE_TAG_BYTES[i]) return null
    }

    val viewBoxLow = -designSpan
    val viewBoxSize = designSpan * 2f
    val newHead = (
        "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "width=\"$sidePx\" height=\"$sidePx\" " +
            "viewBox=\"$viewBoxLow $viewBoxLow $viewBoxSize $viewBoxSize\">"
        ).encodeToByteArray()
    val innerLen = closeStart - (openEnd + 1)
    val out = ByteArray(newHead.size + innerLen + SVG_CLOSE_TAG_BYTES.size)
    var pos = 0
    newHead.copyInto(out, pos); pos += newHead.size
    svgBytes.copyInto(out, pos, openEnd + 1, closeStart); pos += innerLen
    SVG_CLOSE_TAG_BYTES.copyInto(out, pos)
    return out
}

private fun indexOfByte(bytes: ByteArray, target: Byte, from: Int): Int {
    val n = bytes.size
    var i = from
    while (i < n) {
        if (bytes[i] == target) return i
        i++
    }
    return -1
}

// ─── createImageBitmap bridge ─────────────────────────────────────────

/**
 * Hands [svgBytes] to the browser's native SVG renderer via
 * `createImageBitmap(Blob)`. The Promise resolves to an `ImageBitmap`
 * whose internal pixels we can read with `drawImage` + `getImageData`.
 * Returns null when SVG parsing fails (the Promise rejects).
 */
private suspend fun jsCreateImageBitmapFromSvg(svgBytes: ByteArray): JsAny? =
    suspendCancellableCoroutine { cont ->
        val arr = svgBytes.toJsUint8ArrayLocal()
        jsCreateImageBitmapFromSvgRaw(
            arr,
            onSuccess = { bm -> if (cont.isActive) cont.resume(bm) },
            onError = { if (cont.isActive) cont.resume(null) },
        )
    }

@JsFun(
    """
    (uint8Array, onSuccess, onError) => {
        // Chrome 147+ rejects createImageBitmap(svgBlob) with
        // InvalidStateError ("source image could not be decoded"). Going
        // through an <img> element with a blob URL is the reliable path:
        // the browser's HTML image pipeline accepts SVG, and the loaded
        // <img> is a valid createImageBitmap source.
        try {
            const blob = new Blob([uint8Array], { type: 'image/svg+xml' });
            const url = URL.createObjectURL(blob);
            const img = new Image();
            img.onload = () => {
                createImageBitmap(img).then(
                    (bm) => { URL.revokeObjectURL(url); onSuccess(bm); },
                    (_e) => { URL.revokeObjectURL(url); onError(); }
                );
            };
            img.onerror = () => { URL.revokeObjectURL(url); onError(); };
            img.src = url;
        } catch (_e) {
            onError();
        }
    }
    """
)
private external fun jsCreateImageBitmapFromSvgRaw(
    uint8Array: JsAny,
    onSuccess: (JsAny) -> Unit,
    onError: () -> Unit,
)

/**
 * Draws [imageBitmap] into a fresh [w] × [h] OffscreenCanvas 2D context
 * and reads back the pixels as RGBA8 unpremul. Returns null when the
 * 2D context can't be acquired (very old browsers).
 */
private fun jsExtractPixelsFromImageBitmap(
    imageBitmap: JsAny,
    w: Int,
    h: Int,
): ByteArray? {
    val pixelsJs = jsExtractPixelsFromImageBitmapRaw(imageBitmap, w, h) ?: return null
    return jsUint8ArrayToByteArrayLocal(pixelsJs)
}

@JsFun(
    """
    (imageBitmap, w, h) => {
        const canvas = new OffscreenCanvas(w, h);
        const ctx = canvas.getContext('2d');
        if (!ctx) return null;
        ctx.drawImage(imageBitmap, 0, 0);
        return new Uint8Array(ctx.getImageData(0, 0, w, h).data.buffer);
    }
    """
)
private external fun jsExtractPixelsFromImageBitmapRaw(
    imageBitmap: JsAny,
    w: Int,
    h: Int,
): JsAny?

@JsFun("(bm) => { try { bm.close(); } catch (_e) {} }")
private external fun jsCloseImageBitmap(bm: JsAny)

// ─── ByteArray ↔ JS Uint8Array helpers ────────────────────────────────
// Local copies of the same bridges in HarfBuzzWasm.kt - kept private to
// this file rather than exposed through harfbuzz-core's public API. They
// use chunked i32 loads for the JS→Wasm direction (matches the decoder's
// jsUint8ArrayToByteArray) and a scoped allocator + JS-side `set()` for
// the Wasm→JS direction.

@OptIn(UnsafeWasmMemoryApi::class)
private fun ByteArray.toJsUint8ArrayLocal(): JsAny {
    if (isEmpty()) return jsAllocFreshUint8Array(0, 0)
    return withScopedMemoryAllocator { alloc ->
        val ptr = alloc.allocate(this.size)
        for (i in indices) (ptr + i).storeByte(this[i])
        jsAllocFreshUint8Array(ptr.address.toInt(), this.size)
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
private external fun jsAllocFreshUint8Array(srcAddr: Int, len: Int): JsAny

@JsFun("(arr) => arr.length")
private external fun jsTypedArrayLength(arr: JsAny): Int

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

@OptIn(UnsafeWasmMemoryApi::class)
private fun jsUint8ArrayToByteArrayLocal(arr: JsAny): ByteArray {
    val n = jsTypedArrayLength(arr)
    if (n == 0) return EMPTY_BYTE_ARRAY
    return withScopedMemoryAllocator { alloc ->
        val ptr = alloc.allocate(n)
        jsCopyUint8ArrayToWasmMemory(arr, ptr.address.toInt())
        val out = ByteArray(n)
        val whole = n ushr 2
        for (q in 0 until whole) {
            val w = (ptr + q * 4).loadInt()
            val o = q * 4
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

private val EMPTY_BYTE_ARRAY = ByteArray(0)
