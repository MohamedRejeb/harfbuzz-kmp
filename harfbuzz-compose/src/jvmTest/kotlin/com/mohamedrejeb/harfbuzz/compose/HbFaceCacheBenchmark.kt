package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tracks the perf delta of [HbFaceCache]. Direct [HbFace.fromBytes]
 * re-parses the face on every call: that's the baseline. [HbFaceCache.get]
 * returns the parsed face from a process-wide map on the second-and-
 * subsequent call.
 *
 * Run:
 * ```
 * ./gradlew :harfbuzz-compose:jvmTest -PrunBenchmarks --tests "*HbFaceCacheBenchmark*"
 * ```
 */
class HbFaceCacheBenchmark {

    private lateinit var bytes: ByteArray

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "Run benchmarks with -PrunBenchmarks",
            System.getProperty("runBenchmarks") == "true",
        )
        runBlocking { harfBuzzInit() }
        bytes = TestFonts.robotoRegular()
        clearHbFaceCacheForTest()
    }

    @Test
    fun `bench fromBytes re-parses every call`() = runBlocking {
        // Baseline: each iteration parses the face from scratch and
        // closes it. Mirrors what the pre-J path did inside the
        // rememberHbFont LaunchedEffect on every recomposition that
        // changed sizePx.
        bench("fromBytes-direct (Roboto, parse + close)") {
            val face = HbFace.fromBytes(bytes)
            face.close()
        }
    }

    @Test
    fun `bench HbFaceCache get hits cache after first call`() = runBlocking {
        // After warmup populates the cache, the timed region measures
        // pure map-lookup cost. The first warmup iteration parses,
        // every subsequent iteration is a hit.
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
        println(
            "%-50s min=%7.2f%s  median=%7.2f%s  p95=%7.2f%s  max=%7.2f%s".format(
                label,
                min / divisor, unit,
                median / divisor, unit,
                p95 / divisor, unit,
                max / divisor, unit,
            ),
        )
    }

    companion object {
        private const val WARMUP = 5
        private const val MEASURE = 50
    }
}
