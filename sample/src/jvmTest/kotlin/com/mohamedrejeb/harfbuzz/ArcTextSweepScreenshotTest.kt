package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ArcAlignment
import com.mohamedrejeb.harfbuzz.compose.ArcDirection
import com.mohamedrejeb.harfbuzz.compose.ArcSide
import com.mohamedrejeb.harfbuzz.compose.ArcText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
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
 * Parameter-sweep screenshot tests for [ArcText]. Where
 * `ArcTextScreenshotTest` covers one-of-each parameter at a single
 * canonical radius, this file walks radius (tight / medium / shallow)
 * across Latin, Arabic, mixed BiDi, decorative cursive, plus the
 * Kashida-fill-the-ring case, and pairs every radius sweep with its
 * upward / downward variant.
 *
 * Stage is 280 dp square. Reference radii:
 *  - tight   = 70 dp  (full circle fits, text wraps a large slice),
 *  - medium  = 110 dp (matches the canonical existing tests),
 *  - shallow = 135 dp (gentler curvature than the medium baseline).
 *
 * Centering rule (matches what designers expect from "arc text"):
 *  - upward arcs centre the text at the visual top of the circle
 *    (clockwise path, `startAngle = 90f`, [ArcAlignment.Center]). With
 *    `addArc(rect, 90f, 360f)` the path begins at the bottom and the
 *    arc-length midpoint sits at -90°, tangent pointing right.
 *  - downward arcs centre the text at the visual bottom of the circle
 *    (counter-clockwise path, `startAngle = -90f`, [ArcAlignment.Center]).
 *    With `addArc(rect, -90f, -360f)` the path begins at the top and
 *    the arc-length midpoint sits at +90°, tangent again pointing right
 *    so the text reads left-to-right under the curve.
 *
 * Goldens land under `sample/src/jvmTest/resources/goldens/` and are
 * regenerated with `-Dkotlin.harfbuzz.regenerate.goldens=true`.
 */
class ArcTextSweepScreenshotTest {

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

    // ---- Latin upward radius sweep -------------------------------------------

    @Test
    fun `latin tight radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 18f)
        rule.captureGolden("arc_text_sweep_latin_tight_upward.png") {
            ArcStage { UpwardArc(text = "tight curve around small radius", font = font, sizePx = sizePx, radius = 70.dp, color = Color.Black) }
        }
    }

    @Test
    fun `latin medium radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("arc_text_sweep_latin_medium_upward.png") {
            ArcStage { UpwardArc(text = "medium radius arc", font = font, sizePx = sizePx, radius = 110.dp, color = Color.Black) }
        }
    }

    @Test
    fun `latin shallow radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("arc_text_sweep_latin_shallow_upward.png") {
            ArcStage { UpwardArc(text = "shallow arc segment", font = font, sizePx = sizePx, radius = 135.dp, color = Color.Black) }
        }
    }

    // ---- Latin downward radius sweep -----------------------------------------

    @Test
    fun `latin tight radius downward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 18f)
        rule.captureGolden("arc_text_sweep_latin_tight_downward.png") {
            ArcStage { DownwardArc(text = "tight curve around small radius", font = font, sizePx = sizePx, radius = 70.dp, color = Color.Black) }
        }
    }

    @Test
    fun `latin medium radius downward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("arc_text_sweep_latin_medium_downward.png") {
            ArcStage { DownwardArc(text = "medium radius arc", font = font, sizePx = sizePx, radius = 110.dp, color = Color.Black) }
        }
    }

    @Test
    fun `latin shallow radius downward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("arc_text_sweep_latin_shallow_downward.png") {
            ArcStage { DownwardArc(text = "shallow arc segment", font = font, sizePx = sizePx, radius = 135.dp, color = Color.Black) }
        }
    }

    // ---- Arabic upward radius sweep ------------------------------------------

    @Test
    fun `arabic tight radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 20f)
        rule.captureGolden("arc_text_sweep_arabic_tight_upward.png") {
            ArcStage { UpwardArc(text = "نص قصير على دائرة", font = font, sizePx = sizePx, radius = 70.dp, color = Color(0xFF005F73)) }
        }
    }

    @Test
    fun `arabic medium radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 24f)
        rule.captureGolden("arc_text_sweep_arabic_medium_upward.png") {
            ArcStage { UpwardArc(text = "نص متوسط على دائرة", font = font, sizePx = sizePx, radius = 110.dp, color = Color(0xFF005F73)) }
        }
    }

    @Test
    fun `arabic shallow radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 24f)
        rule.captureGolden("arc_text_sweep_arabic_shallow_upward.png") {
            ArcStage { UpwardArc(text = "قوس عربي شالو", font = font, sizePx = sizePx, radius = 135.dp, color = Color(0xFF005F73)) }
        }
    }

    // ---- Arabic downward radius sweep ----------------------------------------

    @Test
    fun `arabic tight radius downward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 20f)
        rule.captureGolden("arc_text_sweep_arabic_tight_downward.png") {
            ArcStage { DownwardArc(text = "نص قصير على دائرة", font = font, sizePx = sizePx, radius = 70.dp, color = Color(0xFF005F73)) }
        }
    }

    @Test
    fun `arabic medium radius downward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 24f)
        rule.captureGolden("arc_text_sweep_arabic_medium_downward.png") {
            ArcStage { DownwardArc(text = "نص متوسط على دائرة", font = font, sizePx = sizePx, radius = 110.dp, color = Color(0xFF005F73)) }
        }
    }

    @Test
    fun `arabic shallow radius downward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 24f)
        rule.captureGolden("arc_text_sweep_arabic_shallow_downward.png") {
            ArcStage { DownwardArc(text = "قوس عربي شالو", font = font, sizePx = sizePx, radius = 135.dp, color = Color(0xFF005F73)) }
        }
    }

    // ---- Arabic inside ring (concave) ----------------------------------------

    @Test
    fun `arabic inside ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 22f)
        rule.captureGolden("arc_text_sweep_arabic_inside_ring.png") {
            ArcStage {
                ArcText(
                    text = "كلمات على الحلقة الداخلية",
                    font = font,
                    sizePx = sizePx,
                    radius = 100.dp,
                    startAngle = TOP_CENTERED_START_ANGLE,
                    alignment = ArcAlignment.Center,
                    side = ArcSide.Inside,
                    color = Color(0xFF8B0000),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---- Kashida fills the ring (target arc length tracks radius) -----------

    @Test
    fun `arabic kashida fills tight ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 18f)
        rule.captureGolden("arc_text_sweep_arabic_kashida_tight.png") {
            KashidaArcStage(text = "نص عربي", font = font, fontSizePx = sizePx, radius = 70.dp)
        }
    }

    @Test
    fun `arabic kashida fills medium ring`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.ARABIC_BOLD, 22f)
        rule.captureGolden("arc_text_sweep_arabic_kashida_medium.png") {
            KashidaArcStage(text = "نص عربي", font = font, fontSizePx = sizePx, radius = 110.dp)
        }
    }

    // ---- Decorative cursive (cluster preservation) --------------------------

    @Test
    fun `arabic aref ruqaa medium radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.AREF_RUQAA_REGULAR, 26f)
        rule.captureGolden("arc_text_sweep_arabic_aref_ruqaa_medium.png") {
            ArcStage { UpwardArc(text = "نص عربي بخط", font = font, sizePx = sizePx, radius = 110.dp, color = Color(0xFF005F73)) }
        }
    }

    // ---- Mixed BiDi (single line, no run splitter today) --------------------

    @Test
    fun `mixed ltr lead medium radius upward`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 20f)
        rule.captureGolden("arc_text_sweep_mixed_ltr_lead_medium.png") {
            ArcStage { UpwardArc(text = "hello مرحبا", font = font, sizePx = sizePx, radius = 110.dp, color = Color.Black) }
        }
    }

    // ---- Overflow: long text on tight radius is clipped by ArcText ----------

    @Test
    fun `latin overflow on tight radius is clipped`() {
        val (font, sizePx) = loadFontSynchronously(FontPath.LATIN_REGULAR, 22f)
        rule.captureGolden("arc_text_sweep_latin_overflow_clipped.png") {
            ArcStage {
                UpwardArc(
                    text = "this string is far too long to fit on this little ring it must clip",
                    font = font,
                    sizePx = sizePx,
                    radius = 70.dp,
                    color = Color.Black,
                )
            }
        }
    }

    // ---- Helpers --------------------------------------------------------------

    @Composable
    private fun UpwardArc(text: String, font: HbFont, sizePx: Float, radius: Dp, color: Color) {
        ArcText(
            text = text,
            font = font,
            sizePx = sizePx,
            radius = radius,
            startAngle = TOP_CENTERED_START_ANGLE,
            alignment = ArcAlignment.Center,
            direction = ArcDirection.Clockwise,
            side = ArcSide.Outside,
            color = color,
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Composable
    private fun DownwardArc(text: String, font: HbFont, sizePx: Float, radius: Dp, color: Color) {
        ArcText(
            text = text,
            font = font,
            sizePx = sizePx,
            radius = radius,
            startAngle = BOTTOM_CENTERED_START_ANGLE,
            alignment = ArcAlignment.Center,
            direction = ArcDirection.CounterClockwise,
            side = ArcSide.Outside,
            color = color,
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Composable
    private fun KashidaArcStage(text: String, font: HbFont, fontSizePx: Float, radius: Dp) {
        ArcStage {
            val density = LocalDensity.current
            val arcLenPx = with(density) { 2f * PI.toFloat() * radius.toPx() }
            val loadState by rememberMeasuredText(
                text = text,
                font = font,
                sizePx = fontSizePx,
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
                    color = Color(0xFF005F73),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
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
        /**
         * Bottom of the circle in degrees. With the default clockwise
         * direction and [ArcAlignment.Center], lands the text-midpoint
         * at the top (`-90°`), tangent pointing right.
         */
        const val TOP_CENTERED_START_ANGLE = 90f

        /**
         * Top of the circle in degrees. Combined with
         * [ArcDirection.CounterClockwise] and [ArcAlignment.Center],
         * lands the text-midpoint at the bottom (`+90°`), tangent
         * pointing right so the text reads under the curve.
         */
        const val BOTTOM_CENTERED_START_ANGLE = -90f
    }
}
