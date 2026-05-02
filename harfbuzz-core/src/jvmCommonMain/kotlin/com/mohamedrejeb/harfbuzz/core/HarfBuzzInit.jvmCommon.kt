package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.withContext

/**
 * JVM/Android: load the bundled JNI shared library on the HarfBuzz
 * background dispatcher.
 *
 * `System.loadLibrary` is the heaviest call on first paint: on Android
 * cold start it can take 30–80 ms while the platform mmap's the `.so`,
 * resolves dynamic symbols, and runs `JNI_OnLoad`; on Desktop JVM it
 * additionally extracts the bundled binary from a jar resource and
 * verifies a SHA-256 checksum (see `NativeLibraryLoader.jvm.kt`). Both
 * paths block the calling thread synchronously.
 *
 * `harfBuzzInit()` is normally invoked from a `LaunchedEffect`, whose
 * coroutine context is `Dispatchers.Main`. Without the `withContext`
 * hop here, that load runs on Main and the first composed frame
 * stalls until it completes. Hopping to `harfbuzzDispatcher` keeps Main
 * free; JNI symbol registration is process-wide (not thread-bound), so
 * subsequent calls from any thread see the loaded library.
 */
public actual suspend fun harfBuzzInit(): Unit =
    withContext(harfbuzzDispatcher) {
        HarfbuzzNative.ensureLoaded()
    }
