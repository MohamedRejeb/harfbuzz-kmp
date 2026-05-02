package com.mohamedrejeb.harfbuzz.core

/**
 * Output of [HbFontStack.buildMeasured]. A bundle of one [ShapedParagraph]
 * plus the per-font raw glyph caches the renderer needs (path strings,
 * paint-tree wire bytes, COLR v0 layer indices, SVG-in-OT bytes), with
 * each cache keyed by glyph id. Path strings and wire bytes stay in
 * platform-neutral form here - the Compose layer parses them into
 * `androidx.compose.ui.graphics.Path` and `RecordedPaintOp` lists where
 * the `Path` type is in scope.
 *
 * The point of this aggregate is to let platform implementations bundle
 * the entire Compose-side `buildMeasuredText` request into a single
 * platform call. On Kotlin/Wasm this collapses into one Web Worker
 * round-trip per measured paragraph; on JVM/iOS/Android the orchestration
 * lives entirely in the platform-neutral fallback path inside
 * [HbFontStack.buildMeasured].
 */
public class MeasuredPass internal constructor(
    public val paragraph: ShapedParagraph,
    public val fontPasses: List<MeasuredFontPass>,
    /**
     * True when the platform layer has already extracted the per-glyph
     * `<g id="glyph${gid}">` subtree out of any multi-glyph OT-SVG
     * documents (e.g. NotoColorEmoji, Aref Ruqaa Ink) and packed only
     * those subtrees into [MeasuredFontPass.svgBytes]. The Compose
     * renderer can then hand the bytes straight to Skia without
     * re-running its own slicer over them.
     *
     * Set by the Wasm fast path because `hb-worker.js` slices in
     * linear memory before posting back. JVM / iOS / Android keep this
     * false - they ship the full document to the renderer, which slices
     * on the main thread.
     */
    public val svgBytesPreSliced: Boolean = false,
)

/**
 * Per-font subsection of a [MeasuredPass]. Each map is keyed by glyph id
 * (font-specific) and contains the raw byte-or-string payload directly as
 * HarfBuzz emits it. Empty maps mean either the corresponding capability
 * was disabled by the caller's [GlyphSnapshotFlags] or the font has no
 * data for that table - see [HbFont.snapshotGlyphs] for the wire format
 * details.
 */
public class MeasuredFontPass internal constructor(
    public val font: HbFont,
    public val flippedPathSvg: Map<Int, String>,
    public val rawPathSvg: Map<Int, String>,
    public val colorLayers: Map<Int, List<ColorLayer>>,
    public val paintTreeBytes: Map<Int, ByteArray>,
    public val svgBytes: Map<Int, ByteArray>,
)
