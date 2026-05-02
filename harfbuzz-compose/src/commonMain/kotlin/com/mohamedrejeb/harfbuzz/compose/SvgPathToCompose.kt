package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.parseSvgPathToSink

/**
 * Parse an SVG-path string emitted by HarfBuzz's drawGlyph (via
 * `StringPathSink` on JVM/iOS, or `font.glyphToPath` on Wasm) into a
 * Compose [Path].
 *
 *  - `flipY = true` builds the Y-down (Compose) variant - used for
 *    direct mono/glyph draws.
 *  - `flipY = false` keeps Y-up and stashes coords in HarfBuzz's 26.6
 *    fixed-point scale, the way `ComposeRawPathSink` records them, so
 *    `ComposePaintSink.pushClipGlyph` can render them under HB-scaled
 *    canvas transforms without re-flipping.
 *
 * [scale] is the per-platform unit conversion sourced from
 * [com.mohamedrejeb.harfbuzz.core.HbFont.pathScale]:
 *  - JVM/iOS: `1f` (path strings already in pixel space).
 *  - Wasm: `1f / 64f` (path strings in `pixels * 64`).
 */
internal fun parseSvgPathToCompose(svg: String, flipY: Boolean, scale: Float): Path {
    val path = Path()
    val sink = if (flipY) ComposePathSink(path) else ComposeRawPathSink(path)
    parseSvgPathToSink(svg, sink, scale = scale)
    return path
}
