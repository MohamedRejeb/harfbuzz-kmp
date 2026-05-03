package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.ColorLayer
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph

/**
 * The Compose-side bundle of everything needed to render a piece of text
 * shaped with HarfBuzz. Created by [rememberMeasuredText]; safe to retain
 * across recompositions and frames since it owns no native resources
 * directly - the per-glyph caches hold values derived once from the font
 * and reused on every redraw.
 *
 * When created from an [HbFontStack] (multi-font fallback shaping) every
 * cache is keyed first by [HbFont] then by glyph id, because glyph ids
 * are font-specific. Single-font shaping populates only the primary
 * font's bucket.
 */
@Immutable
public class MeasuredText internal constructor(
    public val paragraph: ShapedParagraph,
    /**
     * The full font stack that produced [paragraph]. For single-font
     * shaping this contains only [font]; for multi-font shaping every
     * fallback that contributed glyphs is included so renderers can look
     * up font-specific caches by run.
     */
    public val fontStack: HbFontStack,
    public val ink: Rect,
    public val logical: Rect,
    public val baseline: Float,
    public val advance: Float,
    /** Distance from baseline up to the recommended top (font h-extents). */
    public val ascent: Float,
    /** Distance from baseline down to the recommended bottom (positive). */
    public val descent: Float,
    /** Extra vertical space the font designer recommends between lines. */
    public val lineGap: Float,
    /**
     * Length of the source text in UTF-16 code units. Recorded so the
     * char-keyed accessors ([horizontalPositionOf], [advanceWidthOf],
     * [clusterAt]) can validate indices and resolve `charIndex == length`
     * to the trailing edge of the line.
     */
    public val textLength: Int,
    /**
     * Compose-flipped (y-down) outlines per font, keyed by glyph id. Used
     * for monochrome draws and COLR v0 layer composition.
     */
    internal val flippedPathsByFont: Map<HbFont, Map<Int, Path>>,
    /**
     * Raw (y-up, in HB scale) outlines per font, keyed by glyph id. Used
     * by [ComposePaintSink]'s `pushClipGlyph` when replaying COLR v1
     * paint trees.
     */
    internal val rawPathsByFont: Map<HbFont, Map<Int, Path>>,
    /**
     * COLR v0 color-layer decompositions per font, keyed by glyph id.
     * Empty list means the glyph is monochrome (or the face has no color
     * tables) - render the base path with the caller-provided color.
     */
    internal val colorLayersByFont: Map<HbFont, Map<Int, List<ColorLayer>>>,
    /**
     * COLR v1 paint trees per font, keyed by glyph id. Replaying the
     * recorded ops against a [ComposePaintSink] reproduces the colored
     * glyph without touching JNI again.
     */
    internal val paintTreesByFont: Map<HbFont, Map<Int, List<RecordedPaintOp>>>,
    /**
     * Lazy SVG-in-OT glyph caches per font. Each cache holds the sliced
     * SVG bytes (cheap, populated at build time) and memoises raster
     * output on first draw - `renderSvgGlyph` is heavy enough that
     * eagerly running it for every emoji glyph would pin the main
     * thread for hundreds of ms after a paragraph appears. Empty when
     * the face has no `SVG ` table or the platform can't rasterise.
     */
    internal val svgGlyphCachesByFont: Map<HbFont, LazySvgGlyphCache>,
    /** True when any font in the stack has color glyphs of any kind. */
    public val hasColorGlyphs: Boolean,
    /** True when any font in the stack has a COLR v1 paint table. */
    public val hasColorPaintTree: Boolean,
    /**
     * True when any font in the stack has an `SVG ` table AND the
     * platform can actually rasterise SVG documents (Android currently
     * can't).
     */
    public val hasColorSvg: Boolean,
) {
    /** The primary font (kept for backward compatibility). */
    public val font: HbFont get() = fontStack.primary

    public val isEmpty: Boolean get() = paragraph.isEmpty
    /** `ascent + descent + lineGap` - the recommended row height for one line. */
    public val lineHeight: Float get() = ascent + descent + lineGap

    internal fun flippedPath(font: HbFont, glyphId: Int): Path? =
        flippedPathsByFont[font]?.get(glyphId)

    internal fun rawPath(font: HbFont, glyphId: Int): Path? =
        rawPathsByFont[font]?.get(glyphId)

    internal fun colorLayers(font: HbFont, glyphId: Int): List<ColorLayer>? =
        colorLayersByFont[font]?.get(glyphId)

    internal fun paintTree(font: HbFont, glyphId: Int): List<RecordedPaintOp>? =
        paintTreesByFont[font]?.get(glyphId)

    internal fun svgBitmap(font: HbFont, glyphId: Int): SvgGlyphRender? =
        svgGlyphCachesByFont[font]?.get(glyphId)

    internal fun hasSvgBitmaps(font: HbFont): Boolean =
        svgGlyphCachesByFont[font]?.hasAnyGlyphs == true

    internal fun rawPathsFor(font: HbFont): Map<Int, Path> =
        rawPathsByFont[font].orEmpty()

    /**
     * Rect of the caret (a thin vertical bar) at the given codepoint index in
     * the original logical (UTF-16) text. Not yet implemented.
     */
    public fun caretRectAt(charIndex: Int): Rect {
        TODO("Caret hit-testing not yet implemented")
    }

    /**
     * Codepoint index closest to [offset], measured from the top-left of the
     * logical layout. Inverse of [caretRectAt]. Not yet implemented.
     */
    public fun charIndexAt(offset: Offset): Int {
        TODO("Caret hit-testing not yet implemented")
    }

    /**
     * X position of the leading edge of the cluster containing
     * [charIndex], measured from the paragraph origin. For an LTR
     * paragraph the leading edge is the cluster's left side, so the
     * value increases monotonically with [charIndex] from `0` to
     * [advance]. For an RTL paragraph the leading edge is the right
     * side, so the value decreases from [advance] (at `charIndex=0`)
     * to `0` (at `charIndex=textLength`).
     *
     * `charIndex == textLength` returns the trailing edge of the last
     * cluster (the paragraph's logical end-of-line). Inside a cluster
     * (e.g. a Latin ligature, an Arabic mark sequence, or the trail
     * surrogate of a non-BMP codepoint) every codepoint shares the
     * cluster's leading edge - the cluster is treated as a single
     * unbreakable unit, matching HarfBuzz's cluster semantics and
     * Compose's `getHorizontalPosition` for cluster-internal offsets.
     */
    public fun horizontalPositionOf(charIndex: Int): Float {
        require(charIndex in 0..textLength) {
            "charIndex=$charIndex out of [0, $textLength]"
        }
        if (charIndex == textLength) {
            return paragraphTrailingEdgeX
        }
        val clusterId = charToClusterArray[charIndex]
        return clusterPositionMap[clusterId]?.leadingX ?: 0f
    }

    /**
     * Per-character advance width. The cluster's full advance is
     * attributed to its leading codepoint; every other codepoint in
     * the cluster (combining marks, non-BMP trail surrogates,
     * intra-ligature characters) returns `0f`. Summing
     * [advanceWidthOf] over `0 until textLength` therefore equals
     * [advance] regardless of clustering.
     */
    public fun advanceWidthOf(charIndex: Int): Float {
        require(charIndex in 0 until textLength) {
            "charIndex=$charIndex out of [0, $textLength)"
        }
        val clusterId = charToClusterArray[charIndex]
        if (charIndex != clusterId) return 0f
        return clusterPositionMap[clusterId]?.advance ?: 0f
    }

    /**
     * Cluster id (the leading codepoint of the cluster span)
     * containing [charIndex], or `null` if [charIndex] is out of
     * range. HarfBuzz clusters are codepoint-indexed under the default
     * cluster level, so for ligatures this is the leading character
     * and for combining mark sequences it's the base character.
     */
    public fun clusterAt(charIndex: Int): Int? {
        if (charIndex !in 0 until textLength) return null
        return charToClusterArray[charIndex]
    }

    /**
     * Single Compose [Path] holding every glyph silhouette in the
     * line, positioned to match `drawShapedText(measured, topLeft =
     * Offset.Zero)`. Useful as a clip mask for image-filled text or
     * `Path.op(Union)` post-processing. Built lazily once and reused
     * across draws because the silhouette is paint-invariant.
     *
     * Pen origin is `(0, baseline)` (matching the no-arg `topLeft`
     * draw), so a caller wanting to clip image fill at a different
     * position should `Path.addPath(silhouettePath, topLeft)` into a
     * fresh path (the cached one is shared and must not be mutated).
     *
     * Color glyphs that ship without a base outline silhouette (e.g.
     * Noto Color Emoji's COLR v1 paint trees) contribute nothing to
     * the path - those positions are gaps in the clip mask, since the
     * library has no monochrome shape to fall back on.
     */
    public val silhouettePath: Path by lazy { buildSilhouettePath() }

    private fun buildSilhouettePath(): Path {
        val out = Path()
        if (paragraph.isEmpty) return out
        var penX = 0f
        var penY = baseline
        for (run in paragraph.runs) {
            val runFont = run.font ?: font
            val pathsForFont = flippedPathsByFont[runFont] ?: emptyMap()
            var localX = penX
            var localY = penY
            for (i in run.glyphs.indices) {
                val gid = run.glyphs[i].glyphId
                val pos = run.positions[i]
                val drawX = localX + pos.xOffset
                val drawY = localY - pos.yOffset
                val p = pathsForFont[gid]
                if (p != null) {
                    out.addPath(p, Offset(drawX, drawY))
                }
                localX += pos.xAdvance
                localY += pos.yAdvance
            }
            penX = localX
            penY = localY
        }
        return out
    }

    /**
     * Per-codepoint cluster index, materialised once on first cluster
     * query. Each entry holds the cluster id (= leading codepoint of
     * the cluster) the codepoint belongs to. Built by walking every
     * run's glyphs to collect cluster starts, then carrying each start
     * forward through the gaps between starts so cluster-internal
     * codepoints inherit their base's cluster id.
     */
    private val charToClusterArray: IntArray by lazy {
        if (textLength == 0) return@lazy IntArray(0)
        val starts = HashSet<Int>()
        for (run in paragraph.runs) {
            for (g in run.glyphs) starts.add(g.cluster)
        }
        val out = IntArray(textLength)
        var current = if (0 in starts) 0 else -1
        for (i in 0 until textLength) {
            if (i in starts) current = i
            out[i] = if (current >= 0) current else 0
        }
        out
    }

    /**
     * Cluster id → leading-edge X plus total advance, in paragraph
     * coordinates. A cluster's glyphs may span more than one entry in
     * a single run (decomposed marks, complex Indic stacks); the map
     * accumulates the full advance and picks the leading edge based
     * on the run's [HbDirection] - left side for LTR, right side for
     * RTL - so [horizontalPositionOf] matches Compose's
     * `getHorizontalPosition` direction semantics.
     */
    private val clusterPositionMap: Map<Int, ClusterPosition> by lazy {
        if (paragraph.isEmpty) return@lazy emptyMap()
        val firstCanvasX = HashMap<Int, Float>()
        val lastEndCanvasX = HashMap<Int, Float>()
        val totalAdvance = HashMap<Int, Float>()
        val isRtl = HashMap<Int, Boolean>()
        var paragraphX = 0f
        for (run in paragraph.runs) {
            var runX = 0f
            val runIsRtl = run.direction == HbDirection.RTL
            for (i in run.glyphs.indices) {
                val cluster = run.glyphs[i].cluster
                val xAdvance = run.positions[i].xAdvance
                val canvasX = paragraphX + runX
                val canvasEnd = canvasX + xAdvance
                val priorFirst = firstCanvasX[cluster]
                if (priorFirst == null || canvasX < priorFirst) {
                    firstCanvasX[cluster] = canvasX
                }
                val priorLast = lastEndCanvasX[cluster]
                if (priorLast == null || canvasEnd > priorLast) {
                    lastEndCanvasX[cluster] = canvasEnd
                }
                totalAdvance[cluster] = (totalAdvance[cluster] ?: 0f) + xAdvance
                isRtl[cluster] = runIsRtl
                runX += xAdvance
            }
            paragraphX += run.totalAdvance
        }
        val out = HashMap<Int, ClusterPosition>(firstCanvasX.size)
        for ((cluster, first) in firstCanvasX) {
            val last = lastEndCanvasX.getValue(cluster)
            val total = totalAdvance.getValue(cluster)
            val leadingX = if (isRtl[cluster] == true) last else first
            out[cluster] = ClusterPosition(leadingX, total)
        }
        out
    }

    /**
     * X of the paragraph's trailing edge (`charIndex == textLength`):
     * for LTR paragraphs this is [advance] (right edge of the line);
     * for RTL paragraphs it is `0` (left edge of the line, where the
     * RTL flow ends).
     */
    private val paragraphTrailingEdgeX: Float
        get() = if (paragraph.baseDirection == HbDirection.RTL) 0f else advance

    private data class ClusterPosition(val leadingX: Float, val advance: Float)

    public companion object {
        /** A no-op placeholder for slots that need a non-null `MeasuredText`. */
        public fun empty(font: HbFont): MeasuredText = MeasuredText(
            paragraph = ShapedParagraph.EMPTY,
            fontStack = HbFontStack(font),
            ink = Rect.Zero,
            logical = Rect.Zero,
            baseline = 0f,
            advance = 0f,
            ascent = 0f,
            descent = 0f,
            lineGap = 0f,
            textLength = 0,
            flippedPathsByFont = emptyMap(),
            rawPathsByFont = emptyMap(),
            colorLayersByFont = emptyMap(),
            paintTreesByFont = emptyMap(),
            svgGlyphCachesByFont = emptyMap(),
            hasColorGlyphs = false,
            hasColorPaintTree = false,
            hasColorSvg = false,
        )
    }
}

internal fun HbRect.toComposeRect(): Rect = Rect(left, top, right, bottom)
