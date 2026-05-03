package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.paragraph.ShapedParagraphText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Multi-line counterparts of the brush / stroke / shadow coverage in
 * `ShapedTextScreenshotTest`. Each test wraps the paragraph under a
 * tight maxWidth so the gradient / stroke is visible across two or more
 * lines, then captures the result for golden comparison.
 *
 *  - Latin gradient fill: horizontal gradient spans the whole paragraph
 *    (not per-line), so the colour at the start of line 2 continues from
 *    where line 1 ended in the gradient ramp.
 *  - Arabic RTL gradient: cursive glyphs accumulate into the same
 *    silhouette path; the brush still maps to the paragraph's bounds.
 *  - Vertical gradient stroke: stroke style routes every glyph through
 *    the silhouette outline; the gradient fills along the paragraph's
 *    vertical axis so each line's stroke shifts colour.
 *  - Brush + shadow: per-line shadow under the brush gradient.
 *  - Stroke / stroke + shadow without brush: covers the solid-color
 *    paragraph stroke path, which the prior coverage only tested on
 *    single lines.
 *  - Aref Ruqaa Ink (COLR v1): brush leaves designed colours intact.
 *    forceForegroundColor=true tints silhouettes with the brush.
 *  - Noto Color Emoji: COLR v1 paint trees survive a brush argument.
 */
class ShapedParagraphBrushScreenshotTest {

    @get:Rule
    val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    private fun loadFont(path: String, sizePx: Float): HbFont = runBlocking {
        val bytes = readFontBytes(path)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont(sizePx)
        openFonts.add(font)
        openFonts.add(face)
        font
    }

    @Test
    fun `latin paragraph brush horizontal gradient fill`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_brush_latin_horizontal_gradient.png") {
            Stage {
                ShapedParagraphText(
                    text = "The quick brown fox jumps over the lazy dog and runs away today.",
                    font = font,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFFE91E63),
                            Color(0xFF673AB7),
                            Color(0xFF2196F3),
                        ),
                    ),
                    modifier = Modifier
                        .width(280.dp)
                        .height(140.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic paragraph brush horizontal gradient fill`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_brush_arabic_horizontal_gradient.png") {
            Stage {
                ShapedParagraphText(
                    text = "هذا نص عربي تجريبي للاختبار. نص متدرج الألوان بالعربية للقراءة.",
                    font = font,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFF5722),
                            Color(0xFFFFC107),
                        ),
                    ),
                    direction = HbDirection.RTL,
                    modifier = Modifier
                        .width(360.dp)
                        .height(160.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph brush vertical gradient stroke`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("paragraph_brush_latin_stroke_gradient.png") {
            Stage {
                ShapedParagraphText(
                    text = "Stroked text spans multiple lines with a vertical gradient.",
                    font = font,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xFF1976D2),
                            Color(0xFF388E3C),
                        ),
                    ),
                    style = Stroke(width = 2f),
                    modifier = Modifier
                        .width(320.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph brush with shadow`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_brush_latin_with_shadow.png") {
            Stage {
                ShapedParagraphText(
                    text = "Gradient text with a soft drop shadow under each line.",
                    font = font,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFFE91E63), Color(0xFF2196F3)),
                    ),
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.3f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f,
                    ),
                    modifier = Modifier
                        .width(320.dp)
                        .height(140.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph stroke without brush`() {
        // Solid-color stroke through the paragraph pipeline: pins the
        // shared stroke path - both brush and color routes pull
        // silhouettes from the same per-glyph cache, so this guards
        // against regressions in the non-brush stroke path.
        val font = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("paragraph_stroke_latin_solid.png") {
            Stage {
                ShapedParagraphText(
                    text = "Stroked solid color across multiple lines of text.",
                    font = font,
                    color = Color(0xFF111827),
                    style = Stroke(width = 1.5f),
                    modifier = Modifier
                        .width(320.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph stroke with shadow`() {
        // Stroked text + shadow at the paragraph level: per-line shadow
        // must follow the stroke path (i.e. shadow itself is stroked, not
        // filled), matching the single-line `stroke with shadow combined`
        // test.
        val font = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("paragraph_stroke_latin_with_shadow.png") {
            Stage {
                ShapedParagraphText(
                    text = "Stroked paragraph with shadow on every wrapped line.",
                    font = font,
                    color = Color(0xFF1F2937),
                    style = Stroke(width = 1.5f),
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.4f),
                        offset = Offset(2f, 2f),
                        blurRadius = 3f,
                    ),
                    modifier = Modifier
                        .width(320.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    /**
     * COLR v1 designed colours must survive a paragraph-level brush:
     * Aref Ruqaa Ink should still paint with its own gradient inks
     * because the paint tree owns the foreground. Pins that the
     * brush-aware paragraph path does not accidentally tint COLR v1
     * glyphs across wrapped lines.
     */
    @Test
    fun `aref ruqaa ink paragraph brush preserves designed colors`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 32f)
        rule.captureGolden("paragraph_brush_aref_ruqaa_ink_unchanged.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. اختبار عرض النصوص بالعربية.",
                    font = font,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF00BCD4), Color(0xFFE91E63)),
                    ),
                    direction = HbDirection.RTL,
                    modifier = Modifier
                        .width(320.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    /**
     * Brush + forceForegroundColor opts a COLR v1 font into the
     * silhouette path, so Aref Ruqaa Ink's glyphs paint with the
     * gradient instead of the font's designed inks - on every wrapped
     * line.
     */
    @Test
    fun `aref ruqaa ink paragraph brush force foreground tints silhouettes`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 32f)
        rule.captureGolden("paragraph_brush_aref_ruqaa_ink_force_foreground.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. اختبار عرض النصوص بالعربية.",
                    font = font,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF00BCD4), Color(0xFFE91E63)),
                    ),
                    direction = HbDirection.RTL,
                    forceForegroundColor = true,
                    modifier = Modifier
                        .width(320.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    /**
     * Color emoji + paragraph brush: each glyph still walks its COLR v1
     * paint tree, so the brush has no effect on the emoji glyphs - they
     * keep their authored colours across line wraps.
     */
    @Test
    fun `noto color emoji paragraph brush preserves designed colors`() {
        val font = loadFont(FontPath.EMOJI, 40f)
        rule.captureGolden("paragraph_brush_emoji_unchanged.png") {
            Stage {
                ShapedParagraphText(
                    text = "😀🌍🎉⭐ 🚀✨🎨🎵 🌟🌙☀️🌊",
                    font = font,
                    brush = Brush.horizontalGradient(
                        listOf(Color.Cyan, Color.Magenta),
                    ),
                    modifier = Modifier
                        .width(280.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .background(Color.White)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }

    private companion object {
        val BoundsColor = Color(0xFFB0B0B0)
    }
}
