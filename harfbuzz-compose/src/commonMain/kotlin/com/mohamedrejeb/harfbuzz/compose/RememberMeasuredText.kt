package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.ColorLayer
import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.MeasuredFontPass
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.buildMeasured
import com.mohamedrejeb.harfbuzz.core.defaultFlagsFor
import com.mohamedrejeb.harfbuzz.core.remapFontRuns
import com.mohamedrejeb.harfbuzz.core.paragraph.AdvanceStretchJustifier
import com.mohamedrejeb.harfbuzz.core.paragraph.ArabicTextUtils
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.LineJustifier
import com.mohamedrejeb.harfbuzz.core.paragraph.WordSpacingJustifier
import com.mohamedrejeb.harfbuzz.core.runShapingWork
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import com.mohamedrejeb.harfbuzz.core.yieldToBrowser
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shape [text] with [font] and package the result + per-glyph caches
 * (Compose [Path] + COLR v0 layers + COLR v1 paint trees) into a
 * [MeasuredText] safe to retain across recompositions.
 *
 * Returns a [State] of [MeasuredTextLoad]. The build runs off-main
 * (background dispatcher on JVM/Android/iOS, worker on Wasm). The
 * initial value is [MeasuredTextLoad.Loading] until the first shape
 * completes; subsequent input changes keep the previous
 * [MeasuredTextLoad.Ready] visible while the new build runs
 * (stale-while-revalidate), so animating size, features, justification
 * etc. does not blank the rendered text between frames.
 *
 * The paint-tree cache means [drawShapedText] never touches JNI per
 * frame: every redraw replays the recorded ops directly.
 */
@Composable
public fun rememberMeasuredText(
    text: String,
    font: HbFont,
    sizePx: Float,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
    maxWidth: Float = Float.POSITIVE_INFINITY,
    justification: JustificationStrategy = JustificationStrategy.None,
    fontRuns: List<FontRun> = emptyList(),
): State<MeasuredTextLoad> {
    val stack = remember(font) { HbFontStack(font) }
    return rememberMeasuredText(text, stack, sizePx, features, direction, language, maxWidth, justification, fontRuns)
}

/**
 * Shape [text] with a font stack - same as the single-font overload but
 * routes shaping through [HbFontStack.buildMeasured], so any cluster
 * the primary font cannot resolve falls back to [HbFontStack.fallbacks]
 * in order. Per-glyph caches end up partitioned by font, since glyph
 * ids are font-specific.
 *
 * If [maxWidth] is finite and [justification] is non-[None], the
 * resulting [MeasuredText] is widened to match [maxWidth] by re-shaping
 * with extra Kashida / thin-space glyphs (see [LineJustifier]). When
 * the original advance already meets or exceeds [maxWidth] the input is
 * returned as-is.
 *
 * [fontRuns] carries authored font assignments over ranges of [text]
 * (original-text UTF-16 offsets; clamp and last-wins semantics, see
 * [com.mohamedrejeb.harfbuzz.core.shapeParagraph]). The caller retains
 * ownership of the fonts and must keep them alive while the returned
 * state is in use, same as [fontStack]'s fonts.
 */
@Composable
public fun rememberMeasuredText(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
    maxWidth: Float = Float.POSITIVE_INFINITY,
    justification: JustificationStrategy = JustificationStrategy.None,
    fontRuns: List<FontRun> = emptyList(),
): State<MeasuredTextLoad> {
    return produceState<MeasuredTextLoad>(
        initialValue = MeasuredTextLoad.Loading,
        text, fontStack, sizePx, features, direction, language, maxWidth, justification, fontRuns,
    ) {
        // Deliberately do NOT reset to Loading here: keep the previous
        // Ready value visible while the new build runs so size /
        // feature / justification animation does not blank the text.
        try {
            val measured = buildMeasuredTextWithJustify(
                text, fontStack, sizePx, features, direction, language, maxWidth, justification, fontRuns,
            )
            value = MeasuredTextLoad.Ready(measured)
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: Throwable) {
            if (isStaleHbHandle(cause)) {
                // Stale-font race: rememberHbFont's DisposableEffect closed the
                // previous HbFont while this job still held a reference. The
                // produceState relaunch (keyed on the new fontStack) will take
                // over from here - no Failed flicker, no log noise.
                return@produceState
            }
            println("[kotlin-harfbuzz] buildMeasuredText failed: $cause")
            cause.printStackTrace()
            value = MeasuredTextLoad.Failed(cause)
        }
    }
}

/**
 * Build a [MeasuredText] for [text]; if [justification] is non-
 * [JustificationStrategy.None] and a finite target width is available,
 * widen the line by inserting extra Kashida / thin-space glyphs and
 * re-shape so the resulting advance approaches the target. Returns the
 * un-justified build whenever there is no slack to distribute (advance
 * already at or beyond the target, empty text, no insertion points).
 *
 * The target width is normally [maxWidth]; for
 * [JustificationStrategy.KashidaTo] the strategy's embedded
 * `targetWidthPx` overrides it so callers can keep `maxWidth` infinite
 * (no wrap) while still driving Kashida insertion.
 */
public suspend fun buildMeasuredTextWithJustify(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
    maxWidth: Float,
    justification: JustificationStrategy,
    fontRuns: List<FontRun> = emptyList(),
): MeasuredText {
    val initial = buildMeasuredText(text, fontStack, sizePx, features, direction, language, fontRuns)
    val targetWidth = when (justification) {
        is JustificationStrategy.KashidaTo -> justification.targetWidthPx
        is JustificationStrategy.AdvanceStretchTo -> justification.targetWidthPx
        else -> maxWidth
    }
    if (
        text.isEmpty() ||
        justification == JustificationStrategy.None ||
        targetWidth.isInfinite() ||
        targetWidth <= 0f ||
        initial.advance >= targetWidth
    ) return initial

    if (justification is JustificationStrategy.AdvanceStretchTo) {
        val stretched = AdvanceStretchJustifier.stretch(initial.paragraph, targetWidth)
        return if (stretched === initial.paragraph) initial
        else initial.withStretchedParagraph(stretched)
    }

    val needsKashidaWidth = justification == JustificationStrategy.Mixed ||
        justification is JustificationStrategy.KashidaTo
    val kashidaWidth = if (needsKashidaWidth) {
        fontStack.shapeParagraph(
            ArabicTextUtils.KASHIDA.toString(), sizePx, direction, features, language,
        ).totalAdvance
    } else 0f
    val thinSpaceWidth = fontStack.shapeParagraph(
        WordSpacingJustifier.THIN_SPACE.toString(), sizePx, direction, features, language,
    ).totalAdvance

    val justified = LineJustifier.justify(
        text = text,
        strategy = justification,
        currentWidth = initial.advance,
        targetWidth = targetWidth,
        kashidaGlyphWidth = kashidaWidth,
        thinSpaceWidth = thinSpaceWidth,
    )
    if (justified.justifiedText.length == text.length) return initial
    // Project authored ranges into the justified text so the re-shape
    // keeps each range, connectors included, under its authored font.
    val justifiedFontRuns = justified.originalToJustifiedIndex?.let { m ->
        remapFontRuns(fontRuns, m, justified.justifiedText.length)
    } ?: fontRuns
    val shaped = buildMeasuredText(
        justified.justifiedText, fontStack, sizePx, features, direction, language, justifiedFontRuns,
    )
    val mapping = justified.originalToJustifiedIndex ?: return shaped
    return shaped.withOriginalMapping(originalTextLength = text.length, mapping = mapping)
}


/**
 * Justify [text] to fill EXACTLY [targetWidthPx], never exceeding it — the
 * exact-fill alternative to [buildMeasuredTextWithJustify]'s quantized
 * connector insertion. Two stages, CoreText-style:
 *
 *  1. **Coarse** (script-authentic, never over target): Arabic content is
 *     widened with Kashida (tatweel) via [JustificationStrategy.KashidaTo],
 *     whose floored count keeps the line at or below the target; non-Arabic
 *     content is left at its natural shape.
 *  2. **Fine** (exact): the remaining gap is distributed geometrically with
 *     [AdvanceStretchJustifier.fillToWidth] so the advance lands on
 *     [targetWidthPx] precisely. Latin fills into inter-word spaces; Arabic
 *     distributes the (sub-Kashida) residual across cluster gaps.
 *
 * Because the fine pass is continuous (not connector-quantized) the line never
 * overshoots and a paragraph of these lines ends up uniform width — fixing the
 * "short justified line wider than the longest line" and "lines differ in
 * width" artifacts of pure Kashida / thin-space insertion.
 *
 * Returns the natural shape unchanged when [text] is empty, [targetWidthPx] is
 * non-finite / non-positive, or the natural advance already meets the target.
 *
 * With [fontRuns], the coarse Kashida count is still estimated from a
 * single stack-shaped tatweel; per-range tatweel widths can differ, but
 * the geometric fine pass closes the residual, so the exact-width
 * guarantee holds on mixed-font lines too.
 */
public suspend fun buildMeasuredTextJustified(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
    targetWidthPx: Float,
    fontRuns: List<FontRun> = emptyList(),
): MeasuredText {
    val natural = buildMeasuredText(text, fontStack, sizePx, features, direction, language, fontRuns)
    if (
        text.isEmpty() ||
        !targetWidthPx.isFinite() ||
        targetWidthPx <= 0f ||
        natural.advance >= targetWidthPx
    ) return natural

    val isArabic = ArabicTextUtils.isArabicText(text)

    // Stage 1 — coarse fill, guaranteed at/under target.
    val coarse: MeasuredText = if (isArabic) {
        val kashida = buildMeasuredTextWithJustify(
            text = text,
            fontStack = fontStack,
            sizePx = sizePx,
            features = features,
            direction = direction,
            language = language,
            maxWidth = targetWidthPx,
            justification = JustificationStrategy.KashidaTo(targetWidthPx),
            fontRuns = fontRuns,
        )
        // Guard against a font whose in-context Kashida runs wider than the
        // standalone estimate (would push past target): fall back to natural,
        // the geometric pass below then fills cleanly.
        if (kashida.advance > targetWidthPx) natural else kashida
    } else {
        natural
    }
    if (coarse.advance >= targetWidthPx) return coarse

    // Stage 2 — exact geometric close. Latin distributes into word gaps; Arabic
    // passes null so the small residual spreads across cluster gaps.
    val elastic = if (isArabic) null else wordGapGlyphIndices(coarse, text)
    val filled = AdvanceStretchJustifier.fillToWidth(coarse.paragraph, targetWidthPx, elastic)
    return if (filled === coarse.paragraph) coarse else coarse.withFilledParagraph(filled)
}

/**
 * Global (visual, run-concatenated) indices of [measured]'s inter-word space
 * glyphs — the elastic gaps for Latin word-justify. The line's last glyph and
 * trailing whitespace are excluded so the fill lands between words, not after
 * the final one. Returns `null` when there is no inter-word space (single
 * token), so [AdvanceStretchJustifier.fillToWidth] falls back to cluster gaps.
 */
private fun wordGapGlyphIndices(measured: MeasuredText, text: String): IntArray? {
    val runs = measured.paragraph.runs
    val lastNonEmptyRun = runs.indexOfLast { !it.isEmpty }
    val indices = ArrayList<Int>()
    var globalIndex = 0
    for ((runIndex, run) in runs.withIndex()) {
        for (i in run.glyphs.indices) {
            val isGlobalLast = runIndex == lastNonEmptyRun && i == run.glyphs.lastIndex
            val ch = text.getOrNull(run.glyphs[i].cluster)
            if (!isGlobalLast && ch != null && ch.isWhitespace()) indices.add(globalIndex)
            globalIndex++
        }
    }
    return if (indices.isEmpty()) null else indices.toIntArray()
}

private fun isStaleHbHandle(cause: Throwable): Boolean =
    cause is IllegalStateException && cause.message == "hb object disposed"

/**
 * @param fontRuns Authored font assignments over ranges of [text]; see
 *   [com.mohamedrejeb.harfbuzz.core.shapeParagraph]. Part of the cache
 *   key, so shapes with and without authored runs never collide. The
 *   caller retains ownership of the fonts and must keep them alive for
 *   as long as the returned [MeasuredText] is in use, same as the
 *   stack fonts.
 */
public suspend fun buildMeasuredText(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
    fontRuns: List<FontRun> = emptyList(),
): MeasuredText {
    // Bucket the requested size to [SIZE_PX_BUCKET_PX] before keying the
    // cache and before passing it down to the shaper. Slider scrubs that
    // emit dozens of distinct floats per second collapse to a handful of
    // entries instead of churning the LRU; the resulting [MeasuredText]
    // reports the bucketed size in [MeasuredText.sizePx], so behaviour
    // is deterministic regardless of which slider value arrived first
    // in a bucket. The visual difference between requested and bucketed
    // size is sub-pixel and invisible above small body text.
    val bucketedSizePx = quantizeSizePxForCache(sizePx)
    // Fast path: cache hit on identical layout-affecting inputs. The key
    // intentionally excludes paint-only props (color, alpha, shadow) so
    // animating them never invalidates the entry. See [MeasuredTextCache]
    // for the workloads this catches.
    val cacheKey = measureKeyOf(text, fontStack, bucketedSizePx, features, direction, language, fontRuns)
    MeasuredTextCache.get(cacheKey)?.let { return it }
    val measured = buildMeasuredTextUncached(text, fontStack, bucketedSizePx, features, direction, language, fontRuns)
    // Empty-text builds skip the cache: there's nothing to amortise and an
    // empty entry per (fontStack, features, ...) tuple would just pollute
    // the working set.
    if (text.isNotEmpty()) MeasuredTextCache.put(cacheKey, measured)
    return measured
}

private suspend fun buildMeasuredTextUncached(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
    fontRuns: List<FontRun> = emptyList(),
): MeasuredText = runShapingWork {
    if (text.isEmpty()) {
        // Zero geometry, but real vertical metrics: callers planning line
        // boxes rely on the ascent/descent slots even for empty lines.
        val ext = runCatching { fontStack.primary.hExtents(sizePx) }.getOrNull()
        val emptyAscent = ext?.ascender ?: (sizePx * 0.8f)
        val emptyDescent = ext?.let { -it.descender } ?: (sizePx * 0.2f)
        val emptyLineGap = ext?.lineGap ?: 0f
        return@runShapingWork MeasuredText(
            paragraph = ShapedParagraph.EMPTY,
            fontStack = fontStack,
            sizePx = sizePx,
            ink = Rect.Zero,
            logical = Rect(0f, -emptyAscent, 0f, emptyDescent + emptyLineGap),
            baseline = emptyAscent,
            advance = 0f,
            ascent = emptyAscent,
            descent = emptyDescent,
            lineGap = emptyLineGap,
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

    val perFontFlags = fontStack.fonts.map { defaultFlagsFor(it) }
    val pass = fontStack.buildMeasured(
        text = text,
        sizePx = sizePx,
        baseDirection = direction,
        features = features,
        language = language,
        perFontFlags = perFontFlags,
        fontRuns = fontRuns,
    )

    val flippedPathsByFont = HashMap<HbFont, Map<Int, Path>>()
    val rawPathsByFont = HashMap<HbFont, Map<Int, Path>>()
    val colorLayersByFont = HashMap<HbFont, Map<Int, List<ColorLayer>>>()
    val paintTreesByFont = HashMap<HbFont, Map<Int, List<RecordedPaintOp>>>()
    val svgGlyphCachesByFont = HashMap<HbFont, LazySvgGlyphCache>()

    var anyHasColorGlyphs = false
    var anyHasColorPaint = false
    var anyHasSvg = false

    for ((index, fontPass) in pass.fontPasses.withIndex()) {
        if (index > 0) yieldToBrowser()
        val caches = parseFontPass(fontPass, sizePx = sizePx, svgPreSliced = pass.svgBytesPreSliced)
        val font = fontPass.font
        flippedPathsByFont[font] = caches.flippedPaths
        rawPathsByFont[font] = caches.rawPaths
        colorLayersByFont[font] = caches.colorLayers
        paintTreesByFont[font] = caches.paintTrees
        svgGlyphCachesByFont[font] = caches.svgCache

        val face = font.face
        val hasColorPaint = runCatching { face.hasColorPaint() }.getOrDefault(false)
        val hasColorLayers = runCatching { face.hasColorLayers() }.getOrDefault(false)
        // Bitmap-emoji faces (CBDT/sbix) render through the same paint-tree
        // replay - their trees hold PAINT_IMAGE ops instead of COLR fills.
        val hasColorPng = runCatching { face.hasColorPng() }.getOrDefault(false)

        if (hasColorPaint || hasColorPng) anyHasColorPaint = true
        if (hasColorLayers || hasColorPaint || hasColorPng) anyHasColorGlyphs = true
        if (caches.svgCache.hasAnyGlyphs) {
            anyHasSvg = true
            anyHasColorGlyphs = true
        }
    }

    val advance = pass.paragraph.totalAdvance

    val ext = runCatching { fontStack.primary.hExtents(sizePx) }.getOrNull()
    val ascent = ext?.ascender ?: (sizePx * 0.8f)
    val descent = ext?.let { -it.descender } ?: (sizePx * 0.2f)
    val lineGap = ext?.lineGap ?: 0f
    val baseline = ascent

    // Effective line metrics: max across the base font and every font
    // that contributed a glyph run. hExtents is one dispatcher hop per
    // extra font; contributing sets are tiny in practice.
    var maxAscent = ascent
    var maxDescent = descent
    var maxLineGap = lineGap
    val contributing = LinkedHashSet<HbFont>().apply {
        for (run in pass.paragraph.runs) run.font?.let { add(it) }
        remove(fontStack.primary)
    }
    for (f in contributing) {
        val fx = runCatching { f.hExtents(sizePx) }.getOrNull() ?: continue
        maxAscent = maxOf(maxAscent, fx.ascender)
        maxDescent = maxOf(maxDescent, -fx.descender)
        maxLineGap = maxOf(maxLineGap, fx.lineGap)
    }

    val ink = if (pass.paragraph.ink.isEmpty) Rect.Zero
    else Rect(pass.paragraph.ink.left, pass.paragraph.ink.top, pass.paragraph.ink.right, pass.paragraph.ink.bottom)
    val logical = Rect(0f, -ascent, advance, descent + lineGap)

    MeasuredText(
        paragraph = pass.paragraph,
        fontStack = fontStack,
        sizePx = sizePx,
        ink = ink,
        logical = logical,
        baseline = baseline,
        advance = advance,
        ascent = ascent,
        descent = descent,
        lineGap = lineGap,
        maxAscent = maxAscent,
        maxDescent = maxDescent,
        maxLineGap = maxLineGap,
        textLength = text.length,
        flippedPathsByFont = flippedPathsByFont,
        rawPathsByFont = rawPathsByFont,
        colorLayersByFont = colorLayersByFont,
        paintTreesByFont = paintTreesByFont,
        svgGlyphCachesByFont = svgGlyphCachesByFont,
        hasColorGlyphs = anyHasColorGlyphs,
        hasColorPaintTree = anyHasColorPaint,
        hasColorSvg = anyHasSvg,
    )
}

private class FontCaches(
    val flippedPaths: Map<Int, Path>,
    val rawPaths: Map<Int, Path>,
    val colorLayers: Map<Int, List<ColorLayer>>,
    val paintTrees: Map<Int, List<RecordedPaintOp>>,
    val svgCache: LazySvgGlyphCache,
)

/**
 * Convert a platform-neutral [MeasuredFontPass] into the Compose-typed
 * caches the renderer consumes. SVG path strings + COLR v1 paint-tree
 * bytes are wrapped in lazy `Map<Int, _>` adapters that decode on first
 * `get` and memoise. The build no longer pays the per-glyph decode cost
 * up-front; only the glyphs that actually paint into the viewport (a
 * fraction of the shaped paragraph for color-emoji and CJK lists) get
 * parsed.
 *
 * SVG-in-OT raster work below is unrelated and stays as-is - that one
 * is already lazy via [LazySvgGlyphCache].
 */
private suspend fun parseFontPass(
    pass: MeasuredFontPass,
    sizePx: Float,
    svgPreSliced: Boolean,
): FontCaches {
    val font = pass.font
    val pathScale = font.pathScale

    val flippedPaths = LazyGlyphPathMap(
        rawSvgs = pass.flippedPathSvg,
        flipY = true,
        scale = pathScale,
        font = font,
        sizePx = sizePx,
    )
    val rawPaths = LazyGlyphPathMap(
        rawSvgs = pass.rawPathSvg,
        flipY = false,
        scale = pathScale,
        font = font,
        sizePx = sizePx,
    )
    val paintTrees = LazyPaintTreeMap(pass.paintTreeBytes)

    // SVG-in-OT raster is the single most expensive per-glyph step
    // (Skia SVGDOM parse + paint into an ImageBitmap). When the platform
    // already sliced the per-glyph subtree (Wasm worker), feed the
    // bytes straight through; otherwise run [SvgGlyphSlicer] here on
    // the main thread so JVM / iOS / Android still hand Skia just one
    // glyph at a time. Profiling on the Wasm sample showed ~190 ms of
    // a 414 ms longtask coming from the slicer's byte-pattern scan
    // running on already-sliced bytes - the fast-path skip removes it.
    val upem = font.face.upem
    val svgBytesByGlyph = HashMap<Int, ByteArray>(pass.svgBytes.size)
    var counter = 0
    for ((gid, bytes) in pass.svgBytes) {
        if (bytes.isEmpty()) continue
        val sliced = if (svgPreSliced) bytes else SvgGlyphSlicer.sliceGlyph(bytes, gid) ?: bytes
        svgBytesByGlyph[gid] = sliced
        if (++counter % PATH_YIELD_BATCH == 0) yieldToBrowser()
    }
    val svgCache = LazySvgGlyphCache.build(
        face = font.face,
        svgBytesByGlyph = svgBytesByGlyph,
        pointSize = sizePx,
        upem = upem,
    )

    return FontCaches(
        flippedPaths = flippedPaths,
        rawPaths = rawPaths,
        colorLayers = pass.colorLayers,
        paintTrees = paintTrees,
        svgCache = svgCache,
    )
}

// Yield batch size for the cheap path-parse and paint-tree-decode loops.
// Tuned so a typical Latin/Arabic paragraph (≤30 glyphs) yields once or
// twice - enough to keep input handlers responsive without adding
// noticeable latency to the build itself.
private const val PATH_YIELD_BATCH = 16
