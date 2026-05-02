package com.mohamedrejeb.harfbuzz.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.SystemFallback
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device counterpart of the JVM `MeasuredTextCacheBenchmark`. Tracks
 * the perf delta of [MeasuredTextCache] on a real Android device, where
 * Compose Path materialisation, GC pauses, and JNI hops have different
 * costs than on the JVM.
 *
 * Default test runs **skip** every benchmark via [Assume.assumeTrue]
 * (the `runBenchmarks` instrumentation argument is unset). To run on a
 * connected device:
 *
 * ```
 * ./gradlew :harfbuzz-compose:connectedDebugAndroidTest -PrunBenchmarks
 * ```
 *
 * Numbers are wall-clock and include coroutine dispatch overhead. Use
 * for *delta tracking* across optimisation work, not absolute throughput.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMeasuredTextCacheBenchmark {

    private lateinit var face: HbFace
    private lateinit var font: HbFont
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
            // Bundled in src/androidInstrumentedTest/assets/fonts/.
            // Reading via the Instrumentation context (not target) since the
            // assets ship with the test APK, not the library APK.
            val bytes = ctx.assets.open("fonts/Roboto-Regular.ttf").use { it.readBytes() }
            face = HbFace.fromBytes(bytes)
            font = face.toFont(POINT_SIZE)
        }
        // Hermetic stack - no platform resolver lookups bleed into the
        // timed region. The point of this bench is to exercise the
        // MeasuredTextCache path, not the system-fallback machinery.
        stack = HbFontStack(font, system = SystemFallback.None)
        clearMeasuredTextCacheForTest()
    }

    @After
    fun tearDown() {
        if (::stack.isInitialized) stack.close()
        if (::font.isInitialized) font.close()
        if (::face.isInitialized) face.close()
    }

    @Test
    fun bench_buildMeasuredText_same_text_repeated() = runBlocking {
        // After warmup the cache (when present) is populated; the timed
        // region therefore measures cache-hit cost. Without the cache,
        // every call re-runs the full shape+snapshot pipeline.
        bench("buildMeasured-repeat (Latin 80c, same key)") {
            buildMeasuredText(
                text = LATIN_PARAGRAPH,
                fontStack = stack,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
        }
    }

    @Test
    fun bench_buildMeasuredText_distinct_text_every_call() = runBlocking {
        // Negative control: every call has a fresh key, so the cache
        // can never hit. Confirms the per-call lookup overhead on miss
        // doesn't dominate.
        var counter = 0
        bench("buildMeasured-distinct (Latin, fresh key per call)") {
            buildMeasuredText(
                text = "$LATIN_PARAGRAPH ${counter++}",
                fontStack = stack,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
        }
    }

    @Test
    fun bench_buildMeasuredText_fresh_stack_each_call() = runBlocking {
        // The recomposition case: caller drops `remember(font) { HbFontStack(font) }`
        // and rebuilds the stack on every pass. With the cache keyed on
        // (face, pointSize) per font, this case still hits - that's the
        // whole point.
        bench("buildMeasured-stackChurn (same face, new stack)") {
            val freshStack = HbFontStack(font, system = SystemFallback.None)
            try {
                buildMeasuredText(
                    text = LATIN_PARAGRAPH,
                    fontStack = freshStack,
                    features = emptyList(),
                    direction = HbDirection.AUTO,
                    language = HbLanguage.AUTO,
                )
            } finally {
                freshStack.close()
            }
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
        // Both Logcat and stdout - matches AndroidColdInitBenchmarks so
        // results are visible whether the user reads `adb logcat` or the
        // gradle test report.
        android.util.Log.i(LOG_TAG, msg)
        println(msg)
    }

    companion object {
        private const val POINT_SIZE = 16f
        private const val WARMUP = 10
        private const val MEASURE = 100
        private const val LOG_TAG = "HbCacheBench"
        private const val LATIN_PARAGRAPH =
            "The quick brown fox jumps over the lazy dog while five hex wizards run the test."
    }
}
