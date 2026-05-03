package com.mohamedrejeb.harfbuzz.core

/**
 * One font face. A `.ttc` collection contains multiple faces - pass [faceIndex]
 * to pick one. Closing is idempotent.
 */
public expect class HbFace : AutoCloseable {
    /** Units per em - the scale of font-design coordinates. */
    public val upem: Int

    /** Number of faces in the underlying file (1 for `.ttf` / `.otf`). */
    public val faceCount: Int

    public val isClosed: Boolean

    /** All OpenType feature tags this face advertises (`liga`, `kern`, …). */
    public fun openTypeTags(): List<HbTag>

    /**
     * `true` if this face ships COLR v0 layered color glyphs. Use
     * [HbFont.glyphColorLayers] to iterate the layers per glyph.
     */
    public fun hasColorLayers(): Boolean

    /**
     * `true` if this face ships COLR v1 paint-tree color glyphs (gradients,
     * transforms, composite layers). Use [HbFont.paintGlyph] to walk the
     * paint tree per glyph.
     */
    public fun hasColorPaint(): Boolean

    /**
     * `true` if this face ships an `SVG ` (SVG-in-OpenType) table.
     * Authors typically use it for higher-fidelity color glyph rendering
     * than COLR can express (gradients-on-paths, filters, full SVG 1.1
     * geometry). Use [HbFont.glyphSvg] to fetch the SVG document for a
     * glyph.
     *
     * Many fonts ship both `COLR` and `SVG ` and renderers prefer SVG
     * when available. The drawing layer ([drawShapedText]) checks this
     * before falling back to COLR v1 / v0.
     */
    public fun hasColorSvg(): Boolean

    /**
     * Style metadata inferred from the face's OS/2 + STAT tables - weight
     * (1..1000), italic (true/false), and width (1.0 = normal). Used by
     * [HbFontStack] to inherit the primary face's style when resolving
     * system fallbacks: a bold-italic Latin primary picks up bold-italic
     * Arabic from the system, no manual config required.
     *
     * Returns the registered defaults (`weight=400`, `italic=false`,
     * `width=1.0`) when the face doesn't expose the corresponding axis.
     *
     * Sizeless: derived from face design space, independent of any
     * render size.
     */
    public val styleHint: FontStyleHint

    /**
     * Construct a sizeless [HbFont] from this face. The returned font
     * can shape and render at any size - pass the desired pixel size to
     * each [HbFont] call.
     */
    public suspend fun toFont(): HbFont

    /**
     * Construct a sizeless [HbFont] from this face with [variations]
     * pinned. Each [HbVariation] selects a value on its axis; axes not
     * listed take their default. Variation values out of range are
     * clamped by HarfBuzz. For non-variable faces this is equivalent to
     * [toFont] - the variation list is silently ignored.
     */
    public suspend fun toFont(variations: List<HbVariation>): HbFont

    /**
     * Variation axes this face exposes (`fvar` table). Empty list when
     * the face is not a variable font. Discover the axes available,
     * inspect their `[min, default, max]` ranges, and feed pinned
     * values back through [toFont].
     */
    public fun variationAxes(): List<HbVariationAxis>

    override fun close()

    public companion object {
        /**
         * Load a face by describing where the bytes come from. The platform
         * source builders available inside the block depend on the target -
         * `bytes(...)` works everywhere, `file(...)` only on JVM / Native,
         * `resource(...)` via Compose Multiplatform Resources, etc.
         *
         * Throws [HbFaceLoadException] on parse failure; use [tryFrom] for a
         * sealed result type instead.
         *
         * **Wasm note:** [from] throws on Wasm because face creation has to
         * suspend across the worker boundary. Wasm callers must use
         * [fromBytes] instead.
         */
        public fun from(block: HbFaceSource.() -> Unit): HbFace

        /** Like [from] but returns [FaceLoad] instead of throwing. */
        public fun tryFrom(block: HbFaceSource.() -> Unit): FaceLoad

        /**
         * Asynchronous variant of [from] required on Wasm (the worker
         * boundary makes face creation suspend); on JVM/Android/iOS this is
         * a convenience wrapper that delegates to [from] inside
         * `withContext(harfbuzzDispatcher)` so callers don't block the
         * caller thread on the I/O-style face-load path.
         */
        public suspend fun fromBytes(bytes: ByteArray, faceIndex: Int = 0): HbFace
    }
}

/**
 * Builder collected by [HbFace.from] / [HbFace.tryFrom]. Platform-specific
 * source builders extend this via `expect`/`actual` so each target can offer
 * what makes sense for it.
 */
public expect class HbFaceSource {
    /** Universal - works on every target. */
    public fun bytes(bytes: ByteArray, faceIndex: Int = 0)

    /**
     * Load the font directly from a filesystem [path], skipping the
     * `readBytes()` + JVM-heap copy that [bytes] requires. The platform
     * mmaps the file so HarfBuzz reads from kernel-shared pages on
     * demand - font header on load, glyph outlines lazily during shape.
     *
     * Supported on JVM (incl. Android) and iOS, where the platform has
     * a real filesystem. **Not supported on Wasm** (browser sandbox has
     * no `mmap`); calling [path] on Wasm fails the face load with
     * [FaceLoad.InvalidFontData]. Wasm callers should use [bytes].
     *
     * Setting both [path] and [bytes] in the same builder is undefined -
     * the platform picks one (typically [path] since it's cheaper).
     *
     * @param path Absolute path to the font file. The file must exist
     *   and be readable; the platform `open(2)`s it and the resulting
     *   mapping is owned by the returned [HbFace] until close.
     * @param faceIndex Index into a TTC (TrueType Collection) bundle.
     *   Defaults to 0 - the first face in the file.
     */
    public fun path(path: String, faceIndex: Int = 0)
}

/** Sealed result of [HbFace.tryFrom]. */
public sealed interface FaceLoad {
    public data class Success(val face: HbFace) : FaceLoad
    public data class InvalidFontData(val cause: Throwable?) : FaceLoad
    public data class FaceIndexOutOfRange(val requested: Int, val available: Int) : FaceLoad
}
