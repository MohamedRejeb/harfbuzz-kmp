package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.withContext

/**
 * JVM/Android: route paragraph build orchestration onto the
 * single-threaded `harfbuzz-bg` executor. The per-primitive
 * `withContext(harfbuzzDispatcher)` calls inside HB stub bodies
 * collapse to no-ops once we're already on the dispatcher, so the
 * net effect is one context switch at the start and one at the end
 * - instead of one per HB call plus all the heavy in-between work
 * (path / paint-tree / SVG raster) running on Main.
 */
public actual suspend fun <T> runShapingWork(block: suspend () -> T): T =
    withContext(harfbuzzDispatcher) { block() }
