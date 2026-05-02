package com.mohamedrejeb.harfbuzz.core

/**
 * Run a block of paragraph build / measure / glyph cache work on the
 * platform's HarfBuzz background dispatcher.
 *
 * Per-call HB primitives (`face.toFont`, `font.snapshotGlyphs`,
 * `font.shapeParagraph`, etc.) already hop onto the dispatcher
 * internally, but the *orchestration* around them - Compose [Path]
 * parsing, paint-tree decode, SVG slicing, SVG glyph rasterisation -
 * is pure Kotlin / Skia / AndroidSVG work that runs on whatever
 * dispatcher the suspend function is invoked from. From a
 * `produceState` / `LaunchedEffect`, that's `Dispatchers.Main`, which
 * means a paragraph with a few dozen color-emoji glyphs blocks the UI
 * thread for hundreds of ms while AndroidSVG / Skia raster each glyph.
 *
 * This wrapper picks the right "off-main" target per platform:
 *
 *  - **JVM / Android:** dedicated `harfbuzz-bg` thread (the same
 *    single-threaded executor every HB primitive uses). Inner
 *    `withContext(harfbuzzDispatcher)` calls collapse to no-ops once
 *    we're already on it.
 *  - **iOS:** parallelism-1 view onto `Dispatchers.Default` (a real
 *    Kotlin/Native worker), same single-thread guarantee.
 *  - **Wasm:** pass-through. `Dispatchers.Default` on Wasm *is* Main,
 *    so a `withContext` switch buys nothing; HB calls cross to a Web
 *    Worker via [HbWorker] RPC, and the orchestrator stays on Main
 *    cooperatively yielding via `yieldToBrowser` (real `setTimeout(0)`
 *    macrotasks).
 *
 * Re-entrancy is safe: nested calls stay on the same dispatcher.
 *
 * Cancellation propagates from the caller's coroutine through the
 * `withContext` wrap, so cancelling a `produceState` build cancels the
 * shaping work too.
 */
public expect suspend fun <T> runShapingWork(block: suspend () -> T): T
