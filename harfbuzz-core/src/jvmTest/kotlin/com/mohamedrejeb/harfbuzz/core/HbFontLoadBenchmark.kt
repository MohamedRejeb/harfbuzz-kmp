package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import kotlin.test.Test

/**
 * Compares the cost of loading a font face via `bytesProvider` (the
 * legacy path: `File.readBytes()` → JVM heap copy → `hb_blob_create`
 * malloc+memcpy → parse) vs. the mmap path (`HbFaceSource.path(...)`
 * which routes through `hb_blob_create_from_file_or_fail` and lets
 * the kernel page font bytes in on demand).
 *
 * Run:
 * ```
 * ./gradlew :harfbuzz-core:jvmTest -PrunBenchmarks --tests "*HbFontLoadBenchmark*"
 * ```
 */
class HbFontLoadBenchmark {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val robotoFile = File(sampleFonts, "Roboto-Regular.ttf")
    private val notoEmojiFile = File(sampleFonts, "NotoColorEmoji-Regular.ttf")

    private fun assumeBenchmarksEnabled() {
        Assume.assumeTrue(
            "Run benchmarks with -PrunBenchmarks",
            System.getProperty("runBenchmarks") == "true",
        )
    }

    @Test
    fun `bench load Roboto via bytes vs path`() {
        assumeBenchmarksEnabled()
        require(robotoFile.exists()) { "Roboto font not found at ${robotoFile.absolutePath}" }

        // Pre-warm the OS page cache so neither path eats a cold-disk
        // first-iteration spike. We're tracking the user-space delta
        // between malloc+memcpy and mmap, not disk read latency.
        robotoFile.readBytes()

        // Pre-read once to avoid timing the readBytes vs internal hop;
        // for the bytes case we hold a single ByteArray and use it on
        // every iteration so the measurement is purely "create face
        // from in-memory bytes" - the most charitable interpretation.
        val cachedBytes = robotoFile.readBytes()

        benchN("load-bytes (Roboto, ~159 KB, fromBytes + close)", warmup = 5, measure = 50) {
            runBlocking {
                val face = HbFace.fromBytes(cachedBytes)
                face.close()
            }
        }

        // The realistic bytes path on every loop iteration: read +
        // parse + close. Compose-resources users hit this every cold
        // load because `Res.readBytes(...)` returns a fresh ByteArray.
        benchN("load-bytes-fresh (readBytes + fromBytes + close)", warmup = 5, measure = 50) {
            runBlocking {
                val bytes = robotoFile.readBytes()
                val face = HbFace.fromBytes(bytes)
                face.close()
            }
        }

        // mmap path: each iteration creates a fresh blob via
        // hb_blob_create_from_file_or_fail and parses the font. The
        // kernel keeps the file's pages in the page cache, so the
        // cost is the syscall + parse - no userspace memcpy.
        val path = robotoFile.absolutePath
        benchN("load-path (Roboto, mmap + parse + close)", warmup = 5, measure = 50) {
            val face = HbFace.from { path(path) }
            face.close()
        }
    }

    @Test
    fun `bench load NotoColorEmoji via bytes vs path`() {
        assumeBenchmarksEnabled()
        require(notoEmojiFile.exists()) { "NotoColorEmoji font not found at ${notoEmojiFile.absolutePath}" }

        // Larger font (~25 MB) - exercises the malloc+memcpy cost on
        // bytes loading, which scales with face size; mmap is largely
        // size-insensitive because nothing is eagerly copied.
        notoEmojiFile.readBytes()
        val cachedBytes = notoEmojiFile.readBytes()

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
                val bytes = notoEmojiFile.readBytes()
                val face = HbFace.fromBytes(bytes)
                face.close()
            }
        }

        val path = notoEmojiFile.absolutePath
        benchN("load-path (NotoColorEmoji, mmap + parse + close)", warmup = 2, measure = 10) {
            val face = HbFace.from { path(path) }
            face.close()
        }
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
        println(
            "%-58s min=%7.2f%s  median=%7.2f%s  p95=%7.2f%s  max=%7.2f%s".format(
                label,
                min / divisor, unit,
                median / divisor, unit,
                p95 / divisor, unit,
                max / divisor, unit,
            ),
        )
    }
}
