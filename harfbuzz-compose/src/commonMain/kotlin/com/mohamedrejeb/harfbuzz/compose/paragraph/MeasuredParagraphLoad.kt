package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.runtime.Immutable

/**
 * Async load state for [MeasuredParagraph]. Mirrors `MeasuredTextLoad`.
 *
 * The build runs off-main (background dispatcher on JVM/Android/iOS, worker
 * on Wasm), so [Loading] is the initial state until the layout + per-line
 * shape passes complete.
 */
@Immutable
public sealed interface MeasuredParagraphLoad {
    /** Layout has not yet produced a result. */
    public data object Loading : MeasuredParagraphLoad

    /** Layout completed successfully - [measured] is safe to render. */
    public data class Ready(val measured: MeasuredParagraph) : MeasuredParagraphLoad

    /** Layout failed; [cause] is logged to stderr by the Compose helper. */
    public data class Failed(val cause: Throwable) : MeasuredParagraphLoad
}
