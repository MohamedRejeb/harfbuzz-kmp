package com.mohamedrejeb.harfbuzz.core

/**
 * A font configured for shaping - a face plus a point size (and, in v1.1,
 * variation axes). Shaping calls go through this object.
 *
 * Not thread-safe: don't call shaping methods on the same `HbFont` from
 * multiple threads concurrently. Distinct `HbFont` instances are independent.
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
    public var pointSize: Float
    public val isClosed: Boolean

    /** Glyph id for a Unicode codepoint, or `0` if the font has none. */
    public suspend fun glyphIdForCodepoint(codepoint: Int): Int

    /** Tight ink bbox of a glyph at this font's point size, or `null` if unknown. */
    public suspend fun glyphExtents(glyphId: Int): GlyphExtents?

    /**
     * Font-wide horizontal extents at this font's point size. Computed
     * lazily on first read; null when the font reports no usable
     * horizontal table or the font has already been closed.
     *
     * Threading: the underlying lookup (a JNI / cinterop call on
     * JVM/Android/iOS) is not internally locked, so the first read should
     * happen inside [runShapingWork] (or any other `harfbuzzDispatcher`
     * context). All resident library callers already do this.
     */
    public val hExtents: FontExtents?

    /**
     * Conversion factor that maps coordinates in the format produced by
     * [snapshotGlyphs]'s SVG-path strings into Compose pixel space.
     *
     *  - JVM/iOS: `1f` - `StringPathSink` records pixel-space coords because
     *    HarfBuzz's draw callbacks pre-divide hb_position_t by 64 (font
     *    scale is `pointSize * 64`).
     *  - Wasm: `1f / 64f` - harfbuzzjs's `font.glyphToPath(gid)` returns
     *    coords in the raw 26.6 fixed-point (pixels × 64).
     *
     * Snapshotted at construction; consumed by `parseSvgPathToCompose` in
     * the compose layer to keep cross-platform unit conversion in one place.
     */
    public val pathScale: Float

    /**
     * Style metadata inferred from the font's OS/2 + STAT tables - weight
     * (1..1000), italic (true/false), and width (1.0 = normal). Used by
     * [HbFontStack] to inherit the primary font's style when resolving
     * system fallbacks: a bold-italic Latin primary picks up bold-italic
     * Arabic from the system, no manual config required.
     *
     * Returns the registered defaults (`weight=400`, `italic=false`,
     * `width=1.0`) when the font doesn't expose the corresponding axis.
     */
    public val styleHint: FontStyleHint

    /** Horizontal advance of a glyph at this font's point size. */
    public suspend fun glyphAdvance(glyphId: Int): Float

    /**
     * Shape a single-direction run. The buffer's `text`, `direction`, `script`,
     * `language`, and `features` must already be configured. The buffer is
     * left in a consumed state; call `reset()` before reusing it.
     */
    public suspend fun shape(buffer: HbBuffer): ShapedRun

    /**
     * Shape a paragraph: split [text] into bidi runs, segment by script, shape
     * each run, and assemble a [ShapedParagraph] in visual order with
     * logical↔visual maps.
     */
    public suspend fun shapeParagraph(
        text: String,
        baseDirection: HbDirection = HbDirection.AUTO,
        features: List<HbFeature> = emptyList(),
        language: HbLanguage = HbLanguage.AUTO,
    ): ShapedParagraph

    /** Emit the outline of a single glyph as draw commands into [sink]. */
    public suspend fun drawGlyph(glyphId: Int, sink: HbPathSink)

    /**
     * Layered color decomposition of [glyphId] for COLR v0 fonts. Returns
     * the empty list for monochrome glyphs or when the face has no color
     * tables. Each entry is a base glyph + a palette color (or `null` for
     * the caller-provided foreground); composite the layers in returned
     * order to render the colored glyph.
     */
    public suspend fun glyphColorLayers(glyphId: Int): List<ColorLayer>

    /**
     * Walk the COLR v1 paint tree for [glyphId] and dispatch every
     * operation into [sink]. No-op for fonts without a COLR v1 paint table
     * (check [HbFace.hasColorPaint] first to skip the call entirely).
     *
     * @param glyphId Glyph to paint.
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
        foreground: Int = 0xFF000000.toInt(),
        paletteIndex: Int = 0,
        sink: HbPaintSink,
    )

    /**
     * SVG document bytes for [glyphId] from the face's `SVG ` table, or
     * `null` if the face has no SVG table or the glyph isn't covered by
     * any of its `SVGDocumentRecord` ranges.
     *
     * The returned bytes are a complete, well-formed SVG document - but
     * note that SVG-in-OpenType encodes one document per glyph *range*
     * (`startGlyphID`..`endGlyphID`), so the same byte sequence may be
     * returned for several adjacent glyphs. Within that document the
     * specific glyph is identified by `id="glyphNN"` on a `<g>` element;
     * a renderer can either focus that subtree or rely on the document's
     * coordinate system to position the glyph correctly when drawn at
     * its glyph extents.
     *
     * Documents may be SVGZ (gzip-compressed); HarfBuzz returns them
     * already decompressed. Callers can typically feed them straight into
     * an SVG renderer such as Skia's `SVGDOM`.
     */
    public suspend fun glyphSvg(glyphId: Int): ByteArray?

    /**
     * Build a batched [GlyphSnapshot] for [gids] in a single dispatcher
     * hop (JVM/iOS) or a single worker round-trip (Wasm).
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
    public suspend fun snapshotGlyphs(gids: IntArray, flags: GlyphSnapshotFlags): GlyphSnapshot

    override fun close()
}
