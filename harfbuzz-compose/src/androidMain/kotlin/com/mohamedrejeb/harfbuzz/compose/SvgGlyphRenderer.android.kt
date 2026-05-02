package com.mohamedrejeb.harfbuzz.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Android SVG-in-OT rasteriser backed by the AndroidSVG library
 * (`com.caverock:androidsvg-aar`). Android's bundled Skia doesn't
 * expose `SVGDOM` to apps, so the JVM/iOS skiko path isn't reachable
 * here; AndroidSVG is the same library Coil's `coil-svg` uses on
 * Android, so it's well-trodden ground for the Compose ecosystem.
 *
 * The contract matches the JVM/iOS actual: the bitmap covers a
 * `2 * glyphHalfEm`-em square centred on the design-space origin so
 * descenders / ascenders / wide horizontals don't get clipped, and the
 * canvas transform translates+scales design units to pixels before
 * AndroidSVG's `renderToCanvas` paints.
 */
internal actual suspend fun renderSvgGlyph(
    svgBytes: ByteArray,
    pointSize: Float,
    upem: Int,
    glyphHalfEm: Float,
): SvgGlyphRender? {
    if (svgBytes.isEmpty() || pointSize <= 0f || upem <= 0) return null

    val sidePx = max(1, ceil(pointSize * 2f * glyphHalfEm).toInt())
    val designSpan = upem.toFloat() * glyphHalfEm
    val designToPx = pointSize / upem

    val svg = try {
        SVG.getFromInputStream(ByteArrayInputStream(svgBytes))
    } catch (t: Throwable) {
        return null
    }
    // Most OT-SVG documents have neither width/height nor viewBox, so
    // these setters mainly exist as a safety hint for `100%` lengths.
    // The canvas transform below is what really positions the glyph.
    svg.documentWidth = sidePx.toFloat()
    svg.documentHeight = sidePx.toFloat()

    val bitmap = Bitmap.createBitmap(sidePx, sidePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // Center the design-unit origin at the bitmap center, then scale
    // design units to pixels. After this, drawing at design (x, y)
    // lands on canvas pixel (sidePx/2 + x*scale, sidePx/2 + y*scale).
    canvas.translate(sidePx / 2f, sidePx / 2f)
    canvas.scale(designToPx, designToPx)

    return try {
        svg.renderToCanvas(canvas)
        SvgGlyphRender(
            bitmap = bitmap.asImageBitmap(),
            designOffsetX = -designSpan,
            designOffsetY = -designSpan,
            designWidth = designSpan * 2f,
            designHeight = designSpan * 2f,
        )
    } catch (t: Throwable) {
        null
    }
}
