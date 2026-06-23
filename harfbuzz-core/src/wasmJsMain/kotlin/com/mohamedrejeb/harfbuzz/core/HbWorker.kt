@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.CompletableDeferred

/**
 * Singleton client for the harfbuzz Web Worker. Lazy-initialized on first
 * [ensureWorkerReady] call. All HB ops on Wasm route through [send]; the
 * worker hosts hb.wasm + the per-handle registries.
 *
 * NOT thread-safe - assumes the single-threaded JS event loop on the main
 * realm. If kotlin-wasm ever exposes isolated workers that share this
 * singleton, the [pending] map and [nextRequestId] need synchronization
 * (or per-realm instances).
 */
internal object HbWorker {

    private var worker: JsAny? = null
    private var nextRequestId: Long = 1L
    private val pending = HashMap<Long, CompletableDeferred<JsAny?>>()
    private val readyDeferred: CompletableDeferred<Unit> = CompletableDeferred()
    private var bootstrapStarted: Boolean = false

    /**
     * Latched when the worker fires a fatal load/runtime error (see
     * [handleWorkerError]). Once set, the singleton stays failed: there is no
     * live worker to recover the per-handle registries from, so every call
     * fails fast with a clear error instead of parking forever.
     */
    private var failure: Throwable? = null

    suspend fun ensureWorkerReady() {
        failure?.let { throw it }
        if (readyDeferred.isCompleted) {
            // Surface any prior init failure to subsequent callers.
            readyDeferred.await()
            return
        }
        if (!bootstrapStarted) {
            bootstrapStarted = true
            try {
                spawnWorker()
                send("init", null)
                readyDeferred.complete(Unit)
            } catch (t: Throwable) {
                readyDeferred.completeExceptionally(t)
                throw t
            }
            return
        }
        // Another coroutine is bootstrapping - wait on its outcome.
        readyDeferred.await()
    }

    /** Send an RPC and await the reply. */
    suspend fun send(type: String, payload: JsAny?, transfer: JsAny? = null): JsAny? {
        failure?.let { throw it }
        val id = nextRequestId++
        val deferred = CompletableDeferred<JsAny?>()
        pending[id] = deferred
        try {
            postRequest(requireWorker(), id, type, payload, transfer)
            return deferred.await()
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    /**
     * Send an RPC without awaiting the reply. Used by `close()` paths so
     * callers stay synchronous; the worker drops the handle when the
     * message arrives. The worker's reply is silently ignored - see
     * [handleReply].
     */
    fun fireAndForget(type: String, payload: JsAny?) {
        val id = nextRequestId++
        postRequest(requireWorker(), id, type, payload, null)
    }

    /** Hooked by the JS-side onmessage handler. */
    private fun handleReply(id: Long, isError: Boolean, payload: JsAny?, errorMessage: String?) {
        // Missing pending entry is normal for fireAndForget RPCs.
        val deferred = pending.remove(id) ?: return
        if (isError) {
            deferred.completeExceptionally(HbException(errorMessage ?: "unknown worker error"))
        } else {
            deferred.complete(payload)
        }
    }

    /**
     * Hooked by the JS-side `worker.onerror` / `onmessageerror`. A worker-level
     * fault (script 404, syntax error, missing dependency, structured-clone
     * failure) means no reply will ever arrive for in-flight RPCs. Reject every
     * pending await so callers get a clear error instead of an infinite hang,
     * and latch [failure] so later calls fail fast.
     */
    private fun handleWorkerError(message: String) {
        if (failure != null) return
        val ex = HbException(message)
        failure = ex
        val inflight = pending.values.toList()
        pending.clear()
        inflight.forEach { it.completeExceptionally(ex) }
        if (!readyDeferred.isCompleted) readyDeferred.completeExceptionally(ex)
    }

    private fun spawnWorker() {
        worker = createWorker(::handleReply, ::handleWorkerError)
    }

    private fun requireWorker(): JsAny =
        worker ?: error("HbWorker not initialized - call ensureWorkerReady() first")
}

@JsFun(
    """
    (handleReply, handleWorkerError) => {
        // Resolve the worker script against the document base URL (which honours
        // <base href>) rather than self.location.href. Under a deep SPA route
        // like /template/8979, self.location.href resolves './hb-worker.js' to
        // '/template/hb-worker.js' (404) even though the asset is served from
        // the site root; document.baseURI respects the page's <base href="/">
        // and resolves to '/hb-worker.js'. Falls back to self.location.href
        // when no document is present (e.g. a nested worker realm).
        const base = (typeof document !== 'undefined' && document.baseURI)
            ? document.baseURI
            : self.location.href;
        const w = new Worker(new URL('./hb-worker.js', base));
        w.onmessage = (e) => {
            const { id, type, payload } = e.data;
            const isError = type === 'error';
            const message = isError ? (payload && payload.message) : null;
            handleReply(id, isError, payload, message);
        };
        // A worker load/runtime failure (script 404, syntax error, missing
        // dependency, structured-clone failure) fires here with no reply for
        // any in-flight RPC. Surface to console for diagnosis AND notify the
        // Kotlin side so it can fail every pending await instead of leaving
        // them parked forever (which shows up as an infinite loading spinner).
        w.onerror = (e) => {
            const msg = (e && e.message) ? e.message : 'worker script failed to load';
            console.error('[kotlin-harfbuzz] worker error:',
                msg, e && e.filename, e && e.lineno);
            handleWorkerError('HarfBuzz worker error: ' + msg);
        };
        w.onmessageerror = (e) => {
            console.error('[kotlin-harfbuzz] worker message error:', e);
            handleWorkerError('HarfBuzz worker message error');
        };
        return w;
    }
    """
)
private external fun createWorker(
    handleReply: (Long, Boolean, JsAny?, String?) -> Unit,
    handleWorkerError: (String) -> Unit,
): JsAny

@JsFun(
    """
    (worker, id, type, payload, transfer) => {
        const msg = { id, type, payload };
        worker.postMessage(msg, transfer ? [transfer] : []);
    }
    """
)
private external fun postRequest(worker: JsAny, id: Long, type: String, payload: JsAny?, transfer: JsAny?)
