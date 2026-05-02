package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Process-wide single-thread executor for HarfBuzz calls. Mirrors the
 * Wasm worker's serialized model - one queue, no concurrent HB access.
 * The thread is named `harfbuzz-bg` and marked daemon so it doesn't block
 * JVM shutdown.
 */
internal actual val harfbuzzDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "harfbuzz-bg").apply { isDaemon = true }
    }.asCoroutineDispatcher()
