package com.mohamedrejeb.harfbuzz.core

/**
 * A font configured for shaping. Owns a HarfBuzz `hb_font_t` derived from
 * a face plus an optional set of variation-axis pinnings. Sizeless: the
 * point size is supplied per shape / glyph call so a single `HbFont`
 * can render at any size without re-creating native handles.
 *
 * Threading: not safe to call shaping methods on the same `HbFont` from
 * multiple threads concurrently. Distinct `HbFont` instances are
 * independent. Internally every method dispatches to the
 * single-threaded `harfbuzzDispatcher`, so concurrent calls from
 * coroutines on different threads are serialised - but the underlying
 * `hb_font_t` scale is mutated per call, so two shaping calls at
 * different sizes that share the same handle must not be observed
 * mid-flight by a third reader. The dispatcher serialisation makes this
 * a non-issue for normal use; raw native pointer escape is not
 * supported.
 */
/**
 * HarfBuzz style tag four-CC values - packed as `(c1<<24)|(c2<<16)|(c3<<8)|c4`,
 * matching `HB_TAG('w','g','h','t')` on the C side. Passed to
 * `hb_style_get_value` via the [HarfbuzzNative.fontStyleValue] JNI binding.
 */
internal const val HB_STYLE_TAG_WEIGHT: Int = 0x77676874 // 'wght'
internal const val HB_STYLE_TAG_ITALIC: Int = 0x6974616C // 'ital'
internal const val HB_STYLE_TAG_WIDTH: Int = 0x77647468  // 'wdth'

public expect class HbFont : AutoCloseable {
    public val face: HbFace
    public val isClosed: Boolean

    /** Glyph id for a Unicode codepoint, or `0` if the font has none. */
    public suspend fun glyphIdForCodepoint(codepoint: Int): Int

    /** Tight ink bbox of a glyph at [sizePx], or `null` if unknown. */
    public suspend fun glyphExtents(glyphId: Int, sizePx: Float): GlyphExtents?

    /**
     * Font-wide horizontal extents at [sizePx]. Returns `null` when the
     * font reports no usable horizontal table or the font has already
     * been closed.
     *
     * Threading: must be called from a coroutine context. Internally
     * runs on the harfbuzzDispatcher, so no extra synchronisation is
     * required at the call site.
     */
    public suspend fun hExtents(sizePx: Float): FontExtents?

    /**
     * Conversion factor that maps coordinates in the format produced by
     * [snapshotGlyphs]'s SVG-path strings into Compose pixel space.
     *
     *  - JVM/iOS: `1f` - `StringPathSink` records pixel-space coords because
     *    HarfBuzz's draw callbacks pre-divide hb_position_t by 64.
     *  - Wasm: `1f / 64f` - harfbuzzjs's `font.glyphToPath(gid)` returns
     *    coords in the raw 26.6 fixed-point (pixels x 64).
     *
     * Snapshotted at construction; consumed by `parseSvgPathToCompose` in
     * the compose layer to keep cross-platform unit conversion in one place.
     */
    public val pathScale: Float

    /** Horizontal advance of a glyph at [sizePx]. */
    public suspend fun glyphAdvance(glyphId: Int, sizePx: Float): Float

    /**
     * Shape a single-direction run at [sizePx]. The buffer's `text`,
     * `direction`, `script`, `language`, and `features` must already be
     * configured. The buffer is left in a consumed state; call `reset()`
     * before reusing it.
     */
    public suspend fun shape(buffer: HbBuffer, sizePx: Float): ShapedRun

    /**
     * Shape a paragraph at [sizePx]: split [text] into bidi runs,
     * segment by script, shape each run, and assemble a [ShapedParagraph]
     * in visual order with logical/visual maps.
     */
    public suspend fun shapeParagraph(
        text: String,
        sizePx: Float,
        baseDirection: HbDirection = HbDirection.AUTO,
        features: List<HbFeature> = emptyList(),
        language: HbLanguage = HbLanguage.AUTO,
    ): ShapedParagraph

    /**
     * Emit the outline of a single glyph at [sizePx] as draw commands
     * into [sink].
     */
    public suspend fun drawGlyph(glyphId: Int, sizePx: Float, sink: HbPathSink)

    /**
     * Layered color decomposition of [glyphId] for COLR v0 fonts. Returns
     * the empty list for monochrome glyphs or when the face has no color
     * tables. Each entry is a base glyph + a palette color (or `null` for
     * the caller-provided foreground); composite the layers in returned
     * order to render the colored glyph.
     *
     * Sizeless: the layer structure (which sub-glyphs and which palette
     * indices) is face-level. Render each returned layer with
     * [drawGlyph] / [paintGlyph] at whatever size is desired.
     */
    public suspend fun glyphColorLayers(glyphId: Int): List<ColorLayer>

    /**
     * Walk the COLR v1 paint tree for [glyphId] at [sizePx] and dispatch
     * every operation into [sink]. No-op for fonts without a COLR v1
     * paint table (check [HbFace.hasColorPaint] first to skip the call
     * entirely).
     *
     * @param glyphId Glyph to paint.
     * @param sizePx Render size in pixels.
     * @param foreground 32-bit packed `0xAARRGGBB` foreground color
     *   substituted whenever the paint tree references the caller's
     *   foreground (for instance, the glyph is partially monochrome).
     * @param paletteIndex Which CPAL palette to resolve color indices
     *   against. `0` is the default palette every conformant font ships.
     * @param sink Receives the paint operations in HarfBuzz emit order.
     *   Implementations can record into a [RecordingPaintSink] for caching
     *   or dispatch directly into a renderer.
     */
    public suspend fun paintGlyph(
        glyphId: Int,
        sizePx: Float,
        foreground: Int = 0xFF000000.toInt(),
        paletteIndex: Int = 0,
        sink: HbPaintSink,
    )

    /**
     * SVG document bytes for [glyphId] from the face's `SVG ` table, or
     * `null` if the face has no SVG table or the glyph isn't covered by
     * any of its `SVGDocumentRecord` ranges.
     *
     * Sizeless: SVG documents are stored on the face and rendered at any
     * size by the consuming SVG renderer.
     */
    public suspend fun glyphSvg(glyphId: Int): ByteArray?

    /**
     * Build a batched [GlyphSnapshot] for [gids] at [sizePx] in a single
     * dispatcher hop (JVM/iOS) or a single worker round-trip (Wasm).
     *
     * Each entry in [flags] controls which map of the snapshot is
     * populated. Callers that only need a few of the maps should leave
     * the other flags `false` so the helper skips the corresponding
     * native calls.
     *
     * Use this instead of looping per-glyph calls when building caches
     * for shaped runs - it amortises the dispatcher / worker overhead
     * across the whole batch.
     */
    public suspend fun snapshotGlyphs(
        gids: IntArray,
        sizePx: Float,
        flags: GlyphSnapshotFlags,
    ): GlyphSnapshot

    override fun close()
}
