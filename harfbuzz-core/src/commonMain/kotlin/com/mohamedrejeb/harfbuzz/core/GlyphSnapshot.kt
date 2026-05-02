package com.mohamedrejeb.harfbuzz.core

/**
 * Pre-computed snapshot of every per-glyph datum a renderer might need,
 * built once per shape pass via [HbFont.snapshotGlyphs]. All maps are keyed
 * by glyph id; absent ids mean "this glyph didn't need that data".
 *
 * The wire format mirrors what the JNI/worker layer emits, so the same
 * decoder runs on every platform:
 *
 *  - [flippedPathSvg] / [rawPathSvg] - SVG-path strings using the subset
 *    `M x,y`, `L x,y`, `Q cx,cy x,y`, `C c1x,c1y c2x,c2y x,y`, `Z`.
 *    Both maps share the same coordinate space; the consumer applies
 *    Y-flip differently when parsing each map.
 *  - [colorLayers] - COLR v0 base-glyph + palette-color tuples per glyph.
 *  - [paintTreeBytes] - opaque wire-format bytes produced by the COLR v1
 *    paint-tree walker; consumed by `PaintBufferParser.dispatch`.
 *  - [svgBytes] - already-decompressed SVG document bytes from the
 *    face's `SVG ` table.
 *
 * Built by [HbFont.snapshotGlyphs]; not constructed directly.
 */
public class GlyphSnapshot internal constructor(
    public val flippedPathSvg: Map<Int, String>,
    public val rawPathSvg: Map<Int, String>,
    public val colorLayers: Map<Int, List<ColorLayer>>,
    public val paintTreeBytes: Map<Int, ByteArray>,
    public val svgBytes: Map<Int, ByteArray>,
)

/**
 * Selector for the pieces of glyph data [HbFont.snapshotGlyphs] should
 * fetch. Every flag defaults to `false` so callers opt in only to what
 * they will actually consume - the unfetched branches stay empty in the
 * resulting [GlyphSnapshot].
 */
public data class GlyphSnapshotFlags(
    val flippedPath: Boolean = false,
    val rawPath: Boolean = false,
    val colorLayers: Boolean = false,
    val paintTree: Boolean = false,
    val svg: Boolean = false,
)
