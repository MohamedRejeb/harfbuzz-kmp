package com.mohamedrejeb.harfbuzz.compose.paragraph

import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Each hard-wrapped paragraph (text between line terminators) resolves its own
 * base direction, like Compose. A line that starts a new paragraph must not
 * inherit the previous line's direction — otherwise an LTR line after an RTL one
 * (e.g. "08:00 PM" under an Arabic line) is shaped RTL and reorders to "PM 08:00".
 */
class ParagraphDirectionTest {

    private val handles = mutableListOf<AutoCloseable>()

    @AfterTest
    fun closeAll() {
        handles.reversed().forEach { it.close() }
        handles.clear()
    }

    @Test
    fun `arabic line then english line each keep their own direction`() = runBlocking {
        val p = layout("مرحبا\n08:00 PM")
        assertEquals(2, p.lineCount, "expected two hard-wrapped lines")
        assertEquals(
            HbDirection.RTL,
            p.lines[0].measured.paragraph.baseDirection,
            "Arabic line should render RTL",
        )
        assertEquals(
            HbDirection.LTR,
            p.lines[1].measured.paragraph.baseDirection,
            "English line after an Arabic line should render LTR, not inherit RTL",
        )
    }

    @Test
    fun `english line then arabic line each keep their own direction`() = runBlocking {
        val p = layout("Hello\n١٢٣ مرحبا")
        assertEquals(2, p.lineCount)
        assertEquals(
            HbDirection.LTR,
            p.lines[0].measured.paragraph.baseDirection,
            "English line should render LTR",
        )
        assertEquals(
            HbDirection.RTL,
            p.lines[1].measured.paragraph.baseDirection,
            "Arabic line after an English line should render RTL, not inherit LTR",
        )
    }

    private suspend fun layout(text: String): MeasuredParagraph {
        harfBuzzInit()
        val latinFace = HbFace.fromBytes(TestFonts.robotoRegular()).also { handles.add(it) }
        val latin = latinFace.toFont().also { handles.add(it) }
        val arabicFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium()).also { handles.add(it) }
        val arabic = arabicFace.toFont().also { handles.add(it) }
        return buildMeasuredParagraph(
            text = text,
            fontStack = HbFontStack(latin, listOf(arabic)),
            sizePx = 28f,
            maxWidth = 1000f,
            alignment = ParagraphAlignment.Start,
            direction = HbDirection.AUTO,
            features = emptyList(),
            language = HbLanguage.AUTO,
            lineSpacing = 0f,
            justification = JustificationStrategy.None,
        )
    }
}
