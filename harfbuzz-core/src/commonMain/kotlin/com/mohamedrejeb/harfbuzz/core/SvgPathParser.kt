package com.mohamedrejeb.harfbuzz.core

/**
 * Parse the SVG path subset emitted by HarfBuzz's draw callbacks (`M x,y`,
 * `L x,y`, `Q cx,cy x,y`, `C c1x,c1y c2x,c2y x,y`, `Z`) into [HbPathSink]
 * callbacks.
 *
 * Two emit conventions feed this parser:
 *  - JVM/iOS [StringPathSink] writes coordinates already in pixel space -
 *    HarfBuzz's draw callbacks pre-divide hb_position_t by 64 because the
 *    font scale was set to `pointSize * 64`. Callers pass `scale = 1f`.
 *  - Wasm `font.glyphToPath(gid)` returns coords in `pixels * 64` (the raw
 *    HB fixed-point), so callers pass `scale = 1f / 64f`.
 *
 * [scale] is applied uniformly to every coordinate before forwarding to
 * the sink.
 */
public fun parseSvgPathToSink(path: String, sink: HbPathSink, scale: Float) {
    if (path.isEmpty()) return
    var i = 0
    val n = path.length

    fun skipSeparators() {
        while (i < n && (path[i] == ' ' || path[i] == ',' || path[i] == '\t')) i++
    }

    fun readNumber(): Float {
        skipSeparators()
        val start = i
        if (i < n && (path[i] == '-' || path[i] == '+')) i++
        while (i < n) {
            val c = path[i]
            if (c.isDigit() || c == '.' || c == 'e' || c == 'E' ||
                ((c == '-' || c == '+') && (path[i - 1] == 'e' || path[i - 1] == 'E'))
            ) i++ else break
        }
        return path.substring(start, i).toFloat()
    }

    while (i < n) {
        val c = path[i]
        when (c) {
            'M' -> {
                i++
                val x = readNumber() * scale
                val y = readNumber() * scale
                sink.moveTo(x, y)
            }
            'L' -> {
                i++
                val x = readNumber() * scale
                val y = readNumber() * scale
                sink.lineTo(x, y)
            }
            'Q' -> {
                i++
                val cx = readNumber() * scale
                val cy = readNumber() * scale
                val x = readNumber() * scale
                val y = readNumber() * scale
                sink.quadTo(cx, cy, x, y)
            }
            'C' -> {
                i++
                val c1x = readNumber() * scale
                val c1y = readNumber() * scale
                val c2x = readNumber() * scale
                val c2y = readNumber() * scale
                val x = readNumber() * scale
                val y = readNumber() * scale
                sink.cubicTo(c1x, c1y, c2x, c2y, x, y)
            }
            'Z', 'z' -> {
                i++
                sink.close()
            }
            ' ', ',', '\t', '\n', '\r' -> i++
            else -> i++   // tolerate unknown commands
        }
    }
}
