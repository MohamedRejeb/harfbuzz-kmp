package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.ColorLayer
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph

/**
 * The Compose-side bundle of everything needed to render a piece of text
 * shaped with HarfBuzz. Created by [rememberMeasuredText]; safe to retain
 * across recompositions and frames since it owns no native resources
 * directly - the per-glyph caches hold values derived once from the font
 * and reused on every redraw.
 *
 * When created from an [HbFontStack] (multi-font fallback shaping) every
 * cache is keyed first by [HbFont] then by glyph id, because glyph ids
 * are font-specific. Single-font shaping populates only the primary
 * font's bucket.
 */
@Immutable
public class MeasuredText internal constructor(
    public val paragraph: ShapedParagraph,
    /**
     * The full font stack that produced [paragraph]. For single-font
     * shaping this contains only [font]; for multi-font shaping every
     * fallback that contributed glyphs is included so renderers can look
     * up font-specific caches by run.
     */
    public val fontStack: HbFontStack,
    public val ink: Rect,
    public val logical: Rect,
    public val baseline: Float,
    public val advance: Float,
    /** Distance from baseline up to the recommended top (font h-extents). */
    public val ascent: Float,
    /** Distance from baseline down to the recommended bottom (positive). */
    public val descent: Float,
    /** Extra vertical space the font designer recommends between lines. */
    public val lineGap: Float,
    /**
     * Compose-flipped (y-down) outlines per font, keyed by glyph id. Used
     * for monochrome draws and COLR v0 layer composition.
     */
    internal val flippedPathsByFont: Map<HbFont, Map<Int, Path>>,
    /**
     * Raw (y-up, in HB scale) outlines per font, keyed by glyph id. Used
     * by [ComposePaintSink]'s `pushClipGlyph` when replaying COLR v1
     * paint trees.
     */
    internal val rawPathsByFont: Map<HbFont, Map<Int, Path>>,
    /**
     * COLR v0 color-layer decompositions per font, keyed by glyph id.
     * Empty list means the glyph is monochrome (or the face has no color
     * tables) - render the base path with the caller-provided color.
     */
    internal val colorLayersByFont: Map<HbFont, Map<Int, List<ColorLayer>>>,
    /**
     * COLR v1 paint trees per font, keyed by glyph id. Replaying the
     * recorded ops against a [ComposePaintSink] reproduces the colored
     * glyph without touching JNI again.
     */
    internal val paintTreesByFont: Map<HbFont, Map<Int, List<RecordedPaintOp>>>,
    /**
     * Lazy SVG-in-OT glyph caches per font. Each cache holds the sliced
     * SVG bytes (cheap, populated at build time) and memoises raster
     * output on first draw - `renderSvgGlyph` is heavy enough that
     * eagerly running it for every emoji glyph would pin the main
     * thread for hundreds of ms after a paragraph appears. Empty when
     * the face has no `SVG ` table or the platform can't rasterise.
     */
    internal val svgGlyphCachesByFont: Map<HbFont, LazySvgGlyphCache>,
    /** True when any font in the stack has color glyphs of any kind. */
    public val hasColorGlyphs: Boolean,
    /** True when any font in the stack has a COLR v1 paint table. */
    public val hasColorPaintTree: Boolean,
    /**
     * True when any font in the stack has an `SVG ` table AND the
     * platform can actually rasterise SVG documents (Android currently
     * can't).
     */
    public val hasColorSvg: Boolean,
) {
    /** The primary font (kept for backward compatibility). */
    public val font: HbFont get() = fontStack.primary

    public val isEmpty: Boolean get() = paragraph.isEmpty
    /** `ascent + descent + lineGap` - the recommended row height for one line. */
    public val lineHeight: Float get() = ascent + descent + lineGap

    internal fun flippedPath(font: HbFont, glyphId: Int): Path? =
        flippedPathsByFont[font]?.get(glyphId)

    internal fun rawPath(font: HbFont, glyphId: Int): Path? =
        rawPathsByFont[font]?.get(glyphId)

    internal fun colorLayers(font: HbFont, glyphId: Int): List<ColorLayer>? =
        colorLayersByFont[font]?.get(glyphId)

    internal fun paintTree(font: HbFont, glyphId: Int): List<RecordedPaintOp>? =
        paintTreesByFont[font]?.get(glyphId)

    internal fun svgBitmap(font: HbFont, glyphId: Int): SvgGlyphRender? =
        svgGlyphCachesByFont[font]?.get(glyphId)

    internal fun hasSvgBitmaps(font: HbFont): Boolean =
        svgGlyphCachesByFont[font]?.hasAnyGlyphs == true

    internal fun rawPathsFor(font: HbFont): Map<Int, Path> =
        rawPathsByFont[font].orEmpty()

    /**
     * Rect of the caret (a thin vertical bar) at the given codepoint index in
     * the original logical (UTF-16) text. Not yet implemented.
     */
    public fun caretRectAt(charIndex: Int): Rect {
        TODO("Caret hit-testing not yet implemented")
    }

    /**
     * Codepoint index closest to [offset], measured from the top-left of the
     * logical layout. Inverse of [caretRectAt]. Not yet implemented.
     */
    public fun charIndexAt(offset: Offset): Int {
        TODO("Caret hit-testing not yet implemented")
    }

    public companion object {
        /** A no-op placeholder for slots that need a non-null `MeasuredText`. */
        public fun empty(font: HbFont): MeasuredText = MeasuredText(
            paragraph = ShapedParagraph.EMPTY,
            fontStack = HbFontStack(font),
            ink = Rect.Zero,
            logical = Rect.Zero,
            baseline = 0f,
            advance = 0f,
            ascent = 0f,
            descent = 0f,
            lineGap = 0f,
            flippedPathsByFont = emptyMap(),
            rawPathsByFont = emptyMap(),
            colorLayersByFont = emptyMap(),
            paintTreesByFont = emptyMap(),
            svgGlyphCachesByFont = emptyMap(),
            hasColorGlyphs = false,
            hasColorPaintTree = false,
            hasColorSvg = false,
        )
    }
}

internal fun HbRect.toComposeRect(): Rect = Rect(left, top, right, bottom)
