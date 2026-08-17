package com.mohamedrejeb.harfbuzz.compose.paragraph

import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredText
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredTextJustified
import com.mohamedrejeb.harfbuzz.core.FontRun
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [buildMeasuredTextJustified] must fill a line to EXACTLY the target width and
 * NEVER beyond it — the two justify defects this replaces: lines differing in
 * width, and a short line growing wider than the target (the longest line).
 */
class BuildMeasuredTextJustifiedTest {

    private val handles = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        handles.reversed().forEach { it.close() }
        handles.clear()
    }

    private suspend fun stack(bytes: ByteArray): HbFontStack {
        harfBuzzInit()
        val face = HbFace.fromBytes(bytes)
        val font = face.toFont()
        handles.add(font)
        handles.add(face)
        return HbFontStack(font)
    }

    private suspend fun natural(text: String, s: HbFontStack, dir: HbDirection) =
        buildMeasuredText(text, s, SIZE, emptyList(), dir, HbLanguage.AUTO)

    private suspend fun justified(text: String, s: HbFontStack, dir: HbDirection, target: Float) =
        buildMeasuredTextJustified(text, s, SIZE, emptyList(), dir, HbLanguage.AUTO, target)

    @Test
    fun `latin multiword justify lands exactly on target`() = runBlocking {
        val s = stack(TestFonts.robotoRegular())
        val target = natural(LATIN, s, HbDirection.AUTO).advance + 120f
        val out = justified(LATIN, s, HbDirection.AUTO, target)
        assertTrue(out.advance <= target + EPS, "must not exceed: advance=${out.advance} target=$target")
        assertTrue(abs(out.advance - target) <= EPS, "must reach target: advance=${out.advance} target=$target")
    }

    @Test
    fun `latin single word justify lands exactly on target`() = runBlocking {
        val s = stack(TestFonts.robotoRegular())
        // No inter-word space — fillToWidth falls back to cluster-gap distribution.
        val target = natural("Hello", s, HbDirection.AUTO).advance + 60f
        val out = justified("Hello", s, HbDirection.AUTO, target)
        assertTrue(abs(out.advance - target) <= EPS, "advance=${out.advance} target=$target")
    }

    @Test
    fun `arabic justify never exceeds and reaches target`() = runBlocking {
        val s = stack(TestFonts.notoNaskhArabicMedium())
        val target = natural(ARABIC, s, HbDirection.RTL).advance + 100f
        val out = justified(ARABIC, s, HbDirection.RTL, target)
        assertTrue(out.advance <= target + EPS, "must not exceed: advance=${out.advance} target=$target")
        assertTrue(abs(out.advance - target) <= EPS, "must reach target: advance=${out.advance} target=$target")
    }

    @Test
    fun `identity when target at or below natural advance`() = runBlocking {
        val s = stack(TestFonts.robotoRegular())
        val nat = natural(LATIN, s, HbDirection.AUTO)
        val out = justified(LATIN, s, HbDirection.AUTO, nat.advance - 10f)
        assertTrue(out.advance == nat.advance, "should return natural unchanged: ${out.advance} vs ${nat.advance}")
    }

    @Test
    fun `short line justified to long line width matches it and never exceeds`() = runBlocking {
        val s = stack(TestFonts.robotoRegular())
        val target = natural(LATIN, s, HbDirection.AUTO).advance // widest natural line
        val shortJustified = justified("Hi", s, HbDirection.AUTO, target)
        assertTrue(shortJustified.advance <= target + EPS, "short must not exceed long: ${shortJustified.advance} vs $target")
        assertTrue(abs(shortJustified.advance - target) <= EPS, "short should match long: ${shortJustified.advance} vs $target")
    }

    @Test
    fun `arabic line with authored run lands exactly on target width`() = runBlocking {
        val s = stack(TestFonts.notoNaskhArabicMedium())
        val saudi = font(TestFonts.saudiRegular())
        val runs = listOf(FontRun(0, 5, saudi))
        val target = naturalWithRuns(ARABIC, s, HbDirection.RTL, runs).advance + 100f
        val out = justifiedWithRuns(ARABIC, s, HbDirection.RTL, target, runs)
        assertTrue(out.advance <= target + EPS, "must not exceed: advance=${out.advance} target=$target")
        assertTrue(abs(out.advance - target) <= EPS, "must reach target: advance=${out.advance} target=$target")
    }

    @Test
    fun `mixed latin arabic line with authored run lands exactly on target width`() = runBlocking {
        val s = stack(TestFonts.robotoRegular())
        val noto = font(TestFonts.notoNaskhArabicMedium())
        val mixed = "Hello مرحبا World"
        val arabicStart = mixed.indexOf('م')
        val runs = listOf(FontRun(arabicStart, arabicStart + 5, noto))
        val target = naturalWithRuns(mixed, s, HbDirection.AUTO, runs).advance + 80f
        val out = justifiedWithRuns(mixed, s, HbDirection.AUTO, target, runs)
        assertTrue(out.advance <= target + EPS, "must not exceed: advance=${out.advance} target=$target")
        assertTrue(abs(out.advance - target) <= EPS, "must reach target: advance=${out.advance} target=$target")
    }

    private suspend fun font(bytes: ByteArray): HbFont {
        harfBuzzInit()
        val face = HbFace.fromBytes(bytes)
        val f = face.toFont()
        handles.add(f)
        handles.add(face)
        return f
    }

    private suspend fun naturalWithRuns(text: String, s: HbFontStack, dir: HbDirection, runs: List<FontRun>) =
        buildMeasuredText(text, s, SIZE, emptyList(), dir, HbLanguage.AUTO, runs)

    private suspend fun justifiedWithRuns(
        text: String,
        s: HbFontStack,
        dir: HbDirection,
        target: Float,
        runs: List<FontRun>,
    ) = buildMeasuredTextJustified(text, s, SIZE, emptyList(), dir, HbLanguage.AUTO, target, runs)

    private companion object {
        const val SIZE = 28f
        const val EPS = 0.5f
        const val LATIN = "Hello World Wide"
        const val ARABIC = "مرحبا بالعالم"
    }
}
