package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.withContext

/**
 * iOS: same shape as JVM/Android - route the orchestration onto
 * the parallelism-1 view of `Dispatchers.Default` we use for HB
 * primitives. Skia path / SVG raster off-Main is fine on
 * Kotlin/Native; the resulting handles are passed back to Main via
 * Compose's `MutableState`, which already gives the necessary
 * happens-before.
 */
public actual suspend fun <T> runShapingWork(block: suspend () -> T): T =
    withContext(harfbuzzDispatcher) { block() }
