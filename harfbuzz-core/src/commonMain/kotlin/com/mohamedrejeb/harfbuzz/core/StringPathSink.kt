package com.mohamedrejeb.harfbuzz.core

/**
 * [HbPathSink] that records every command into an SVG-path string.
 *
 * The output uses the same subset emitted by harfbuzzjs's
 * `font.glyphToPath(gid)`, so the resulting string is parseable by the
 * shared [parseSvgPathToSink] helper:
 *
 *  - `M x,y` move-to
 *  - `L x,y` line-to
 *  - `Q cx,cy x,y` quadratic
 *  - `C c1x,c1y c2x,c2y x,y` cubic
 *  - `Z` close
 *
 * Coordinates are written verbatim - the sink does not scale them. JVM
 * and iOS callers therefore record coordinates in pixel units (HarfBuzz
 * is configured with `font_scale = pointSize * 64` and HarfBuzz emits
 * the draw callbacks already pre-divided by 64), while the Wasm side
 * already returns this same string from harfbuzzjs in `pixels * 64`
 * units. Each consumer picks the matching [parseSvgPathToSink] `scale`
 * argument.
 *
 * Use [build] to extract the recorded string after [HbFont.drawGlyph]
 * has dispatched its commands. Each instance is single-use; create a
 * fresh [StringPathSink] per glyph.
 */
internal class StringPathSink : HbPathSink {

    private val sb: StringBuilder = StringBuilder(64)

    override fun moveTo(x: Float, y: Float) {
        sb.append('M').append(x).append(',').append(y)
    }

    override fun lineTo(x: Float, y: Float) {
        sb.append('L').append(x).append(',').append(y)
    }

    override fun quadTo(c1x: Float, c1y: Float, x: Float, y: Float) {
        sb.append('Q')
            .append(c1x).append(',').append(c1y).append(' ')
            .append(x).append(',').append(y)
    }

    override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        sb.append('C')
            .append(c1x).append(',').append(c1y).append(' ')
            .append(c2x).append(',').append(c2y).append(' ')
            .append(x).append(',').append(y)
    }

    override fun close() {
        sb.append('Z')
    }

    fun build(): String = sb.toString()
}
