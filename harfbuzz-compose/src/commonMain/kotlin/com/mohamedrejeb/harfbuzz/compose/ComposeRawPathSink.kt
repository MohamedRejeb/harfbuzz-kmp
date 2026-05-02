package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbPathSink

/**
 * Like [ComposePathSink] but without the Y flip and stored in HarfBuzz's
 * 26.6 fixed-point scale (= pixel × 64) instead of pixel space. Used by
 * [ComposePaintSink] for `pushClipGlyph` clip outlines.
 *
 * The painter applies `canvas.scale(1/64, -1/64)` at the top of its
 * walk so that HB paint coordinates (which arrive in HB scale, see
 * `hb-paint-impl.hh::push_font_transform`) land in canvas pixels.
 * Clip paths drawn under that scaled canvas must therefore be in the
 * matching HB scale - otherwise the path renders at 1/64 of the glyph
 * size and the gradient fills miss the visible region.
 *
 * `font.drawGlyph` divides hb_position_t by 64 to deliver pixel-space
 * floats; we multiply back by 64 here to undo that and stash the
 * outline in HB scale.
 */
internal class ComposeRawPathSink(private val path: Path) : HbPathSink {
    override fun moveTo(x: Float, y: Float) {
        path.moveTo(x * HB_SCALE, y * HB_SCALE)
    }

    override fun lineTo(x: Float, y: Float) {
        path.lineTo(x * HB_SCALE, y * HB_SCALE)
    }

    override fun quadTo(c1x: Float, c1y: Float, x: Float, y: Float) {
        path.quadraticTo(c1x * HB_SCALE, c1y * HB_SCALE, x * HB_SCALE, y * HB_SCALE)
    }

    override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        path.cubicTo(
            c1x * HB_SCALE, c1y * HB_SCALE,
            c2x * HB_SCALE, c2y * HB_SCALE,
            x * HB_SCALE, y * HB_SCALE,
        )
    }

    override fun close() {
        path.close()
    }

    private companion object {
        /** HarfBuzz 26.6 fixed-point: 1 pixel = 64 HB scale units. */
        const val HB_SCALE = 64f
    }
}
