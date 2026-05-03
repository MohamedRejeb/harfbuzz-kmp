package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedTextBox
import com.mohamedrejeb.harfbuzz.compose.ShapedTextStroke
import com.mohamedrejeb.harfbuzz.compose.ShapedTextStyle
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot coverage for [ShapedTextBox]. Each test exercises one
 * cell of the (background, stroke, fill, shadow) matrix so a
 * regression in one layer does not silently take the others down
 * with it.
 */
class ShapedTextBoxScreenshotTest {

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
    fun `plain fill no extras`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 32f)
        rule.captureGolden("box_plain_fill.png") {
            Stage {
                ShapedTextBox(
                    text = "Body copy",
                    style = ShapedTextStyle(font = font, color = Color(0xFF263238)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `fill plus shadow`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 36f)
        rule.captureGolden("box_fill_shadow.png") {
            Stage {
                ShapedTextBox(
                    text = "With shadow",
                    style = ShapedTextStyle(
                        font = font,
                        color = Color(0xFF1A237E),
                        shadow = Shadow(
                            color = Color(0x66000000),
                            offset = Offset(2f, 2f),
                            blurRadius = 4f,
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `fill plus solid stroke`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 44f)
        rule.captureGolden("box_fill_stroke_solid.png") {
            Stage {
                ShapedTextBox(
                    text = "Outlined",
                    style = ShapedTextStyle(font = font, color = Color(0xFFFFC107)),
                    stroke = ShapedTextStroke.solid(width = 3.dp, color = Color(0xFF263238)),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }
    }

    @Test
    fun `brush fill plus solid stroke`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 44f)
        rule.captureGolden("box_brush_fill_stroke.png") {
            Stage {
                ShapedTextBox(
                    text = "Gradient",
                    style = ShapedTextStyle(
                        font = font,
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFFEF5350), Color(0xFF26A69A)),
                        ),
                    ),
                    stroke = ShapedTextStroke.solid(width = 2.dp, color = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }
    }

    @Test
    fun `background plain fill`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 28f)
        rule.captureGolden("box_background_fill.png") {
            Stage {
                ShapedTextBox(
                    text = "Filled bar",
                    style = ShapedTextStyle(
                        font = font,
                        color = Color.White,
                        alignment = com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment.Center,
                    ),
                    background = SolidColor(Color(0xFF005F73)),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `rounded background fill stroke`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 28f)
        rule.captureGolden("box_rounded_bg_fill_stroke.png") {
            Stage {
                ShapedTextBox(
                    text = "Pill style",
                    style = ShapedTextStyle(
                        font = font,
                        color = Color.White,
                        alignment = com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment.Center,
                    ),
                    background = SolidColor(Color(0xFF14213D)),
                    backgroundCornerRadius = CornerRadius(24f, 24f),
                    stroke = ShapedTextStroke.solid(width = 1.5.dp, color = Color(0xFFFFC107)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `all layers combined`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 40f)
        rule.captureGolden("box_all_layers.png") {
            Stage {
                ShapedTextBox(
                    text = "Heading",
                    style = ShapedTextStyle(
                        font = font,
                        color = Color(0xFFFFE082),
                        alignment = com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment.Center,
                        shadow = Shadow(
                            color = Color(0xAA000000),
                            offset = Offset(2f, 3f),
                            blurRadius = 5f,
                        ),
                    ),
                    background = SolidColor(Color(0xFF1B1B1F)),
                    backgroundCornerRadius = CornerRadius(12f, 12f),
                    stroke = ShapedTextStroke.solid(width = 2.dp, color = Color(0xFF005F73)),
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        }
    }

    @Test
    fun `wraps long text and self-sizes height`() {
        // No explicit height: the box self-sizes vertically to the
        // wrapped paragraph. fillMaxWidth gives a bounded slot so the
        // line breaker kicks in and the box reports the multi-line
        // height back to its parent.
        val font = loadFont(FontPath.LATIN_REGULAR, 24f)
        rule.captureGolden("box_wraps_long_text.png") {
            Stage {
                ShapedTextBox(
                    text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.",
                    style = ShapedTextStyle(font = font, color = Color.White),
                    background = SolidColor(Color(0xFF1B5E20)),
                    backgroundCornerRadius = CornerRadius(8f, 8f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Test
    fun `content-sized box no fill modifier`() {
        // No fillMaxWidth: the box should hug the text's intrinsic
        // width, so the background ends right after the last glyph
        // (badge-style) instead of stretching to the column width.
        val font = loadFont(FontPath.LATIN_REGULAR, 28f)
        rule.captureGolden("box_content_sized.png") {
            Stage {
                ShapedTextBox(
                    text = "Compact",
                    style = ShapedTextStyle(font = font, color = Color.White),
                    background = SolidColor(Color(0xFF6D28D9)),
                    backgroundCornerRadius = CornerRadius(6f, 6f),
                )
            }
        }
    }

    @Test
    fun `arabic fill stroke shadow`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 40f)
        rule.captureGolden("box_arabic_fill_stroke_shadow.png") {
            Stage {
                ShapedTextBox(
                    text = "نص عربي",
                    style = ShapedTextStyle(
                        font = font,
                        color = Color(0xFFFFD54F),
                        direction = com.mohamedrejeb.harfbuzz.core.HbDirection.RTL,
                        shadow = Shadow(
                            color = Color(0x99000000),
                            offset = Offset(3f, 3f),
                            blurRadius = 5f,
                        ),
                    ),
                    stroke = ShapedTextStroke.solid(width = 3.dp, color = Color(0xFF1B5E20)),
                    modifier = Modifier.fillMaxWidth().height(80.dp),
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
