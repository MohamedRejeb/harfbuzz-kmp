package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end smoke for the Wasm worker boundary: boot the worker, ship
 * one paragraph through, verify shaped runs come back.
 *
 * This test compiles cleanly today but cannot actually run inside the
 * current karma harness - karma's webpack-as-preprocessor pipeline
 * doesn't serve `hb.wasm` (the worker fetches it relative to its own
 * URL) or the bundled test font. The same blocker is documented in
 * commit `14832ce`. Once the karma shim that proxies static assets
 * lands, this test exercises:
 *
 *  1. `harfBuzzInit()` - loads `hb-worker.js`, the worker imports
 *     `kotlin-harfbuzzjs`, the wasm module instantiates inside the
 *     worker.
 *  2. `HbFace.fromBytes(...)` - main thread bytes traverse the
 *     postMessage channel, the worker materialises an `hb_face_t`.
 *  3. `face.toFont(...)` - same trip for `hb_font_t`.
 *  4. `font.shapeParagraph("hello")` - main thread sends the BiDi-
 *     pre-resolved request, the worker shapes, returns runs.
 */
class WorkerInitTest {

    @Test
    fun worker_bootstraps_and_shapes_one_paragraph() = runTest {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFontsWasm.robotoRegular())
        val font = face.toFont(16f)
        try {
            val paragraph = font.shapeParagraph("hello")
            assertTrue(paragraph.runs.isNotEmpty(), "expected at least one run")
            assertTrue(paragraph.totalAdvance > 0f, "expected positive advance")
        } finally {
            font.close()
            face.close()
        }
    }
}
