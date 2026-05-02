package com.mohamedrejeb.harfbuzz.core

/**
 * Suspend until the host event loop has had a chance to handle other
 * work - pointer events, layout, paint. Use between heavy parse / decode
 * batches so the UI stays responsive while a `MeasuredText` builds.
 *
 * Platform behaviour:
 *  - **Wasm/JS:** hops the continuation through `setTimeout(0)`, which
 *    schedules a real macrotask. `kotlinx.coroutines.yield()` and
 *    `delay(0L)` on this target both stay inside the current event-loop
 *    task (microtask scheduling) - they never break a long task -
 *    so a manual macrotask break is required.
 *  - **JVM / iOS / Android:** the build runs on a background dispatcher
 *    already, so this is a `kotlinx.coroutines.yield()` (cooperative
 *    cancellation point + dispatcher rescheduling). Equivalent to
 *    "let other coroutines progress" in the sense the caller expects.
 *
 * Cost is small but non-zero - about ~4 ms minimum on Wasm because the
 * HTML clamping rule for nested timeouts ramps after a few back-to-back
 * `setTimeout(0)` calls. Yield once per batch (every ~16 items), not
 * once per item.
 */
public expect suspend fun yieldToBrowser()
