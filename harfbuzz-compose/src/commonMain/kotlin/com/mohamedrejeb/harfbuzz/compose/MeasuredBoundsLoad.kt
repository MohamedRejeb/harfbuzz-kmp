package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import com.mohamedrejeb.harfbuzz.core.MeasuredBounds
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph

/**
 * Async load state for [rememberTextBounds]. Mirror of [MeasuredTextLoad]
 * but for the lightweight bounds-only path.
 *
 * [Ready.paragraph] is exposed because the path-bounds Composable
 * ([rememberPathTextBounds]) reuses the shape output to walk glyphs along
 * a path without re-shaping. End-user code can ignore it and read only
 * [Ready.bounds].
 */
@Immutable
public sealed interface MeasuredBoundsLoad {
    public data object Loading : MeasuredBoundsLoad

    public data class Ready(
        val bounds: MeasuredBounds,
        val paragraph: ShapedParagraph,
    ) : MeasuredBoundsLoad

    public data class Failed(val cause: Throwable) : MeasuredBoundsLoad
}
