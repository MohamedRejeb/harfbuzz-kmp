package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbVariation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot coverage for the variable-fonts pipeline. Loads
 * `Trox-R.ttf` (a custom variable font with three normalised 0..1 axes:
 * `MORF`, `wdth`, `BNCE`) and renders the same string with two distinct
 * variation states:
 *
 *  1. **default** - every axis pinned at `0.0`. Baseline shape with no
 *     morph, no width change, no bounce.
 *  2. **all-axes-max** - every axis pinned at `1.0`. Maxed-out morph,
 *     widest glyphs, full bounce.
 *
 * If shaping ever stops applying variations on the JVM JNI path (e.g.
 * `fontCreateWithVariations` regresses), the rendered glyphs collapse
 * back onto the default outlines and these two goldens diverge from
 * what they capture today.
 */
class VariableFontsScreenshotTest {

    @get:Rule
    val rule = composeRule()

    private val openFonts = mutableListOf<AutoCloseable>()

    @After
    fun closeFonts() {
        openFonts.reversed().forEach { it.close() }
        openFonts.clear()
    }

    /**
     * Load the Trox face once and mint a font with the requested axis
     * values pinned. Keeps the face open until [closeFonts] runs so a
     * test can share a single byte-blob across multiple captures.
     */
    private fun troxFont(
        morf: Float = 0f,
        wdth: Float = 0f,
        bnce: Float = 0f,
    ): HbFont = runBlocking {
        val bytes = readFontBytes(FontPath.TROX_VARIABLE)
        val face = HbFace.from { bytes(bytes) }
        val font = face.toFont(
            variations = listOf(
                HbVariation.of("MORF", morf),
                HbVariation.of("wdth", wdth),
                HbVariation.of("BNCE", bnce),
            ),
        )
        openFonts.add(font)
        openFonts.add(face)
        font
    }

    @Test
    fun trox_axes_default() {
        rule.captureGolden("variable_trox_axes_default.png") {
            TroxStage(troxFont())
        }
    }

    @Test
    fun trox_axes_all_max() {
        rule.captureGolden("variable_trox_axes_all_max.png") {
            TroxStage(troxFont(morf = 1f, wdth = 1f, bnce = 1f))
        }
    }

    /**
     * Fixed white stage that mirrors what the in-app demo renders -
     * a single line of `ShapedText` at 64 px on a white background
     * with a generous canvas so the bouncy / wide variants don't
     * clip on either edge.
     */
    @Composable
    private fun TroxStage(font: HbFont) {
        Box(
            modifier = Modifier
                .background(Color.White)
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            ShapedText(
                text = "Trox harfbuzz",
                font = font,
                sizePx = 64f,
                color = Color(0xFF1E1B22),
                modifier = Modifier.fillMaxWidth().height(80.dp),
            )
        }
    }
}
