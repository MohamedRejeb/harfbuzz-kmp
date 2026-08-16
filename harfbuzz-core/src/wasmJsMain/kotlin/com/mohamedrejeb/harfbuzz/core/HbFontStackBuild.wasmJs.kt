@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm fast-path for [HbFontStack.buildMeasured]: one Web Worker round-trip
 * for the entire pipeline (BiDi-resolved shape with multi-font fallback,
 * per-font glyph snapshot, clip-glyph + COLR v0 base re-snapshot). The
 * worker handler is `rpcBuildMeasured` in `native/harfbuzzjs/hb-worker.js`.
 *
 * Returning a single non-null [MeasuredPass] here means the platform-neutral
 * orchestrator in `HbFontStackBuild.kt` skips its slow path entirely.
 */
internal actual suspend fun HbFontStack.tryBuildMeasuredFastPath(
    text: String,
    sizePx: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    perFontFlags: List<GlyphSnapshotFlags>,
    fontRuns: List<FontRun>,
): MeasuredPass? {
    if (text.isEmpty() || fonts.isEmpty()) return null

    // The mega-RPC has no authored-run concept; fall back to the portable
    // shapeParagraph plus snapshot path, which does (slower but correct).
    if (fontRuns.isNotEmpty()) return null

    // Surface stale-handle races as the same IllegalStateException the
    // single-RPC paths emit, so produceState's `isStaleHbHandle` swallow
    // continues to work. A font closed mid-flight would otherwise throw
    // `unknown fontId N` from the worker, which Compose has no way to
    // identify as the recompose-driven race it actually is.
    fonts.forEach { throwIfDisposed(it.isClosed) }

    HbWorker.ensureWorkerReady()

    // BiDi resolution stays on main (pure Kotlin). Pre-resolved runs land
    // in the worker payload - same pattern as `shapeParagraph`.
    val resolver = BidiResolver()
    val bidiRuns = resolver.resolve(text, baseDirection)
    if (bidiRuns.isEmpty()) {
        return MeasuredPass(
            paragraph = ShapedParagraph.EMPTY.copy(baseDirection = baseDirection),
            fontPasses = emptyList(),
            svgBytesPreSliced = true,
        )
    }

    val resolvedBaseDirection = if (baseDirection == HbDirection.AUTO) {
        if (bidiRuns.firstOrNull()?.isRtl == true) HbDirection.RTL else HbDirection.LTR
    } else baseDirection

    val payload = buildBuildMeasuredPayload(
        fontIds = IntArray(fonts.size) { fonts[it].fontId.toInt() },
        sizePx = sizePx,
        text = text,
        baseDirection = bufferDirectionToString(baseDirection),
        language = if (language == HbLanguage.AUTO) "auto" else language.bcp47,
        features = features,
        bidiRuns = bidiRuns,
        perFontFlags = perFontFlags,
    )

    val reply = perfTrack("hb.buildMeasured.send") {
        HbWorker.send("buildMeasured", payload)
    } ?: return null

    return perfTrack("hb.buildMeasured.decode") {
        decodeBuildMeasured(
            reply = reply,
            text = text,
            fontStackFonts = fonts,
            resolvedBaseDirection = resolvedBaseDirection,
        )
    }
}

/**
 * Wasm: the slow path is unreachable (the fast path above always returns
 * a non-null [MeasuredPass]), so this value isn't consulted in practice.
 * Keep it `true` to match the fast-path's `svgBytesPreSliced = true`
 * convention - the worker pre-slices each glyph subtree inline.
 */
internal actual val nativeSlicesSvgInOt: Boolean = true
