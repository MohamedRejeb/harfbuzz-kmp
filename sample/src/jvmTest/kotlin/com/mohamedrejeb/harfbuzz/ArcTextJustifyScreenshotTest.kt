package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ArcAlignment
import com.mohamedrejeb.harfbuzz.compose.ArcDirection
import com.mohamedrejeb.harfbuzz.compose.ArcSide
import com.mohamedrejeb.harfbuzz.compose.ArcText
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.TextOnPathAlignment
import com.mohamedrejeb.harfbuzz.compose.TextOnPathOverflow
import com.mohamedrejeb.harfbuzz.compose.drawTextAlongPath
import com.mohamedrejeb.harfbuzz.compose.drawWarpedTextAlongPath
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for the three closed parity gaps:
 *  - Latin / mixed arc fill via
 *    [JustificationStrategy.AdvanceStretchTo] (post-shape advance
 *    stretch, the script-symmetric counterpart to
 *    [JustificationStrategy.KashidaTo]).
 *  - `overflow` parameter on [ArcText] (Compress shrinks per-glyph
 *    spacing so an over-long line fits the ring exactly instead of
 *    being clipped).
 *  - Mixed-script arc text on a non-full-circle path (half-circle
 *    via [drawTextAlongPath] with a custom 180° arc).
 *
 * Stage is 280 dp square; tight = 70 dp, medium = 110 dp.
 *
 * Goldens land under `sample/src/jvmTest/resources/goldens/` and are
 * regenerated with `-Dkotlin.harfbuzz.regenerate.goldens=true`.
 */
class ArcTextJustifyScreenshotTest {

    @get:Rule
    val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    private fun loadFontSynchronously(path: String, sizePx: Float): SizedFont = runBlocking {
        val bytes = readFontBytes(path)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont()
        openFonts.add(font)
        openFonts.add(face)
        SizedFont(font, sizePx)
    }

    // ---- Latin advance-stretch fills the full ring --------------------------

    @Test
    fun `latin advance stretch fills full ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 18f)
        rule.captureGolden("arc_text_justify_latin_stretch_full_ring.png") {
            ArcStage {
                val radius = 90.dp
                val density = LocalDensity.current
                val arcLenPx = with(density) { 2f * PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "stretch to fill the ring",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.AdvanceStretchTo(arcLenPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    ArcText(
                        measured = measured,
                        radius = radius,
                        startAngle = TOP_CENTERED_START_ANGLE,
                        alignment = ArcAlignment.Center,
                        side = ArcSide.Outside,
                        color = Color.Black,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // ---- Mixed-script kashida-only justify on a full ring ------------------

    @Test
    fun `mixed bidi kashida fills full ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 20f)
        rule.captureGolden("arc_text_justify_mixed_kashida_full_ring.png") {
            ArcStage {
                val radius = 95.dp
                val density = LocalDensity.current
                val arcLenPx = with(density) { 2f * PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "hello مرحبا",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.KashidaTo(arcLenPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    ArcText(
                        measured = measured,
                        radius = radius,
                        startAngle = TOP_CENTERED_START_ANGLE,
                        alignment = ArcAlignment.Center,
                        side = ArcSide.Outside,
                        color = Color(0xFF14213D),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // ---- Mixed-script kashida-only justify on a half-circle path -----------

    @Test
    fun `mixed bidi kashida fills half circle`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 20f)
        rule.captureGolden("arc_text_justify_mixed_kashida_half_circle.png") {
            ArcStage {
                val radius = 110.dp
                val density = LocalDensity.current
                val halfArcPx = with(density) { PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "hello مرحبا friends",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.KashidaTo(halfArcPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    HalfCircleText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF14213D),
                    )
                }
            }
        }
    }

    // ---- Arabic kashida fills a half-circle path ----------------------------

    @Test
    fun `arabic kashida fills half circle`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 22f)
        rule.captureGolden("arc_text_justify_arabic_kashida_half_circle.png") {
            ArcStage {
                val radius = 110.dp
                val density = LocalDensity.current
                val halfArcPx = with(density) { PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "نص عربي",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.KashidaTo(halfArcPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    HalfCircleText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF005F73),
                    )
                }
            }
        }
    }

    // ---- ArcText overflow = Compress on a tight ring ------------------------

    @Test
    fun `latin compress on tight ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("arc_text_justify_latin_compress_tight.png") {
            ArcStage {
                ArcText(
                    text = "this string is far too long to fit on this little ring",
                    font = font,
                    sizePx = sizePx,
                    radius = 70.dp,
                    startAngle = TOP_CENTERED_START_ANGLE,
                    alignment = ArcAlignment.Center,
                    side = ArcSide.Outside,
                    overflow = TextOnPathOverflow.Compress,
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---- Warp drawer keeps Arabic cursive joins intact ----------------------

    @Test
    fun `arabic warp on tight half circle`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 24f)
        rule.captureGolden("arc_text_justify_arabic_warp_half_circle.png") {
            ArcStage {
                val radius = 110.dp
                val density = LocalDensity.current
                val halfArcPx = with(density) { PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "نص عربي",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.KashidaTo(halfArcPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    HalfCircleWarpedText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF005F73),
                    )
                }
            }
        }
    }

    @Test
    fun `mixed bidi warp on half circle with kashida`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 22f)
        rule.captureGolden("arc_text_justify_mixed_warp_half_circle.png") {
            ArcStage {
                val radius = 110.dp
                val density = LocalDensity.current
                val halfArcPx = with(density) { PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "hello مرحبا friends",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.KashidaTo(halfArcPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    HalfCircleWarpedText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF14213D),
                    )
                }
            }
        }
    }

    @Test
    fun `arabic warp tight ring no kashida`() {
        // Without kashida, the warp drawer keeps cursive joins continuous
        // even on a tight 60 dp ring. The companion test
        // `arabic tight radius upward` in `ArcTextSweepScreenshotTest`
        // renders the same string at the same radius via the rigid-glyph
        // drawer for comparison.
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 18f)
        rule.captureGolden("arc_text_justify_arabic_warp_tight_no_kashida.png") {
            ArcStage {
                val radius = 60.dp
                val density = LocalDensity.current
                val loadState by rememberMeasuredText(
                    text = "نص عربي تجريبي",
                    font = font,
                    sizePx = sizePx,
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    FullCircleWarpedText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF005F73),
                    )
                }
            }
        }
    }

    @Test
    fun `warp drawer falls back for color glyph font`() {
        // ArefRuqaaInk has a COLR v1 paint tree; the warp drawer cannot
        // express it as a single monochrome outline so it auto-falls back
        // to per-glyph rigid placement and the colored variant renders.
        val (font, sizePx) = loadFontSynchronously(FontPath.AREF_RUQAA_INK_REGULAR, 28f)
        rule.captureGolden("arc_text_justify_warp_color_glyph_fallback.png") {
            ArcStage {
                val radius = 110.dp
                val density = LocalDensity.current
                val loadState by rememberMeasuredText(
                    text = "نص ملون",
                    font = font,
                    sizePx = sizePx,
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    HalfCircleWarpedText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF005F73),
                    )
                }
            }
        }
    }

    @Test
    fun `arabic warp fills full ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 22f)
        rule.captureGolden("arc_text_justify_arabic_warp_full_ring.png") {
            ArcStage {
                val radius = 90.dp
                val density = LocalDensity.current
                val arcLenPx = with(density) { 2f * PI.toFloat() * radius.toPx() }
                val loadState by rememberMeasuredText(
                    text = "نص عربي",
                    font = font,
                    sizePx = sizePx,
                    justification = JustificationStrategy.KashidaTo(arcLenPx),
                )
                val measured = (loadState as? MeasuredTextLoad.Ready)?.measured
                if (measured != null) {
                    val rPx = with(density) { radius.toPx() }
                    val stageSizePx = with(density) { 280.dp.toPx() }
                    FullCircleWarpedText(
                        stageSizePx = stageSizePx,
                        radiusPx = rPx,
                        measured = measured,
                        color = Color(0xFF005F73),
                    )
                }
            }
        }
    }

    // ---- Mixed BiDi without justify (regression marker for run splitter) ----

    @Test
    fun `mixed bidi no justify medium ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 22f)
        rule.captureGolden("arc_text_justify_mixed_no_justify_medium.png") {
            ArcStage {
                ArcText(
                    text = "hello مرحبا",
                    font = font,
                    sizePx = sizePx,
                    radius = 110.dp,
                    startAngle = TOP_CENTERED_START_ANGLE,
                    alignment = ArcAlignment.Center,
                    side = ArcSide.Outside,
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---- Helpers ------------------------------------------------------------

    @Composable
    private fun HalfCircleText(
        stageSizePx: Float,
        radiusPx: Float,
        measured: MeasuredText,
        color: Color,
    ) {
        val cx = stageSizePx / 2f
        val cy = stageSizePx / 2f
        val rect = Rect(
            left = cx - radiusPx,
            top = cy - radiusPx,
            right = cx + radiusPx,
            bottom = cy + radiusPx,
        )
        val path = Path().apply {
            // 180 deg arc from 9 o'clock to 3 o'clock through 12 o'clock,
            // i.e. CCW through the top of the circle, so the text rides
            // the top half reading left to right.
            addArc(rect, startAngleDegrees = 180f, sweepAngleDegrees = -180f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawTextAlongPath(
                        measured = measured,
                        path = path,
                        color = color,
                        alignment = TextOnPathAlignment.Center,
                        overflow = TextOnPathOverflow.Clip,
                    )
                },
        )
    }

    @Composable
    private fun HalfCircleWarpedText(
        stageSizePx: Float,
        radiusPx: Float,
        measured: MeasuredText,
        color: Color,
    ) {
        val cx = stageSizePx / 2f
        val cy = stageSizePx / 2f
        val rect = Rect(
            left = cx - radiusPx,
            top = cy - radiusPx,
            right = cx + radiusPx,
            bottom = cy + radiusPx,
        )
        val path = Path().apply {
            addArc(rect, startAngleDegrees = 180f, sweepAngleDegrees = -180f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawWarpedTextAlongPath(
                        measured = measured,
                        path = path,
                        color = color,
                        alignment = TextOnPathAlignment.Center,
                        overflow = TextOnPathOverflow.Clip,
                    )
                },
        )
    }

    @Composable
    private fun FullCircleWarpedText(
        stageSizePx: Float,
        radiusPx: Float,
        measured: MeasuredText,
        color: Color,
    ) {
        val cx = stageSizePx / 2f
        val cy = stageSizePx / 2f
        val rect = Rect(
            left = cx - radiusPx,
            top = cy - radiusPx,
            right = cx + radiusPx,
            bottom = cy + radiusPx,
        )
        val path = Path().apply {
            // 360 deg CW starting at the bottom so Center alignment lands
            // the text-midpoint at the top, tangent pointing right.
            addArc(rect, startAngleDegrees = 90f, sweepAngleDegrees = 360f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawWarpedTextAlongPath(
                        measured = measured,
                        path = path,
                        color = color,
                        alignment = TextOnPathAlignment.Center,
                        overflow = TextOnPathOverflow.Clip,
                    )
                },
        )
    }

    @Composable
    private fun ArcStage(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(Color.White),
        ) {
            content()
        }
    }

    private companion object {
        const val TOP_CENTERED_START_ANGLE = 90f
    }
}
