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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.ShapedTextOverflow
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
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
 *  - Arabic with tashkeel - mark positioning across base letters so
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

    private fun loadFont(path: String, sizePx: Float): SizedFont = runBlocking {
        val bytes = readFontBytes(path)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont()
        openFonts.add(font)
        openFonts.add(face)
        SizedFont(font, sizePx)
    }

    @Test
    fun `latin roboto regular 24px`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_latin_roboto_regular.png") {
            Stage {
                ShapedText(
                    text = "Hello, kotlin-harfbuzz! 1234",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
            }
        }
    }

    @Test
    fun `latin roboto bold 28px`() {
        val (font, sizePx) = loadFont("font/Roboto-Bold.ttf", 28f)
        rule.captureGolden("shaped_latin_roboto_bold.png") {
            Stage {
                ShapedText(
                    text = "The quick brown fox jumps over",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            }
        }
    }

    @Test
    fun `latin roboto italic 24px`() {
        val (font, sizePx) = loadFont("font/Roboto-Italic.ttf", 24f)
        rule.captureGolden("shaped_latin_roboto_italic.png") {
            Stage {
                ShapedText(
                    text = "Italic typography 0123456789",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
            }
        }
    }

    @Test
    fun `arabic naskh regular tashkeel sample`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_tashkeel_sample.png") {
            Stage {
                ShapedText(
                    text = "نَصٌّ عَرَبِيٌّ مُشَكَّلٌ لِلْاِخْتِبَارِ",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `arabic naskh regular plain words`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_plain_words.png") {
            Stage {
                ShapedText(
                    text = "أهلا كلمات عربية للتجربة",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `arabic naskh bold tashkeel greeting`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_BOLD, 32f)
        rule.captureGolden("shaped_arabic_bold_tashkeel_greeting.png") {
            Stage {
                ShapedText(
                    text = "كَلِمَاتٌ عَرَبِيَّةٌ مُشَكَّلَةٌ لِلْاِخْتِبَارِ",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `mixed latin arabic bidi line`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_mixed_bidi.png") {
            Stage {
                ShapedText(
                    text = "Hello مرحبا 123 لاختبار النصوص العربية",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
            }
        }
    }

    @Test
    fun `arabic lam alef ligation`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 48f)
        rule.captureGolden("shaped_arabic_lam_alef.png") {
            Stage {
                ShapedText(
                    text = "لا كلام بلا فائدة",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `latin liga feature on`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.captureGolden("shaped_latin_liga_on.png") {
            Stage {
                ShapedText(
                    text = "office afflict film final",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    features = listOf(HbFeature("liga", value = 1u)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `latin liga feature off`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.captureGolden("shaped_latin_liga_off.png") {
            Stage {
                ShapedText(
                    text = "office afflict film final",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    features = listOf(HbFeature("liga", value = 0u)),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `latin bounds overlay`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_latin.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "Hello, kotlin-harfbuzz! gjpqy",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `arabic bounds overlay tashkeel`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_arabic.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "نَصٌّ عَرَبِيٌّ مُشَكَّلٌ لِلْاِخْتِبَارِ",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `aref ruqaa regular`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_REGULAR, 36f)
        rule.captureGolden("shaped_aref_ruqaa_regular.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `aref ruqaa bold`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_BOLD, 36f)
        rule.captureGolden("shaped_aref_ruqaa_bold.png") {
            Stage {
                ShapedText(
                    text = "نص عربي",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    /**
     * Aref Ruqaa Ink ships COLR v1 + CPAL + SVG color tables. The default
     * draw path now walks the paint tree via [ComposePaintSink], so the
     * sample text renders with the font's designed gradient inks instead
     * of the caller's foreground color (the [color] parameter is only
     * used for layers that explicitly reference the foreground).
     */
    @Test
    fun `aref ruqaa ink colr v1 paints gradient inks`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shaped_aref_ruqaa_ink_v1_paint.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
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
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shaped_aref_ruqaa_ink_v1_force_foreground.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
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
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 80f)
        rule.captureGolden("shaped_aref_ruqaa_ink_v1_paint_80pt.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
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
        val (font, sizePx) = loadFont(FontPath.EMOJI, 64f)
        rule.captureGolden("shaped_noto_color_emoji.png") {
            Stage {
                ShapedText(
                    text = "😀🌍🎉⭐",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    @Test
    fun `aref ruqaa bounds overlay`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_REGULAR, 36f)
        rule.captureGolden("shaped_bounds_overlay_aref_ruqaa.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `latin custom color crimson`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 28f)
        rule.captureGolden("shaped_latin_color_crimson.png") {
            Stage {
                ShapedText(
                    text = "Color test 1234",
                    font = font,
                    sizePx = sizePx,
                    color = Color(0xFFB00020),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            }
        }
    }

    @Test
    fun `arabic custom color teal`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_color_teal.png") {
            Stage {
                ShapedText(
                    text = "نص عربي",
                    font = font,
                    sizePx = sizePx,
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
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_mixed_bidi_tashkeel.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "Hello أَنْتَ 1234",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `mixed bidi bounds overlay`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_bounds_overlay_mixed_bidi.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "Hello مرحبا 123 لاختبار النصوص العربية",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `arabic plain words bounds overlay`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_bounds_overlay_arabic_plain.png") {
            Stage {
                ShapedTextWithBounds(
                    text = "أهلا كلمات عربية للتجربة",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `arabic explicit rtl direction`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 32f)
        rule.captureGolden("shaped_arabic_explicit_rtl.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    direction = HbDirection.RTL,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    // ===== Single-line alignment =====
    //
    // Each alignment test pins the [ShapedText] inside a fixed-width slot
    // (cyan stroke) so the alignment offset is visually obvious. Latin and
    // Arabic share the same slot width, so the LTR / RTL semantics of
    // Start / End read off side-by-side.

    @Test
    fun `latin align start in slot`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_align_latin_start.png") {
            Stage {
                ShapedTextInSlot(
                    text = "Hello world",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Start,
                )
            }
        }
    }

    @Test
    fun `latin align end in slot`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_align_latin_end.png") {
            Stage {
                ShapedTextInSlot(
                    text = "Hello world",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.End,
                )
            }
        }
    }

    @Test
    fun `latin align left in slot`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_align_latin_left.png") {
            Stage {
                ShapedTextInSlot(
                    text = "Hello world",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Left,
                )
            }
        }
    }

    @Test
    fun `latin align right in slot`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_align_latin_right.png") {
            Stage {
                ShapedTextInSlot(
                    text = "Hello world",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Right,
                )
            }
        }
    }

    @Test
    fun `latin align center in slot`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_align_latin_center.png") {
            Stage {
                ShapedTextInSlot(
                    text = "Hello world",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Center,
                )
            }
        }
    }

    @Test
    fun `arabic align end in slot`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_align_arabic_end.png") {
            Stage {
                ShapedTextInSlot(
                    text = "مرحبا بالعالم",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.End,
                )
            }
        }
    }

    @Test
    fun `arabic align left in slot`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_align_arabic_left.png") {
            Stage {
                ShapedTextInSlot(
                    text = "مرحبا بالعالم",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Left,
                )
            }
        }
    }

    @Test
    fun `arabic align right in slot`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_align_arabic_right.png") {
            Stage {
                ShapedTextInSlot(
                    text = "مرحبا بالعالم",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Right,
                )
            }
        }
    }

    @Test
    fun `arabic align center in slot`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_align_arabic_center.png") {
            Stage {
                ShapedTextInSlot(
                    text = "مرحبا بالعالم",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 400.dp,
                    alignment = ParagraphAlignment.Center,
                )
            }
        }
    }

    // ===== Single-line justify =====
    //
    // Justify widens the line by re-shaping with extra glyphs (thin space
    // for Latin, Kashida for Arabic when the strategy is Mixed). The slot
    // outline shows the text inflating to the slot edges.

    @Test
    fun `latin justify wordspacing in slot`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_justify_latin_wordspacing.png") {
            Stage {
                ShapedTextInSlot(
                    text = "Hello world from kotlin",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 480.dp,
                    alignment = ParagraphAlignment.Justify,
                    justification = JustificationStrategy.WordSpacing,
                )
            }
        }
    }

    @Test
    fun `arabic justify kashida in slot`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_justify_arabic_kashida.png") {
            Stage {
                ShapedTextInSlot(
                    text = "مرحبا بك في تجربة النص",
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 480.dp,
                    alignment = ParagraphAlignment.Justify,
                    justification = JustificationStrategy.Mixed,
                )
            }
        }
    }

    // ===== Single-line overflow =====
    //
    // The slot is intentionally narrower than the shaped advance so the
    // [ShapedTextOverflow] modes are visible:
    //  - [ShapedTextOverflow.Clip] trims painted glyphs at the slot edge.
    //  - [ShapedTextOverflow.Visible] lets glyphs paint past the edge.
    //  - [ShapedTextOverflow.Compress] scales per-glyph spacing so the
    //    line fits the slot exactly (glyphs may overlap).
    //
    // Latin tests use a Lorem Ipsum filler. Arabic tests use a generic
    // sample so clipped output never lands mid-word in copyrighted /
    // sacred text.

    @Test
    fun `latin overflow clip start`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_overflow_latin_clip_start.png") {
            Stage {
                ShapedTextInSlot(
                    text = LATIN_LOREM,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 200.dp,
                    alignment = ParagraphAlignment.Start,
                    overflow = ShapedTextOverflow.Clip,
                )
            }
        }
    }

    @Test
    fun `latin overflow clip end`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_overflow_latin_clip_end.png") {
            Stage {
                ShapedTextInSlot(
                    text = LATIN_LOREM,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 200.dp,
                    alignment = ParagraphAlignment.End,
                    overflow = ShapedTextOverflow.Clip,
                )
            }
        }
    }

    @Test
    fun `latin overflow clip center`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_overflow_latin_clip_center.png") {
            Stage {
                ShapedTextInSlot(
                    text = LATIN_LOREM,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 200.dp,
                    alignment = ParagraphAlignment.Center,
                    overflow = ShapedTextOverflow.Clip,
                )
            }
        }
    }

    @Test
    fun `latin overflow visible start`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_overflow_latin_visible_start.png") {
            Stage {
                ShapedTextInSlot(
                    text = LATIN_LOREM,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 200.dp,
                    alignment = ParagraphAlignment.Start,
                    overflow = ShapedTextOverflow.Visible,
                )
            }
        }
    }

    @Test
    fun `latin overflow compress start`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_overflow_latin_compress_start.png") {
            Stage {
                ShapedTextInSlot(
                    text = LATIN_LOREM,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 280.dp,
                    alignment = ParagraphAlignment.Start,
                    overflow = ShapedTextOverflow.Compress,
                )
            }
        }
    }

    @Test
    fun `arabic overflow clip start`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_overflow_arabic_clip_start.png") {
            Stage {
                ShapedTextInSlot(
                    text = ARABIC_DUMMY,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 240.dp,
                    alignment = ParagraphAlignment.Start,
                    overflow = ShapedTextOverflow.Clip,
                )
            }
        }
    }

    @Test
    fun `arabic overflow clip end`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_overflow_arabic_clip_end.png") {
            Stage {
                ShapedTextInSlot(
                    text = ARABIC_DUMMY,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 240.dp,
                    alignment = ParagraphAlignment.End,
                    overflow = ShapedTextOverflow.Clip,
                )
            }
        }
    }

    @Test
    fun `arabic overflow visible end`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_overflow_arabic_visible_end.png") {
            Stage {
                ShapedTextInSlot(
                    text = ARABIC_DUMMY,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 240.dp,
                    alignment = ParagraphAlignment.End,
                    overflow = ShapedTextOverflow.Visible,
                )
            }
        }
    }

    @Test
    fun `arabic overflow compress start`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("shaped_overflow_arabic_compress_start.png") {
            Stage {
                ShapedTextInSlot(
                    text = ARABIC_DUMMY,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 320.dp,
                    alignment = ParagraphAlignment.Start,
                    overflow = ShapedTextOverflow.Compress,
                )
            }
        }
    }

    // ===== Brush fill / stroke =====
    //
    // [Brush]-based variants of [ShapedText]: monochrome glyph silhouettes
    // (and COLR v0 foreground-color layers) are accumulated into a single
    // path so a [Brush.linearGradient] / [Brush.horizontalGradient] spans
    // the line instead of repeating per glyph. Designed-color glyphs - SVG
    // bitmaps, COLR v1 paint trees, and COLR v0 palette layers - keep
    // their authored colors so emoji and Aref Ruqaa Ink continue to paint
    // with their own gradients.

    @Test
    fun `latin brush horizontal gradient fill`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("shaped_brush_latin_horizontal_gradient.png") {
            Stage {
                ShapedText(
                    text = "Hello, gradient world!",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFFE91E63),
                            Color(0xFF673AB7),
                            Color(0xFF2196F3),
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `arabic brush horizontal gradient fill`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 36f)
        rule.captureGolden("shaped_brush_arabic_horizontal_gradient.png") {
            Stage {
                ShapedText(
                    text = "نص متدرج الألوان",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFF5722),
                            Color(0xFFFFC107),
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `latin brush vertical gradient stroke`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 56f)
        rule.captureGolden("shaped_brush_latin_stroke_gradient.png") {
            Stage {
                ShapedText(
                    text = "Stroke",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xFF1976D2),
                            Color(0xFF388E3C),
                        ),
                    ),
                    style = Stroke(width = 2f),
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        }
    }

    @Test
    fun `latin brush with shadow`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("shaped_brush_latin_with_shadow.png") {
            Stage {
                ShapedText(
                    text = "Gradient with shadow",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFFE91E63), Color(0xFF2196F3)),
                    ),
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.3f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    /**
     * Brush spans the line's combined silhouette path. With Compress
     * the line is squeezed to the slot, so the gradient maps to the
     * slot width too - pins that the brush draw runs after the
     * spacing scale so the path bounds reflect compressed positions.
     */
    @Test
    fun `latin brush in slot with compress`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("shaped_brush_latin_compress.png") {
            Stage {
                ShapedTextInSlot(
                    text = LATIN_LOREM,
                    font = font,
                    sizePx = sizePx,
                    slotWidth = 280.dp,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFFE91E63), Color(0xFF2196F3)),
                    ),
                    overflow = ShapedTextOverflow.Compress,
                )
            }
        }
    }

    /**
     * COLR v1 designed colors must survive a brush argument: Aref Ruqaa
     * Ink should still paint with its own gradient inks because the
     * paint tree owns the foreground. Pins that the brush-aware draw
     * path does not accidentally tint COLR v1 glyphs.
     */
    @Test
    fun `aref ruqaa ink brush preserves designed colors`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shaped_brush_aref_ruqaa_ink_unchanged.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF00BCD4), Color(0xFFE91E63)),
                    ),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    /**
     * Color emoji + brush: each glyph still walks its COLR v1 paint
     * tree, so the brush has no effect. Pins that the plumbing does
     * not accidentally tint emoji silhouettes when a brush is
     * supplied.
     */
    @Test
    fun `noto color emoji brush preserves designed colors`() {
        val (font, sizePx) = loadFont(FontPath.EMOJI, 64f)
        rule.captureGolden("shaped_brush_emoji_unchanged.png") {
            Stage {
                ShapedText(
                    text = "😀🌍🎉⭐",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.horizontalGradient(
                        listOf(Color.Cyan, Color.Magenta),
                    ),
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /**
     * Brush + forceForegroundColor opts a COLR v1 font into the
     * silhouette path, so Aref Ruqaa Ink's glyphs paint with the
     * gradient instead of the font's designed inks. (Noto Color Emoji
     * cannot demonstrate this: its glyphs ship empty base outlines, so
     * forceForegroundColor renders nothing.)
     */
    @Test
    fun `aref ruqaa ink brush force foreground tints silhouettes`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shaped_brush_aref_ruqaa_ink_force_foreground.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    sizePx = sizePx,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF00BCD4), Color(0xFFE91E63)),
                    ),
                    forceForegroundColor = true,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    /**
     * Renders [text] inside a fixed [slotWidth] x [slotHeight] box stroked in
     * cyan so the alignment / justify / overflow effect is visible against
     * the slot bounds. Reuses the same cyan as `ShapedTextWithBounds`'s
     * logical-box overlay for consistency across the suite. The inner
     * [ShapedText] is NOT wrapped in `fillMaxWidth()`: when alignment is
     * [ParagraphAlignment.Start], the layout block reports the line's
     * natural advance, so the text pins to the slot's leading edge instead
     * of being centred by Compose's automatic constraint-clamping.
     */
    @Composable
    private fun ShapedTextInSlot(
        text: String,
        font: HbFont,
        sizePx: Float,
        slotWidth: Dp,
        slotHeight: Dp = 48.dp,
        alignment: ParagraphAlignment = ParagraphAlignment.Start,
        justification: JustificationStrategy = JustificationStrategy.None,
        overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
        direction: HbDirection = HbDirection.AUTO,
        color: Color = Color.Black,
        brush: Brush? = null,
        slotColor: Color = Color(0xFF00BCD4),
    ) {
        Box(
            modifier = Modifier
                .width(slotWidth)
                .height(slotHeight)
                .drawBehind {
                    drawRect(color = slotColor, style = Stroke(width = 1f))
                },
        ) {
            ShapedText(
                text = text,
                font = font,
                sizePx = sizePx,
                color = color,
                brush = brush,
                direction = direction,
                alignment = alignment,
                justification = justification,
                overflow = overflow,
            )
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
        const val LATIN_LOREM =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit"
        const val ARABIC_DUMMY =
            "نص تجريبي للاختبار يستخدم لعرض المظهر العام للنص العربي"
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
    sizePx: Float,
    color: Color,
    direction: HbDirection = HbDirection.AUTO,
    inkColor: Color = Color(0xFFE91E63),
    logicalColor: Color = Color(0xFF00BCD4),
    baselineColor: Color = Color(0xFFFFB300),
) {
    val loadState by rememberMeasuredText(text, font, sizePx = sizePx, direction = direction)
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
