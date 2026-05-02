package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable

/**
 * Async load state for [MeasuredText]. Mirror of [FontLoad] for shaping work.
 *
 * Build is off-main (worker on Wasm, dedicated thread on JVM/Android/iOS),
 * so [Loading] is the initial state until the snapshot is ready. Failures
 * surface as [Failed] and are logged to platform stderr by the Compose
 * helper.
 */
@Immutable
public sealed interface MeasuredTextLoad {
    /** The shaping/measurement coroutine has not yet produced a result. */
    public data object Loading : MeasuredTextLoad

    /** Shaping completed successfully - [measured] is safe to render. */
    public data class Ready(val measured: MeasuredText) : MeasuredTextLoad

    /** Shaping failed; [cause] is logged to stderr by the Compose helper. */
    public data class Failed(val cause: Throwable) : MeasuredTextLoad
}
