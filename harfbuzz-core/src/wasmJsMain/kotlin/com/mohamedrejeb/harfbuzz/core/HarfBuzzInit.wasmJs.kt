package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm: ensure the singleton [HbWorker] is up and the underlying
 * `harfbuzzjs` wasm module is initialised inside it. Idempotent.
 */
public actual suspend fun harfBuzzInit() {
    HbWorker.ensureWorkerReady()
}
