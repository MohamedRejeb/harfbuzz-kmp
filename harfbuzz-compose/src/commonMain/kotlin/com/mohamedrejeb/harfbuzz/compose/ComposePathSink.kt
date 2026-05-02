package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbPathSink

/**
 * Adapts HarfBuzz's draw callbacks (`hb_draw_funcs_t`) to a Compose [Path].
 * HarfBuzz emits y-up coordinates; this sink flips to Compose's y-down
 * convention so paths can be drawn directly without an extra transform.
 */
internal class ComposePathSink(private val path: Path) : HbPathSink {
    override fun moveTo(x: Float, y: Float) {
        path.moveTo(x, -y)
    }

    override fun lineTo(x: Float, y: Float) {
        path.lineTo(x, -y)
    }

    override fun quadTo(c1x: Float, c1y: Float, x: Float, y: Float) {
        path.quadraticTo(c1x, -c1y, x, -y)
    }

    override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        path.cubicTo(c1x, -c1y, c2x, -c2y, x, -y)
    }

    override fun close() {
        path.close()
    }
}
