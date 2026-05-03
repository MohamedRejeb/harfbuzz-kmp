package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.paragraph.LineLayout
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment

/**
 * Compose-side bundle of a multi-line paragraph laid out under a width
 * constraint. Each entry in [lines] carries its own [MeasuredText] (so the
 * existing per-glyph caches and the full color-glyph render ladder are
 * reused) plus the [LineLayout] record from `layoutParagraph` describing
 * where the line sits inside the paragraph's logical box.
 *
 * Created by [rememberMeasuredParagraph]; safe to retain across
 * recompositions and frames.
 */
@Immutable
public class MeasuredParagraph internal constructor(
    public val text: String,
    public val lines: List<MeasuredLine>,
    public val maxWidth: Float,
    public val width: Float,
    public val height: Float,
    public val firstBaseline: Float,
    public val baseDirection: HbDirection,
    public val alignment: ParagraphAlignment,
    public val fontStack: HbFontStack,
) {
    public val isEmpty: Boolean get() = lines.isEmpty() || lines.all { it.measured.isEmpty }
    public val lineCount: Int get() = lines.size

    /**
     * Single Compose [Path] holding every line's silhouette,
     * positioned in paragraph coordinates: each line contributes its
     * own [MeasuredText.silhouettePath] translated by
     * `(line.xOffset, line.top)`. The line's silhouette is built with
     * pen at `(0, line.measured.baseline)`, and `line.top + ascent`
     * equals the line's paragraph-baseline, so the per-line offsets
     * compose cleanly here without any baseline correction.
     *
     * Suitable as a clip mask for image-filled multi-line paragraphs.
     * Cached lazily because it is paint-invariant; do not mutate the
     * returned [Path] - it is shared across draws.
     */
    public val silhouettePath: Path by lazy { buildSilhouettePath() }

    private fun buildSilhouettePath(): Path {
        val out = Path()
        if (lines.isEmpty()) return out
        for (line in lines) {
            if (line.measured.isEmpty) continue
            out.addPath(line.measured.silhouettePath, Offset(line.xOffset, line.top))
        }
        return out
    }

    /**
     * Index of the visual line that contains [charIndex] - the largest
     * line `i` with `lines[i].charRange.first <= charIndex`. Returns
     * `-1` when the paragraph is empty.
     *
     * `charIndex == text.length` resolves to the last line so callers
     * can probe the paragraph's trailing edge without special-casing.
     * For boundary indices that sit exactly on a line start
     * (`charIndex == lines[i+1].charRange.first`), this returns `i+1`
     * - matching the leading-edge convention of [horizontalPositionOf].
     *
     * Backed by [lineStartsCache] so calls are O(log lineCount) after
     * the first probe.
     */
    public fun lineForCharIndex(charIndex: Int): Int {
        require(charIndex in 0..text.length) {
            "charIndex=$charIndex out of [0, ${text.length}]"
        }
        if (lines.isEmpty()) return -1
        return findLineIndex(charIndex)
    }

    /**
     * X position of the leading edge of the cluster containing
     * [charIndex], measured from the paragraph origin. The value is
     * paragraph-X only - call [lineForCharIndex] (or read
     * `lines[lineIdx].baseline` / `top`) when you also need a Y.
     *
     * For an LTR paragraph the leading edge is the cluster's left
     * side, so values increase monotonically within each line. For an
     * RTL paragraph it is the right side. Cluster-internal codepoints
     * (Latin ligatures, Arabic mark sequences, non-BMP trail
     * surrogates) all share the cluster's leading edge - matching
     * HarfBuzz's cluster semantics and [MeasuredText.horizontalPositionOf].
     *
     * `charIndex == text.length` returns the trailing edge of the last
     * line. Trailing whitespace / hard-break chars stripped before
     * shaping resolve to the trailing edge of their owning line, since
     * they have no glyph of their own.
     *
     * When the line was justified via [JustificationStrategy], the
     * char index is mapped through
     * [LineLayout.originalToJustifiedIndex] before delegating to the
     * line's [MeasuredText] - so a paragraph caller always passes
     * indices in the **original** text space, regardless of whether
     * justification inserted Kashidas or thin-spaces.
     */
    public fun horizontalPositionOf(charIndex: Int): Float {
        require(charIndex in 0..text.length) {
            "charIndex=$charIndex out of [0, ${text.length}]"
        }
        if (lines.isEmpty()) return 0f
        val lineIdx = findLineIndex(charIndex)
        val line = lines[lineIdx]
        return line.xOffset + line.measured.horizontalPositionOf(toJustifiedLocal(line, charIndex))
    }

    /**
     * Per-character advance width. The cluster's full advance is
     * attributed to its leading codepoint; every other codepoint in
     * the cluster (combining marks, non-BMP trail surrogates,
     * intra-ligature characters) returns `0f`.
     *
     * Trailing whitespace / hard-break chars stripped before shaping
     * have zero advance (they contributed no glyphs). Justified lines
     * map the char index through
     * [LineLayout.originalToJustifiedIndex] before delegating, so
     * inserted Kashida / thin-space glyphs remain attributed to the
     * original cluster's leading codepoint.
     *
     * Summing [advanceWidthOf] over `0 until text.length` therefore
     * equals the sum of all line [MeasuredLine.advance]s.
     */
    public fun advanceWidthOf(charIndex: Int): Float {
        require(charIndex in 0 until text.length) {
            "charIndex=$charIndex out of [0, ${text.length})"
        }
        if (lines.isEmpty()) return 0f
        val lineIdx = findLineIndex(charIndex)
        val line = lines[lineIdx]
        val localOriginal = charIndex - line.charRange.first
        if (localOriginal >= line.originalTrimmedLength) return 0f
        val mapping = line.originalToJustifiedIndex
        val localJustified = mapping?.get(localOriginal) ?: localOriginal
        if (localJustified >= line.measured.textLength) return 0f
        return line.measured.advanceWidthOf(localJustified)
    }

    /**
     * Cluster id (the leading codepoint of the cluster span)
     * containing [charIndex], in **paragraph-relative original-text
     * coordinates**. Returns `null` when [charIndex] is outside
     * `[0, text.length)` or sits in stripped trailing whitespace.
     *
     * For un-justified lines, this is `line.measured.clusterAt(local) +
     * line.charRange.first`. For justified lines, the line-local
     * justified cluster id is reverse-mapped through
     * [LineLayout.originalToJustifiedIndex] (a binary search on the
     * monotonically-increasing mapping) so the returned index is
     * always the original char that owns the cluster - even when the
     * cluster's leading glyph is an inserted Kashida.
     */
    public fun clusterAt(charIndex: Int): Int? {
        if (charIndex !in 0 until text.length) return null
        if (lines.isEmpty()) return null
        val lineIdx = findLineIndex(charIndex)
        val line = lines[lineIdx]
        val localOriginal = charIndex - line.charRange.first
        if (localOriginal >= line.originalTrimmedLength) return null
        val mapping = line.originalToJustifiedIndex
        val localJustified = mapping?.get(localOriginal) ?: localOriginal
        val justifiedClusterId = line.measured.clusterAt(localJustified) ?: return null
        val originalClusterLocal = if (mapping == null) {
            justifiedClusterId
        } else {
            reverseJustifiedIndex(mapping, justifiedClusterId)
        }
        return originalClusterLocal + line.charRange.first
    }

    /**
     * Translate a paragraph-relative original char index to the
     * line-local justified index expected by [line]'s [MeasuredText]
     * accessors. Returns the line's full justified-text length for
     * indices that fall in the line's stripped trim suffix - so
     * delegating callers land on the line's trailing edge instead of
     * dereferencing past the cluster map.
     */
    private fun toJustifiedLocal(line: MeasuredLine, paragraphCharIndex: Int): Int {
        val localOriginal = paragraphCharIndex - line.charRange.first
        if (localOriginal >= line.originalTrimmedLength) return line.measured.textLength
        if (localOriginal <= 0) return 0
        val mapping = line.originalToJustifiedIndex
        return mapping?.get(localOriginal) ?: localOriginal
    }

    /**
     * Largest `i` in `[0, mapping.size)` such that `mapping[i] <=
     * justified`. Relies on the justifier mappings being monotonically
     * increasing (each step in [KashidaJustifier] / [WordSpacingJustifier]
     * appends to the builder before the next index is recorded), so a
     * single binary search resolves cluster ownership in O(log n).
     */
    private fun reverseJustifiedIndex(mapping: IntArray, justified: Int): Int {
        if (mapping.isEmpty()) return 0
        val raw = mapping.binarySearch(justified)
        return if (raw >= 0) raw else (-raw - 2).coerceAtLeast(0)
    }

    /**
     * Find the line containing [charIndex] via binary search on
     * [lineStartsCache]. Returns the largest line index whose
     * `charRange.first <= charIndex`; for `charIndex == text.length`
     * this is the last line. Caller guarantees `lines.isNotEmpty()`.
     */
    private fun findLineIndex(charIndex: Int): Int {
        val starts = lineStartsCache
        val raw = starts.binarySearch(charIndex)
        return if (raw >= 0) raw else (-raw - 2).coerceAtLeast(0)
    }

    /**
     * Per-line `charRange.first`, materialised once for binary search
     * in [findLineIndex]. Lines are appended in source order, so this
     * is monotonically increasing.
     */
    private val lineStartsCache: IntArray by lazy {
        if (lines.isEmpty()) IntArray(0)
        else IntArray(lines.size) { lines[it].charRange.first }
    }

    public companion object {
        public fun empty(fontStack: HbFontStack): MeasuredParagraph = MeasuredParagraph(
            text = "",
            lines = emptyList(),
            maxWidth = 0f,
            width = 0f,
            height = 0f,
            firstBaseline = 0f,
            baseDirection = HbDirection.LTR,
            alignment = ParagraphAlignment.Start,
            fontStack = fontStack,
        )
    }
}

/**
 * One visual line within a [MeasuredParagraph]. Pairs the line's own
 * [MeasuredText] (per-glyph caches keyed by font, fully renderable) with
 * the [LineLayout] record that places it inside the paragraph: alignment-
 * applied [LineLayout.xOffset], top-of-box [LineLayout.top], baseline,
 * ascent / descent, and the original [LineLayout.charRange] in the source
 * text.
 */
@Immutable
public class MeasuredLine internal constructor(
    public val measured: MeasuredText,
    public val layout: LineLayout,
) {
    public val xOffset: Float get() = layout.xOffset
    public val top: Float get() = layout.top
    public val baseline: Float get() = layout.baseline
    public val advance: Float get() = layout.advance
    public val charRange: IntRange get() = layout.charRange
    public val ascent: Float get() = layout.ascent
    public val descent: Float get() = layout.descent
    public val lineHeight: Float get() = layout.lineHeight

    /**
     * Per-character mapping from the trimmed source line into the
     * justified [measured] text, or `null` when no justification was
     * applied (identity mapping). See
     * [LineLayout.originalToJustifiedIndex].
     */
    internal val originalToJustifiedIndex: IntArray? get() = layout.originalToJustifiedIndex

    /**
     * Length of the trimmed source line (the slice of paragraph text
     * the layout actually shaped, after stripping trailing whitespace
     * / hard-break characters). When no justification was applied this
     * equals `measured.textLength`; when justification ran, it equals
     * `originalToJustifiedIndex!!.size`.
     */
    internal val originalTrimmedLength: Int
        get() = layout.originalToJustifiedIndex?.size ?: measured.textLength
}
