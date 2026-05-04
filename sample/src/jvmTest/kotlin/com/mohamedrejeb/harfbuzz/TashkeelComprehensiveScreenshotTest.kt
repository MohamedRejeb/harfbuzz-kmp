package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.StyledText
import com.mohamedrejeb.harfbuzz.compose.withTashkeelColor
import com.mohamedrejeb.harfbuzz.core.HbFace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Tashkeel coloring coverage across the full Arabic alphabet, recorded
 * once per supported Arabic font. The abjad pangram exercises every one
 * of the 28 base letters; FATHA / SUKUN / SHADDA / DAMMA / KASRA are
 * sprinkled across letters with diverse shaping behaviour:
 *
 *  - Dotted bodies that some fonts split into [body, dot] glyphs
 *    (BAA, NOON, FAA, QAF, YAH, JEEM, etc.) - this is the case the
 *    cluster-position heuristic has to handle correctly.
 *  - Initial / medial / final / isolated form variants triggered by
 *    word-internal joining.
 *  - Decomposed mark stacks (FATHA + SHADDA over the same base).
 *
 * One golden per font protects against font-specific shaping
 * regressions: a NotoNaskh fix that breaks ArefRuqaa would slip
 * through a single-font test.
 */
class TashkeelComprehensiveScreenshotTest {

    @get:Rule val rule = composeRule()

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

    // Traditional abjad pangram. Each word groups four consecutive
    // letters of the classical teaching order and carries its own
    // mix of tashkeel:
    //   أَبْجَدْ  - FATHA, SUKUN, FATHA, SUKUN
    //   هَوَّزْ   - FATHA, FATHA + SHADDA, SUKUN
    //   حُطِّيْ   - DAMMA, KASRA + SHADDA, SUKUN
    //   كَلَمُنْ  - FATHA, FATHA, DAMMA, SUKUN
    //   سَعْفَصْ  - FATHA, SUKUN, FATHA, SUKUN
    //   قَرَشَتْ  - FATHA, FATHA, FATHA, SUKUN
    //   ثَخَذْ    - FATHA, FATHA, SUKUN
    //   ضَظَغْ    - FATHA, FATHA, SUKUN
    private val abjadPangram =
        "أَبْجَدْ هَوَّزْ حُطِّيْ كَلَمُنْ سَعْفَصْ قَرَشَتْ ثَخَذْ ضَظَغْ"

    @Test
    fun `abjad pangram tashkeel noto naskh regular`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_REGULAR, 36f)
        rule.captureGolden("styled_tashkeel_pangram_noto_regular.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(
                    text = StyledText(abjadPangram).withTashkeelColor(Color.Red),
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `abjad pangram tashkeel noto naskh bold`() {
        val (font, sizePx) = loadFont(FontPath.ARABIC_BOLD, 36f)
        rule.captureGolden("styled_tashkeel_pangram_noto_bold.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(
                    text = StyledText(abjadPangram).withTashkeelColor(Color.Red),
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }

    @Test
    fun `abjad pangram tashkeel aref ruqaa regular`() {
        val (font, sizePx) = loadFont(FontPath.AREF_RUQAA_REGULAR, 36f)
        rule.captureGolden("styled_tashkeel_pangram_aref_ruqaa.png") {
            Box(Modifier.padding(16.dp)) {
                ShapedText(
                    text = StyledText(abjadPangram).withTashkeelColor(Color.Red),
                    font = font,
                    sizePx = sizePx,
                    color = Color.Black,
                )
            }
        }
    }
}
