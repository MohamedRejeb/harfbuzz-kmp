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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Noto Color Emoji screenshot coverage. Each test pins a distinct
 * stress on the COLR v1 [com.mohamedrejeb.harfbuzz.compose.ComposePaintSink]:
 * different paint-tree shapes (single-region vs. deeply nested),
 * different gradient kinds (linear vs. radial), and different shaping
 * paths (single codepoint vs. ZWJ sequences vs. regional-indicator
 * pairs).
 *
 * Goldens are regenerated with `-Dkotlin.harfbuzz.regenerate.goldens=true`.
 * Diff failures here usually point at one of three regressions:
 *   - Coordinate scale drift in the painter (most glyphs go pale or
 *     shift position).
 *   - Clip-glyph cache miss (stroke or feature outlines disappear).
 *   - Composite group mishandling (overlapping layers blend wrong).
 */
class ColorEmojiScreenshotTest {

    @get:Rule
    val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    private fun loadEmoji(sizePx: Float): SizedFont = runBlocking {
        val bytes = readFontBytes(FontPath.EMOJI)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont()
        openFonts.add(font)
        openFonts.add(face)
        SizedFont(font, sizePx)
    }

    /** Smiling face row - radial face gradient + nested feature layers. */
    @Test
    fun `emoji faces render with features`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_faces.png") {
            Stage {
                ShapedText(
                    text = "😀😍🤔😢🤣",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /** Hearts - simple shape, different palette colors per glyph. */
    @Test
    fun `emoji hearts render with palette colors`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_hearts.png") {
            Stage {
                ShapedText(
                    text = "❤️💛💚💙💜🖤🤍🤎",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /** Animals - typically deep multi-layer trees with body fur + facial detail. */
    @Test
    fun `emoji animals render with multi layer detail`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_animals.png") {
            Stage {
                ShapedText(
                    text = "🐱🐶🦁🐼🦊",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /** Food - varied color saturations exercise gradient stop fidelity. */
    @Test
    fun `emoji food renders with varied gradients`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_food.png") {
            Stage {
                ShapedText(
                    text = "🍕🍔🍎🍌🍇🍓",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /**
     * Nature and weather - mixes of radial gradients (sun/moon) and
     * complex multi-region fills (clouds, snowflake). Each glyph stresses
     * a different paint-tree shape: radial fills, layered overlays, and
     * fine line work.
     */
    @Test
    fun `emoji nature renders with radial gradients and multi region`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_nature.png") {
            Stage {
                ShapedText(
                    text = "☀️🌙⭐🔥❄️☁️",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /**
     * Activity / object emojis - high paint-op density per glyph.
     * Party popper is a known multi-color confetti pattern; gem and
     * trophy use metallic gradients.
     */
    @Test
    fun `emoji objects render with high op density`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_objects.png") {
            Stage {
                ShapedText(
                    text = "🎉🎊🎁💎🏆⚽",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /**
     * Hand emojis - default skin tone tests palette resolution for
     * glyphs that reference foreground via `is_foreground=true` stops
     * (some emoji fonts do; Noto resolves all stops to concrete colors).
     */
    @Test
    fun `emoji hands render`() {
        val (font, sizePx) = loadEmoji(64f)
        rule.captureGolden("emoji_hands.png") {
            Stage {
                ShapedText(
                    text = "👋👍👎✋🤝",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                )
            }
        }
    }

    /**
     * Mixed at large size - shows that scaling holds at 96pt without
     * any proportionality regressions in the paint tree replay.
     */
    @Test
    fun `emoji mixed large size`() {
        val (font, sizePx) = loadEmoji(96f)
        rule.captureGolden("emoji_mixed_large.png") {
            Stage {
                ShapedText(
                    text = "🎨🚀🌍",
                    font = font,
                    sizePx = sizePx,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
        }
    }

    /**
     * Test-local stage. Kept private to this file (rather than shared
     * with [ShapedTextScreenshotTest]) so changes to the other test's
     * stage can't silently move our goldens.
     */
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
