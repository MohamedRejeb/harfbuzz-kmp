@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm-side adapter for [PaintBufferParser]. Copies the JS Uint8Array into
 * a Kotlin [ByteArray] and delegates to the shared, byte-array-driven
 * dispatcher in commonMain so the wire format stays decoded by one
 * implementation across every target.
 *
 * Multibyte values are little-endian on Wasm (and on the JNI hosts the
 * format originated from), so no byte-swap is needed.
 */
internal object WasmPaintBufferParser {

    /** Copy the JS Uint8Array into a Kotlin [ByteArray] and dispatch. */
    fun dispatch(buffer: JsAny, sink: HbPaintSink) {
        val bytes = jsUint8ArrayToByteArray(buffer)
        if (bytes.isEmpty()) return
        PaintBufferParser.dispatch(bytes, sink)
    }
}
