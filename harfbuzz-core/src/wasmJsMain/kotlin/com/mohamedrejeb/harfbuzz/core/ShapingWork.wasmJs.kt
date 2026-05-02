package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm: pass-through. There is no real background thread -
 * `Dispatchers.Default` is `Main`, and HB calls already cross to a
 * Web Worker via [HbWorker] RPC. The orchestrator stays on Main and
 * yields cooperatively via `yieldToBrowser` (real `setTimeout(0)`
 * macrotasks), which is what keeps the page responsive across the
 * heavy in-between work (path / paint-tree decode, SVG slice).
 */
public actual suspend fun <T> runShapingWork(block: suspend () -> T): T = block()
