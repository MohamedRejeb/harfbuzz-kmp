@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.await

/**
 * Wasm test-time helper that fetches font bytes via the browser's `fetch`
 * API. The bytes are served by the karma test bundle (or by a proxy
 * shim that maps `/test-fonts/...` to a static directory).
 *
 * Today the karma harness in this repo doesn't yet serve static binary
 * assets to the test browser - the same blocker described in commit
 * `14832ce` for `hb.wasm`. Once that shim lands, this helper resolves a
 * Roboto-Regular byte array and the [WorkerInitTest] gates the worker
 * round-trip end-to-end. Until then the test compiles cleanly and runs
 * successfully whenever the bytes can actually be served.
 */
internal object TestFontsWasm {
    private const val ROBOTO_REGULAR_URL = "test-fonts/Roboto-Regular.ttf"

    suspend fun robotoRegular(): ByteArray {
        val response = jsFetch(ROBOTO_REGULAR_URL).await<JsAny>()
        val arrayBuffer = jsResponseArrayBuffer(response).await<JsAny>()
        return jsArrayBufferToByteArray(arrayBuffer)
    }
}

@JsFun("(url) => fetch(url).then(r => { if (!r.ok) throw new Error('fetch failed: ' + r.status); return r; })")
private external fun jsFetch(url: String): kotlin.js.Promise<JsAny>

@JsFun("(r) => r.arrayBuffer()")
private external fun jsResponseArrayBuffer(response: JsAny): kotlin.js.Promise<JsAny>

@JsFun(
    """
    (buf) => {
        const bytes = new Uint8Array(buf);
        let bin = '';
        for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
        return btoa(bin);
    }
    """,
)
private external fun jsArrayBufferToBase64(buf: JsAny): JsString

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun jsArrayBufferToByteArray(buf: JsAny): ByteArray {
    val b64 = jsArrayBufferToBase64(buf).toString()
    return kotlin.io.encoding.Base64.Default.decode(b64)
}
