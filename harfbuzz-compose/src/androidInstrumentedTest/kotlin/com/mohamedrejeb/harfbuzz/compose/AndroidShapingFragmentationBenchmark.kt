package com.mohamedrejeb.harfbuzz.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.SystemFallback
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tracks the run-count and timing of `HbFontStack.shapeParagraph` on
 * fragmented mixed Latin+emoji paragraphs. Useful to validate the
 * "approximate spaces" optimisation on Android, where the bundled
 * NotoColorEmoji asset shipped with the test APK may behave differently
 * from the JVM test corpus.
 *
 * Run with:
 * ```
 * ./gradlew :harfbuzz-compose:connectedDebugAndroidTest -PrunBenchmarks
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AndroidShapingFragmentationBenchmark {

    private lateinit var robotoFace: HbFace
    private lateinit var arabicFace: HbFace
    private lateinit var emojiFace: HbFace
    private lateinit var roboto: HbFont
    private lateinit var arabic: HbFont
    private lateinit var emoji: HbFont
    private lateinit var stack: HbFontStack

    private fun assumeBenchmarksEnabled() {
        val arg = InstrumentationRegistry.getArguments().getString("runBenchmarks")
        Assume.assumeTrue(
            "Run with -PrunBenchmarks (e.g. " +
                "'./gradlew :harfbuzz-compose:connectedDebugAndroidTest -PrunBenchmarks')",
            arg == "true",
        )
    }

    @Before
    fun setUp() {
        assumeBenchmarksEnabled()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        runBlocking {
            harfBuzzInit()
            robotoFace = HbFace.fromBytes(
                ctx.assets.open("fonts/Roboto-Regular.ttf").use { it.readBytes() },
            )
            arabicFace = HbFace.fromBytes(
                ctx.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { it.readBytes() },
            )
            emojiFace = HbFace.fromBytes(
                ctx.assets.open("fonts/NotoColorEmoji-Regular.ttf").use { it.readBytes() },
            )
            roboto = robotoFace.toFont(POINT_SIZE)
            arabic = arabicFace.toFont(POINT_SIZE)
            emoji = emojiFace.toFont(POINT_SIZE)
        }
        stack = HbFontStack(
            roboto,
            fallbacks = listOf(arabic, emoji),
            system = SystemFallback.None,
        )
    }

    @After
    fun tearDown() {
        if (::stack.isInitialized) stack.close()
        if (::emoji.isInitialized) emoji.close()
        if (::arabic.isInitialized) arabic.close()
        if (::roboto.isInitialized) roboto.close()
        if (::emojiFace.isInitialized) emojiFace.close()
        if (::arabicFace.isInitialized) arabicFace.close()
        if (::robotoFace.isInitialized) robotoFace.close()
    }

    @Test
    fun bench_shape_fragment_latin_emoji() = runBlocking {
        // Same text as the JVM bench in :harfbuzz-core ShapingBenchmarks
        // so JVM/Android numbers compare apples-to-apples. Logs the run
        // count alongside the timing: a regression in fragmentation
        // should drop runCount.
        val runCount = stack.shapeParagraph(FRAGMENT_PARAGRAPH).runs.size
        log("shape-fragment runCount=$runCount  (text=\"$FRAGMENT_PARAGRAPH\")")
        bench("shape-fragment (Latin+emoji via stack)") {
            stack.shapeParagraph(FRAGMENT_PARAGRAPH)
        }
    }

    @Test
    fun bench_shape_emoji_primary_with_spaces() = runBlocking {
        // Edge case where the "approximate spaces" optimisation should
        // fire if Android's NotoColorEmoji asset lacks an ASCII space
        // glyph: primary=emoji, text contains emoji+space sequences.
        val emojiPrimaryStack = HbFontStack(
            emoji,
            fallbacks = listOf(roboto),
            system = SystemFallback.None,
        )
        val runCount = emojiPrimaryStack.shapeParagraph(EMOJI_SPACE_PARAGRAPH).runs.size
        log("shape-emoji-primary runCount=$runCount  (text=\"$EMOJI_SPACE_PARAGRAPH\")")
        bench("shape-emoji-primary (🙂 🙃 😀)") {
            emojiPrimaryStack.shapeParagraph(EMOJI_SPACE_PARAGRAPH)
        }
        emojiPrimaryStack.close()
    }

    private fun bench(label: String, block: suspend () -> Unit) = runBlocking {
        repeat(WARMUP) { block() }
        val timesNs = LongArray(MEASURE)
        for (i in 0 until MEASURE) {
            val t0 = System.nanoTime()
            block()
            timesNs[i] = System.nanoTime() - t0
        }
        timesNs.sort()
        val min = timesNs.first()
        val median = timesNs[MEASURE / 2]
        val p95 = timesNs[(MEASURE * 95 / 100).coerceAtMost(MEASURE - 1)]
        val max = timesNs.last()
        val unit = if (median > 1_000_000) "ms" else "µs"
        val divisor = if (unit == "ms") 1_000_000.0 else 1_000.0
        val msg = "%-50s min=%7.2f%s  median=%7.2f%s  p95=%7.2f%s  max=%7.2f%s".format(
            label,
            min / divisor, unit,
            median / divisor, unit,
            p95 / divisor, unit,
            max / divisor, unit,
        )
        android.util.Log.i(LOG_TAG, msg)
        println(msg)
    }

    private fun log(msg: String) {
        android.util.Log.i(LOG_TAG, msg)
        println(msg)
    }

    companion object {
        private const val POINT_SIZE = 16f
        private const val WARMUP = 5
        private const val MEASURE = 50
        private const val LOG_TAG = "HbFragBench"
        private const val FRAGMENT_PARAGRAPH =
            "Hello 🙂 world 🙃 byes 😀 again 🙃."
        private const val EMOJI_SPACE_PARAGRAPH = "🙂 🙃 😀"
    }
}
