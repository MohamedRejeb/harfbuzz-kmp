package com.mohamedrejeb.harfbuzz.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device counterpart of `HbFontLoadBenchmark`. Compares the cost of
 * loading a face via `bytesProvider` (read into JVM heap →
 * `hb_blob_create` → parse) vs. the mmap path
 * (`hb_blob_create_from_file_or_fail`).
 *
 * Asset files live inside the APK and can't be mmap'd directly via a
 * file path, so the benchmark copies the asset to the app's internal
 * cache dir on setup and exercises both loading paths against that
 * extracted file. The `bytesProvider` case still hits `File.readBytes`,
 * making the comparison representative of what an app pays once it's
 * extracted assets to writable storage at install time.
 */
@RunWith(AndroidJUnit4::class)
class AndroidHbFontLoadBenchmark {

    private lateinit var robotoFile: File
    private lateinit var emojiFile: File

    private fun assumeBenchmarksEnabled() {
        val arg = InstrumentationRegistry.getArguments().getString("runBenchmarks")
        Assume.assumeTrue("Run with -PrunBenchmarks", arg == "true")
    }

    @Before
    fun setUp() {
        assumeBenchmarksEnabled()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val cacheDir = ctx.cacheDir
        robotoFile = extractAsset(cacheDir, "fonts/Roboto-Regular.ttf", "Roboto-Regular.ttf")
        emojiFile = extractAsset(cacheDir, "fonts/NotoColorEmoji-Regular.ttf", "NotoColorEmoji-Regular.ttf")
    }

    @Test
    fun bench_load_Roboto_bytes_vs_path() {
        // Page-cache warmup so neither path eats a cold-disk first read.
        robotoFile.readBytes()
        val cachedBytes = robotoFile.readBytes()

        benchN("load-bytes (Roboto, ~159 KB, fromBytes + close)", warmup = 5, measure = 50) {
            runBlocking {
                val face = HbFace.fromBytes(cachedBytes)
                face.close()
            }
        }
        benchN("load-bytes-fresh (readBytes + fromBytes + close)", warmup = 5, measure = 50) {
            runBlocking {
                val bytes = robotoFile.readBytes()
                val face = HbFace.fromBytes(bytes)
                face.close()
            }
        }
        val path = robotoFile.absolutePath
        benchN("load-path (Roboto, mmap + parse + close)", warmup = 5, measure = 50) {
            val face = HbFace.from { path(path) }
            face.close()
        }
    }

    @Test
    fun bench_load_NotoColorEmoji_bytes_vs_path() {
        // 25 MB font - exercises malloc+memcpy scaling on the bytes path.
        emojiFile.readBytes()
        val cachedBytes = emojiFile.readBytes()

        benchN("load-bytes (NotoColorEmoji, ~25 MB, fromBytes + close)", warmup = 2, measure = 10) {
            runBlocking {
                val face = HbFace.fromBytes(cachedBytes)
                face.close()
            }
        }
        benchN(
            "load-bytes-fresh (readBytes + fromBytes + close, NotoColorEmoji)",
            warmup = 2,
            measure = 10,
        ) {
            runBlocking {
                val bytes = emojiFile.readBytes()
                val face = HbFace.fromBytes(bytes)
                face.close()
            }
        }
        val path = emojiFile.absolutePath
        benchN("load-path (NotoColorEmoji, mmap + parse + close)", warmup = 2, measure = 10) {
            val face = HbFace.from { path(path) }
            face.close()
        }
    }

    private fun extractAsset(cacheDir: File, assetPath: String, outName: String): File {
        val out = File(cacheDir, outName)
        if (!out.exists() || out.length() == 0L) {
            val ctx = InstrumentationRegistry.getInstrumentation().context
            ctx.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    private fun benchN(label: String, warmup: Int, measure: Int, block: () -> Unit) {
        repeat(warmup) { block() }
        val timesNs = LongArray(measure)
        for (i in 0 until measure) {
            val t0 = System.nanoTime()
            block()
            timesNs[i] = System.nanoTime() - t0
        }
        timesNs.sort()
        val min = timesNs.first()
        val median = timesNs[measure / 2]
        val p95 = timesNs[(measure * 95 / 100).coerceAtMost(measure - 1)]
        val max = timesNs.last()
        val unit = if (median > 1_000_000) "ms" else "µs"
        val divisor = if (unit == "ms") 1_000_000.0 else 1_000.0
        val msg = "%-58s min=%7.2f%s  median=%7.2f%s  p95=%7.2f%s  max=%7.2f%s".format(
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
        private const val LOG_TAG = "HbLoadBench"
    }
}
