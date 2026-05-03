package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.paragraph.ShapedParagraphText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Multi-line paragraph rendering coverage. Each test runs `ShapedParagraphText`
 * under a width constraint that forces wrapping, then captures the result for
 * golden comparison.
 *
 * Coverage:
 *  - Latin Roboto Regular - greedy wrap into 2-3 lines.
 *  - Latin Roboto Bold - heavier weight, same wrap behaviour.
 *  - Arabic Naskh - RTL paragraph wraps, default Start alignment puts each
 *    line against the right edge.
 *  - Center alignment - LTR, every line's xOffset centres it.
 *  - Right alignment - LTR, every line pushed to the right edge.
 *  - Custom line spacing - extra gap between lines.
 *  - Hard line breaks - explicit '\n' inserts a forced new line.
 */
class ShapedParagraphScreenshotTest {

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
    fun `latin paragraph wraps to multiple lines`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_latin_wrap.png") {
            Stage {
                ShapedParagraphText(
                    text = "The quick brown fox jumps over the lazy dog and runs away.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier
                        .width(280.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph centered alignment`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_latin_center.png") {
            Stage {
                ShapedParagraphText(
                    text = "Centered multi line typography sample text.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Center,
                    modifier = Modifier
                        .width(360.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph right alignment`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_latin_right.png") {
            Stage {
                ShapedParagraphText(
                    text = "Right aligned multi line typography sample text.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Right,
                    modifier = Modifier
                        .width(360.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph extra line spacing`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_latin_line_spacing.png") {
            Stage {
                ShapedParagraphText(
                    text = "Line one. Line two. Line three of the same paragraph.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    lineSpacing = 12f,
                    modifier = Modifier
                        .width(280.dp)
                        .height(180.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `latin paragraph hard line breaks`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_latin_hard_breaks.png") {
            Stage {
                ShapedParagraphText(
                    text = "First line\nSecond line\nThird line",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    modifier = Modifier
                        .width(360.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic paragraph wraps with rtl base direction`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_arabic_wrap_rtl.png") {
            Stage {
                ShapedParagraphText(
                    text = "هذا نص عربي تجريبي طويل لاختبار التفاف النصوص العربية على عدة أسطر متتالية.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    direction = HbDirection.RTL,
                    modifier = Modifier
                        .width(360.dp)
                        .height(140.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic paragraph centered`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_arabic_center.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. كلمات لعرض الخطوط العربية.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Center,
                    direction = HbDirection.RTL,
                    modifier = Modifier
                        .width(420.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic paragraph absolute left alignment`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_arabic_left.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. كلمات لعرض الخطوط العربية.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Left,
                    direction = HbDirection.RTL,
                    modifier = Modifier
                        .width(420.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic paragraph absolute right alignment`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_arabic_right.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. كلمات لعرض الخطوط العربية.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Right,
                    direction = HbDirection.RTL,
                    modifier = Modifier
                        .width(420.dp)
                        .height(120.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    // ── Phase 2 ── Justification ─────────────────────────────────────────

    @Test
    fun `latin justify with word spacing strategy`() {
        val (font, sizePx) = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("paragraph_latin_justify_wordspacing.png") {
            Stage {
                ShapedParagraphText(
                    text = "The quick brown fox jumps over the lazy dog and runs away today.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Justify,
                    justification = JustificationStrategy.WordSpacing,
                    modifier = Modifier
                        .width(360.dp)
                        .height(140.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic justify with kashida via mixed strategy`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_arabic_justify_kashida.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. كلمات لاختبار التفاف الأسطر العربية.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Justify,
                    direction = HbDirection.RTL,
                    justification = JustificationStrategy.Mixed,
                    modifier = Modifier
                        .width(360.dp)
                        .height(160.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `arabic justify with word spacing strategy`() {
        // Compose-style: thin-space distribution even for Arabic content.
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 28f)
        rule.captureGolden("paragraph_arabic_justify_wordspacing.png") {
            Stage {
                ShapedParagraphText(
                    text = "نص عربي تجريبي للاختبار. كلمات لاختبار التفاف الأسطر العربية.",
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Justify,
                    direction = HbDirection.RTL,
                    justification = JustificationStrategy.WordSpacing,
                    modifier = Modifier
                        .width(360.dp)
                        .height(160.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `mixed arabic and latin justify with mixed strategy`() {
        // Each line picks Kashida if it has Arabic content, thin-space
        // otherwise - exercises the per-line script dispatch.
        val arabic = loadFont(FontPath.ARABIC_REGULAR, 24f)
        val latin = loadFont(FontPath.LATIN_REGULAR, 24f)
        val stack = HbFontStack(latin.font, listOf(arabic.font))
        rule.captureGolden("paragraph_mixed_justify_mixed.png") {
            Stage {
                ShapedParagraphText(
                    text = "Welcome to the typography studio. مرحبا بكم في الاستوديو. " +
                        "We support Arabic and Latin scripts together in one paragraph.",
                    fontStack = stack,
                    sizePx = latin.sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Justify,
                    justification = JustificationStrategy.Mixed,
                    modifier = Modifier
                        .width(420.dp)
                        .height(220.dp)
                        .border(1.dp, BoundsColor),
                )
            }
        }
    }

    @Test
    fun `mixed arabic and latin justify with word spacing strategy`() {
        val arabic = loadFont(FontPath.ARABIC_REGULAR, 24f)
        val latin = loadFont(FontPath.LATIN_REGULAR, 24f)
        val stack = HbFontStack(latin.font, listOf(arabic.font))
        rule.captureGolden("paragraph_mixed_justify_wordspacing.png") {
            Stage {
                ShapedParagraphText(
                    text = "Welcome to the typography studio. مرحبا بكم في الاستوديو. " +
                        "We support Arabic and Latin scripts together in one paragraph.",
                    fontStack = stack,
                    sizePx = latin.sizePx,
                    color = Color.Black,
                    alignment = ParagraphAlignment.Justify,
                    justification = JustificationStrategy.WordSpacing,
                    modifier = Modifier
                        .width(420.dp)
                        .height(220.dp)
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
        // Light gray border drawn around each paragraph's max-width box so
        // the constraint is visible in the captured golden. Dark enough to
        // see on white, light enough not to compete with the rendered text.
        val BoundsColor = Color(0xFFB0B0B0)
    }
}
