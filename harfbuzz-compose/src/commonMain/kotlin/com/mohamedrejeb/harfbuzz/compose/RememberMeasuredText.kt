package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.ColorLayer
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.MeasuredFontPass
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.buildMeasured
import com.mohamedrejeb.harfbuzz.core.defaultFlagsFor
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
): State<MeasuredTextLoad> {
    val stack = remember(font) { HbFontStack(font) }
    return rememberMeasuredText(text, stack, sizePx, features, direction, language, maxWidth, justification)
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
): State<MeasuredTextLoad> {
    return produceState<MeasuredTextLoad>(
        initialValue = MeasuredTextLoad.Loading,
        text, fontStack, sizePx, features, direction, language, maxWidth, justification,
    ) {
        // Deliberately do NOT reset to Loading here: keep the previous
        // Ready value visible while the new build runs so size /
        // feature / justification animation does not blank the text.
        try {
            val measured = buildMeasuredTextWithJustify(
                text, fontStack, sizePx, features, direction, language, maxWidth, justification,
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
): MeasuredText {
    val initial = buildMeasuredText(text, fontStack, sizePx, features, direction, language)
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
    val shaped = buildMeasuredText(
        justified.justifiedText, fontStack, sizePx, features, direction, language,
    )
    val mapping = justified.originalToJustifiedIndex ?: return shaped
    return shaped.withOriginalMapping(originalTextLength = text.length, mapping = mapping)
}

private fun isStaleHbHandle(cause: Throwable): Boolean =
    cause is IllegalStateException && cause.message == "hb object disposed"

public suspend fun buildMeasuredText(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    features: List<HbFeature>,
    direction: HbDirection,
    language: HbLanguage,
): MeasuredText {
    // Fast path: cache hit on identical layout-affecting inputs. The key
    // intentionally excludes paint-only props (color, alpha, shadow) so
    // animating them never invalidates the entry. See [MeasuredTextCache]
    // for the workloads this catches.
    val cacheKey = measureKeyOf(text, fontStack, sizePx, features, direction, language)
    MeasuredTextCache.get(cacheKey)?.let { return it }
    val measured = buildMeasuredTextUncached(text, fontStack, sizePx, features, direction, language)
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
): MeasuredText = runShapingWork {
    if (text.isEmpty()) return@runShapingWork MeasuredText.empty(fontStack.primary, sizePx)

    val perFontFlags = fontStack.fonts.map { defaultFlagsFor(it) }
    val pass = fontStack.buildMeasured(
        text = text,
        sizePx = sizePx,
        baseDirection = direction,
        features = features,
        language = language,
        perFontFlags = perFontFlags,
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

        if (hasColorPaint) anyHasColorPaint = true
        if (hasColorLayers || hasColorPaint) anyHasColorGlyphs = true
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

    val flippedPaths = LazyGlyphPathMap(pass.flippedPathSvg, flipY = true, scale = pathScale)
    val rawPaths = LazyGlyphPathMap(pass.rawPathSvg, flipY = false, scale = pathScale)
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
