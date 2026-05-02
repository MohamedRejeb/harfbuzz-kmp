package com.mohamedrejeb.harfbuzz

import com.mohamedrejeb.harfbuzz.core.HbBuffer
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbScript
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.RecordingPaintSink
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke coverage for the COLR v1 paint plumbing on JVM. Confirms that:
 *   1. ArefRuqaaInk-Regular reports `hasColorPaint() == true` (it ships
 *      paint trees with gradient inks).
 *   2. `paintGlyph` emits at least one operation for a real glyph.
 *   3. The recorded ops include color or gradient instructions - i.e. the
 *      JNI buffer round-trips and the parser dispatches correctly.
 *
 * We don't verify rendering yet - that lands with [ComposePaintSink] in
 * PR-2. The point here is that the JNI / commonMain / parser plumbing
 * works end-to-end for a font we control.
 */
class ColorPaintSmokeTest {

    @Test
    fun `aref ruqaa ink reports paint table and emits paint ops for glyph`() = runBlocking {
        val bytes = readFontBytes(FontPath.AREF_RUQAA_INK_REGULAR)
        HbFace.from { bytes(bytes) }.use { face ->
            assertTrue(
                face.hasColorPaint(),
                "ArefRuqaaInk should advertise a COLR v1 paint table",
            )

            face.toFont(64f).use { font ->
                // Pick the first non-zero glyph id from a representative shape.
                // Any ink-styled Arabic letter has paint content; "ب" gives a
                // dotted glyph that exercises multiple paint layers.
                val glyphId = HbBuffer().use { buf ->
                    buf.text = "ب"
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    val run = font.shape(buf)
                    run.glyphs.firstOrNull { it.glyphId != 0 }?.glyphId
                }
                assertNotNull(glyphId, "expected at least one shaped glyph")

                val sink = RecordingPaintSink()
                font.paintGlyph(glyphId, foreground = 0xFF000000.toInt(), sink = sink)

                assertTrue(
                    sink.ops.isNotEmpty(),
                    "paintGlyph should emit at least one paint op for an ink glyph",
                )

                val hasFill = sink.ops.any { op ->
                    op is RecordedPaintOp.SolidColor ||
                        op is RecordedPaintOp.LinearGradient ||
                        op is RecordedPaintOp.RadialGradient ||
                        op is RecordedPaintOp.SweepGradient
                }
                assertTrue(
                    hasFill,
                    "expected at least one fill op (color/gradient) - got ${sink.ops}",
                )

                // Regression guard: a former JNI bug read `*count` instead
                // of the function return value when probing
                // hb_color_line_get_color_stops, so every gradient came
                // back with stops=0 and rendered transparent. Aref Ruqaa
                // Ink uses gradient fills, so any gradient op must carry
                // stops.
                val gradientStopsCounts = sink.ops.mapNotNull { op ->
                    when (op) {
                        is RecordedPaintOp.LinearGradient -> op.stops.size
                        is RecordedPaintOp.RadialGradient -> op.stops.size
                        is RecordedPaintOp.SweepGradient -> op.stops.size
                        else -> null
                    }
                }
                if (gradientStopsCounts.isNotEmpty()) {
                    assertTrue(
                        gradientStopsCounts.all { it > 0 },
                        "gradient ops must have at least one color stop - got $gradientStopsCounts",
                    )
                }
            }
        }
    }

    @Test
    fun `aref ruqaa ink reports svg table and returns svg bytes for glyph`() = runBlocking {
        val bytes = readFontBytes(FontPath.AREF_RUQAA_INK_REGULAR)
        HbFace.from { bytes(bytes) }.use { face ->
            assertTrue(
                face.hasColorSvg(),
                "ArefRuqaaInk ships an SVG-in-OT table",
            )
            face.toFont(64f).use { font ->
                val glyphId = HbBuffer().use { buf ->
                    buf.text = "ب"
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    font.shape(buf).glyphs.firstOrNull { it.glyphId != 0 }?.glyphId
                }
                assertNotNull(glyphId, "expected at least one shaped glyph")

                val svg = font.glyphSvg(glyphId)
                assertNotNull(svg, "expected SVG document for glyph $glyphId")
                assertTrue(svg.size > 0, "SVG document should not be empty")

                // The document is well-formed - opens with `<?xml` or `<svg`,
                // not gzip magic (HarfBuzz auto-decompresses SVGZ).
                val head = svg.copyOfRange(0, minOf(8, svg.size)).decodeToString()
                assertTrue(
                    head.startsWith("<?xml") || head.startsWith("<svg"),
                    "SVG should start with <?xml or <svg, got: ${head.take(20)}",
                )
            }
        }
    }
}
