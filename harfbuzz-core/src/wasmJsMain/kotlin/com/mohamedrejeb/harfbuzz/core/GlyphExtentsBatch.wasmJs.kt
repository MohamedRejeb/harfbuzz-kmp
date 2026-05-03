@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm shaping is already worker-bounded and the per-glyph extents path
 * is unused inside [ShapingHelpers] (the worker emits its own ink rect).
 * We don't add a `getGlyphExtentsBatch` RPC today - defer to the loop.
 *
 * If a paragraph really needs N extents from main on Wasm, a future
 * batch RPC over [HbWorker] would replace this null with one round-trip
 * per call instead of N.
 */
internal actual suspend fun HbFont.tryGlyphExtentsBatchNative(
    glyphIds: IntArray,
    sizePx: Float,
): List<GlyphExtents?>? = null
