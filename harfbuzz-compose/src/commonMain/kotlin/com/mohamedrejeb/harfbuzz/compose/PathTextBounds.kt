package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/**
 * Tight outer ink bounds of text laid along a path. Parallel to
 * `MeasuredText.ink` but aware of glyph rotation along the curve.
 *
 * @property ink Tight outer `Rect` - union of every placed glyph's
 *   transformed ink box.
 * @property advance Sum of the placed glyphs' advances. With
 *   `TextOnPathOverflow.Clip` this excludes dropped glyphs; with
 *   `Visible` and `Compress` it equals the paragraph's natural advance.
 * @property pathLength Informational - the path's measured length.
 */
@Immutable
public data class PathTextBounds(
    val ink: Rect,
    val advance: Float,
    val pathLength: Float,
) {
    public companion object {
        public val EMPTY: PathTextBounds = PathTextBounds(
            ink = Rect.Zero,
            advance = 0f,
            pathLength = 0f,
        )
    }
}

@Immutable
public sealed interface PathTextBoundsLoad {
    public data object Loading : PathTextBoundsLoad
    public data class Ready(val bounds: PathTextBounds) : PathTextBoundsLoad
    public data class Failed(val cause: Throwable) : PathTextBoundsLoad
}
