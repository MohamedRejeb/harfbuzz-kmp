package com.mohamedrejeb.harfbuzz.core

import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp.PushClipGlyph

/**
 * Shape [text] with this font stack and bundle every per-glyph cache the
 * renderer might need into a single [MeasuredPass]. Returns paragraph-level
 * shaping output plus, for every contributing font, the path / color-layer
 * / paint-tree / SVG bytes the Compose renderer pulls in.
 *
 * Implementation strategy: platform fast paths get a single trip through
 * the platform's HarfBuzz binding. On Wasm that's one Web Worker
 * round-trip per call - the redesigned wire format (`buildMeasured` RPC
 * in `native/harfbuzzjs/hb-worker.js`) collapses the previous BiDi-shape
 * + per-font snapshot + clip-glyph re-snapshot pipeline into one mega
 * request. On JVM / iOS / Android the JNI path is already sync per call,
 * so we sequence the existing primitives in the platform-neutral
 * fallback below - same number of calls as before, just bundled.
 *
 * The mega-RPC matters on Wasm because the worker message queue is
 * single-threaded: 12 paragraphs initialising in parallel used to enqueue
 * ~3 messages each, and the second `snapshotGlyphs` call (for clip
 * glyphs in COLR v1 paint trees) landed at the back of the queue and
 * blocked rendering for ~13 seconds. With one RPC per paragraph there's
 * still serialisation, but no per-paragraph head-of-line wait - the user
 * sees paragraphs pop in incrementally.
 *
 * @param perFontFlags Flags for which glyph tables to fetch per font in
 *   the stack, indexed parallel to [HbFontStack.fonts]. The default
 *   probes each font's face capability flags so any font with a COLR v1
 *   paint table contributes paint-tree bytes, any face with an `SVG `
 *   table contributes SVG fragments, etc. Passing a custom list lets a
 *   caller skip work it doesn't need (e.g. monochrome-only renderer).
 */
public suspend fun HbFontStack.buildMeasured(
    text: String,
    sizePx: Float,
    baseDirection: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    perFontFlags: List<GlyphSnapshotFlags> = fonts.map { defaultFlagsFor(it) },
): MeasuredPass {
    require(perFontFlags.size == fonts.size) {
        "perFontFlags must have ${fonts.size} entries (one per font in stack), got ${perFontFlags.size}"
    }
    if (text.isEmpty()) {
        return MeasuredPass(
            paragraph = ShapedParagraph.EMPTY.copy(baseDirection = baseDirection),
            fontPasses = emptyList(),
        )
    }
    tryBuildMeasuredFastPath(text, sizePx, baseDirection, features, language, perFontFlags)?.let { return it }

    val paragraph = shapeParagraph(text, sizePx, baseDirection, features, language)
    return MeasuredPass(
        paragraph = paragraph,
        fontPasses = collectFontPassesViaSnapshot(paragraph, sizePx, perFontFlags),
        svgBytesPreSliced = nativeSlicesSvgInOt,
    )
}

/**
 * `true` when the platform's [HbFont.snapshotGlyphs] returns SVG bytes
 * that are already sliced down to the per-glyph `<g id="glyphN">`
 * subtree (plus the document's `<defs>`). On JVM/Android the C++ slicer
 * inside the JNI bridge does this; on iOS the cinterop path returns
 * whole documents and the Compose layer's [SvgGlyphSlicer] still runs;
 * Wasm uses its own fast path that bypasses this slow-path entirely.
 *
 * When `true`, [MeasuredPass.svgBytesPreSliced] flips on, telling the
 * Compose-layer slicer to skip its byte scan. Output is bit-identical
 * either way - this is purely a perf-vs-portability split.
 */
internal expect val nativeSlicesSvgInOt: Boolean

/**
 * Default per-face cache-flags for a font. Mirrors the heuristics in the
 * Compose layer: every font needs flipped outlines for mono fallbacks;
 * COLR v1 fonts also need raw outlines (for `pushClipGlyph`) and paint
 * trees; COLR v0 fonts need color layer descriptors; faces with an `SVG `
 * table need their per-glyph SVG bytes.
 *
 * When a face ships **both** SVG-in-OT and COLR (e.g. NotoColorEmoji), we
 * skip the COLR caches entirely. The Compose renderer's priority cascade
 * (`drawShapedText`) is `forceForeground → SVG → COLR-v1 → COLR-v0 → mono`,
 * and crucially `useSvg = true` on a run *suppresses* both `useV1Paint`
 * and `useV0Layers` for every glyph in that run - even glyphs that have
 * no SVG fragment fall through to the monochrome outline rather than
 * COLR. So the paint-tree / raw-path / color-layer data we'd collect
 * here is never consumed when SVG is present, and skipping its JNI
 * collection is a sizeable cold-start win on large emoji fonts (~1 MB
 * of paint-tree wire bytes per emoji-bearing paragraph for
 * NotoColorEmoji's COLR table, plus the matching half of `paths=222`
 * raw outlines).
 */
public fun defaultFlagsFor(font: HbFont): GlyphSnapshotFlags {
    val face = font.face
    val hasColorPaint = runCatching { face.hasColorPaint() }.getOrDefault(false)
    val hasColorLayers = runCatching { face.hasColorLayers() }.getOrDefault(false)
    val hasColorSvg = runCatching { face.hasColorSvg() }.getOrDefault(false)
    // Bitmap-emoji faces (CBDT/sbix) walk the same paint funcs and emit
    // PAINT_IMAGE ops, so they need paint trees collected just like COLR.
    // `hasColorPng` is false on platforms whose paint walker can't forward
    // image ops yet (iOS, wasm), which keeps their collection unchanged.
    val hasColorPng = runCatching { face.hasColorPng() }.getOrDefault(false)
    val needsColr = !hasColorSvg
    return GlyphSnapshotFlags(
        flippedPath = true,
        rawPath = hasColorPaint && needsColr,
        colorLayers = hasColorLayers && needsColr,
        paintTree = (hasColorPaint || hasColorPng) && needsColr,
        svg = hasColorSvg,
    )
}

/**
 * Hook for platform fast paths. Wasm's actual collapses the entire build
 * into one worker round-trip; every other platform's actual returns
 * `null`, signalling the caller to use the platform-neutral slow path.
 */
internal expect suspend fun HbFontStack.tryBuildMeasuredFastPath(
    text: String,
    sizePx: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    perFontFlags: List<GlyphSnapshotFlags>,
): MeasuredPass?

/**
 * Slow path: one [HbFont.snapshotGlyphs] per contributing font, plus a
 * second `snapshotGlyphs` for paint-tree clip glyphs and COLR v0 base
 * glyph references. Identical to what [com.mohamedrejeb.harfbuzz] callers
 * used to do by hand inside the Compose layer's `buildMeasuredText`,
 * just lifted into one place so JVM / iOS / Android share an
 * implementation.
 */
private suspend fun HbFontStack.collectFontPassesViaSnapshot(
    paragraph: ShapedParagraph,
    sizePx: Float,
    perFontFlags: List<GlyphSnapshotFlags>,
): List<MeasuredFontPass> {
    val contributing = LinkedHashSet<HbFont>().apply {
        add(primary)
        for (run in paragraph.runs) run.font?.let { add(it) }
    }
    val fontIndices = HashMap<HbFont, Int>(fonts.size).apply {
        fonts.forEachIndexed { i, f -> this[f] = i }
    }

    return contributing.map { font ->
        val gids = HashSet<Int>()
        for (run in paragraph.runs) {
            val rf = run.font
            if (rf != null && rf != font) continue
            for (g in run.glyphs) if (g.glyphId != 0) gids.add(g.glyphId)
        }
        if (gids.isEmpty()) {
            return@map MeasuredFontPass(
                font = font,
                flippedPathSvg = emptyMap(),
                rawPathSvg = emptyMap(),
                colorLayers = emptyMap(),
                paintTreeBytes = emptyMap(),
                svgBytes = emptyMap(),
            )
        }
        // System fallback fonts aren't in the explicit `fonts` list, so the
        // caller's `perFontFlags` doesn't have an entry for them. Probing
        // their face capabilities directly is what makes emoji from system
        // fallback render - without this they'd inherit perFontFlags[0]
        // (the primary's flags), which on a Latin primary asks only for
        // monochrome outlines and skips the COLR / SVG tables NotoColorEmoji
        // actually uses.
        val flags = fontIndices[font]?.let { perFontFlags[it] } ?: defaultFlagsFor(font)

        val first = font.snapshotGlyphs(gids.toIntArray(), sizePx, flags)
        val flipped = HashMap<Int, String>(first.flippedPathSvg.size).apply { putAll(first.flippedPathSvg) }
        val raw = HashMap<Int, String>(first.rawPathSvg.size).apply { putAll(first.rawPathSvg) }

        val extras = HashSet<Int>()
        if (flags.paintTree) {
            for (bytes in first.paintTreeBytes.values) collectClipGlyphsFromPaintBytes(bytes, extras)
        }
        if (flags.colorLayers) {
            for (layers in first.colorLayers.values) for (l in layers) extras.add(l.glyphId)
        }
        extras.remove(0)
        extras.removeAll(gids)

        if (extras.isNotEmpty() && (flags.flippedPath || flags.rawPath)) {
            val extraSnap = font.snapshotGlyphs(
                gids = extras.toIntArray(),
                sizePx = sizePx,
                flags = GlyphSnapshotFlags(flippedPath = flags.flippedPath, rawPath = flags.rawPath),
            )
            flipped.putAll(extraSnap.flippedPathSvg)
            raw.putAll(extraSnap.rawPathSvg)
        }

        MeasuredFontPass(
            font = font,
            flippedPathSvg = flipped,
            rawPathSvg = raw,
            colorLayers = first.colorLayers,
            paintTreeBytes = first.paintTreeBytes,
            svgBytes = first.svgBytes,
        )
    }
}

/**
 * Walk a paint-tree wire buffer and collect every `PushClipGlyph` target
 * into [out]. Mirror of `collectClipGlyphsFromPaintBytes` in the worker -
 * we keep two copies because the worker is JS, this is Kotlin, and the
 * wire format originates here. Only used on the slow path; the worker
 * does the same scan in-line on the Wasm fast path.
 */
private fun collectClipGlyphsFromPaintBytes(bytes: ByteArray, out: MutableSet<Int>) {
    val recorder = RecordingPaintSink()
    runCatching { PaintBufferParser.dispatch(bytes, recorder) }
    for (op in recorder.ops) if (op is PushClipGlyph) out.add(op.glyphId)
}
