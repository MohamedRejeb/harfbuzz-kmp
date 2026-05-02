package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Plain (non-arc) text screenshot coverage. Each test renders a known
 * `ShapedText` configuration onto a fixed white stage, then compares against
 * a golden under `sample/src/jvmTest/resources/goldens/`.
 *
 * Coverage:
 *  - Latin (Roboto) - baseline rendering, no GPOS marks.
 *  - Arabic with tashkeel - the Bismillah, exercises mark positioning so
 *    regressions in y_offset sign re-surface here as the wrong dot/mark Y.
 *  - Mixed BiDi - Latin + Arabic + Arabic-Indic numerals on one line.
 *  - Arabic Bold - heavier weight rendering through the Naskh GPOS table.
 *  - Lam-Alef ligation - single-glyph collapse must stay visible.
 *  - OT feature toggles - `liga` on vs off must produce different output.
 *  - Bounds overlay - magenta ink + cyan logical + amber baseline pinned.
 *
 * On first run (or with `-Dkotlin.harfbuzz.regenerate.goldens=true`) the
 * captured image becomes the new golden. See [ScreenshotHarness] for the
 * comparison logic.
 */
class ShapedTextScreenshotTest {

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
    fun `latin roboto regular 24px`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_latin_roboto_regular.png") {
            Stage {
                ShapedText(
                    text = "Hello, kotlin-harfbuzz! 1234",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
            }
        }
    }

    @Test
    fun `latin roboto bold 28px`() {
        val font = loadFont("font/Roboto-Bold.ttf", 28f)
        rule.captureGolden("shaped_latin_roboto_bold.png") {
            Stage {
                ShapedText(
                    text = "The quick brown fox jumps over",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            }
        }
    }

    @Test
    fun `latin roboto italic 24px`() {
        val font = loadFont("font/Roboto-Italic.ttf", 24f)
        rule.captureGolden("shaped_latin_roboto_italic.png") {
            Stage {
                ShapedText(
                    text = "Italic typography 0123456789",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
            }
        }
    }

    @Test
    fun `arabic naskh regular bismillah with tashkeel`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_bismillah_tashkeel.png") {
            Stage {
                ShapedText(
                    text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `arabic naskh regular plain words`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_plain_words.png") {
            Stage {
                ShapedText(
                    text = "أنت السلام عليكم تجربة",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `arabic naskh bold greeting`() {
        val font = loadFont(FontPath.ARABIC_BOLD, 32f)
        rule.captureGolden("shaped_arabic_bold_greeting.png") {
            Stage {
                ShapedText(
                    text = "السَّلَامُ عَلَيْكُمْ وَرَحْمَةُ اللهِ",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `mixed latin arabic bidi line`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_mixed_bidi.png") {
            Stage {
                ShapedText(
                    text = "Hello مرحبا 123 محمد صلى الله عليه وسلم",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
            }
        }
    }

    @Test
    fun `arabic lam alef ligation`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 48f)
        rule.captureGolden("shaped_arabic_lam_alef.png") {
            Stage {
                ShapedText(
                    text = "لا الله إلا الله",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `latin liga feature on`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.captureGolden("shaped_latin_liga_on.png") {
            Stage {
                ShapedText(
                    text = "office afflict film final",
                    font = font,
                    color = Color.Black,
                    features = listOf(HbFeature("liga", value = 1u)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `latin liga feature off`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.captureGolden("shaped_latin_liga_off.png") {
            Stage {
                ShapedText(
                    text = "office afflict film final",
                    font = font,
                    color = Color.Black,
                    features = listOf(HbFeature("liga", value = 0u)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `latin bounds overlay`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_latin.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "Hello, kotlin-harfbuzz! gjpqy",
                    font = font,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `arabic bounds overlay tashkeel`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_arabic.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
                    font = font,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `aref ruqaa regular`() {
        val font = loadFont(FontPath.AREF_RUQAA_REGULAR, 36f)
        rule.captureGolden("shaped_aref_ruqaa_regular.png") {
            Stage {
                ShapedText(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `aref ruqaa bold`() {
        val font = loadFont(FontPath.AREF_RUQAA_BOLD, 36f)
        rule.captureGolden("shaped_aref_ruqaa_bold.png") {
            Stage {
                ShapedText(
                    text = "السلام عليكم",
                    font = font,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    /**
     * Aref Ruqaa Ink ships COLR v1 + CPAL + SVG color tables. The default
     * draw path now walks the paint tree via [ComposePaintSink], so the
     * Bismillah here renders with the font's designed gradient inks
     * instead of the caller's foreground color (the [color] parameter is
     * only used for layers that explicitly reference the foreground).
     */
    @Test
    fun `aref ruqaa ink colr v1 paints gradient inks`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shaped_aref_ruqaa_ink_v1_paint.png") {
            Stage {
                ShapedText(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    color = Color(0xFFB00020),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    /**
     * Force-foreground override: even on a COLR v1 font, callers can opt
     * out of the paint tree and render every glyph in their requested
     * color. Pins the override path so future paint-tree changes don't
     * accidentally drag it back into the colored render.
     */
    @Test
    fun `aref ruqaa ink colr v1 force foreground respects caller color`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shaped_aref_ruqaa_ink_v1_force_foreground.png") {
            Stage {
                ShapedText(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    color = Color(0xFFB00020),
                    forceForegroundColor = true,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    /**
     * Larger size pin: catches scale bugs in the COLR v1 painter that are
     * easy to miss at 40pt. Compare against the hb-view ground-truth render
     * (`hb-view --font-size=80 ArefRuqaaInk-Regular.ttf "..."`) - the
     * gradient ribbon should be clearly visible across each glyph stroke.
     */
    @Test
    fun `aref ruqaa ink colr v1 large size shows full gradient`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 80f)
        rule.captureGolden("shaped_aref_ruqaa_ink_v1_paint_80pt.png") {
            Stage {
                ShapedText(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    color = Color(0xFFB00020),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
        }
    }

    /**
     * Multi-layer coverage via Noto Color Emoji. Each emoji glyph in this
     * font ships a paint tree with multiple clipped gradient regions
     * (face fill + eye/mouth details). Pins that the painter composites
     * the layers correctly - a regression that breaks group/clip nesting
     * shows up here as missing details (e.g. blank eyes).
     */
    @Test
    fun `noto color emoji renders multilayer paint trees`() {
        val font = loadFont(FontPath.EMOJI, 64f)
        rule.captureGolden("shaped_noto_color_emoji.png") {
            Stage {
                ShapedText(
                    text = "😀🌍🎉🌈",
                    font = font,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    @Test
    fun `aref ruqaa bounds overlay`() {
        val font = loadFont(FontPath.AREF_RUQAA_REGULAR, 36f)
        rule.captureGolden("shaped_bounds_overlay_aref_ruqaa.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `latin custom color crimson`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 28f)
        rule.captureGolden("shaped_latin_color_crimson.png") {
            Stage {
                ShapedText(
                    text = "Color test 1234",
                    font = font,
                    color = Color(0xFFB00020),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            }
        }
    }

    @Test
    fun `arabic custom color teal`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_color_teal.png") {
            Stage {
                ShapedText(
                    text = "السلام عليكم",
                    font = font,
                    color = Color(0xFF00897B),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `mixed bidi tashkeel bounds overlay`() {
        // Mark-heavy Arabic word ("أَنْتَ" carries a hamza-on-alef + fatha +
        // sukun + fatha) sandwiched between Latin letters and Latin digits.
        // Exercises both the multi-run ink rect accumulation and the
        // y_offset sign for marks above the cap.
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_mixed_bidi_tashkeel.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "Hello أَنْتَ 1234",
                    font = font,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `mixed bidi bounds overlay`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_bounds_overlay_mixed_bidi.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "Hello مرحبا 123 محمد صلى الله عليه وسلم",
                    font = font,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `arabic plain words bounds overlay`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_arabic_plain.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "أنت السلام عليكم تجربة",
                    font = font,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `arabic explicit rtl direction`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_explicit_rtl.png") {
            Stage {
                ShapedText(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    color = Color.Black,
                    direction = HbDirection.RTL,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
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
}

/**
 * Test-local copy of the sample's bounds-overlay helper. Kept here (instead
 * of imported from `App.kt`) so the test gate is self-contained - changes to
 * the sample composable don't silently change golden output.
 */
@Composable
private fun ShapedTextWithBounds(
    text: String,
    font: HbFont,
    color: Color,
    direction: HbDirection = HbDirection.AUTO,
    inkColor: Color = Color(0xFFE91E63),
    logicalColor: Color = Color(0xFF00BCD4),
    baselineColor: Color = Color(0xFFFFB300),
) {
    val loadState by rememberMeasuredText(text, font, direction = direction)
    val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured

    // Auto-size the layout so both the logical line box (top at y=0, bottom
    // at y=lineHeight, baseline at y=ascent) and the ink box (which can
    // extend further above or below - e.g. Arabic tashkeel above the
    // ascender, or descenders past the descent) fit. Shift everything down
    // by the highest paint above y=0 so nothing clips at the top.
    val pad = 4f
    val inkTopOnScreen = if (measured == null || measured.ink.isEmpty) 0f
        else measured.baseline + measured.ink.top
    val inkBottomOnScreen = if (measured == null || measured.ink.isEmpty)
        measured?.lineHeight ?: 0f
        else measured.baseline + measured.ink.bottom
    val lineH = measured?.lineHeight ?: 0f
    val needTop = minOf(0f, inkTopOnScreen) - pad
    val needBottom = maxOf(lineH, inkBottomOnScreen) + pad
    val shift = -needTop
    val totalH = (needBottom - needTop).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val width = if (measured == null || measured.isEmpty) constraints.minWidth
                    else minOf(constraints.maxWidth, ceil(measured.advance).toInt().coerceAtLeast(0))
                val height = ceil(totalH).toInt().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(maxOf(width, constraints.minWidth), height) { placeable.place(0, 0) }
            }
            .drawBehind {
                if (measured == null || measured.isEmpty) return@drawBehind
                drawShapedText(measured, topLeft = Offset(0f, shift), color = color)

                drawRect(
                    color = logicalColor,
                    topLeft = Offset(0f, shift),
                    size = Size(measured.advance.coerceAtLeast(1f), measured.lineHeight),
                    style = Stroke(width = 1f),
                )
                if (!measured.ink.isEmpty) {
                    drawRect(
                        color = inkColor,
                        topLeft = Offset(measured.ink.left, shift + measured.baseline + measured.ink.top),
                        size = Size(
                            width = (measured.ink.right - measured.ink.left).coerceAtLeast(1f),
                            height = (measured.ink.bottom - measured.ink.top).coerceAtLeast(1f),
                        ),
                        style = Stroke(width = 1f),
                    )
                }
                drawLine(
                    color = baselineColor,
                    start = Offset(0f, shift + measured.baseline),
                    end = Offset(measured.advance.coerceAtLeast(1f), shift + measured.baseline),
                    strokeWidth = 1f,
                )
            },
    )
}
