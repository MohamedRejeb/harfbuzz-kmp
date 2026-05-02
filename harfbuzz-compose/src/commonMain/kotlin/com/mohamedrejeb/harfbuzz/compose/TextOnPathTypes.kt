package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable

/**
 * Which side of the path the glyphs sit on.
 *
 *  - [Above] (default): baseline lies on the path; glyphs ascend
 *    perpendicular to the local tangent toward the side that is
 *    conventionally "up" (negative Y in screen space when the tangent
 *    points +X).
 *  - [Below]: glyphs are flipped via `scale(1, -1)` after the tangent
 *    rotation so they appear on the opposite side of the path. Reads
 *    correctly when paired with `autoFlip = true` for paths that
 *    double back.
 */
@Immutable
public enum class TextOnPathSide { Above, Below }

/**
 * Where along the path the text sits when its natural advance is
 * shorter than the available path length.
 */
@Immutable
public enum class TextOnPathAlignment { Start, Center, End }

/**
 * What happens when the text's natural advance is longer than the
 * available path length.
 */
@Immutable
public enum class TextOnPathOverflow {
    /** Default: drop glyphs whose center falls outside `[0, pathLength]`. */
    Clip,

    /**
     * Render every glyph; for those past `pathLength`, extrapolate the
     * end-tangent in a straight line. Symmetrically extrapolates the
     * start-tangent for glyphs at d < 0 (only reachable with a negative
     * `startOffset`).
     */
    Visible,

    /**
     * When text natural advance > available pathLength, scale per-glyph
     * spacing uniformly so total fits exactly. Glyphs keep their
     * original size and may overlap - this is intentional. No effect
     * when text already fits. Note: heavy compression ratios will
     * make Arabic shaping joins visually misalign.
     */
    Compress,
}
