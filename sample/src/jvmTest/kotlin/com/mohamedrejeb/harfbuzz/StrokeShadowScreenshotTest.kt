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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Stroke and shadow screenshot coverage. Pins the new style/shadow params
 * on `ShapedText`:
 *
 *  - Stroke on monochrome Latin/Arabic - the user's stroke applies to
 *    the silhouette outline as expected.
 *  - Stroke on color fonts (Aref Ruqaa Ink, Noto Color Emoji) - falls
 *    through to the silhouette outline so the stroke is meaningful
 *    instead of degenerating to a frame around the v1 cover rect or
 *    being silently ignored on raster SVG.
 *  - Shadow with offset + blur on monochrome text.
 *  - Shadow on color fonts uses the silhouette only (matches Compose's
 *    `BasicText` shadow convention).
 *  - Shadow with blurRadius = 0 - sharp offset copy, no blur.
 *  - Stroke + shadow combined.
 */
class StrokeShadowScreenshotTest {

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
    fun `stroke latin roboto 3px`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 36f)
        rule.captureGolden("stroke_latin_roboto_3px.png") {
            Stage {
                ShapedText(
                    text = "Stroked Hello",
                    font = font,
                    color = Color.Black,
                    style = Stroke(width = 3f),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `stroke latin roboto 1px thin`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 36f)
        rule.captureGolden("stroke_latin_roboto_1px.png") {
            Stage {
                ShapedText(
                    text = "Thin stroke",
                    font = font,
                    color = Color(0xFF1A237E),
                    style = Stroke(width = 1f),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    @Test
    fun `stroke arabic naskh 2px`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 36f)
        rule.captureGolden("stroke_arabic_naskh_2px.png") {
            Stage {
                ShapedText(
                    text = "نص عربي",
                    font = font,
                    color = Color.Black,
                    style = Stroke(width = 2f),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `stroke aref ruqaa ink falls back to outline`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("stroke_aref_ruqaa_ink_outline.png") {
            Stage {
                ShapedText(
                    text = "نص عربي تجريبي للاختبار",
                    font = font,
                    color = Color(0xFFB00020),
                    style = Stroke(width = 2f),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `stroke emoji falls back to outline`() {
        val font = loadFont(FontPath.EMOJI, 56f)
        rule.captureGolden("stroke_emoji_outline.png") {
            Stage {
                ShapedText(
                    text = "😀🌍🎉⭐",
                    font = font,
                    color = Color(0xFF263238),
                    style = Stroke(width = 2f),
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                )
            }
        }
    }

    @Test
    fun `shadow latin offset blur`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 36f)
        rule.captureGolden("shadow_latin_offset_blur.png") {
            Stage {
                ShapedText(
                    text = "Drop shadow",
                    font = font,
                    color = Color.Black,
                    shadow = Shadow(
                        color = Color(0x80000000),
                        offset = Offset(3f, 3f),
                        blurRadius = 4f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `shadow arabic naskh offset blur`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 36f)
        rule.captureGolden("shadow_arabic_naskh_offset_blur.png") {
            Stage {
                ShapedText(
                    text = "نص عربي",
                    font = font,
                    color = Color.Black,
                    shadow = Shadow(
                        color = Color(0x80000000),
                        offset = Offset(3f, 3f),
                        blurRadius = 4f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `shadow no blur sharp offset`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 36f)
        rule.captureGolden("shadow_latin_offset_no_blur.png") {
            Stage {
                ShapedText(
                    text = "Sharp shadow",
                    font = font,
                    color = Color.Black,
                    shadow = Shadow(
                        color = Color(0xFFB00020),
                        offset = Offset(2f, 2f),
                        blurRadius = 0f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
            }
        }
    }

    @Test
    fun `shadow large blur soft glow`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 40f)
        rule.captureGolden("shadow_latin_large_blur.png") {
            Stage {
                ShapedText(
                    text = "Soft glow",
                    font = font,
                    color = Color.Black,
                    shadow = Shadow(
                        color = Color(0x80000000),
                        offset = Offset(0f, 0f),
                        blurRadius = 12f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    @Test
    fun `shadow on aref ruqaa ink silhouette`() {
        val font = loadFont(FontPath.AREF_RUQAA_INK_REGULAR, 40f)
        rule.captureGolden("shadow_aref_ruqaa_ink_silhouette.png") {
            Stage {
                ShapedText(
                    text = "نص عربي",
                    font = font,
                    color = Color(0xFFB00020),
                    shadow = Shadow(
                        color = Color(0x66000000),
                        offset = Offset(2f, 2f),
                        blurRadius = 3f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }
    }

    @Test
    fun `shadow on emoji silhouette`() {
        val font = loadFont(FontPath.EMOJI, 56f)
        rule.captureGolden("shadow_emoji_silhouette.png") {
            Stage {
                ShapedText(
                    text = "😀🌍🎉",
                    font = font,
                    shadow = Shadow(
                        color = Color(0x80000000),
                        offset = Offset(3f, 3f),
                        blurRadius = 4f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    @Test
    fun `stroke with shadow combined`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 40f)
        rule.captureGolden("stroke_with_shadow_combined.png") {
            Stage {
                ShapedText(
                    text = "Outlined glow",
                    font = font,
                    color = Color(0xFF1A237E),
                    style = Stroke(width = 2f),
                    shadow = Shadow(
                        color = Color(0x80000000),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f,
                    ),
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }

    /**
     * Fill + stroke on the same paragraph by stacking two `ShapedText`
     * composables. Both call [rememberMeasuredText] with identical
     * inputs, so [MeasuredTextCache] serves the second one and shaping
     * runs once. Stroke draws first (behind), fill on top - the inner
     * half of the stroke is hidden by the fill so only the outer half
     * remains visible, giving a clean outlined-fill look.
     */
    @Test
    fun `fill plus stroke layered`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.captureGolden("fill_plus_stroke_layered.png") {
            Stage {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    ShapedText(
                        text = "Fill + Stroke",
                        font = font,
                        color = Color(0xFF263238),
                        style = Stroke(width = 3f),
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                    ShapedText(
                        text = "Fill + Stroke",
                        font = font,
                        color = Color(0xFFFFC107),
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                }
            }
        }
    }

    /**
     * Fill + stroke + shadow. The shadow rides on the stroke pass so it
     * sits under everything; stroke draws behind the fill so its outer
     * half remains visible as a crisp outline.
     */
    @Test
    fun `fill plus stroke plus shadow layered`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 48f)
        rule.captureGolden("fill_plus_stroke_plus_shadow_layered.png") {
            Stage {
                Box(modifier = Modifier.fillMaxWidth().height(72.dp)) {
                    ShapedText(
                        text = "Bold poster",
                        font = font,
                        color = Color(0xFF263238),
                        style = Stroke(width = 4f),
                        shadow = Shadow(
                            color = Color(0x99000000),
                            offset = Offset(3f, 3f),
                            blurRadius = 6f,
                        ),
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    )
                    ShapedText(
                        text = "Bold poster",
                        font = font,
                        color = Color(0xFFFFC107),
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    )
                }
            }
        }
    }

    /**
     * Layered fill + stroke + shadow on Arabic. Validates the stacking
     * order survives RTL shaping and mark positioning.
     */
    @Test
    fun `fill plus stroke plus shadow arabic`() {
        val font = loadFont(FontPath.ARABIC_REGULAR, 48f)
        rule.captureGolden("fill_plus_stroke_plus_shadow_arabic.png") {
            Stage {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                    ShapedText(
                        text = "نص عربي",
                        font = font,
                        color = Color(0xFF1B5E20),
                        style = Stroke(width = 4f),
                        shadow = Shadow(
                            color = Color(0x99000000),
                            offset = Offset(3f, 3f),
                            blurRadius = 6f,
                        ),
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                    )
                    ShapedText(
                        text = "نص عربي",
                        font = font,
                        color = Color(0xFFFFD54F),
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                    )
                }
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
