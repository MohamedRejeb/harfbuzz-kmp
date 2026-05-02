package com.mohamedrejeb.harfbuzz.core

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext

// ───── HbBlob ──────────────────────────────────────────────────────────────

public actual class HbBlob internal constructor(
    internal val ptr: Long,
    public actual val sizeBytes: Int,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed

    actual override fun close() {
        if (closed) return
        closed = true
        // Same race-window reasoning as HbFont.close() - see comment there.
        val p = ptr
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { HarfbuzzNative.blobDestroy(p) })
    }
}

// ───── HbFaceSource (DSL receiver) ────────────────────────────────────────

public actual class HbFaceSource internal constructor() {
    internal var sourceBytes: ByteArray? = null
    internal var sourcePath: String? = null
    internal var sourceFaceIndex: Int = 0

    public actual fun bytes(bytes: ByteArray, faceIndex: Int) {
        sourceBytes = bytes
        sourceFaceIndex = faceIndex
    }

    public actual fun path(path: String, faceIndex: Int) {
        sourcePath = path
        sourceFaceIndex = faceIndex
    }
}

// ───── HbFace ──────────────────────────────────────────────────────────────

public actual class HbFace internal constructor(
    internal val ptr: Long,
    public actual val upem: Int,
    public actual val faceCount: Int,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed

    public actual fun openTypeTags(): List<HbTag> {
        check(!closed) { "hb object disposed" }
        var capacity = 64
        while (true) {
            val out = IntArray(capacity)
            val total = HarfbuzzNative.faceFeatureTags(ptr, out)
            if (total <= capacity) {
                return List(total) { HbTag(out[it].toUInt()) }
            }
            capacity = total
        }
    }

    public actual fun hasColorLayers(): Boolean {
        check(!closed) { "hb object disposed" }
        return HarfbuzzNative.faceHasColorLayers(ptr) != 0
    }

    public actual fun hasColorPaint(): Boolean {
        check(!closed) { "hb object disposed" }
        return HarfbuzzNative.faceHasColorPaint(ptr) != 0
    }

    public actual fun hasColorSvg(): Boolean {
        check(!closed) { "hb object disposed" }
        return HarfbuzzNative.faceHasColorSvg(ptr) != 0
    }

    public actual suspend fun toFont(pointSize: Float): HbFont = withContext(harfbuzzDispatcher) {
        check(!closed) { "hb object disposed" }
        val fontPtr = HarfbuzzNative.fontCreate(ptr, pointSize)
        check(fontPtr != 0L) { "fontCreate returned null pointer" }
        val font = HbFont(this@HbFace, fontPtr, pointSize)
        warmFont(font)
        font
    }

    public actual suspend fun toFont(pointSize: Float, variations: List<HbVariation>): HbFont =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            if (variations.isEmpty()) return@withContext toFont(pointSize)
            val tags = IntArray(variations.size) { variations[it].tag.raw.toInt() }
            val values = FloatArray(variations.size) { variations[it].value }
            val fontPtr = HarfbuzzNative.fontCreateWithVariations(ptr, pointSize, tags, values)
            check(fontPtr != 0L) { "fontCreateWithVariations returned null pointer" }
            val font = HbFont(this@HbFace, fontPtr, pointSize)
            warmFont(font)
            font
        }

    public actual fun variationAxes(): List<HbVariationAxis> {
        check(!closed) { "hb object disposed" }
        // First call: probe count without writing.
        val total = HarfbuzzNative.faceVariationAxisInfos(
            ptr,
            outTags = null,
            outDefaults = null,
            outMins = null,
            outMaxs = null,
            outFlags = null,
            capacity = 0,
        )
        if (total <= 0) return emptyList()
        val tags = IntArray(total)
        val defaults = FloatArray(total)
        val mins = FloatArray(total)
        val maxs = FloatArray(total)
        val flags = IntArray(total)
        HarfbuzzNative.faceVariationAxisInfos(ptr, tags, defaults, mins, maxs, flags, total)
        return List(total) { i ->
            HbVariationAxis(
                tag = HbTag(tags[i].toUInt()),
                defaultValue = defaults[i],
                minValue = mins[i],
                maxValue = maxs[i],
                // OpenType `fvar` axis flag bit 0 = HIDDEN_AXIS.
                hidden = (flags[i] and 0x1) != 0,
            )
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Same race-window reasoning as HbFont.close() - see comment there.
        val p = ptr
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { HarfbuzzNative.faceDestroy(p) })
    }

    public actual companion object {
        public actual fun from(block: HbFaceSource.() -> Unit): HbFace =
            when (val result = tryFrom(block)) {
                is FaceLoad.Success -> result.face
                is FaceLoad.InvalidFontData -> throw HbInvalidFontDataException(result.cause)
                is FaceLoad.FaceIndexOutOfRange ->
                    throw HbFaceIndexOutOfRangeException(result.requested, result.available)
            }

        public actual fun tryFrom(block: HbFaceSource.() -> Unit): FaceLoad {
            HarfbuzzNative.ensureLoaded()
            val source = HbFaceSource().apply(block)
            // `path` wins over `bytes` when both are set - the mmap path
            // skips the JVM ByteArray allocation and the native memcpy
            // inside blobCreate, so it's strictly cheaper when available.
            val blobPtr = source.sourcePath?.let(HarfbuzzNative::blobCreateFromPath)
                ?: source.sourceBytes?.let(HarfbuzzNative::blobCreate)
                ?: return FaceLoad.InvalidFontData(
                    IllegalArgumentException(
                        "HbFaceSource: no `bytes(...)` or `path(...)` source provided",
                    ),
                )
            if (blobPtr == 0L) {
                return FaceLoad.InvalidFontData(null)
            }

            val available = HarfbuzzNative.faceCountInBlob(blobPtr)
            if (source.sourceFaceIndex < 0 || source.sourceFaceIndex >= available) {
                HarfbuzzNative.blobDestroy(blobPtr)
                return FaceLoad.FaceIndexOutOfRange(source.sourceFaceIndex, available)
            }

            val facePtr = HarfbuzzNative.faceCreate(blobPtr, source.sourceFaceIndex)
            // Face holds its own ref to blob; we drop our ref now so the blob is
            // auto-freed once the face is destroyed.
            HarfbuzzNative.blobDestroy(blobPtr)

            if (facePtr == 0L) {
                return FaceLoad.InvalidFontData(null)
            }

            val upem = HarfbuzzNative.faceUpem(facePtr)
            return FaceLoad.Success(HbFace(facePtr, upem, available))
        }

        public actual suspend fun fromBytes(bytes: ByteArray, faceIndex: Int): HbFace =
            withContext(harfbuzzDispatcher) {
                from { bytes(bytes, faceIndex) }
            }
    }
}

// ───── HbFont ──────────────────────────────────────────────────────────────

public actual class HbFont internal constructor(
    public actual val face: HbFace,
    internal val ptr: Long,
    pointSizeInit: Float,
) : AutoCloseable {
    private var closed: Boolean = false

    public actual var pointSize: Float = pointSizeInit
        set(value) {
            check(!closed) { "hb object disposed" }
            HarfbuzzNative.fontSetPointSize(ptr, value)
            field = value
        }

    public actual val isClosed: Boolean get() = closed

    public actual suspend fun glyphIdForCodepoint(codepoint: Int): Int =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            HarfbuzzNative.fontGlyphForCodepoint(ptr, codepoint)
        }

    public actual suspend fun glyphAdvance(glyphId: Int): Float =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            HarfbuzzNative.fontGlyphHAdvance(ptr, glyphId)
        }

    public actual suspend fun glyphExtents(glyphId: Int): GlyphExtents? =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            val out = FloatArray(4)
            val ok = HarfbuzzNative.fontGlyphExtents(ptr, glyphId, out)
            if (ok == 0) return@withContext null
            GlyphExtents(
                xBearing = out[0],
                yBearing = out[1],
                width = out[2],
                height = out[3],
            )
        }

    // Lazy so the JNI call doesn't run at HbFont construction - that
    // shifts the cost to first read, which is always a `runShapingWork`
    // caller (`buildMeasuredText`'s line-metric block). One JNI hop saved
    // per HbFont in the cold-start critical path.
    public actual val hExtents: FontExtents? by lazy {
        if (closed) return@lazy null
        val out = FloatArray(3)
        if (HarfbuzzNative.fontHExtents(ptr, out) == 0) null
        else FontExtents(ascender = out[0], descender = out[1], lineGap = out[2])
    }

    // JVM `StringPathSink` records pixel-space coords; no conversion needed.
    public actual val pathScale: Float = 1f

    public actual val styleHint: FontStyleHint by lazy {
        if (closed) return@lazy FontStyleHint()
        val weight = HarfbuzzNative.fontStyleValue(ptr, HB_STYLE_TAG_WEIGHT)
        val italic = HarfbuzzNative.fontStyleValue(ptr, HB_STYLE_TAG_ITALIC)
        val width = HarfbuzzNative.fontStyleValue(ptr, HB_STYLE_TAG_WIDTH)
        FontStyleHint(
            weight = if (weight > 0f) weight.toInt().coerceIn(1, 1000) else 400,
            italic = italic > 0.5f,
            width = if (width > 0f) width else 1f,
        )
    }

    public actual suspend fun glyphColorLayers(glyphId: Int): List<ColorLayer> =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            // Probe count first.
            val total = HarfbuzzNative.fontGlyphColorLayers(ptr, glyphId, null, null, 0)
            if (total <= 0) return@withContext emptyList()
            val capped = minOf(total, 64)
            val gids = IntArray(capped)
            val argbs = IntArray(capped)
            val read = HarfbuzzNative.fontGlyphColorLayers(ptr, glyphId, gids, argbs, capped)
            if (read <= 0) return@withContext emptyList()
            val n = minOf(read, capped)
            List(n) { i ->
                ColorLayer(glyphId = gids[i], argb = if (argbs[i] == 0) null else argbs[i])
            }
        }

    public actual suspend fun shape(buffer: HbBuffer): ShapedRun =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            val rendered = ShapingHelpers.shapeBufferIntoRun(this@HbFont, buffer, buffer.features)
            rendered
        }

    public actual suspend fun shapeParagraph(
        text: String,
        baseDirection: HbDirection,
        features: List<HbFeature>,
        language: HbLanguage,
    ): ShapedParagraph = withContext(harfbuzzDispatcher) {
        check(!closed) { "hb object disposed" }
        ShapingHelpers.shapeParagraph(this@HbFont, text, baseDirection, features, language)
    }

    public actual suspend fun drawGlyph(glyphId: Int, sink: HbPathSink) {
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            ShapingHelpers.drawGlyph(this@HbFont, glyphId, sink)
        }
    }

    public actual suspend fun paintGlyph(
        glyphId: Int,
        foreground: Int,
        paletteIndex: Int,
        sink: HbPaintSink,
    ) {
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            val buf = HarfbuzzNative.fontPaintGlyph(ptr, glyphId, foreground, paletteIndex)
                ?: return@withContext
            PaintBufferParser.dispatch(buf, sink)
        }
    }

    public actual suspend fun glyphSvg(glyphId: Int): ByteArray? =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            HarfbuzzNative.fontGlyphSvg(ptr, glyphId)
        }

    public actual suspend fun snapshotGlyphs(
        gids: IntArray,
        flags: GlyphSnapshotFlags,
    ): GlyphSnapshot = withContext(harfbuzzDispatcher) {
        check(!closed) { "hb object disposed" }
        val flipped = if (flags.flippedPath) HashMap<Int, String>(gids.size) else null
        val raw = if (flags.rawPath) HashMap<Int, String>(gids.size) else null
        val layers = if (flags.colorLayers) HashMap<Int, List<ColorLayer>>(gids.size) else null
        val paint = if (flags.paintTree) HashMap<Int, ByteArray>(gids.size) else null
        val svg = if (flags.svg) HashMap<Int, ByteArray>(gids.size) else null

        for (gid in gids) {
            if (flipped != null || raw != null) {
                val sink = StringPathSink()
                ShapingHelpers.drawGlyph(this@HbFont, gid, sink)
                val svgPath = sink.build()
                if (flipped != null) flipped[gid] = svgPath
                if (raw != null) raw[gid] = svgPath
            }
            if (layers != null) {
                layers[gid] = readGlyphColorLayers(gid)
            }
            if (paint != null) {
                val buf = HarfbuzzNative.fontPaintGlyph(ptr, gid, 0xFF000000.toInt(), 0)
                if (buf != null) paint[gid] = buf
            }
            if (svg != null) {
                // Slice the per-glyph subtree out of the document inside the
                // JNI call so multi-glyph documents (Noto Color Emoji's 3 MB
                // ranges) are reduced to a tiny per-glyph fragment before
                // crossing JNI. Falls back to whole-document bytes when the
                // document only contains one glyph (Aref Ruqaa Ink) or when
                // the slice scan fails - same fallback the Compose layer's
                // SvgGlyphSlicer used to apply, just paid in C++.
                val s = HarfbuzzNative.fontGlyphSvgSliced(ptr, gid)
                if (s != null) svg[gid] = s
            }
        }
        GlyphSnapshot(
            flippedPathSvg = flipped ?: emptyMap(),
            rawPathSvg = raw ?: emptyMap(),
            colorLayers = layers ?: emptyMap(),
            paintTreeBytes = paint ?: emptyMap(),
            svgBytes = svg ?: emptyMap(),
        )
    }

    /**
     * Pure compute: reads color layers for [glyphId] without re-entering the
     * dispatcher. Callers must already be on `harfbuzzDispatcher` and have
     * verified that the font is not disposed.
     */
    private fun readGlyphColorLayers(glyphId: Int): List<ColorLayer> {
        val total = HarfbuzzNative.fontGlyphColorLayers(ptr, glyphId, null, null, 0)
        if (total <= 0) return emptyList()
        val capped = minOf(total, 64)
        val gidsOut = IntArray(capped)
        val argbs = IntArray(capped)
        val read = HarfbuzzNative.fontGlyphColorLayers(ptr, glyphId, gidsOut, argbs, capped)
        if (read <= 0) return emptyList()
        val n = minOf(read, capped)
        return List(n) { i ->
            ColorLayer(glyphId = gidsOut[i], argb = if (argbs[i] == 0) null else argbs[i])
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Native destroy is queued on harfbuzzDispatcher (single-threaded)
        // instead of running synchronously. Compose call sites can swap and
        // close a previous HbFont from Main while a snapshotGlyphs / shape
        // is still iterating glyphs on harfbuzz-bg - destroying ptr there
        // would free hb_font_t out from under hb_font_draw_glyph and SEGV.
        // Queuing the destroy serialises it after any in-flight shaping
        // work for this handle.
        val p = ptr
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { HarfbuzzNative.fontDestroy(p) })
    }
}

// ───── HbBuffer ────────────────────────────────────────────────────────────

public actual class HbBuffer actual constructor() : AutoCloseable {

    init {
        HarfbuzzNative.ensureLoaded()
    }

    internal val ptr: Long = HarfbuzzNative.bufferCreate().also {
        check(it != 0L) { "hb_buffer_create returned null" }
    }

    private var closed: Boolean = false

    public actual var text: String = ""
        set(value) {
            check(!closed) { "hb object disposed" }
            field = value
            HarfbuzzNative.bufferSetText(ptr, value)
            // Re-apply other props that bufferSetText cleared.
            HarfbuzzNative.bufferSetDirection(ptr, _direction.toNative())
            HarfbuzzNative.bufferSetScript(ptr, _script.tag.raw.toInt())
            HarfbuzzNative.bufferSetLanguage(ptr, if (_language == HbLanguage.AUTO) null else _language.bcp47)
        }

    private var _direction: HbDirection = HbDirection.AUTO
    public actual var direction: HbDirection
        get() = _direction
        set(value) {
            check(!closed) { "hb object disposed" }
            _direction = value
            HarfbuzzNative.bufferSetDirection(ptr, value.toNative())
        }

    private var _script: HbScript = HbScript.AUTO
    public actual var script: HbScript
        get() = _script
        set(value) {
            check(!closed) { "hb object disposed" }
            _script = value
            HarfbuzzNative.bufferSetScript(ptr, value.tag.raw.toInt())
        }

    private var _language: HbLanguage = HbLanguage.AUTO
    public actual var language: HbLanguage
        get() = _language
        set(value) {
            check(!closed) { "hb object disposed" }
            _language = value
            HarfbuzzNative.bufferSetLanguage(ptr, if (value == HbLanguage.AUTO) null else value.bcp47)
        }

    public actual var features: List<HbFeature> = emptyList()

    public actual val isClosed: Boolean get() = closed

    public actual fun reset() {
        check(!closed) { "hb object disposed" }
        HarfbuzzNative.bufferReset(ptr)
        text = ""
        _direction = HbDirection.AUTO
        _script = HbScript.AUTO
        _language = HbLanguage.AUTO
        features = emptyList()
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Same race-window reasoning as HbFont.close() - see comment there.
        val p = ptr
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { HarfbuzzNative.bufferDestroy(p) })
    }
}
