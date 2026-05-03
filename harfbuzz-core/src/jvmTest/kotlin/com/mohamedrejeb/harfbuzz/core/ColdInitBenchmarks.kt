package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import kotlin.test.Test

/**
 * Cold-path benchmarks. Where [ShapingBenchmarks] tracks steady-state
 * per-call cost (warmed up, repeat-shaped), this suite tracks the
 * first-time costs that the user experiences as a "freeze on app start":
 *
 *  - JNI library extraction + `System.load`
 *  - `HbFace.fromBytes` parse (scales with font size - the killer is
 *    Apple Color Emoji.ttc at 188 MB on macOS)
 *  - `face.toFont(pointSize)` mint
 *  - First shape on a brand-new font
 *  - Full pipeline: bytes → face → font → shape
 *  - Real system-font load via the JVM resolver (the Apple Color Emoji
 *    case is the dominant cause of multi-100ms freezes when an emoji
 *    codepoint first hits a paragraph)
 *
 * Run the same way as [ShapingBenchmarks]:
 *
 * ```
 * ./gradlew :harfbuzz-core:jvmTest -PrunBenchmarks --tests "*ColdInitBenchmarks*"
 * ```
 *
 * Each case picks an iteration count appropriate to its cost - measuring
 * a 188 MB load 200 times would burn 35 GB of I/O for no extra signal.
 *
 * **Page-cache caveat.** We can't reliably evict the OS page cache
 * between iterations without root, so iteration ≥2 reads from RAM
 * rather than disk. The first iteration is the only true "from disk"
 * sample; it's tagged separately in the output so cold-disk vs
 * cold-parse are distinguishable.
 */
class ColdInitBenchmarks {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val robotoFile = File(sampleFonts, "Roboto-Regular.ttf")
    private val arabicFile = File(sampleFonts, "NotoNaskhArabic-Regular.ttf")
    private val notoEmojiFile = File(sampleFonts, "NotoColorEmoji-Regular.ttf")

    /** macOS system emoji font - 188 MB on the host this benchmark targets. */
    private val appleColorEmoji = File("/System/Library/Fonts/Apple Color Emoji.ttc")

    private fun assumeBenchmarksEnabled() {
        Assume.assumeTrue(
            "Run benchmarks with -PrunBenchmarks",
            System.getProperty("runBenchmarks") == "true",
        )
    }

    @Test
    fun `bench 0 native lib first load`() {
        assumeBenchmarksEnabled()
        // ensureLoaded is idempotent. If a prior test in this JVM already
        // loaded it, we measure the no-op fast path (volatile read + early
        // return) - print a banner so the reader doesn't mistake the µs
        // figure for the real one-shot extraction cost.
        val alreadyLoaded = isNativeLibLoaded()
        val t0 = System.nanoTime()
        HarfbuzzNative.ensureLoaded()
        val cost = System.nanoTime() - t0
        val tag = if (alreadyLoaded) "(already loaded - measures no-op fast path)" else "(true first load)"
        println("native-lib-load %s  %.2fms".format(tag, cost / 1_000_000.0))
    }

    @Test
    fun `bench HbFace fromBytes by font size`() {
        assumeBenchmarksEnabled()
        // Pre-read bytes so the timed region only includes the parse +
        // hb_face_create cost, not file I/O. Bytes for the same file are
        // structurally equivalent across iterations - HarfBuzz's parse
        // cost scales with table count, not the raw byte length.
        val cases = listOf(
            "Roboto-Regular     (~170 KB)" to robotoFile.readBytes(),
            "NotoNaskhArabic    (~470 KB)" to arabicFile.readBytes(),
            "NotoColorEmoji-Reg (~1   MB)" to notoEmojiFile.readBytes(),
        )
        for ((label, bytes) in cases) {
            benchN(label, warmup = 5, measure = 30) {
                val face = HbFace.from { bytes(bytes) }
                face.close()
            }
        }
    }

    @Test
    fun `bench face toFont mint`() {
        assumeBenchmarksEnabled()
        val face = HbFace.from { bytes(robotoFile.readBytes()) }
        try {
            benchN("face.toFont (Roboto, 16f)", warmup = 10, measure = 50) {
                runBlocking { face.toFont().close() }
            }
        } finally {
            face.close()
        }
    }

    @Test
    fun `bench first shape on fresh font`() {
        assumeBenchmarksEnabled()
        val bytes = robotoFile.readBytes()
        // Each iteration mints a brand-new face+font so we measure
        // first-shape cost with no warm HarfBuzz internal caches. This
        // is what the user feels on the first paragraph of a new font.
        benchN("first-shape (fresh face+font, Latin 12c)", warmup = 5, measure = 30) {
            runBlocking {
                val face = HbFace.from { bytes(bytes) }
                val font = face.toFont()
                font.shapeParagraph("Hello world", sizePx = POINT_SIZE)
                font.close()
                face.close()
            }
        }
    }

    @Test
    fun `bench full cold pipeline bytes to first shape`() {
        assumeBenchmarksEnabled()
        // bytes are read once (warm page cache); the rest of the work
        // (face creation, font mint, first shape) is fresh every run.
        val bytes = robotoFile.readBytes()
        benchN("cold-pipeline (bytes→face→font→shape)", warmup = 5, measure = 30) {
            runBlocking {
                val face = HbFace.from { bytes(bytes) }
                val font = face.toFont()
                font.shapeParagraph("The quick brown fox", sizePx = POINT_SIZE)
                font.close()
                face.close()
            }
        }
    }

    @Test
    fun `bench HbFace fromBytes Apple Color Emoji 188MB`() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue(
            "Apple Color Emoji.ttc not found - skip on non-macOS hosts",
            appleColorEmoji.exists(),
        )
        // 188 MB face creation. Three iterations only - enough to see
        // page-cache vs in-memory separation without burning 5+ seconds
        // per measurement on a slower SSD.
        val bytes = appleColorEmoji.readBytes()  // page-cache warm after this
        benchN(
            "HbFace.fromBytes (Apple Color Emoji, 188 MB, page-cache warm)",
            warmup = 1,
            measure = 5,
        ) {
            HbFace.from { bytes(bytes) }.close()
        }
    }

    @Test
    fun `bench Apple Color Emoji file readBytes`() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue(
            "Apple Color Emoji.ttc not found - skip on non-macOS hosts",
            appleColorEmoji.exists(),
        )
        // Pure I/O cost. The first iteration includes cold-disk read on
        // a freshly-rebooted system - print it separately. Subsequent
        // iterations are page-cache hits and effectively measure memcpy.
        val timesNs = LongArray(5)
        for (i in timesNs.indices) {
            val t0 = System.nanoTime()
            appleColorEmoji.readBytes()
            timesNs[i] = System.nanoTime() - t0
        }
        println(
            "readBytes(Apple Color Emoji 188MB) iter1=%.1fms  iter2=%.1fms  iter5=%.1fms".format(
                timesNs[0] / 1_000_000.0,
                timesNs[1] / 1_000_000.0,
                timesNs[4] / 1_000_000.0,
            ),
        )
    }

    @Test
    fun `bench resolver cold load Apple Color Emoji`() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue(
            "Apple Color Emoji.ttc not found - skip on non-macOS hosts",
            appleColorEmoji.exists(),
        )
        // End-to-end: a fresh resolver looks up an emoji codepoint with
        // Apple Color Emoji as the only candidate. This includes the file
        // read, HbFace parse, face metadata probe, and glyphIdForCodepoint.
        // Each iteration creates a fresh resolver so no cache helps it.
        // The user-visible effect is the freeze when the first emoji in
        // a paragraph triggers system-font fallback.
        val timesNs = LongArray(5)
        for (i in timesNs.indices) {
            val t0 = System.nanoTime()
            runBlocking {
                val resolver = JvmSystemFontResolver(
                    SystemFallback.Match(preferColorEmoji = true),
                    candidatePaths = listOf(appleColorEmoji),
                )
                try {
                    resolver.fontFor(codepoint = 0x1F600)
                } finally {
                    resolver.close()
                }
            }
            timesNs[i] = System.nanoTime() - t0
        }
        // No median - five points isn't enough. Surface min, p50, max.
        timesNs.sort()
        println(
            "resolver cold-load (Apple Color Emoji) min=%.1fms  median=%.1fms  max=%.1fms".format(
                timesNs.first() / 1_000_000.0,
                timesNs[2] / 1_000_000.0,
                timesNs.last() / 1_000_000.0,
            ),
        )
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
        // Auto-pick µs vs ms - these benchmarks span ~µs (toFont) to
        // ~hundreds of ms (188 MB face parse). Plain µs would render the
        // 188 MB number as "470000.0µs", which is unreadable.
        val unit = if (median > 1_000_000) "ms" else "µs"
        val divisor = if (unit == "ms") 1_000_000.0 else 1_000.0
        println(
            "%-50s min=%7.1f%s  median=%7.1f%s  p95=%7.1f%s  max=%7.1f%s".format(
                label,
                min / divisor, unit,
                median / divisor, unit,
                p95 / divisor, unit,
                max / divisor, unit,
            ),
        )
    }

    /**
     * Best-effort detection of whether `libharfbuzz_jni` is already loaded
     * in this JVM. We try a cheap native call; if it succeeds, the lib is
     * loaded; if it throws `UnsatisfiedLinkError`, it isn't yet. We
     * deliberately don't call `ensureLoaded()` here - the caller is about
     * to time that call and we'd defeat the measurement.
     */
    private fun isNativeLibLoaded(): Boolean = runCatching {
        HarfbuzzNative.nativeVersion()
    }.isSuccess

    companion object {
        private const val POINT_SIZE = 16f
    }
}
