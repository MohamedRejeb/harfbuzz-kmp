package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * One rasterised SVG-in-OpenType glyph: a Compose [bitmap] plus the
 * design-space rectangle the bitmap covers. SVG-in-OT documents emit
 * paint coordinates in font design units (not pixels) with the y-axis
 * pointing DOWN - so the same bitmap can sit anywhere relative to the
 * glyph origin, including above and below the baseline. Storing the
 * design-unit bounds with the bitmap keeps draw-time positioning
 * straightforward: convert to pixel space via `pointSize / upem` and
 * stamp.
 *
 * @property bitmap the rasterised SVG content
 * @property designOffsetX bitmap (0,0) corresponds to design-space x =
 *   `designOffsetX` (typically negative, e.g. `-padding`)
 * @property designOffsetY bitmap (0,0) corresponds to design-space y =
 *   `designOffsetY`. With OT-SVG's y-down convention, drawing the
 *   bitmap at canvas-y `glyphOriginY + designOffsetY * pointSize/upem`
 *   lines up the glyph with the baseline.
 * @property designWidth design-unit width covered by the bitmap
 * @property designHeight design-unit height covered by the bitmap
 */
internal data class SvgGlyphRender(
    val bitmap: ImageBitmap,
    val designOffsetX: Float,
    val designOffsetY: Float,
    val designWidth: Float,
    val designHeight: Float,
)

/**
 * Renders an SVG-in-OpenType glyph document into a Compose [ImageBitmap]
 * that the draw layer can stamp at the glyph position. Implementations
 * are platform-specific:
 *  - JVM and iOS use skiko's `SVGDOM` (skia-svg) synchronously inside
 *    a suspend wrapper.
 *  - Wasm rasters through the browser's native SVG pipeline via
 *    `createImageBitmap(Blob)` - runs off the JS main thread inside
 *    Chromium's image-decode service, then copies the pixels into a
 *    skia Bitmap so the rest of the renderer (which expects a Compose
 *    [ImageBitmap]) doesn't change.
 *  - Android uses AndroidSVG (`com.caverock:androidsvg-aar`) - the
 *    same library Coil's `coil-svg` ships, since the system Skia
 *    bundled with the platform doesn't expose SVGDOM.
 *
 * The function suspends so the Wasm actual can `await` the
 * `createImageBitmap` Promise without blocking main; JVM / iOS / Android
 * actuals just don't use the suspension point.
 *
 * @param svgBytes complete, well-formed SVG document (HarfBuzz already
 *   decompresses SVGZ before handing it back)
 * @param pointSize the font's current point size in pixels
 * @param upem the face's units-per-em (used to size the bitmap so
 *   `pointSize / upem` is the design-to-pixel scale)
 * @param glyphHalfEm padding multiplier - a glyph spanning more than
 *   `2 * glyphHalfEm` ems in either direction will be cropped. `1.5f`
 *   covers extreme descenders + ascenders without wasting memory.
 *
 * @return an [SvgGlyphRender] with the rasterised bitmap + design-space
 *   bounds, or `null` if the bytes don't parse.
 */
internal expect suspend fun renderSvgGlyph(
    svgBytes: ByteArray,
    pointSize: Float,
    upem: Int,
    glyphHalfEm: Float = 1.5f,
): SvgGlyphRender?
