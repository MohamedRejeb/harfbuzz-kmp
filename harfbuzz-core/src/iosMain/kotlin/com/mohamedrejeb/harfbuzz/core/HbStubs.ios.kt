package com.mohamedrejeb.harfbuzz.core

import com.mohamedrejeb.harfbuzz.native.hb_blob_create
import com.mohamedrejeb.harfbuzz.native.hb_blob_create_from_file_or_fail
import com.mohamedrejeb.harfbuzz.native.hb_memory_mode_t
import com.mohamedrejeb.harfbuzz.native.hb_blob_destroy
import com.mohamedrejeb.harfbuzz.native.hb_blob_get_length
import com.mohamedrejeb.harfbuzz.native.hb_buffer_add_utf16
import com.mohamedrejeb.harfbuzz.native.hb_buffer_clear_contents
import com.mohamedrejeb.harfbuzz.native.hb_buffer_create
import com.mohamedrejeb.harfbuzz.native.hb_buffer_destroy
import com.mohamedrejeb.harfbuzz.native.hb_buffer_get_glyph_infos
import com.mohamedrejeb.harfbuzz.native.hb_buffer_get_glyph_positions
import com.mohamedrejeb.harfbuzz.native.hb_buffer_get_length
import com.mohamedrejeb.harfbuzz.native.hb_buffer_guess_segment_properties
import com.mohamedrejeb.harfbuzz.native.hb_buffer_reset
import com.mohamedrejeb.harfbuzz.native.hb_buffer_set_direction
import com.mohamedrejeb.harfbuzz.native.hb_buffer_set_language
import com.mohamedrejeb.harfbuzz.native.hb_buffer_set_script
import com.mohamedrejeb.harfbuzz.native.hb_face_count
import com.mohamedrejeb.harfbuzz.native.hb_face_create
import com.mohamedrejeb.harfbuzz.native.hb_face_destroy
import com.mohamedrejeb.harfbuzz.native.hb_face_get_upem
import com.mohamedrejeb.harfbuzz.native.hb_feature_t
import com.mohamedrejeb.harfbuzz.native.hb_font_create
import com.mohamedrejeb.harfbuzz.native.hb_font_destroy
import com.mohamedrejeb.harfbuzz.native.hb_font_get_glyph
import com.mohamedrejeb.harfbuzz.native.hb_color_get_alpha
import com.mohamedrejeb.harfbuzz.native.hb_color_get_blue
import com.mohamedrejeb.harfbuzz.native.hb_color_get_green
import com.mohamedrejeb.harfbuzz.native.hb_color_get_red
import com.mohamedrejeb.harfbuzz.native.hb_font_extents_t
import com.mohamedrejeb.harfbuzz.native.hb_font_get_face
import com.mohamedrejeb.harfbuzz.native.hb_font_get_glyph_extents
import com.mohamedrejeb.harfbuzz.native.hb_font_get_h_extents
import com.mohamedrejeb.harfbuzz.native.hb_style_get_value
import com.mohamedrejeb.harfbuzz.native.hb_blob_destroy
import com.mohamedrejeb.harfbuzz.native.hb_blob_get_data
import com.mohamedrejeb.harfbuzz.native.hb_font_paint_glyph
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_glyph_get_layers
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_glyph_reference_svg
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_has_layers
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_has_paint
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_has_svg
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_layer_t
import com.mohamedrejeb.harfbuzz.native.hb_ot_color_palette_get_colors
import com.mohamedrejeb.harfbuzz.native.hb_font_get_glyph_h_advance
import com.mohamedrejeb.harfbuzz.native.hb_font_set_scale
import cnames.structs.hb_font_t
import com.mohamedrejeb.harfbuzz.native.hb_glyph_extents_t
import com.mohamedrejeb.harfbuzz.native.hb_language_from_string
import com.mohamedrejeb.harfbuzz.native.hb_script_from_iso15924_tag
import com.mohamedrejeb.harfbuzz.native.hb_shape
import com.mohamedrejeb.harfbuzz.native.hb_version_string
import com.mohamedrejeb.harfbuzz.native.hb_font_set_variations
import com.mohamedrejeb.harfbuzz.native.hb_ot_var_axis_info_t
import com.mohamedrejeb.harfbuzz.native.hb_ot_var_get_axis_count
import com.mohamedrejeb.harfbuzz.native.hb_ot_var_get_axis_infos
import com.mohamedrejeb.harfbuzz.native.hb_variation_t
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

// ───── iOS pixel-conversion helpers ───────────────────────────────────────
// Same 26.6 fixed-point convention as the JVM bridge.
@OptIn(ExperimentalForeignApi::class)
private const val SUBPIXEL_FACTOR = 64

/**
 * Design-space pseudo-size used to mint the per-face "meta" font that
 * answers style queries. hb_style_get_value normalises by upem internally
 * so the absolute scale is irrelevant; we still need a non-zero value to
 * keep hb_font_t in a valid state. Mirrors `DESIGN_SCALE_POINT_SIZE` on JVM.
 */
@OptIn(ExperimentalForeignApi::class)
private const val DESIGN_SCALE_POINT_SIZE: Float = 1f

private fun toPixels(hbValue: Int): Float = hbValue.toFloat() / SUBPIXEL_FACTOR

@OptIn(ExperimentalForeignApi::class)
private fun setFontScaleFromPointSize(font: CPointer<*>?, pointSize: Float) {
    val s = (pointSize * SUBPIXEL_FACTOR + 0.5f).toInt().coerceAtLeast(1)
    hb_font_set_scale(font?.reinterpret(), s, s)
}

// ───── HbBlob ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class)
public actual class HbBlob internal constructor(
    internal val handle: CPointer<*>,
    public actual val sizeBytes: Int,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed
    actual override fun close() {
        if (closed) return
        closed = true
        // Same race-window reasoning as HbFont.close() - see comment there.
        val h = handle
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { hb_blob_destroy(h.reinterpret()) })
    }
}

// ───── HbFaceSource ────────────────────────────────────────────────────────

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

@OptIn(ExperimentalForeignApi::class)
public actual class HbFace internal constructor(
    internal val handle: CPointer<*>,
    public actual val upem: Int,
    public actual val faceCount: Int,
) : AutoCloseable {
    private var closed: Boolean = false
    public actual val isClosed: Boolean get() = closed

    public actual fun openTypeTags(): List<HbTag> {
        // v1: not yet implemented for iOS - needs hb_ot_layout_table_get_feature_tags
        // bindings. Returns empty list rather than throwing so consumers can detect.
        return emptyList()
    }

    // Color-table presence is a property of the face's binary tables -
    // it does not change once the face is loaded. Memoise the C-side
    // probes so a per-frame `buildMeasuredText` does not pay native
    // calls per font on every shape.
    private val cachedHasColorLayers: Boolean by lazy {
        hb_ot_color_has_layers(handle.reinterpret()) != 0
    }
    private val cachedHasColorPaint: Boolean by lazy {
        hb_ot_color_has_paint(handle.reinterpret()) != 0
    }
    private val cachedHasColorSvg: Boolean by lazy {
        hb_ot_color_has_svg(handle.reinterpret()) != 0
    }

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

    public actual suspend fun toFont(): HbFont = withContext(harfbuzzDispatcher) {
        check(!closed) { "hb object disposed" }
        val fontPtr = hb_font_create(handle.reinterpret())
            ?: throw HbException("hb_font_create returned null")
        HbFont(this@HbFace, fontPtr)
    }

    public actual suspend fun toFont(variations: List<HbVariation>): HbFont =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            val fontPtr = hb_font_create(handle.reinterpret())
                ?: throw HbException("hb_font_create returned null")
            if (variations.isNotEmpty()) {
                memScoped {
                    val arr = allocArray<hb_variation_t>(variations.size)
                    for (i in variations.indices) {
                        arr[i].tag = variations[i].tag.raw
                        arr[i].value = variations[i].value
                    }
                    hb_font_set_variations(fontPtr, arr, variations.size.convert())
                }
            }
            HbFont(this@HbFace, fontPtr)
        }

    private var metaFontPtr: CPointer<hb_font_t>? = null

    private fun ensureMetaFontPtr(): CPointer<hb_font_t> {
        metaFontPtr?.let { return it }
        val p = hb_font_create(handle.reinterpret())
            ?: throw HbException("hb_font_create returned null")
        metaFontPtr = p
        return p
    }

    public actual val styleHint: FontStyleHint by lazy {
        if (closed) return@lazy FontStyleHint()
        val mp = ensureMetaFontPtr()
        val weight = hb_style_get_value(mp, HB_STYLE_TAG_WEIGHT.toUInt())
        val italic = hb_style_get_value(mp, HB_STYLE_TAG_ITALIC.toUInt())
        val width = hb_style_get_value(mp, HB_STYLE_TAG_WIDTH.toUInt())
        FontStyleHint(
            weight = if (weight > 0f) weight.toInt().coerceIn(1, 1000) else 400,
            italic = italic > 0.5f,
            width = if (width > 0f) width else 1f,
        )
    }

    public actual fun variationAxes(): List<HbVariationAxis> {
        check(!closed) { "hb object disposed" }
        val total = hb_ot_var_get_axis_count(handle.reinterpret()).toInt()
        if (total == 0) return emptyList()
        return memScoped {
            val arr = allocArray<hb_ot_var_axis_info_t>(total)
            val countOut = alloc<UIntVar>().apply { value = total.convert() }
            hb_ot_var_get_axis_infos(handle.reinterpret(), 0u, countOut.ptr, arr)
            val written = countOut.value.toInt()
            List(written) { i ->
                HbVariationAxis(
                    tag = HbTag(arr[i].tag),
                    defaultValue = arr[i].default_value,
                    minValue = arr[i].min_value,
                    maxValue = arr[i].max_value,
                    hidden = (arr[i].flags.toInt() and 0x1) != 0,
                )
            }
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Same race-window reasoning as HbFont.close() - see comment there.
        val h = handle
        val mp = metaFontPtr
        metaFontPtr = null
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable {
            mp?.let { hb_font_destroy(it) }
            hb_face_destroy(h.reinterpret())
        })
    }

    public actual companion object {
        public actual fun from(block: HbFaceSource.() -> Unit): HbFace =
            when (val r = tryFrom(block)) {
                is FaceLoad.Success -> r.face
                is FaceLoad.InvalidFontData -> throw HbInvalidFontDataException(r.cause)
                is FaceLoad.FaceIndexOutOfRange ->
                    throw HbFaceIndexOutOfRangeException(r.requested, r.available)
            }

        public actual fun tryFrom(block: HbFaceSource.() -> Unit): FaceLoad {
            val source = HbFaceSource().apply(block)
            // `path` wins over `bytes`: hb_blob_create_from_file_or_fail
            // mmaps the file via the platform's mmap(2), so HarfBuzz reads
            // from kernel-shared pages on demand instead of copying bytes
            // into native memory upfront.
            val pathBlob = source.sourcePath?.let { hb_blob_create_from_file_or_fail(it) }
            val blobPtr = pathBlob ?: source.sourceBytes?.let { bytes ->
                bytes.usePinned { pinned ->
                    hb_blob_create(
                        pinned.addressOf(0).reinterpret<ByteVar>(),
                        bytes.size.convert(),
                        hb_memory_mode_t.HB_MEMORY_MODE_DUPLICATE,
                        null,
                        null,
                    )
                }
            } ?: return FaceLoad.InvalidFontData(
                IllegalArgumentException(
                    "HbFaceSource: no `bytes(...)` or `path(...)` source provided",
                ),
            )

            if (hb_blob_get_length(blobPtr) == 0u) {
                hb_blob_destroy(blobPtr)
                return FaceLoad.InvalidFontData(null)
            }

            val available = hb_face_count(blobPtr).toInt()
            if (source.sourceFaceIndex < 0 || source.sourceFaceIndex >= available) {
                hb_blob_destroy(blobPtr)
                return FaceLoad.FaceIndexOutOfRange(source.sourceFaceIndex, available)
            }

            val facePtr = hb_face_create(blobPtr, source.sourceFaceIndex.convert())
            hb_blob_destroy(blobPtr)
            if (facePtr == null) return FaceLoad.InvalidFontData(null)

            val upem = hb_face_get_upem(facePtr).toInt()
            return FaceLoad.Success(HbFace(facePtr, upem, available))
        }

        public actual suspend fun fromBytes(bytes: ByteArray, faceIndex: Int): HbFace =
            withContext(harfbuzzDispatcher) {
                from { bytes(bytes, faceIndex) }
            }
    }
}

// ───── HbFont ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class)
public actual class HbFont internal constructor(
    public actual val face: HbFace,
    internal val handle: CPointer<*>,
) : AutoCloseable {
    private var closed: Boolean = false

    public actual val isClosed: Boolean get() = closed

    /**
     * Set the underlying hb_font_t scale to [sizePx]. Caller must already
     * be on [harfbuzzDispatcher]; this performs no synchronisation of
     * its own. Used by every size-dependent path right before invoking
     * the relevant cinterop primitive.
     */
    private fun setScale(sizePx: Float) {
        setFontScaleFromPointSize(handle, sizePx)
    }

    public actual suspend fun glyphIdForCodepoint(codepoint: Int): Int =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            memScoped {
                val out = alloc<UIntVar>()
                hb_font_get_glyph(handle.reinterpret(), codepoint.convert(), 0u, out.ptr)
                out.value.toInt()
            }
        }

    public actual suspend fun glyphAdvance(glyphId: Int, sizePx: Float): Float =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            setScale(sizePx)
            val adv = hb_font_get_glyph_h_advance(handle.reinterpret(), glyphId.convert())
            toPixels(adv)
        }

    public actual suspend fun glyphExtents(glyphId: Int, sizePx: Float): GlyphExtents? =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            setScale(sizePx)
            memScoped {
                val ext = alloc<hb_glyph_extents_t>()
                val ok = hb_font_get_glyph_extents(handle.reinterpret(), glyphId.convert(), ext.ptr)
                if (ok == 0) null else GlyphExtents(
                    xBearing = toPixels(ext.x_bearing),
                    yBearing = toPixels(ext.y_bearing),
                    width = toPixels(ext.width),
                    height = toPixels(ext.height),
                )
            }
        }

    public actual suspend fun hExtents(sizePx: Float): FontExtents? =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            setScale(sizePx)
            memScoped {
                val ext = alloc<hb_font_extents_t>()
                val ok = hb_font_get_h_extents(handle.reinterpret(), ext.ptr)
                if (ok == 0) null else FontExtents(
                    ascender = toPixels(ext.ascender),
                    descender = toPixels(ext.descender),
                    lineGap = toPixels(ext.line_gap),
                )
            }
        }

    // iOS draw callbacks emit pixel-space coords once `drawGlyph` is wired
    // up; `snapshotGlyphs` returns empty path maps until then.
    public actual val pathScale: Float = 1f

    public actual suspend fun glyphColorLayers(glyphId: Int): List<ColorLayer> =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            val face = hb_font_get_face(handle.reinterpret()) ?: return@withContext emptyList()
            memScoped {
                // Probe count first.
                val countProbe = alloc<UIntVar>().apply { value = 0u }
                hb_ot_color_glyph_get_layers(face, glyphId.convert(), 0u, countProbe.ptr, null)
                val total = countProbe.value.toInt()
                if (total == 0) return@memScoped emptyList()
                val capped = if (total < 64) total else 64
                val layers = allocArray<hb_ot_color_layer_t>(capped)
                val readVar = alloc<UIntVar>().apply { value = capped.toUInt() }
                hb_ot_color_glyph_get_layers(face, glyphId.convert(), 0u, readVar.ptr, layers)
                val read = readVar.value.toInt().coerceAtMost(capped)
                val foreground: UInt = 0xFFFFFFFFu   // HB_OT_COLOR_PALETTE_COLOR_FOREGROUND
                val defaultPalette: UInt = 0u
                List(read) { i ->
                    val l = layers[i]
                    val gid = l.glyph.toInt()
                    val colorIdx: UInt = l.color_index
                    val argb: Int? = if (colorIdx == foreground) {
                        null
                    } else {
                        // hb_color_t is uint32_t, surfaced as UInt by cinterop.
                        val cVar = alloc<UIntVar>()
                        val cnt = alloc<UIntVar>().apply { value = 1u }
                        hb_ot_color_palette_get_colors(
                            face, defaultPalette,
                            colorIdx, cnt.ptr, cVar.ptr,
                        )
                        val c: UInt = cVar.value
                        val r = hb_color_get_red(c).toInt() and 0xFF
                        val g = hb_color_get_green(c).toInt() and 0xFF
                        val b = hb_color_get_blue(c).toInt() and 0xFF
                        val a = hb_color_get_alpha(c).toInt() and 0xFF
                        var packed = (a shl 24) or (r shl 16) or (g shl 8) or b
                        if (packed == 0) packed = 0x01000000
                        packed
                    }
                    ColorLayer(glyphId = gid, argb = argb)
                }
            }
        }

    public actual suspend fun shape(buffer: HbBuffer, sizePx: Float): ShapedRun =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            setScale(sizePx)
            IosShaping.shapeBufferIntoRun(this@HbFont, buffer, buffer.features)
        }

    public actual suspend fun shapeParagraph(
        text: String,
        sizePx: Float,
        baseDirection: HbDirection,
        features: List<HbFeature>,
        language: HbLanguage,
    ): ShapedParagraph = withContext(harfbuzzDispatcher) {
        check(!closed) { "hb object disposed" }
        setScale(sizePx)
        IosShaping.shapeParagraph(this@HbFont, text, sizePx, baseDirection, features, language)
    }

    public actual suspend fun drawGlyph(glyphId: Int, sizePx: Float, sink: HbPathSink) {
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            setScale(sizePx)
            throw HbException("drawGlyph on iOS not yet implemented")
        }
    }

    public actual suspend fun paintGlyph(
        glyphId: Int,
        sizePx: Float,
        foreground: Int,
        paletteIndex: Int,
        sink: HbPaintSink,
    ) {
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            setScale(sizePx)
            // Pass the sink as user_data via a StableRef so the static C
            // callbacks in IosPaintFuncs can recover it. Dispose immediately
            // after the walk - paint trees are walked synchronously.
            val ref = StableRef.create(sink)
            try {
                hb_font_paint_glyph(
                    handle.reinterpret(),
                    glyphId.toUInt(),
                    IosPaintFuncs.shared,
                    ref.asCPointer(),
                    if (paletteIndex < 0) 0u else paletteIndex.toUInt(),
                    IosPaintFuncs.argbToHbColor(foreground),
                )
            } finally {
                ref.dispose()
            }
        }
    }

    public actual suspend fun glyphSvg(glyphId: Int): ByteArray? =
        withContext(harfbuzzDispatcher) {
            check(!closed) { "hb object disposed" }
            readGlyphSvgBytes(glyphId)
        }

    public actual suspend fun snapshotGlyphs(
        gids: IntArray,
        sizePx: Float,
        flags: GlyphSnapshotFlags,
    ): GlyphSnapshot = withContext(harfbuzzDispatcher) {
        check(!closed) { "hb object disposed" }
        setScale(sizePx)
        // iOS notes:
        //  * `drawGlyph` is not yet implemented; until then `flippedPath`/
        //    `rawPath` are unsupported and the corresponding maps stay empty.
        //  * iOS walks COLR v1 paint trees directly into an `HbPaintSink`
        //    via cinterop callbacks; there is no `ByteArray` wire format
        //    today, so `paintTreeBytes` is left empty.
        val layers = if (flags.colorLayers) HashMap<Int, List<ColorLayer>>(gids.size) else null
        val svg = if (flags.svg) HashMap<Int, ByteArray>(gids.size) else null

        for (gid in gids) {
            if (layers != null) {
                layers[gid] = readGlyphColorLayersIos(gid)
            }
            if (svg != null) {
                val s = readGlyphSvgBytes(gid)
                if (s != null) svg[gid] = s
            }
        }
        GlyphSnapshot(
            flippedPathSvg = emptyMap(),
            rawPathSvg = emptyMap(),
            colorLayers = layers ?: emptyMap(),
            paintTreeBytes = emptyMap(),
            svgBytes = svg ?: emptyMap(),
        )
    }

    /**
     * Pure compute version of [glyphColorLayers] for callers already on
     * `harfbuzzDispatcher` (i.e. inside [snapshotGlyphs]).
     */
    private fun readGlyphColorLayersIos(glyphId: Int): List<ColorLayer> {
        val face = hb_font_get_face(handle.reinterpret()) ?: return emptyList()
        return memScoped {
            val countProbe = alloc<UIntVar>().apply { value = 0u }
            hb_ot_color_glyph_get_layers(face, glyphId.convert(), 0u, countProbe.ptr, null)
            val total = countProbe.value.toInt()
            if (total == 0) return@memScoped emptyList()
            val capped = if (total < 64) total else 64
            val layers = allocArray<hb_ot_color_layer_t>(capped)
            val readVar = alloc<UIntVar>().apply { value = capped.toUInt() }
            hb_ot_color_glyph_get_layers(face, glyphId.convert(), 0u, readVar.ptr, layers)
            val read = readVar.value.toInt().coerceAtMost(capped)
            val foreground: UInt = 0xFFFFFFFFu
            val defaultPalette: UInt = 0u
            List(read) { i ->
                val l = layers[i]
                val gid = l.glyph.toInt()
                val colorIdx: UInt = l.color_index
                val argb: Int? = if (colorIdx == foreground) {
                    null
                } else {
                    val cVar = alloc<UIntVar>()
                    val cnt = alloc<UIntVar>().apply { value = 1u }
                    hb_ot_color_palette_get_colors(
                        face, defaultPalette,
                        colorIdx, cnt.ptr, cVar.ptr,
                    )
                    val c: UInt = cVar.value
                    val r = hb_color_get_red(c).toInt() and 0xFF
                    val g = hb_color_get_green(c).toInt() and 0xFF
                    val b = hb_color_get_blue(c).toInt() and 0xFF
                    val a = hb_color_get_alpha(c).toInt() and 0xFF
                    var packed = (a shl 24) or (r shl 16) or (g shl 8) or b
                    if (packed == 0) packed = 0x01000000
                    packed
                }
                ColorLayer(glyphId = gid, argb = argb)
            }
        }
    }

    /**
     * Pure compute version of [glyphSvg] for callers already on
     * `harfbuzzDispatcher` (i.e. inside [snapshotGlyphs]).
     */
    private fun readGlyphSvgBytes(glyphId: Int): ByteArray? {
        val face = hb_font_get_face(handle.reinterpret()) ?: return null
        if (hb_ot_color_has_svg(face) == 0) return null
        val blob = hb_ot_color_glyph_reference_svg(face, glyphId.toUInt()) ?: return null
        return try {
            memScoped {
                val lengthVar = alloc<UIntVar>()
                val data = hb_blob_get_data(blob, lengthVar.ptr)
                val length = lengthVar.value.toInt()
                if (length <= 0 || data == null) null
                else ByteArray(length) { i -> data[i] }
            }
        } finally {
            hb_blob_destroy(blob)
        }
    }

    actual override fun close() {
        if (closed) return
        closed = true
        // Native destroy is queued on harfbuzzDispatcher (single-threaded
        // limitedParallelism(1) view) instead of running synchronously.
        // Compose call sites can swap and close a previous HbFont from Main
        // while a snapshotGlyphs / shape is still iterating glyphs on the
        // dispatcher - destroying handle there would free hb_font_t out
        // from under hb_font_draw_glyph and crash. Queuing serialises the
        // destroy after any in-flight shaping work for this handle.
        val h = handle
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { hb_font_destroy(h.reinterpret()) })
    }
}

// ───── HbBuffer ────────────────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class)
public actual class HbBuffer actual constructor() : AutoCloseable {
    internal val handle: CPointer<*> = hb_buffer_create()
        ?: throw HbException("hb_buffer_create returned null")

    private var closed: Boolean = false

    public actual var text: String = ""
        set(value) {
            check(!closed) { "hb object disposed" }
            field = value
            hb_buffer_clear_contents(handle.reinterpret())
            value.toCharArray().usePinned { pinned ->
                hb_buffer_add_utf16(
                    handle.reinterpret(),
                    pinned.addressOf(0).reinterpret(),
                    value.length,
                    0u,
                    value.length,
                )
            }
            hb_buffer_set_direction(handle.reinterpret(), _direction.toNativeIos().convert())
            hb_buffer_set_script(
                handle.reinterpret(),
                hb_script_from_iso15924_tag(_script.tag.raw),
            )
            if (_language != HbLanguage.AUTO) {
                hb_buffer_set_language(
                    handle.reinterpret(),
                    hb_language_from_string(_language.bcp47, -1),
                )
            }
        }

    private var _direction: HbDirection = HbDirection.AUTO
    public actual var direction: HbDirection
        get() = _direction
        set(value) {
            check(!closed) { "hb object disposed" }
            _direction = value
            hb_buffer_set_direction(handle.reinterpret(), value.toNativeIos().convert())
        }

    private var _script: HbScript = HbScript.AUTO
    public actual var script: HbScript
        get() = _script
        set(value) {
            check(!closed) { "hb object disposed" }
            _script = value
            hb_buffer_set_script(
                handle.reinterpret(),
                hb_script_from_iso15924_tag(value.tag.raw),
            )
        }

    private var _language: HbLanguage = HbLanguage.AUTO
    public actual var language: HbLanguage
        get() = _language
        set(value) {
            check(!closed) { "hb object disposed" }
            _language = value
            if (value != HbLanguage.AUTO) {
                hb_buffer_set_language(handle.reinterpret(), hb_language_from_string(value.bcp47, -1))
            }
        }

    public actual var features: List<HbFeature> = emptyList()

    public actual val isClosed: Boolean get() = closed

    public actual fun reset() {
        check(!closed) { "hb object disposed" }
        hb_buffer_reset(handle.reinterpret())
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
        val h = handle
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext, Runnable { hb_buffer_destroy(h.reinterpret()) })
    }
}

// ───── Direction conversion ────────────────────────────────────────────────

private fun HbDirection.toNativeIos(): Int = when (this) {
    HbDirection.AUTO -> 0   // HB_DIRECTION_INVALID
    HbDirection.LTR -> 4
    HbDirection.RTL -> 5
    HbDirection.TTB -> 6
    HbDirection.BTT -> 7
}

// ───── iOS shaping helpers ─────────────────────────────────────────────────

@OptIn(ExperimentalForeignApi::class)
internal object IosShaping {

    fun shapeBufferIntoRun(font: HbFont, buffer: HbBuffer, features: List<HbFeature>): ShapedRun {
        check(!buffer.isClosed) { "hb object disposed" }

        memScoped {
            val featureBlock: CPointer<hb_feature_t>?
            val featureCount: Int
            if (features.isEmpty()) {
                featureBlock = null
                featureCount = 0
            } else {
                val arr = allocArray<hb_feature_t>(features.size)
                features.forEachIndexed { i, f ->
                    arr[i].tag = f.tag.raw
                    arr[i].value = f.value
                    arr[i].start = f.start.toUInt()
                    arr[i].end = if (f.end == Int.MAX_VALUE) UInt.MAX_VALUE else f.end.toUInt()
                }
                featureBlock = arr
                featureCount = features.size
            }

            hb_buffer_guess_segment_properties(buffer.handle.reinterpret())
            hb_shape(font.handle.reinterpret(), buffer.handle.reinterpret(), featureBlock, featureCount.convert())
        }

        val n = hb_buffer_get_length(buffer.handle.reinterpret()).toInt()
        if (n == 0) return ShapedRun.EMPTY.copy(direction = buffer.direction, script = buffer.script)

        val glyphs = ArrayList<GlyphInfo>(n)
        val positions = ArrayList<GlyphPosition>(n)
        var totalAdvance = 0f

        memScoped {
            val countOut = alloc<UIntVar>()
            val infos = hb_buffer_get_glyph_infos(buffer.handle.reinterpret(), countOut.ptr)
            val pos = hb_buffer_get_glyph_positions(buffer.handle.reinterpret(), countOut.ptr)
            for (i in 0 until n) {
                val info = infos!![i]
                val p = pos!![i]
                glyphs.add(GlyphInfo(info.codepoint.toInt(), info.cluster.toInt(), info.mask.toInt()))
                val xAdv = toPixels(p.x_advance)
                positions.add(
                    GlyphPosition(
                        xAdvance = xAdv,
                        yAdvance = toPixels(p.y_advance),
                        xOffset = toPixels(p.x_offset),
                        yOffset = toPixels(p.y_offset),
                    )
                )
                totalAdvance += xAdv
            }
        }

        return ShapedRun(
            glyphs = glyphs,
            positions = positions,
            direction = buffer.direction,
            script = buffer.script,
            totalAdvance = totalAdvance,
            ink = HbRect.EMPTY,             // glyph-extents-based ink box not yet implemented
            logical = HbRect(0f, 0f, totalAdvance, 0f),
        )
    }

    suspend fun shapeParagraph(
        font: HbFont,
        text: String,
        sizePx: Float,
        baseDirection: HbDirection,
        features: List<HbFeature>,
        language: HbLanguage,
    ): ShapedParagraph {
        if (text.isEmpty()) return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)

        val resolver = BidiResolver()
        val bidiRuns = resolver.resolve(text, baseDirection)
        if (bidiRuns.isEmpty()) return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)

        val visualOrder = if (
            baseDirection == HbDirection.RTL ||
            (baseDirection == HbDirection.AUTO && bidiRuns.firstOrNull()?.isRtl == true)
        ) bidiRuns.reversed() else bidiRuns

        HbBuffer().use { buf ->
            val runs = mutableListOf<ShapedRun>()
            var totalAdvance = 0f
            for (run in visualOrder) {
                val runText = text.substring(run.start, run.end)
                if (runText.isEmpty()) continue
                buf.reset()
                buf.text = runText
                buf.direction = if (run.isRtl) HbDirection.RTL else HbDirection.LTR
                // Leave script as HbScript.AUTO (default) so HarfBuzz's
                // hb_buffer_guess_segment_properties detects the run's script
                // from its codepoints. Setting UNKNOWN would suppress that and
                // break the Arabic / Indic shapers.
                buf.language = language
                buf.features = features

                val shaped = font.shape(buf, sizePx)
                val offsetGlyphs = shaped.glyphs.map { it.copy(cluster = it.cluster + run.start) }
                runs.add(shaped.copy(glyphs = offsetGlyphs))
                totalAdvance += shaped.totalAdvance
            }
            val n = text.length
            return ShapedParagraph(
                runs = runs,
                baseDirection = if (baseDirection == HbDirection.AUTO) {
                    if (bidiRuns.firstOrNull()?.isRtl == true) HbDirection.RTL else HbDirection.LTR
                } else baseDirection,
                totalAdvance = totalAdvance,
                ink = HbRect.EMPTY,
                logical = HbRect(0f, 0f, totalAdvance, 0f),
                logicalToVisual = identityIntArray(n),
                visualToLogical = identityIntArray(n),
            )
        }
    }
}
