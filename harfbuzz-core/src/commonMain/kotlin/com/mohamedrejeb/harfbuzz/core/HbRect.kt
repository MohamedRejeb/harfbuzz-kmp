package com.mohamedrejeb.harfbuzz.core

/**
 * Plain-data rectangle used by shaping results so `harfbuzz-core` stays free
 * of Compose dependencies. The Compose layer converts these to
 * `androidx.compose.ui.geometry.Rect` at the boundary.
 */
public data class HbRect(
    public val left: Float,
    public val top: Float,
    public val right: Float,
    public val bottom: Float,
) {
    public val width: Float get() = right - left
    public val height: Float get() = bottom - top
    public val isEmpty: Boolean get() = width <= 0f || height <= 0f

    public companion object {
        public val EMPTY: HbRect = HbRect(0f, 0f, 0f, 0f)
    }
}
