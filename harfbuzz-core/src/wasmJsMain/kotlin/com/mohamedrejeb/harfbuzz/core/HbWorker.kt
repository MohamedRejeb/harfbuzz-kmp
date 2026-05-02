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

    suspend fun ensureWorkerReady() {
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

    private fun spawnWorker() {
        worker = createWorker(::handleReply)
    }

    private fun requireWorker(): JsAny =
        worker ?: error("HbWorker not initialized - call ensureWorkerReady() first")
}

@JsFun(
    """
    (handleReply) => {
        const w = new Worker(new URL('./hb-worker.js', self.location.href));
        w.onmessage = (e) => {
            const { id, type, payload } = e.data;
            const isError = type === 'error';
            const message = isError ? (payload && payload.message) : null;
            handleReply(id, isError, payload, message);
        };
        // Without these, a worker load/runtime failure (script syntax error,
        // missing dependency, structured-clone failure) silently hangs every
        // pending RPC because no reply ever arrives. Surface to console so
        // the page operator can diagnose; pending awaits stay parked, which
        // is the right behaviour for an unrecoverable worker fault.
        w.onerror = (e) => {
            console.error('[kotlin-harfbuzz] worker error:',
                e && e.message ? e.message : e,
                e && e.filename, e && e.lineno);
        };
        w.onmessageerror = (e) => {
            console.error('[kotlin-harfbuzz] worker message error:', e);
        };
        return w;
    }
    """
)
private external fun createWorker(handleReply: (Long, Boolean, JsAny?, String?) -> Unit): JsAny

@JsFun(
    """
    (worker, id, type, payload, transfer) => {
        const msg = { id, type, payload };
        worker.postMessage(msg, transfer ? [transfer] : []);
    }
    """
)
private external fun postRequest(worker: JsAny, id: Long, type: String, payload: JsAny?, transfer: JsAny?)
