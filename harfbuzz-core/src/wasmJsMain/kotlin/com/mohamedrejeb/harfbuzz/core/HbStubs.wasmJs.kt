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
        check(!closed) { "hb object disposed" }
        return cachedHasColorLayers
    }

    public actual fun hasColorPaint(): Boolean {
        check(!closed) { "hb object disposed" }
        return cachedHasColorPaint
    }

    public actual fun hasColorSvg(): Boolean {
        check(!closed) { "hb object disposed" }
        return cachedHasColorSvg
    }

    public actual fun variationAxes(): List<HbVariationAxis> {
        check(!closed) { "hb object disposed" }
        return cachedAxes
    }

    public actual suspend fun toFont(pointSize: Float): HbFont = toFont(pointSize, emptyList())

    public actual suspend fun toFont(pointSize: Float, variations: List<HbVariation>): HbFont {
        check(!closed) { "hb object disposed" }
        val payload = buildCreateFontPayload(faceId.toInt(), pointSize, variations)
        val reply = HbWorker.send("createFont", payload)
            ?: error("createFont returned null")
        val fontId = jsGetIntField(reply, "fontId").toLong()
        val ascender = jsGetFloatField(reply, "ascender")
        val descender = jsGetFloatField(reply, "descender")
        val lineGap = jsGetFloatField(reply, "lineGap")
        val hExtents = FontExtents(ascender = ascender, descender = descender, lineGap = lineGap)
        return HbFont(this, fontId, pointSize, hExtents)
    }

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
    pointSizeInit: Float,
    public actual val hExtents: FontExtents?,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed

    /**
     * Mutating `pointSize` after construction would require a new RPC and
     * we'd still need to re-key any per-font caches downstream. For now,
     * callers should obtain a fresh font via `face.toFont(newSize)` when
     * they want a different size; this matches the JVM/iOS handle
     * lifecycle semantics anyway.
     */
    public actual var pointSize: Float = pointSizeInit
        set(value) {
            check(!closed) { "hb object disposed" }
            if (value == field) return
            error(
                "Setting pointSize on Wasm requires a worker round-trip - " +
                    "use face.toFont(newSize) to build a fresh font instead.",
            )
        }

    /** harfbuzzjs's `font.glyphToPath` emits 26.6 fixed-point - divide by 64. */
    public actual val pathScale: Float = 1f / HBJS_FIXED_SCALE

    // Wasm worker doesn't expose hb_style_get_value yet; return registered
    // defaults so [HbFontStack]'s primary-style inheritance falls through to
    // weight=400 / regular / 1.0. Callers needing exact style on Wasm should
    // pass an explicit [SystemFallback.Match] with a manual [FontStyleHint].
    public actual val styleHint: FontStyleHint = FontStyleHint()

    public actual suspend fun glyphIdForCodepoint(codepoint: Int): Int {
        check(!closed) { "hb object disposed" }
        val payload = buildGlyphIdForCodepointPayload(fontId.toInt(), codepoint)
        val reply = HbWorker.send("getGlyphIdForCodepoint", payload)
            ?: return 0
        return jsGetIntField(reply, "glyphId")
    }

    public actual suspend fun glyphAdvance(glyphId: Int): Float {
        check(!closed) { "hb object disposed" }
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId)
        val reply = HbWorker.send("getGlyphAdvance", payload)
            ?: return 0f
        return jsGetFloatField(reply, "advance")
    }

    public actual suspend fun glyphExtents(glyphId: Int): GlyphExtents? {
        check(!closed) { "hb object disposed" }
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId)
        val reply = HbWorker.send("getGlyphExtents", payload) ?: return null
        if (jsGetIntField(reply, "ok") == 0) return null
        return GlyphExtents(
            xBearing = jsGetFloatField(reply, "xBearing"),
            yBearing = jsGetFloatField(reply, "yBearing"),
            width = jsGetFloatField(reply, "width"),
            height = jsGetFloatField(reply, "height"),
        )
    }

    public actual suspend fun shape(buffer: HbBuffer): ShapedRun {
        check(!closed) { "hb object disposed" }
        check(!buffer.isClosed) { "hb object disposed" }
        val payload = buildShapeRunPayload(
            fontId = fontId.toInt(),
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
        baseDirection: HbDirection,
        features: List<HbFeature>,
        language: HbLanguage,
    ): ShapedParagraph {
        check(!closed) { "hb object disposed" }
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

    public actual suspend fun drawGlyph(glyphId: Int, sink: HbPathSink) {
        check(!closed) { "hb object disposed" }
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId)
        val reply = HbWorker.send("getGlyphPath", payload) ?: return
        val svgPath = jsGetStringField(reply, "svg")
        if (svgPath.isEmpty()) return
        parseSvgPathToSink(svgPath, sink, scale = pathScale)
    }

    public actual suspend fun glyphColorLayers(glyphId: Int): List<ColorLayer> {
        check(!closed) { "hb object disposed" }
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId)
        val reply = HbWorker.send("getGlyphColorLayers", payload) ?: return emptyList()
        val layers = jsGetField(reply, "layers") ?: return emptyList()
        return decodeColorLayers(layers)
    }

    public actual suspend fun paintGlyph(
        glyphId: Int,
        foreground: Int,
        paletteIndex: Int,
        sink: HbPaintSink,
    ) {
        check(!closed) { "hb object disposed" }
        val payload = buildPaintGlyphPayload(fontId.toInt(), glyphId, foreground, paletteIndex)
        val reply = HbWorker.send("getGlyphPaint", payload) ?: return
        val bytes = jsGetField(reply, "bytes") ?: return
        if (jsTypedArrayLength(bytes) == 0) return
        WasmPaintBufferParser.dispatch(bytes, sink)
    }

    public actual suspend fun glyphSvg(glyphId: Int): ByteArray? {
        check(!closed) { "hb object disposed" }
        val payload = buildGlyphIdPayload(fontId.toInt(), glyphId)
        val reply = HbWorker.send("getGlyphSvg", payload) ?: return null
        val bytes = jsGetField(reply, "bytes") ?: return null
        val out = jsUint8ArrayToByteArray(bytes)
        return if (out.isEmpty()) null else out
    }

    public actual suspend fun snapshotGlyphs(
        gids: IntArray,
        flags: GlyphSnapshotFlags,
    ): GlyphSnapshot {
        check(!closed) { "hb object disposed" }
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
            check(!closed) { "hb object disposed" }
            field = value
        }

    public actual var direction: HbDirection = HbDirection.AUTO
        set(value) {
            check(!closed) { "hb object disposed" }
            field = value
        }

    public actual var script: HbScript = HbScript.AUTO
        set(value) {
            check(!closed) { "hb object disposed" }
            field = value
        }

    public actual var language: HbLanguage = HbLanguage.AUTO
        set(value) {
            check(!closed) { "hb object disposed" }
            field = value
        }

    public actual var features: List<HbFeature> = emptyList()

    public actual val isClosed: Boolean get() = closed

    public actual fun reset() {
        check(!closed) { "hb object disposed" }
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
