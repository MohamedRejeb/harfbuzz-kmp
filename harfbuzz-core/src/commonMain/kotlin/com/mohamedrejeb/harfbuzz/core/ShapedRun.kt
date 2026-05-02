package com.mohamedrejeb.harfbuzz.core

/**
 * Immutable output of a single-direction shape call. Glyph order is **visual**:
 * for an RTL run, [glyphs]`[0]` is the visually rightmost glyph. [positions]
 * is the same length and parallel to [glyphs].
 *
 * @property font The font that produced this run's glyphs. Populated when the
 *   run came from an [HbFontStack] fallback dance; `null` for the legacy
 *   single-font shaping path. Renderers must use this font (not the
 *   `MeasuredText`-wide one) for cache lookups when it is non-null,
 *   because glyph ids are font-specific.
 */
public data class ShapedRun(
    public val glyphs: List<GlyphInfo>,
    public val positions: List<GlyphPosition>,
    public val direction: HbDirection,
    public val script: HbScript,
    public val totalAdvance: Float,
    public val ink: HbRect,
    public val logical: HbRect,
    public val font: HbFont? = null,
) {
    init {
        require(glyphs.size == positions.size) {
            "glyphs (${glyphs.size}) and positions (${positions.size}) must be the same size"
        }
    }

    public val glyphCount: Int get() = glyphs.size
    public val isEmpty: Boolean get() = glyphs.isEmpty()

    public companion object {
        public val EMPTY: ShapedRun = ShapedRun(
            glyphs = emptyList(),
            positions = emptyList(),
            direction = HbDirection.LTR,
            script = HbScript.UNKNOWN,
            totalAdvance = 0f,
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            font = null,
        )
    }
}

/**
 * Output of a paragraph-level shape call ([HbFont.shapeParagraph]). [runs] are
 * stored in **visual** order; [logicalToVisual] / [visualToLogical] map between
 * codepoint-index spaces for caret hit-testing and selection.
 */
public data class ShapedParagraph(
    public val runs: List<ShapedRun>,
    public val baseDirection: HbDirection,
    public val totalAdvance: Float,
    public val ink: HbRect,
    public val logical: HbRect,
    public val logicalToVisual: IntArray,
    public val visualToLogical: IntArray,
) {
    public val isEmpty: Boolean get() = runs.all { it.isEmpty }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShapedParagraph) return false
        return runs == other.runs &&
            baseDirection == other.baseDirection &&
            totalAdvance == other.totalAdvance &&
            ink == other.ink &&
            logical == other.logical &&
            logicalToVisual.contentEquals(other.logicalToVisual) &&
            visualToLogical.contentEquals(other.visualToLogical)
    }

    override fun hashCode(): Int {
        var h = runs.hashCode()
        h = 31 * h + baseDirection.hashCode()
        h = 31 * h + totalAdvance.hashCode()
        h = 31 * h + ink.hashCode()
        h = 31 * h + logical.hashCode()
        h = 31 * h + logicalToVisual.contentHashCode()
        h = 31 * h + visualToLogical.contentHashCode()
        return h
    }

    public companion object {
        public val EMPTY: ShapedParagraph = ShapedParagraph(
            runs = emptyList(),
            baseDirection = HbDirection.LTR,
            totalAdvance = 0f,
            ink = HbRect.EMPTY,
            logical = HbRect.EMPTY,
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
    }
}
