package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.drawArcTextLayered
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Golden coverage for authored font runs (per-range fonts):
 *
 *  - a flat Arabic line where one word is pinned to a different face
 *    (Ruqaa word inside a Naskh line) - the per-range font must be
 *    visibly distinct while the rest of the line stays in the base face;
 *  - an authored boundary in the middle of a joined Arabic word
 *    (Regular to Bold mid-word) - the letters must keep their joined
 *    initial/medial/final forms across the boundary instead of breaking
 *    into isolated shapes;
 *  - the layered warped arc drawer on a mixed-font shape - outlines must
 *    resolve through each run's own font on the arc path too.
 */
class FontRunsScreenshotTest {

    @get:Rule
    val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    private fun loadFont(path: String): HbFont = runBlocking {
        val bytes = readFontBytes(path)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont()
        openFonts.add(font)
        openFonts.add(face)
        font
    }

    @Test
    fun `authored run pins one word to a different face`() {
        val naskh = loadFont(FontPath.ARABIC_REGULAR)
        val ruqaa = loadFont(FontPath.AREF_RUQAA_REGULAR)
        rule.captureGolden("font_runs_mixed_faces_line.png") {
            Stage {
                AuthoredRunsText(
                    text = "مرحبا بالعالم الواسع",
                    stack = HbFontStack(naskh),
                    sizePx = 36f,
                    fontRuns = listOf(FontRun(6, 13, ruqaa)),
                    height = 72.dp,
                )
            }
        }
    }

    @Test
    fun `authored boundary inside a joined word keeps letter forms`() {
        val regular = loadFont(FontPath.ARABIC_REGULAR)
        val bold = loadFont(FontPath.ARABIC_BOLD)
        rule.captureGolden("font_runs_boundary_inside_word.png") {
            Stage {
                // Boundary between HAH and BEH, a dual-joining pair: the
                // first three letters render Bold, the rest Regular, and
                // the word must stay visually connected.
                AuthoredRunsText(
                    text = "مرحبا",
                    stack = HbFontStack(regular),
                    sizePx = 48f,
                    fontRuns = listOf(FontRun(0, 3, bold)),
                    height = 80.dp,
                )
            }
        }
    }

    @Test
    fun `warped arc resolves outlines per authored run font`() {
        val naskh = loadFont(FontPath.ARABIC_REGULAR)
        val ruqaa = loadFont(FontPath.AREF_RUQAA_REGULAR)
        rule.captureGolden("font_runs_mixed_faces_arc.png") {
            Stage {
                AuthoredRunsArc(
                    text = "مرحبا بالعالم",
                    stack = HbFontStack(naskh),
                    sizePx = 32f,
                    fontRuns = listOf(FontRun(0, 5, ruqaa)),
                )
            }
        }
    }

    @Composable
    private fun AuthoredRunsText(
        text: String,
        stack: HbFontStack,
        sizePx: Float,
        fontRuns: List<FontRun>,
        height: Dp,
    ) {
        val runs = remember { fontRuns }
        val loadState by rememberMeasuredText(
            text = text,
            fontStack = stack,
            sizePx = sizePx,
            direction = HbDirection.RTL,
            fontRuns = runs,
        )
        val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured
        Box(
            modifier = Modifier
                .width(520.dp)
                .height(height)
                .drawBehind {
                    val m = measured ?: return@drawBehind
                    if (m.isEmpty) return@drawBehind
                    drawShapedText(m, topLeft = Offset(0f, 8f), color = Color.Black)
                },
        )
    }

    @Composable
    private fun AuthoredRunsArc(
        text: String,
        stack: HbFontStack,
        sizePx: Float,
        fontRuns: List<FontRun>,
    ) {
        val runs = remember { fontRuns }
        val loadState by rememberMeasuredText(
            text = text,
            fontStack = stack,
            sizePx = sizePx,
            direction = HbDirection.RTL,
            fontRuns = runs,
        )
        val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured
        Box(
            modifier = Modifier
                .width(360.dp)
                .height(200.dp)
                .drawBehind {
                    val m = measured ?: return@drawBehind
                    if (m.isEmpty) return@drawBehind
                    drawArcTextLayered(
                        measured = m,
                        center = Offset(size.width / 2f, size.height - 16f),
                        radiusPx = 130f,
                        startAngleDeg = 180f,
                        sweepAngleDeg = 180f,
                        fillColor = Color.Black,
                    )
                },
        )
    }

    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .background(Color.White)
                .padding(16.dp),
        ) {
            content()
        }
    }
}
