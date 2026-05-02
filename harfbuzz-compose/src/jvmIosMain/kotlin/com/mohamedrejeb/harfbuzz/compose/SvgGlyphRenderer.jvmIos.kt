package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.svg.SVGDOM
import kotlin.math.ceil
import kotlin.math.max

/**
 * Skia-backed SVG-in-OT rasteriser shared by JVM, iOS, and Wasm
 * targets.
 *
 * SVG-in-OT documents emit paint coords in *font design units* with
 * the y-axis pointing DOWN. Skiko's `SVGDOM` renders into a canvas at
 * 1 user-unit-per-pixel by default and respects whatever transform
 * the canvas has applied - which is exactly what we need: scale by
 * `pointSize / upem` and translate so the glyph origin (0, 0 in
 * design space) sits at the bitmap's center, then render.
 *
 * The rendered bitmap covers a `2 * glyphHalfEm`-em square centred on
 * the origin so glyphs with descenders, accents, or wide horizontal
 * extents fit without being cropped at the edge of the buffer.
 *
 * SVG documents that span multiple glyphs (the SVGDocumentRecord may
 * cover a glyph range) get rasterised once per glyph; the redundant
 * `<g>` siblings are harmless because they all reference the same
 * defs and stack on top of each other in the same place.
 */
internal actual suspend fun renderSvgGlyph(
    svgBytes: ByteArray,
    pointSize: Float,
    upem: Int,
    glyphHalfEm: Float,
): SvgGlyphRender? {
    if (svgBytes.isEmpty() || pointSize <= 0f || upem <= 0) return null

    // 2 × halfEm bitmap edge in pixels, rounded up.
    val sidePx = max(1, ceil(pointSize * 2f * glyphHalfEm).toInt())
    // The same in design units - the glyph fits within `±halfEm` ems
    // around the origin.
    val designSpan = upem.toFloat() * glyphHalfEm
    val designToPx = pointSize / upem

    val data = Data.makeFromBytes(svgBytes)
    val dom = try {
        SVGDOM(data)
    } catch (t: Throwable) {
        data.close()
        return null
    }
    return try {
        // The container size is mostly used by SVGs with `width="100%"`
        // / `height="100%"`. Most OT-SVG documents have neither, so
        // this is just a safety hint - what really matters is the
        // canvas transform we apply below.
        dom.setContainerSize(sidePx.toFloat(), sidePx.toFloat())

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(sidePx, sidePx))
        bitmap.erase(0)                         // transparent background
        val canvas = Canvas(bitmap)
        // Center the design-unit origin at the bitmap center, then
        // scale design units to pixels. After this, drawing at design
        // (x, y) lands on canvas pixel (sidePx/2 + x*scale, sidePx/2 + y*scale).
        canvas.translate(sidePx / 2f, sidePx / 2f)
        canvas.scale(designToPx, designToPx)

        dom.render(canvas)

        SvgGlyphRender(
            bitmap = bitmap.asComposeImageBitmap(),
            designOffsetX = -designSpan,
            designOffsetY = -designSpan,
            designWidth = designSpan * 2f,
            designHeight = designSpan * 2f,
        )
    } finally {
        dom.close()
        data.close()
    }
}
