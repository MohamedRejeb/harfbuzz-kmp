package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DrawGlyphTest {

    private fun loadSystemFont(): ByteArray? {
        val candidates = listOf(
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/HelveticaNeue.ttc",
            "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        )
        return candidates.firstOrNull { File(it).exists() }?.let { File(it).readBytes() }
    }

    @Test
    fun `drawGlyph emits at least one move and one close for a typical glyph`() = runBlocking {
        val bytes = loadSystemFont() ?: run {
            println("Skipping - no system font available")
            return@runBlocking
        }
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont().use { font ->
                val gid = font.glyphIdForCodepoint('O'.code)
                if (gid == 0) return@use  // font lacks glyph

                val sink = RecordingSink()
                font.drawGlyph(gid, sizePx = 16f, sink = sink)

                val moves = sink.commands.count { it.startsWith("M ") }
                val closes = sink.commands.count { it == "Z" }
                val curves = sink.commands.count { it.startsWith("Q ") || it.startsWith("C ") }
                val lines = sink.commands.count { it.startsWith("L ") }

                assertTrue(moves >= 1, "expected at least one moveTo, got commands=${sink.commands}")
                assertTrue(closes >= 1, "expected at least one close, got commands=${sink.commands}")
                assertTrue(curves + lines >= 1, "expected at least one curve or line segment")

                // Sanity range-check: at pointSize=16, 'O' coordinates should sit
                // within roughly ±30 px of origin. If they don't (e.g. we forgot
                // to divide by 64 somewhere along the JNI → Compose Path path),
                // this catches it before the bug ships.
                val maxAbs = sink.coords.maxOf { kotlin.math.abs(it) }
                assertTrue(
                    maxAbs in 1f..50f,
                    "glyph coordinates out of pixel-space range (±$maxAbs at pointSize=16). " +
                        "Likely a missing 1/64 scale conversion in the JNI draw callbacks.",
                )
            }
        }
    }

    private class RecordingSink : HbPathSink {
        val commands = mutableListOf<String>()
        val coords = mutableListOf<Float>()
        override fun moveTo(x: Float, y: Float) {
            commands += "M $x $y"; coords += x; coords += y
        }
        override fun lineTo(x: Float, y: Float) {
            commands += "L $x $y"; coords += x; coords += y
        }
        override fun quadTo(c1x: Float, c1y: Float, x: Float, y: Float) {
            commands += "Q $c1x $c1y $x $y"; coords += c1x; coords += c1y; coords += x; coords += y
        }
        override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
            commands += "C $c1x $c1y $c2x $c2y $x $y"
            coords += c1x; coords += c1y; coords += c2x; coords += c2y; coords += x; coords += y
        }
        override fun close() {
            commands += "Z"
        }
    }
}
