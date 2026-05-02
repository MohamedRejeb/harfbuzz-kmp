package com.mohamedrejeb.harfbuzz.core

/**
 * Pure-metric snapshot of a shaped paragraph - what you get without
 * paying for the per-glyph path / COLR / SVG decoding that
 * `MeasuredText` builds.
 *
 * Produced by [HbFontStack.shapeOnlyBounds]. Field set is the strict
 * subset of `MeasuredText` derivable from `ShapedParagraph` plus
 * `HbFont.hExtents`.
 *
 * `ink` and `logical` use [HbRect] so this type stays inside
 * `harfbuzz-core` and remains usable from a future non-Compose layout
 * pass. The Compose layer adds `Rect` extension properties for
 * ergonomics.
 */
public data class MeasuredBounds(
    public val ink: HbRect,
    public val logical: HbRect,
    public val advance: Float,
    public val ascent: Float,
    public val descent: Float,
    public val lineGap: Float,
    public val baseline: Float,
) {
    public val isEmpty: Boolean get() = advance == 0f && ink.isEmpty
    public val lineHeight: Float get() = ascent + descent + lineGap

    public companion object {
        public val EMPTY: MeasuredBounds = MeasuredBounds(
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            advance = 0f,
            ascent = 0f,
            descent = 0f,
            lineGap = 0f,
            baseline = 0f,
        )
    }
}

/**
 * Output of [HbFontStack.shapeOnlyBounds]. Carries the [bounds] alongside
 * the [paragraph] so `rememberPathTextBounds` can reuse the same shape
 * for its path-walk instead of shaping twice.
 */
public data class MeasuredBoundsResult(
    public val bounds: MeasuredBounds,
    public val paragraph: ShapedParagraph,
)
