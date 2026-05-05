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
    /**
     * Pixel size the paragraph was shaped at. Renderers that need to
     * scale per-glyph SVG bitmaps or interpret font-design coordinates
     * (e.g. SVG-in-OT raster paths) read this so they don't have to be
     * threaded the size separately.
     */
    public val sizePx: Float,
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
     * Length of the source text in UTF-16 code units, in the **original
     * text's** coordinate space. The char-keyed accessors
     * ([horizontalPositionOf], [advanceWidthOf], [clusterAt]) accept
     * indices in `[0, textLength]` (or `[0, textLength)` for the
     * advance accessor) and translate them into the shaped run via
     * [originalToJustifiedIndex] when justification widened the line.
     *
     * For un-justified shapes this equals the shaped text's length;
     * with [JustificationStrategy.Mixed] / `WordSpacing` it is the
     * pre-insertion length so callers can keep working in the source
     * text's coordinates.
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
    /**
     * When justification inserted Kashida (U+0640) or thin-space
     * (U+2009) glyphs, mapping from each original char index to its
     * position in the shaped (justified) text. Length equals
     * [textLength]; entries are monotonically increasing.
     *
     * `null` (the default) means no insertions happened, so the
     * original and shaped texts coincide and char-keyed accessors can
     * skip the indirection.
     */
    internal val originalToJustifiedIndex: IntArray? = null,
    /**
     * Length of the shaped text in UTF-16 code units - i.e. the string
     * actually passed to the shaper after justification. Equals
     * [textLength] for un-justified shapes; otherwise larger (the
     * inserted connectors widen the run). Used internally to size the
     * cluster lookup table; public callers should keep using
     * [textLength] which is in the original text's coordinate space.
     */
    internal val shapedTextLength: Int = textLength,
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
     *
     * When the run was justified, [charIndex] is in the **original**
     * text's coordinate space; the lookup translates through
     * [originalToJustifiedIndex] before reading the cluster table so
     * inserted Kashida / thin-space glyphs do not shift query indices.
     */
    public fun horizontalPositionOf(charIndex: Int): Float {
        require(charIndex in 0..textLength) {
            "charIndex=$charIndex out of [0, $textLength]"
        }
        if (charIndex == textLength) {
            return paragraphTrailingEdgeX
        }
        val shapedIndex = toShapedIndex(charIndex)
        val clusterId = charToClusterArray[shapedIndex]
        return clusterPositionMap[clusterId]?.leadingX ?: 0f
    }

    /**
     * Per-character advance width. The cluster's full advance is
     * attributed to its leading codepoint; every other codepoint in
     * the cluster (combining marks, non-BMP trail surrogates,
     * intra-ligature characters) returns `0f`. For an un-justified
     * shape, summing [advanceWidthOf] over `0 until textLength`
     * therefore equals [advance] regardless of clustering.
     *
     * For justified shapes [advanceWidthOf] returns the original
     * cluster's advance only. Inserted Kashida / thin-space glyphs
     * carry their own shaped clusters that no original char owns, so
     * their width is *not* folded into adjacent originals - summing
     * over `0 until textLength` then equals the un-justified line
     * advance, not the widened [advance]. Callers that need per-glyph
     * widths along the justified run should iterate shaped indices
     * directly.
     */
    public fun advanceWidthOf(charIndex: Int): Float {
        require(charIndex in 0 until textLength) {
            "charIndex=$charIndex out of [0, $textLength)"
        }
        val shapedIndex = toShapedIndex(charIndex)
        val clusterId = charToClusterArray[shapedIndex]
        val originalClusterLeader = toOriginalIndex(clusterId)
        if (charIndex != originalClusterLeader) return 0f
        return clusterPositionMap[clusterId]?.advance ?: 0f
    }

    /**
     * Cluster id (the leading codepoint of the cluster span)
     * containing [charIndex], or `null` if [charIndex] is out of
     * range. HarfBuzz clusters are codepoint-indexed under the default
     * cluster level, so for ligatures this is the leading character
     * and for combining mark sequences it's the base character.
     *
     * The returned id is in the **original** text's coordinate space:
     * for justified runs the shaped cluster id is reverse-mapped
     * through [originalToJustifiedIndex] so callers always see the
     * original character that owns the cluster, even when the
     * cluster's first glyph is an inserted Kashida.
     */
    public fun clusterAt(charIndex: Int): Int? {
        if (charIndex !in 0 until textLength) return null
        val shapedIndex = toShapedIndex(charIndex)
        val shapedClusterId = charToClusterArray[shapedIndex]
        return toOriginalIndex(shapedClusterId)
    }

    /**
     * Translate an original-text char index to its shaped-text
     * counterpart. Identity when no justification mapping is present.
     */
    private fun toShapedIndex(originalIndex: Int): Int =
        originalToJustifiedIndex?.get(originalIndex) ?: originalIndex

    /**
     * Translate a shaped-text index back to the original char that
     * owns the cluster. Reverse search exploits the mapping's
     * monotonically-increasing invariant: each justifier appends to
     * the builder in order, so a single binary search resolves
     * cluster ownership in O(log textLength).
     */
    private fun toOriginalIndex(shapedIndex: Int): Int {
        val mapping = originalToJustifiedIndex ?: return shapedIndex
        if (mapping.isEmpty()) return 0
        val raw = mapping.binarySearchInt(shapedIndex)
        return if (raw >= 0) raw else (-raw - 2).coerceAtLeast(0)
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

    /**
     * Flat-baseline glyph union path: every glyph in the line combined
     * into one [Path] with pen origin `(0, 0)` and baseline at `y = 0`.
     * Built lazily once and reused across draws because it is
     * paint-invariant and geometry-agnostic.
     *
     * The library's warp drawers ([drawWarpedTextAlongPath],
     * [drawArcText]) read this property instead of rebuilding the
     * baseline path every frame. External pipelines that implement
     * their own warp drawer should do the same: read
     * `measured.baselineGlyphPath`, then sample / warp from there.
     *
     * Differs from [silhouettePath] only in pen origin: silhouette is
     * positioned at `(0, baseline)` to match a `topLeft = Offset.Zero`
     * draw call; baseline glyph path is positioned at `(0, 0)` to give
     * warp drawers a path whose `y = 0` coincides with the glyph
     * baseline.
     */
    public val baselineGlyphPath: Path by lazy { buildBaselineGlyphPath(this) }

    /**
     * Sampled contour points of [baselineGlyphPath] at [sampleStep] px
     * intervals, one [FloatArray] per sub-contour, layout
     * `[x0, y0, x1, y1, ...]`. Memoised at the most-recently-requested
     * step so repeated calls with the same `sampleStep` (the typical
     * case for a per-frame warp drawer) hit the cache.
     *
     * Caller-visible only through library drawers today; expose
     * publicly only if external warp pipelines need raw samples.
     */
    internal fun contourSamples(sampleStep: Float): List<FloatArray> {
        val cached = cachedContourSamples
        if (cached != null && cachedContourSampleStep == sampleStep) return cached
        val fresh = samplePathContours(baselineGlyphPath, sampleStep)
        cachedContourSamples = fresh
        cachedContourSampleStep = sampleStep
        return fresh
    }

    private var cachedContourSampleStep: Float = Float.NaN
    private var cachedContourSamples: List<FloatArray>? = null

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
     * the cluster, in shaped-text coordinates) the codepoint belongs
     * to. Built by walking every run's glyphs to collect cluster
     * starts, then carrying each start forward through the gaps
     * between starts so cluster-internal codepoints inherit their
     * base's cluster id.
     *
     * Sized by [shapedTextLength] so that justified runs (where the
     * shaped string is longer than the original) keep one entry per
     * shaped codepoint - public accessors translate original indices
     * into this space via [toShapedIndex].
     */
    private val charToClusterArray: IntArray by lazy {
        if (shapedTextLength == 0) return@lazy IntArray(0)
        val starts = HashSet<Int>()
        for (run in paragraph.runs) {
            for (g in run.glyphs) starts.add(g.cluster)
        }
        val out = IntArray(shapedTextLength)
        var current = if (0 in starts) 0 else -1
        for (i in 0 until shapedTextLength) {
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
        public fun empty(font: HbFont, sizePx: Float = 0f): MeasuredText = MeasuredText(
            paragraph = ShapedParagraph.EMPTY,
            fontStack = HbFontStack(font),
            sizePx = sizePx,
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

/**
 * Wrap [this] (built from a justified text) with an original-text view
 * so the public char-keyed accessors accept original-space indices and
 * [textLength] reports the original length. All glyph caches are reused
 * by reference - the wrapper only flips the public surface, not the
 * underlying shape data.
 */
internal fun MeasuredText.withOriginalMapping(
    originalTextLength: Int,
    mapping: IntArray,
): MeasuredText = MeasuredText(
    paragraph = paragraph,
    fontStack = fontStack,
    sizePx = sizePx,
    ink = ink,
    logical = logical,
    baseline = baseline,
    advance = advance,
    ascent = ascent,
    descent = descent,
    lineGap = lineGap,
    textLength = originalTextLength,
    flippedPathsByFont = flippedPathsByFont,
    rawPathsByFont = rawPathsByFont,
    colorLayersByFont = colorLayersByFont,
    paintTreesByFont = paintTreesByFont,
    svgGlyphCachesByFont = svgGlyphCachesByFont,
    hasColorGlyphs = hasColorGlyphs,
    hasColorPaintTree = hasColorPaintTree,
    hasColorSvg = hasColorSvg,
    originalToJustifiedIndex = mapping,
    shapedTextLength = textLength,
)

/**
 * Replace [this]'s paragraph with [stretched] (typically the output of
 * [com.mohamedrejeb.harfbuzz.core.paragraph.AdvanceStretchJustifier.stretch])
 * and rebuild the cached measurements. Glyph caches are reused by
 * reference because per-glyph outlines and color layers are advance-
 * agnostic; only the paragraph's per-glyph spacing and `totalAdvance`
 * change.
 */
internal fun MeasuredText.withStretchedParagraph(
    stretched: ShapedParagraph,
): MeasuredText {
    val newAdvance = stretched.totalAdvance
    val newLogical = Rect(
        left = 0f,
        top = -ascent,
        right = newAdvance,
        bottom = descent + lineGap,
    )
    return MeasuredText(
        paragraph = stretched,
        fontStack = fontStack,
        sizePx = sizePx,
        ink = ink,
        logical = newLogical,
        baseline = baseline,
        advance = newAdvance,
        ascent = ascent,
        descent = descent,
        lineGap = lineGap,
        textLength = textLength,
        flippedPathsByFont = flippedPathsByFont,
        rawPathsByFont = rawPathsByFont,
        colorLayersByFont = colorLayersByFont,
        paintTreesByFont = paintTreesByFont,
        svgGlyphCachesByFont = svgGlyphCachesByFont,
        hasColorGlyphs = hasColorGlyphs,
        hasColorPaintTree = hasColorPaintTree,
        hasColorSvg = hasColorSvg,
        originalToJustifiedIndex = originalToJustifiedIndex,
        shapedTextLength = shapedTextLength,
    )
}
