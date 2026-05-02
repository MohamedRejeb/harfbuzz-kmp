package com.mohamedrejeb.harfbuzz.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device counterpart of `ShapingBenchmarks.bench shape Latin paragraph
 * via boring stack fast path`. Validates the boring fast path on real
 * Android hardware: a Latin paragraph routed through a single-font
 * [HbFontStack] skips the BiDi resolver and cluster walk.
 *
 * Run with:
 * ```
 * ./gradlew :harfbuzz-compose:connectedDebugAndroidTest -PrunBenchmarks
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AndroidBoringShapeBenchmark {

    private lateinit var robotoFace: HbFace
    private lateinit var roboto: HbFont
    private lateinit var boringStack: HbFontStack

    private fun assumeBenchmarksEnabled() {
        val arg = InstrumentationRegistry.getArguments().getString("runBenchmarks")
        Assume.assumeTrue("Run with -PrunBenchmarks", arg == "true")
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
            roboto = robotoFace.toFont(POINT_SIZE)
        }
        // No fallbacks → exercises the boring fast path when text is also
        // boring. Keeping the default `system = SystemFallback.Match()` so
        // the bench mirrors the realistic Compose `HbFontStack(font)` shape
        // - F is supposed to fire there too despite a system resolver
        // being present.
        boringStack = HbFontStack(roboto)
    }

    @After
    fun tearDown() {
        if (::boringStack.isInitialized) boringStack.close()
        if (::roboto.isInitialized) roboto.close()
        if (::robotoFace.isInitialized) robotoFace.close()
    }

    @Test
    fun bench_shape_latin_via_boring_stack() = runBlocking {
        bench("shape-Latin-stack-boring (80c, fallbacks=[], boring text)") {
            boringStack.shapeParagraph(LATIN_PARAGRAPH)
        }
    }

    @Test
    fun bench_shape_latin_direct_font() = runBlocking {
        // Reference point - direct font.shapeParagraph hits the BiDi
        // resolver and the in-buffer guess_segment_properties path. Letting
        // us see what fraction of the boring-stack win comes from skipping
        // BiDi vs the cluster-walk machinery.
        bench("shape-Latin-direct (80c, BiDi+1 shape)") {
            roboto.shapeParagraph(LATIN_PARAGRAPH)
        }
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

    companion object {
        private const val POINT_SIZE = 16f
        private const val WARMUP = 50
        private const val MEASURE = 200
        private const val LOG_TAG = "HbBoringBench"
        private const val LATIN_PARAGRAPH =
            "The quick brown fox jumps over the lazy dog while five hex wizards run the test."
    }
}
