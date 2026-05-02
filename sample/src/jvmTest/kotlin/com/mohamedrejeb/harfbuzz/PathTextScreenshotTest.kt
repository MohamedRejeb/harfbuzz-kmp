package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.PathTextBoundsLoad
import com.mohamedrejeb.harfbuzz.compose.TextOnPath
import com.mohamedrejeb.harfbuzz.compose.TextOnPathAlignment
import com.mohamedrejeb.harfbuzz.compose.TextOnPathOverflow
import com.mohamedrejeb.harfbuzz.compose.drawTextAlongPath
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.compose.rememberPathTextBounds
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Path-text screenshot coverage (Task 12).
 *
 * Exercises the public path-text rendering surface:
 *   - Straight horizontal LTR baseline.
 *   - Half-circle arc via [drawTextAlongPath] from a `Modifier.drawBehind`.
 *   - The three [TextOnPathOverflow] modes: Clip, Visible, Compress.
 *
 * Goldens auto-bootstrap on first run and are then committed alongside
 * the test. To regenerate every golden after an intentional rendering
 * change:
 *
 *   ./gradlew :sample:jvmTest -Dkotlin.harfbuzz.regenerate.goldens=true
 */
class PathTextScreenshotTest {

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
    fun `straight horizontal line LTR`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 28f)
        rule.captureGolden("path_text_straight_ltr.png") {
            Stage(width = 600, height = 120) {
                TextOnPath(
                    text = "Hello, kotlin-harfbuzz",
                    font = font,
                    path = Path().apply {
                        moveTo(20f, 60f)
                        lineTo(580f, 60f)
                    },
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `half circle arc latin`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("path_text_half_circle.png") {
            Stage(width = 320, height = 220) {
                val measuredState = rememberMeasuredText("circular text demo", font)
                val measured = (measuredState.value as? MeasuredTextLoad.Ready)?.measured
                Box(
                    modifier = Modifier.fillMaxSize().drawBehind {
                        val m = measured ?: return@drawBehind
                        drawTextAlongPath(
                            measured = m,
                            path = Path().apply {
                                addArc(Rect(40f, 40f, 280f, 280f), 180f, 180f)
                            },
                            color = Color.Black,
                            alignment = TextOnPathAlignment.Center,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `overflow Clip drops trailing glyphs`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 20f)
        rule.captureGolden("path_text_overflow_clip.png") {
            Stage(width = 360, height = 80) {
                TextOnPath(
                    text = "this text is much longer than the visible path will allow",
                    font = font,
                    path = Path().apply {
                        moveTo(20f, 40f)
                        lineTo(180f, 40f)
                    },
                    overflow = TextOnPathOverflow.Clip,
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `overflow Visible extrapolates past path end`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 20f)
        rule.captureGolden("path_text_overflow_visible.png") {
            Stage(width = 360, height = 80) {
                TextOnPath(
                    text = "this text overflows but stays visible",
                    font = font,
                    path = Path().apply {
                        moveTo(20f, 40f)
                        lineTo(180f, 40f)
                    },
                    overflow = TextOnPathOverflow.Visible,
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `overflow Compress shrinks spacing to fit`() {
        val font = loadFont(FontPath.LATIN_REGULAR, 20f)
        rule.captureGolden("path_text_overflow_compress.png") {
            Stage(width = 360, height = 80) {
                TextOnPath(
                    text = "this text is squished into a short path",
                    font = font,
                    path = Path().apply {
                        moveTo(20f, 40f)
                        lineTo(340f, 40f)
                    },
                    overflow = TextOnPathOverflow.Compress,
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `mixed arabic latin with tashkeel and bounding box on arc`() {
        val latin = loadFont(FontPath.LATIN_REGULAR, 26f)
        val arabic = loadFont(FontPath.ARABIC_BOLD, 26f)
        rule.captureGolden("path_text_mixed_tashkeel_bbox.png") {
            Stage(width = 320, height = 240) {
                val stack = remember(latin, arabic) { HbFontStack(latin, listOf(arabic)) }
                val arcPath = remember {
                    Path().apply { addArc(Rect(30f, 30f, 290f, 290f), 180f, 180f) }
                }
                val text = "kotlin بِسْمِ harfbuzz"

                TextOnPath(
                    text = text,
                    fontStack = stack,
                    path = arcPath,
                    alignment = TextOnPathAlignment.Center,
                    color = Color(0xFF0F1F44),
                    modifier = Modifier.fillMaxSize(),
                )

                val boundsState = rememberPathTextBounds(
                    text = text,
                    fontStack = stack,
                    path = arcPath,
                    alignment = TextOnPathAlignment.Center,
                )
                val ink = (boundsState.value as? PathTextBoundsLoad.Ready)?.bounds?.ink
                if (ink != null && !ink.isEmpty) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            color = Color(0xFFD32F2F),
                            topLeft = Offset(ink.left, ink.top),
                            size = Size(ink.width, ink.height),
                            style = Stroke(width = 1.5f),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `arabic compressed extreme tiny path`() {
        val font = loadFont(FontPath.ARABIC_BOLD, 24f)
        rule.captureGolden("path_text_arabic_compress_extreme.png") {
            Stage(width = 220, height = 80) {
                TextOnPath(
                    text = "السلام عليكم صديقي",
                    font = font,
                    path = Path().apply {
                        moveTo(20f, 50f)
                        lineTo(140f, 50f)
                    },
                    overflow = TextOnPathOverflow.Compress,
                    color = Color(0xFF14213D),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Test
    fun `arabic compressed on half circle arc`() {
        val font = loadFont(FontPath.ARABIC_BOLD, 30f)
        rule.captureGolden("path_text_arabic_compressed_arc.png") {
            Stage(width = 280, height = 200) {
                TextOnPath(
                    text = "بسم الله الرحمن الرحيم",
                    font = font,
                    path = Path().apply {
                        addArc(Rect(60f, 40f, 220f, 200f), 180f, 180f)
                    },
                    overflow = TextOnPathOverflow.Compress,
                    alignment = TextOnPathAlignment.Center,
                    color = Color(0xFF14213D),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @Composable
    private fun Stage(width: Int, height: Int, content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .size(width.dp, height.dp)
                .background(Color.White),
        ) {
            content()
        }
    }
}
