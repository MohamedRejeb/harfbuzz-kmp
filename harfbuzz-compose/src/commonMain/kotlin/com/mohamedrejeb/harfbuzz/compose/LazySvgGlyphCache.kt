package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.yieldToBrowser

/**
 * Per-paragraph view of rasterised SVG-in-OT glyphs. The expensive
 * [renderSvgGlyph] call (Skia `SVGDOM` parse + raster on every
 * supported platform that ships skiko) is delegated to
 * [SvgBitmapCache], a process-wide store keyed by
 * `(face, glyphId, pointSize)` - so emoji used in N paragraphs are
 * rasterised once instead of N times.
 *
 * The original design rasterised on first draw to keep build cheap,
 * but a paragraph with a few dozen emoji glyphs synchronously
 * rasterised in one render frame pinned the Wasm main thread for
 * >13 s. Eagerly paying that cost off-main during build (the build
 * coroutine yields via [yieldToBrowser] between glyphs) trades a
 * Loading state we already show for a draw phase that just stamps
 * bitmaps.
 *
 * Threading: callers are expected to be the renderer (always the UI
 * thread on every supported platform), so the rendered map is plain
 * immutable. Concurrent draws of the same `MeasuredText` from
 * multiple threads aren't a supported pattern.
 */
internal class LazySvgGlyphCache private constructor(
    private val rendered: Map<Int, SvgGlyphRender?>,
) {
    val hasAnyGlyphs: Boolean = rendered.values.any { it != null }

    fun get(glyphId: Int): SvgGlyphRender? = rendered[glyphId]

    companion object {
        val EMPTY: LazySvgGlyphCache = LazySvgGlyphCache(emptyMap())

        suspend fun build(
            face: HbFace,
            svgBytesByGlyph: Map<Int, ByteArray>,
            pointSize: Float,
            upem: Int,
        ): LazySvgGlyphCache {
            if (svgBytesByGlyph.isEmpty()) return EMPTY
            val rendered = HashMap<Int, SvgGlyphRender?>(svgBytesByGlyph.size)
            for ((gid, bytes) in svgBytesByGlyph) {
                rendered[gid] = if (bytes.isEmpty()) null
                    else SvgBitmapCache.getOrRasterize(
                        face = face,
                        glyphId = gid,
                        svgBytes = bytes,
                        pointSize = pointSize,
                        upem = upem,
                    )
                // Yield after every glyph - each cache miss is a Skia
                // SVGDOM parse + raster that for hand-tuned color fonts
                // (Aref Ruqaa Ink) can be hundreds of ms for one
                // document. Cache hits are fast, but the uniform yield
                // keeps the loop predictable and is one cheap macrotask
                // per glyph in steady state.
                yieldToBrowser()
            }
            return LazySvgGlyphCache(rendered)
        }
    }
}

