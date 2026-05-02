package com.mohamedrejeb.harfbuzz.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device counterpart of the JVM HbFaceCacheBenchmark. Tracks
 * [HbFaceCache] perf on real Android hardware where face parsing
 * crosses JNI and `hb_face_create_for_tables` runs against system-Skia
 * rather than skiko.
 *
 * Run with:
 * ```
 * ./gradlew :harfbuzz-compose:connectedDebugAndroidTest -PrunBenchmarks
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AndroidHbFaceCacheBenchmark {

    private lateinit var bytes: ByteArray

    private fun assumeBenchmarksEnabled() {
        val arg = InstrumentationRegistry.getArguments().getString("runBenchmarks")
        Assume.assumeTrue(
            "Run with -PrunBenchmarks",
            arg == "true",
        )
    }

    @Before
    fun setUp() {
        assumeBenchmarksEnabled()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        runBlocking { harfBuzzInit() }
        bytes = ctx.assets.open("fonts/Roboto-Regular.ttf").use { it.readBytes() }
        clearHbFaceCacheForTest()
    }

    @Test
    fun bench_fromBytes_re_parses_every_call() = runBlocking {
        bench("fromBytes-direct (Roboto, parse + close)") {
            val face = HbFace.fromBytes(bytes)
            face.close()
        }
    }

    @Test
    fun bench_HbFaceCache_get_hits_cache_after_first_call() = runBlocking {
        bench("HbFaceCache-get (Roboto, cached)") {
            HbFaceCache.get(bytes)
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
        private const val WARMUP = 5
        private const val MEASURE = 50
        private const val LOG_TAG = "HbFaceBench"
    }
}
