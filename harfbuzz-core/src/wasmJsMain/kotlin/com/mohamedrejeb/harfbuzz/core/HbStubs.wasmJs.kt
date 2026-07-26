@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

/**
 * Wasm-JS implementation of HarfBuzz, backed by the
 * [harfbuzzjs](https://github.com/harfbuzz/harfbuzzjs) npm package, hosted
 * inside a singleton Web Worker (see [HbWorker]).
 *
 * Every HarfBuzz call is a worker round-trip: the main thread holds an
 * integer handle (faceId / fontId) plus a small set of cached aggregates
 * (upem, axes, hExtents, …); the worker owns the actual `hb_face_t` /
 * `hb_font_t` pointers.
 *
 * `close()` paths use [HbWorker.fireAndForget] so they can stay sync; the
 * worker drops the handle when the message arrives. Construction is the
 * one-and-only `suspend` factory: see [HbFace.fromBytes] in commonMain.
 */

private const val HBJS_FIXED_SCALE: Float = 64f

/**
 * Design-space pseudo-size used when minting a wasm hb_font_t. The worker
 * applies setScale per shape/glyph call once the caller's sizePx is in
 * the RPC payload, so the construction-time scale only needs to be
 * non-zero. Mirrors `DESIGN_SCALE_POINT_SIZE` on JVM/iOS.
 */
private const val DESIGN_SCALE_POINT_SIZE: Float = 1f

// ───── HbBlob ───────────────────────────────────────────────────────────────

/**
 * Lives only as a thin shape on Wasm because the wire format never exposes
 * raw blob handles to main - face creation owns its blob inside the worker.
 * Kept around so callers of common APIs that accept `HbBlob` continue to
 * compile; the field is unused in this branch of the codebase.
 */
public actual class HbBlob internal constructor(
    public actual val sizeBytes: Int,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed
    actual override fun close() {
        if (closed) return
        closed = true
    }
}

// ───── HbFace ───────────────────────────────────────────────────────────────

public actual class HbFace internal constructor(
    internal val faceId: Long,
    public actual val upem: Int,
    public actual val faceCount: Int,
    private val cachedAxes: List<HbVariationAxis>,
    private val cachedHasColorLayers: Boolean,
    private val cachedHasColorPaint: Boolean,
    private val cachedHasColorSvg: Boolean,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed

    public actual fun openTypeTags(): List<HbTag> = emptyList()

    public actual fun hasColorLayers(): Boolean {
        throwIfDisposed(closed)
        return cachedHasColorLayers
    }

    public actual fun hasColorPaint(): Boolean {
        throwIfDisposed(closed)
        return cachedHasColorPaint
    }

    public actual fun hasColorPng(): Boolean {
        throwIfDisposed(closed)
        // The worker-side paint walker has no image-op writer yet, so
        // bitmap glyphs can't be drawn on wasm. Report false so nothing
        // ranks a CBDT/sbix font as a drawable color font here.
        return false
    }

    public actual fun hasColorSvg(): Boolean {
        throwIfDisposed(closed)
        return cachedHasColorSvg
    }

    public actual fun variationAxes(): List<HbVariationAxis> {
        throwIfDisposed(closed)
        return cachedAxes
    }

    public actual suspend fun toFont(): HbFont = toFont(emptyList())

    public actual suspend fun toFont(variations: List<HbVariation>): HbFont {
        throwIfDisposed(closed)
        // Mint the worker-side hb_font_t at the design scale; size-dependent
        // RPCs carry their own sizePx so the worker can re-scale per call.
        // Cached design-space ascender/descender/lineGap come back in the
        // reply so [hExtents] can return them after multiplying by sizePx.
        val payload = buildCreateFontPayload(faceId.toInt(), DESIGN_SCALE_POINT_SIZE, variations)
        val reply = HbWorker.send("createFont", payload)
            ?: error("createFont returned null")
        val fontId = jsGetIntField(reply, "fontId").toLong()
        val ascender = jsGetFloatField(reply, "ascender")
        val descender = jsGetFloatField(reply, "descender")
        val lineGap = jsGetFloatField(reply, "lineGap")
        val designExtents = FontExtents(
            ascender = ascender,
            descender = descender,
            lineGap = lineGap,
        )
        return HbFont(this, fontId, designExtents)
    }

    /**
     * Wasm exposes `hb_style_get_value` only via the worker, so the main-
     * side actual returns the registered defaults today. Callers needing
     * exact style on Wasm should pass an explicit [SystemFallback.Match]
     * with a manual [FontStyleHint].
     */
    public actual val styleHint: FontStyleHint = FontStyleHint()

    actual override fun close() {
        if (closed) return
        closed = true
        HbWorker.fireAndForget("destroyFace", buildDestroyFacePayload(faceId.toInt()))
    }

    public actual companion object {
        public actual fun from(block: HbFaceSource.() -> Unit): HbFace =
            error(
                "HbFace.from is unavailable on Wasm - call HbFace.fromBytes(bytes) " +
                    "from a suspend context instead",
            )

        public actual fun tryFrom(block: HbFaceSource.() -> Unit): FaceLoad =
            error(
                "HbFace.tryFrom is unavailable on Wasm - call HbFace.fromBytes(bytes) " +
                    "from a suspend context instead",
            )

        public actual suspend fun fromBytes(bytes: ByteArray, faceIndex: Int): HbFace {
            HbWorker.ensureWorkerReady()
            val payload = buildCreateFacePayload(bytes, faceIndex)
            // Hand the underlying ArrayBuffer to postMessage's transfer list so
            // the worker takes ownership zero-copy. Avoids a structured-clone
            // duplication of the entire font (matters most for large fonts -
            // e.g. NotoColorEmoji is ~25 MB).
            val transfer = jsExtractCreateFaceTransfer(payload)
            val reply = HbWorker.send("createFace", payload, transfer)
                ?: throw HbException("createFace returned null")
            val faceId = jsGetIntField(reply, "faceId").toLong()
            val upem = jsGetIntField(reply, "upem")
            val hasColorLayers = jsGetIntField(reply, "hasColorLayers") != 0
            val hasColorPaint = jsGetIntField(reply, "hasColorPaint") != 0
            val hasColorSvg = jsGetIntField(reply, "hasColorSvg") != 0
            val axes = decodeAxesFromCreateFaceReply(reply)
            // harfbuzzjs doesn't expose hb_face_count today - multi-face TTC
            // support needs a worker-side binding. Fix this when we wire up
            // hb_face_count; until then every TTC face counts as 1.
            return HbFace(
                faceId = faceId,
                upem = upem,
                faceCount = 1,
                cachedAxes = axes,
                cachedHasColorLayers = hasColorLayers,
                cachedHasColorPaint = hasColorPaint,
                cachedHasColorSvg = hasColorSvg,
            )
        }
    }
}

public actual class HbFaceSource internal constructor() {
    internal var _bytes: ByteArray? = null
    internal var _faceIndex: Int = 0

    public actual fun bytes(bytes: ByteArray, faceIndex: Int) {
        _bytes = bytes
        _faceIndex = faceIndex
    }

    /**
     * No-op on Wasm - the browser sandbox isolates the runtime from
     * any host filesystem, so there's nothing to mmap. The companion
     * `tryFrom` errors out for *any* sync face load on Wasm anyway
     * (Wasm requires the suspend `fromBytes`), so the path is
     * effectively unreachable here. Callers that share code across
     * platforms can still pass `path(...)` - it just gets ignored on
     * Wasm and the suspend `bytes` path is the only one that works.
     */
    public actual fun path(path: String, faceIndex: Int) {
        _faceIndex = faceIndex
    }
}

// ───── HbFont ───────────────────────────────────────────────────────────────

public actual class HbFont internal constructor(
    public actual val face: HbFace,
    internal val fontId: Long,
    private val designExtents: FontExtents,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed

    /** harfbuzzjs's `font.glyphToPath` emits 26.6 fixed-point - divide by 64. */
    public actual val pathScale: Float = 1f / HBJS_FIXED_SCALE

    /**
     * Scale the design-space metrics captured at [HbFace.toFont] time by
     * `sizePx / DESIGN_SCALE_POINT_SIZE` (== `sizePx / 1f`). The worker
     * already converts hb's 26.6 fixed-point to pixels at the design
     * size, so the final scaling is a single multiply on the main side
     * with no extra round-trip.
     */
    public actual suspend fun hExtents(sizePx: Float): FontExtents? {
        throwIfDisposed(closed)
        val k = sizePx / DESIGN_SCALE_POINT_SIZE
        return FontExtents(
            ascender = designExtents.ascender * k,
            descender = designExtents.descender * k,
            lineGap = designExtents.lineGap * k,
        )
    }

    public actual suspend fun glyphIdForCodepoint(codepoint: Int): Int {
        throwIfDisposed(closed)
        val payload = buildGlyphIdForCodepointPayload(fontId.toInt(), codepoint)
        val reply = HbWorker.send("getGlyphIdForCodepoint", payload)
            ?: return 0
        return jsGetIntField(reply, "glyphId")
    }

    public actual suspend fun glyphAdvance(glyphId: Int, sizePx: Float): Float {
        throwIfDisposed(closed)
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId, sizePx)
        val reply = HbWorker.send("getGlyphAdvance", payload)
            ?: return 0f
        return jsGetFloatField(reply, "advance")
    }

    public actual suspend fun glyphExtents(glyphId: Int, sizePx: Float): GlyphExtents? {
        throwIfDisposed(closed)
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId, sizePx)
        val reply = HbWorker.send("getGlyphExtents", payload) ?: return null
        if (jsGetIntField(reply, "ok") == 0) return null
        return GlyphExtents(
            xBearing = jsGetFloatField(reply, "xBearing"),
            yBearing = jsGetFloatField(reply, "yBearing"),
            width = jsGetFloatField(reply, "width"),
            height = jsGetFloatField(reply, "height"),
        )
    }

    public actual suspend fun shape(buffer: HbBuffer, sizePx: Float): ShapedRun {
        throwIfDisposed(closed)
        check(!buffer.isClosed) { "hb object disposed" }
        val payload = buildShapeRunPayload(
            fontId = fontId.toInt(),
            sizePx = sizePx,
            text = buffer.text,
            direction = bufferDirectionToString(buffer.direction),
            scriptTag = buffer.script.tag.raw.toInt(),
            language = languageToTag(buffer.language),
            features = buffer.features,
        )
        val reply = HbWorker.send("shapeRun", payload)
            ?: return ShapedRun.EMPTY.copy(direction = buffer.direction, script = buffer.script)
        return decodeShapedRun(reply, fallbackDirection = buffer.direction, fallbackScript = buffer.script)
    }

    public actual suspend fun shapeParagraph(
        text: String,
        sizePx: Float,
        baseDirection: HbDirection,
        features: List<HbFeature>,
        language: HbLanguage,
    ): ShapedParagraph {
        throwIfDisposed(closed)
        if (text.isEmpty()) return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)

        // Resolve BiDi on main - pure Kotlin, no JS interop. Worker receives
        // the pre-resolved sequence and shapes each run.
        val resolver = BidiResolver()
        val bidiRuns = resolver.resolve(text, baseDirection)
        if (bidiRuns.isEmpty()) return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)

        val resolvedBaseDirection = if (baseDirection == HbDirection.AUTO) {
            if (bidiRuns.firstOrNull()?.isRtl == true) HbDirection.RTL else HbDirection.LTR
        } else baseDirection

        val payload = buildShapeParagraphPayload(
            fontId = fontId.toInt(),
            sizePx = sizePx,
            text = text,
            baseDirection = bufferDirectionToString(baseDirection),
            language = languageToTag(language),
            features = features,
            bidiRuns = bidiRuns,
        )
        val reply = perfTrack("hb.shapeParagraph.send") {
            HbWorker.send("shapeParagraph", payload)
        } ?: return ShapedParagraph.EMPTY.copy(baseDirection = resolvedBaseDirection)
        return perfTrackSync("hb.shapeParagraph.decode") {
            decodeShapedParagraph(
                reply,
                text = text,
                resolvedBaseDirection = resolvedBaseDirection,
            )
        }
    }

    public actual suspend fun drawGlyph(glyphId: Int, sizePx: Float, sink: HbPathSink) {
        throwIfDisposed(closed)
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId, sizePx)
        val reply = HbWorker.send("getGlyphPath", payload) ?: return
        val svgPath = jsGetStringField(reply, "svg")
        if (svgPath.isEmpty()) return
        parseSvgPathToSink(svgPath, sink, scale = pathScale)
    }

    public actual suspend fun glyphColorLayers(glyphId: Int): List<ColorLayer> {
        throwIfDisposed(closed)
        // Color-layer structure is sizeless (face-level OT-COLR data); the
        // worker still routes through the font handle, so a unit sizePx
        // keeps the wire format uniform without affecting the output.
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId, DESIGN_SCALE_POINT_SIZE)
        val reply = HbWorker.send("getGlyphColorLayers", payload) ?: return emptyList()
        val layers = jsGetField(reply, "layers") ?: return emptyList()
        return decodeColorLayers(layers)
    }

    public actual suspend fun paintGlyph(
        glyphId: Int,
        sizePx: Float,
        foreground: Int,
        paletteIndex: Int,
        sink: HbPaintSink,
    ) {
        throwIfDisposed(closed)
        val payload = buildPaintGlyphPayload(
            fontId = fontId.toInt(),
            gid = glyphId,
            sizePx = sizePx,
            foreground = foreground,
            paletteIndex = paletteIndex,
        )
        val reply = HbWorker.send("getGlyphPaint", payload) ?: return
        val bytes = jsGetField(reply, "bytes") ?: return
        if (jsTypedArrayLength(bytes) == 0) return
        WasmPaintBufferParser.dispatch(bytes, sink)
    }

    public actual suspend fun glyphSvg(glyphId: Int): ByteArray? {
        throwIfDisposed(closed)
        // SVG-in-OT bytes are sizeless face data; pass a unit sizePx for
        // wire-format uniformity.
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId, DESIGN_SCALE_POINT_SIZE)
        val reply = HbWorker.send("getGlyphSvg", payload) ?: return null
        val bytes = jsGetField(reply, "bytes") ?: return null
        val out = jsUint8ArrayToByteArray(bytes)
        return if (out.isEmpty()) null else out
    }

    public actual suspend fun snapshotGlyphs(
        gids: IntArray,
        sizePx: Float,
        flags: GlyphSnapshotFlags,
    ): GlyphSnapshot {
        throwIfDisposed(closed)
        if (gids.isEmpty()) {
            return GlyphSnapshot(
                flippedPathSvg = emptyMap(),
                rawPathSvg = emptyMap(),
                colorLayers = emptyMap(),
                paintTreeBytes = emptyMap(),
                svgBytes = emptyMap(),
            )
        }
        val payload = buildSnapshotGlyphsPayload(
            fontId = fontId.toInt(),
            sizePx = sizePx,
            gids = gids,
            flippedPath = flags.flippedPath,
            rawPath = flags.rawPath,
            colorLayers = flags.colorLayers,
            paintTree = flags.paintTree,
            svg = flags.svg,
        )
        val reply = perfTrack("hb.snapshotGlyphs.send") {
            HbWorker.send("snapshotGlyphs", payload)
        } ?: error("snapshotGlyphs returned null")
        return perfTrackSync("hb.snapshotGlyphs.decode(${gids.size})") {
            decodeGlyphSnapshot(reply, gids, flags)
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        HbWorker.fireAndForget("destroyFont", buildDestroyFontPayload(fontId.toInt()))
    }
}

// ───── HbBuffer ─────────────────────────────────────────────────────────────

/**
 * Pure Kotlin state holder on Wasm - no JS-side handle. The state is
 * serialised into the `shapeRun` RPC payload when [HbFont.shape] is called.
 */
public actual class HbBuffer actual constructor() : AutoCloseable {
    private var closed: Boolean = false

    public actual var text: String = ""
        set(value) {
            throwIfDisposed(closed)
            field = value
        }

    public actual var direction: HbDirection = HbDirection.AUTO
        set(value) {
            throwIfDisposed(closed)
            field = value
        }

    public actual var script: HbScript = HbScript.AUTO
        set(value) {
            throwIfDisposed(closed)
            field = value
        }

    public actual var language: HbLanguage = HbLanguage.AUTO
        set(value) {
            throwIfDisposed(closed)
            field = value
        }

    public actual var features: List<HbFeature> = emptyList()

    public actual val isClosed: Boolean get() = closed

    public actual fun reset() {
        throwIfDisposed(closed)
        text = ""
        direction = HbDirection.AUTO
        script = HbScript.AUTO
        language = HbLanguage.AUTO
        features = emptyList()
    }

    actual override fun close() {
        if (closed) return
        closed = true
    }
}

// ───── Wire-format encoders / decoders ─────────────────────────────────────

internal fun bufferDirectionToString(d: HbDirection): String = when (d) {
    HbDirection.LTR -> "ltr"
    HbDirection.RTL -> "rtl"
    HbDirection.TTB -> "ttb"
    HbDirection.BTT -> "btt"
    HbDirection.AUTO -> "auto"
}

private fun languageToTag(lang: HbLanguage): String =
    if (lang == HbLanguage.AUTO) "auto" else lang.bcp47

internal fun decodeShapedRun(
    reply: JsAny,
    fallbackDirection: HbDirection,
    fallbackScript: HbScript,
): ShapedRun {
    val direction = stringToDirection(jsGetStringField(reply, "direction"), fallback = fallbackDirection)
    val n = jsGetIntField(reply, "glyphCount")
    if (n == 0) {
        return ShapedRun.EMPTY.copy(direction = direction, script = fallbackScript)
    }
    // The worker packs each run as { glyphsBuf: Int32Array, positionsBuf:
    // Float32Array }. Bulk-copy each typed array into wasm linear memory
    // once via [jsCopy*ArrayToWasmMemory] (one JS↔Wasm boundary crossing
    // per array) and walk the resulting IntArray/FloatArray in pure
    // Kotlin. Without this packing, decoding a 500-glyph paragraph
    // crossed the boundary ~3500 times - the dominant main-thread cost
    // on first scroll for big shaped paragraphs.
    val glyphsBuf = jsGetField(reply, "glyphsBuf")
    val positionsBuf = jsGetField(reply, "positionsBuf")
    if (glyphsBuf == null || positionsBuf == null) {
        return ShapedRun.EMPTY.copy(direction = direction, script = fallbackScript)
    }
    val glyphInts = jsInt32ArrayToIntArray(glyphsBuf)
    val positionFloats = jsFloat32ArrayToFloatArray(positionsBuf)
    val glyphList = ArrayList<GlyphInfo>(n)
    val posList = ArrayList<GlyphPosition>(n)
    for (i in 0 until n) {
        val gi = i * 3
        glyphList.add(
            GlyphInfo(
                glyphId = glyphInts[gi],
                cluster = glyphInts[gi + 1],
                flags = glyphInts[gi + 2],
            ),
        )
        val pi = i * 4
        posList.add(
            GlyphPosition(
                xAdvance = positionFloats[pi],
                yAdvance = positionFloats[pi + 1],
                xOffset = positionFloats[pi + 2],
                yOffset = positionFloats[pi + 3],
            ),
        )
    }
    val totalAdvance = jsGetFloatField(reply, "totalAdvance")
    val ink = decodeRect(jsGetField(reply, "ink"))
    val logical = decodeRect(jsGetField(reply, "logical"))
    return ShapedRun(
        glyphs = glyphList,
        positions = posList,
        direction = direction,
        script = fallbackScript,
        totalAdvance = totalAdvance,
        ink = ink,
        logical = logical,
    )
}

private fun stringToDirection(s: String, fallback: HbDirection): HbDirection = when (s) {
    "ltr" -> HbDirection.LTR
    "rtl" -> HbDirection.RTL
    "ttb" -> HbDirection.TTB
    "btt" -> HbDirection.BTT
    else -> fallback
}

internal fun decodeRect(rect: JsAny?): HbRect {
    if (rect == null) return HbRect.EMPTY
    if (jsGetIntField(rect, "isEmpty") != 0) return HbRect.EMPTY
    return HbRect(
        left = jsGetFloatField(rect, "left"),
        top = jsGetFloatField(rect, "top"),
        right = jsGetFloatField(rect, "right"),
        bottom = jsGetFloatField(rect, "bottom"),
    )
}

internal fun decodeShapedParagraph(
    reply: JsAny,
    text: String,
    resolvedBaseDirection: HbDirection,
): ShapedParagraph {
    val runsArray = jsGetField(reply, "runs")
    val totalAdvance = jsGetFloatField(reply, "totalAdvance")
    val ink = decodeRect(jsGetField(reply, "ink"))
    val n = text.length
    val runCount = if (runsArray != null) jsArrayLength(runsArray) else 0
    val runs = if (runCount == 0) {
        emptyList()
    } else {
        ArrayList<ShapedRun>(runCount).apply {
            for (i in 0 until runCount) {
                val runJs = jsArrayGet(runsArray!!, i)
                add(decodeShapedRun(runJs, fallbackDirection = resolvedBaseDirection, fallbackScript = HbScript.AUTO))
            }
        }
    }
    return ShapedParagraph(
        runs = runs,
        baseDirection = resolvedBaseDirection,
        totalAdvance = totalAdvance,
        ink = ink,
        logical = HbRect(0f, 0f, totalAdvance, 0f),
        logicalToVisual = identityIntArray(n),
        visualToLogical = identityIntArray(n),
    )
}

internal fun decodeColorLayers(layers: JsAny): List<ColorLayer> {
    val len = jsTypedArrayLength(layers)
    if (len == 0) return emptyList()
    val count = jsInt32ArrayGet(layers, 0)
    if (count <= 0) return emptyList()
    return List(count) { i ->
        val gid = jsInt32ArrayGet(layers, 1 + i * 2)
        val argb = jsInt32ArrayGet(layers, 1 + i * 2 + 1)
        ColorLayer(glyphId = gid, argb = if (argb == 0) null else argb)
    }
}

internal fun decodeGlyphSnapshot(
    reply: JsAny,
    gids: IntArray,
    flags: GlyphSnapshotFlags,
): GlyphSnapshot {
    val flipped = if (flags.flippedPath) HashMap<Int, String>(gids.size) else null
    val raw = if (flags.rawPath) HashMap<Int, String>(gids.size) else null
    val layers = if (flags.colorLayers) HashMap<Int, List<ColorLayer>>(gids.size) else null
    val paint = if (flags.paintTree) HashMap<Int, ByteArray>(gids.size) else null
    val svg = if (flags.svg) HashMap<Int, ByteArray>(gids.size) else null

    val flippedJs = if (flags.flippedPath) jsGetField(reply, "flippedPaths") else null
    val rawJs = if (flags.rawPath) jsGetField(reply, "rawPaths") else null
    val layersJs = if (flags.colorLayers) jsGetField(reply, "colorLayers") else null
    val paintJs = if (flags.paintTree) jsGetField(reply, "paintTrees") else null
    val svgJs = if (flags.svg) jsGetField(reply, "svgBytes") else null

    for (gid in gids) {
        if (flipped != null && flippedJs != null) {
            val s = jsGetMapStringByKey(flippedJs, gid)
            if (s != null) flipped[gid] = s
        }
        if (raw != null && rawJs != null) {
            val s = jsGetMapStringByKey(rawJs, gid)
            if (s != null) raw[gid] = s
        }
        if (layers != null && layersJs != null) {
            val arr = jsGetMapEntryByKey(layersJs, gid)
            layers[gid] = if (arr != null) decodeColorLayers(arr) else emptyList()
        }
        if (paint != null && paintJs != null) {
            val arr = jsGetMapEntryByKey(paintJs, gid)
            if (arr != null) {
                val bytes = jsUint8ArrayToByteArray(arr)
                if (bytes.isNotEmpty()) paint[gid] = bytes
            }
        }
        if (svg != null && svgJs != null) {
            val arr = jsGetMapEntryByKey(svgJs, gid)
            if (arr != null) {
                val bytes = jsUint8ArrayToByteArray(arr)
                if (bytes.isNotEmpty()) svg[gid] = bytes
            }
        }
    }
    return GlyphSnapshot(
        flippedPathSvg = flipped ?: emptyMap(),
        rawPathSvg = raw ?: emptyMap(),
        colorLayers = layers ?: emptyMap(),
        paintTreeBytes = paint ?: emptyMap(),
        svgBytes = svg ?: emptyMap(),
    )
}
