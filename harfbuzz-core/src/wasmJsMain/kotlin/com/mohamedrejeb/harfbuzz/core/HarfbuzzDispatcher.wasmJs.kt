package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * On Wasm there is no real background thread - `Default` is just `Main`.
 * Real offload happens in the singleton Web Worker (see [HbWorker]); this
 * dispatcher exists only to keep the call sites symmetric with JVM/iOS.
 * The actuals in HbStubs.wasmJs.kt route through `HbWorker.send(...)`,
 * which is the actual cross-thread mechanism.
 */
internal actual val harfbuzzDispatcher: CoroutineDispatcher = Dispatchers.Default
