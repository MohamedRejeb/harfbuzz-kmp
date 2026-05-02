package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher every HarfBuzz call body runs on. Single-threaded so we never
 * touch the same `HbFace`/`HbFont` from two threads concurrently - HarfBuzz
 * itself is not internally locked. JVM/Android/iOS use a dedicated background
 * thread; Wasm routes through a singleton Web Worker (this dispatcher is just
 * a placeholder there since the actual offload happens in the worker).
 */
internal expect val harfbuzzDispatcher: CoroutineDispatcher
