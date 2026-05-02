package com.mohamedrejeb.harfbuzz

import com.mohamedrejeb.harfbuzz.core.HbBuffer
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbScript
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.RecordingPaintSink
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the COLR + SVG color pipelines on JVM. Pairs
 * with [com.mohamedrejeb.harfbuzz.compose.SvgGlyphSlicerTest] (which
 * runs on every Compose target including Wasm) - between them they
 * gate the byte-level wire-format and the slicing logic that the
 * Wasm and JVM pipelines share.
 *
 * The assertions target places where the Wasm-specific code path
 * could silently diverge from JVM:
 *  - `face.hasColorPaint()` / `hasColorSvg()` consult HarfBuzz
 *  - `paintGlyph` round-trips through the wire format and emits at
 *    least one fill op
 *  - `glyphSvg` returns a complete document with the expected header
 *    and trailer (no truncation when copying out of wasm memory)
 *
 * Wasm doesn't run these directly: the karma test browser doesn't
 * proxy `hb.wasm` to the test runner. Adding a karma file-server
 * shim is tracked separately; for now we trust the slicer common
 * tests + manual browser smoke checks to catch Wasm regressions.
 */
class ColorPipelineJvmTest {

    @Test
    fun arefRuqaaInkPaintAndSvgPipelinesProduceContent() = runTest {
        harfBuzzInit()
        val bytes = readFontBytes(FontPath.AREF_RUQAA_INK_REGULAR)
        HbFace.from { bytes(bytes) }.use { face ->
            assertTrue(face.hasColorPaint(), "ArefRuqaaInk should advertise COLR v1 paint")
            assertTrue(face.hasColorSvg(), "ArefRuqaaInk should advertise SVG-in-OT")

            face.toFont(64f).use { font ->
                val gid = HbBuffer().use { buf ->
                    buf.text = "ب"
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    val run = font.shape(buf)
                    assertTrue(run.glyphs.isNotEmpty(), "ب should shape into at least one glyph")
                    assertTrue(
                        run.glyphs.all { it.glyphId != 0 },
                        "no .notdef glyphs (Aref Ruqaa Ink covers the Arabic block)",
                    )
                    run.glyphs.first().glyphId
                }

                val sink = RecordingPaintSink()
                font.paintGlyph(gid, sink = sink)
                assertTrue(sink.ops.isNotEmpty(), "paint walk should emit at least one op")
                val hasFill = sink.ops.any { op ->
                    op is RecordedPaintOp.SolidColor ||
                        op is RecordedPaintOp.LinearGradient ||
                        op is RecordedPaintOp.RadialGradient ||
                        op is RecordedPaintOp.SweepGradient
                }
                assertTrue(hasFill, "paint walk must emit a fill op (color/gradient)")

                val svg = font.glyphSvg(gid)
                assertNotNull(svg, "SVG bytes must come back for an ink glyph")
                assertTrue(svg.size > 0, "SVG document must be non-empty")
                val head = svg.copyOfRange(0, minOf(8, svg.size)).decodeToString()
                assertTrue(
                    head.startsWith("<?xml") || head.startsWith("<svg"),
                    "SVG should start with <?xml or <svg, got: ${head.take(20)}",
                )
            }
        }
    }

    @Test
    fun notoColorEmojiSurfacesLargeMultiGlyphSvg() = runTest {
        harfBuzzInit()
        val bytes = readFontBytes(FontPath.EMOJI)
        HbFace.from { bytes(bytes) }.use { face ->
            assertTrue(face.hasColorSvg(), "Noto Color Emoji should ship an SVG table")
            face.toFont(64f).use { font ->
                val gid = HbBuffer().use { buf ->
                    buf.text = "😀"
                    val run = font.shape(buf)
                    assertTrue(run.glyphs.isNotEmpty(), "smile emoji should shape")
                    run.glyphs.first { it.glyphId != 0 }.glyphId
                }
                val raw = font.glyphSvg(gid)
                assertNotNull(raw, "smile emoji must yield SVG bytes")
                assertTrue(
                    raw.size > 1024,
                    "Noto's emoji SVG is large (~14MB); got ${raw.size} bytes",
                )
                // The slicer (commonTest in harfbuzz-compose) verifies
                // we can pull individual glyphs out of multi-glyph
                // documents. Here we just ensure the bytes end with the
                // SVG closer - partial reads or truncated wasm-memory
                // copies would lose this.
                val tailStart = maxOf(0, raw.size - 16)
                val tail = raw.decodeToString(tailStart, raw.size).trimEnd()
                assertTrue(
                    tail.endsWith("</svg>"),
                    "raw SVG bytes should be a complete document; tail was: $tail",
                )
            }
        }
    }
}
