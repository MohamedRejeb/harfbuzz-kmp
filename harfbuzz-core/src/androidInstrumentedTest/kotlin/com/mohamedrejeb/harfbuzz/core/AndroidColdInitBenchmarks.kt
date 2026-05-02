package com.mohamedrejeb.harfbuzz.core

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-side cold-init benchmarks. Mirror of [ColdInitBenchmarks] /
 * [ShapingBenchmarks] but exercises the *real* Android system font path
 * via `SystemFonts.getAvailableFonts()` and the bundled emoji2 asset.
 *
 * This is what the user actually feels as a "freeze on first paragraph":
 * JVM benchmarks can't reproduce it because the candidate path list
 * is hand-curated there. On Android, we walk every system font the OS
 * registers (a few hundred entries on a typical device) and lazy-load
 * the first cover.
 *
 * Default test runs **skip** every benchmark via [Assume.assumeTrue]
 * (the `runBenchmarks` instrumentation argument is unset). To run on a
 * connected device or emulator:
 *
 * ```
 * ./gradlew :harfbuzz-core:connectedDebugAndroidTest -PrunBenchmarks
 * ```
 *
 * Numbers are wall-clock and include coroutine dispatch overhead. Use
 * for *delta tracking* across resolver-ordering optimisations, not as
 * absolute throughput claims.
 *
 * **API gate.** [AndroidSystemFontResolver] is API 29+; the resolver
 * benchmarks skip on older devices. [AndroidSystemFontResolver]'s
 * filename is API 29+ via `SystemFonts.getAvailableFonts()`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidColdInitBenchmarks {

    private fun assumeBenchmarksEnabled() {
        val arg = InstrumentationRegistry.getArguments().getString("runBenchmarks")
        Assume.assumeTrue(
            "Run with -PrunBenchmarks (e.g. " +
                "'./gradlew :harfbuzz-core:connectedDebugAndroidTest -PrunBenchmarks')",
            arg == "true",
        )
    }

    @Before
    fun setUp() {
        // The instrumented test runner doesn't fire androidx.startup
        // Initializers from the *target* APK by default, so seed the
        // app-context handle by hand. Without this, the AndroidSystem-
        // FontResolver can't find the emoji2 bundled bytes.
        HarfBuzzAppContext.set(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun bench_0_native_lib_first_load() {
        assumeBenchmarksEnabled()
        val alreadyLoaded = isNativeLibLoaded()
        val t0 = System.nanoTime()
        HarfbuzzNative.ensureLoaded()
        val cost = System.nanoTime() - t0
        val tag = if (alreadyLoaded) "(already loaded - measures fast path)" else "(true first load)"
        log("native-lib-load $tag", cost)
    }

    @Test
    fun bench_SystemFonts_enumeration() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("SystemFonts.getAvailableFonts() requires API 29+", Build.VERSION.SDK_INT >= 29)

        // Cost of enumerating every system font the platform exposes.
        // No I/O - just metadata. Stable across calls.
        benchN("SystemFonts.getAvailableFonts()", warmup = 3, measure = 10) {
            android.graphics.fonts.SystemFonts.getAvailableFonts()
        }
    }

    @Test
    fun bench_resolver_construction_cold() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // Each iteration creates a fresh resolver: walks SystemFonts,
        // builds PendingCandidate metadata, pre-ranks, looks up emoji2.
        // No fontFor calls yet - pure construction cost.
        benchN("createSystemFontResolver(cold)", warmup = 2, measure = 10) {
            createSystemFontResolver(SystemFallback.Match())?.close()
        }
    }

    @Test
    fun bench_first_arabic_codepoint_cold() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // U+0644 ARABIC LETTER LAM. Triggers the first cover, typically
        // NotoNaskhArabic or NotoSansArabic (~500 KB). The cost includes
        // walking pre-ranked pending until cover is found, which is
        // usually ~1-3 candidates on a typical device.
        benchN("first-fontFor(0x0644 Arabic, cold resolver)", warmup = 0, measure = 5) {
            runBlocking {
                val resolver = createSystemFontResolver(SystemFallback.Match())!!
                try {
                    resolver.fontFor(codepoint = 0x0644, pointSize = POINT_SIZE)
                } finally {
                    resolver.close()
                }
            }
        }
    }

    @Test
    fun bench_first_emoji_codepoint_cold() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // U+1F600 GRINNING FACE. The dominant cold-load cost on Android
        // because the platform emoji font (NotoColorEmoji.ttf) is the
        // largest single TTF the resolver typically opens. Measures
        // both file I/O and color-table parse.
        benchN("first-fontFor(0x1F600 emoji, cold resolver)", warmup = 0, measure = 5) {
            runBlocking {
                val resolver = createSystemFontResolver(
                    SystemFallback.Match(preferColorEmoji = true),
                )!!
                try {
                    resolver.fontFor(codepoint = 0x1F600, pointSize = POINT_SIZE)
                } finally {
                    resolver.close()
                }
            }
        }
    }

    @Test
    fun bench_first_cjk_codepoint_cold() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // U+4E2D 中. NotoSansCJK.ttc is ~30 MB on stock Android: heavy
        // disk read + parse. If your screen first paints any CJK, this
        // is the freeze you feel.
        benchN("first-fontFor(0x4E2D CJK, cold resolver)", warmup = 0, measure = 5) {
            runBlocking {
                val resolver = createSystemFontResolver(SystemFallback.Match())!!
                try {
                    resolver.fontFor(codepoint = 0x4E2D, pointSize = POINT_SIZE)
                } finally {
                    resolver.close()
                }
            }
        }
    }

    @Test
    fun bench_full_first_render_mixed_paragraph() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // The full user-felt path on first paint: stack with system
        // fallback, shape a paragraph that needs Latin + Arabic +
        // emoji + CJK glyphs. On cold first run, this loads ~3-4 system
        // fonts back-to-back. Subsequent runs are warm.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Roboto ships with the platform - find any platform font we
        // can use as the primary. Falls back to the first available
        // SystemFonts entry.
        val primaryBytes = readPlatformLatinFontBytes(ctx) ?: run {
            log("(no platform Latin font available - skipping)", 0L)
            return
        }
        // Clear the shared resolver cache between iterations so each one
        // hits the *true* cold path. Without this, the benchmark inherits
        // whatever fonts earlier tests warmed and measures steady-state
        // shape cost instead of the user-visible first-paint freeze.
        benchN("full-first-render(cold) Latin+Arabic+Emoji+CJK", warmup = 0, measure = 5) {
            clearSharedSystemResolverCacheForTest()
            runBlocking {
                val face = HbFace.fromBytes(primaryBytes)
                val font = face.toFont(POINT_SIZE)
                val stack = HbFontStack(font, system = SystemFallback.Match(preferColorEmoji = true))
                try {
                    stack.shapeParagraph("Hello مرحبا 你好 😀")
                } finally {
                    stack.close()
                    font.close()
                    face.close()
                }
            }
        }
    }

    @Test
    fun bench_prewarm_cost() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // How long does prewarmSystemFallback take to actually do its
        // work? This is the cost we're paying *up front* to skip the
        // cold-load freeze later. On the realistic prewarm strategy
        // (kick off after first-frame paint), this work runs on a
        // background coroutine where the user can't see it - but it
        // still has to fit into "before the user types Arabic".
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val primaryBytes = readPlatformLatinFontBytes(ctx) ?: run {
            log("(no platform Latin font available - skipping)", 0L)
            return
        }
        benchN("prewarmSystemFallback() cold", warmup = 0, measure = 3) {
            clearSharedSystemResolverCacheForTest()
            runBlocking {
                val face = HbFace.fromBytes(primaryBytes)
                val font = face.toFont(POINT_SIZE)
                val stack = HbFontStack(font, system = SystemFallback.Match(preferColorEmoji = true))
                try {
                    stack.prewarmSystemFallback()
                } finally {
                    stack.close()
                    font.close()
                    face.close()
                }
            }
        }
    }

    @Test
    fun bench_first_render_after_prewarm() {
        assumeBenchmarksEnabled()
        Assume.assumeTrue("Requires API 29+", Build.VERSION.SDK_INT >= 29)

        // The realistic "did the prewarm help?" measurement. Setup
        // (outside the timed region) clears the shared resolver cache
        // and runs prewarmSystemFallback once - simulating what would
        // happen on a background coroutine after first frame paint.
        // Each timed iteration then measures the user-visible
        // first-paragraph shape. Compare to the
        // bench_full_first_render_mixed_paragraph p95 (which is cold)
        // - the delta is the freeze the prewarm removes.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val primaryBytes = readPlatformLatinFontBytes(ctx) ?: run {
            log("(no platform Latin font available - skipping)", 0L)
            return
        }
        runBlocking {
            clearSharedSystemResolverCacheForTest()
            val face = HbFace.fromBytes(primaryBytes)
            val font = face.toFont(POINT_SIZE)
            val stack = HbFontStack(font, system = SystemFallback.Match(preferColorEmoji = true))
            try {
                stack.prewarmSystemFallback()
            } finally {
                stack.close()
                font.close()
                face.close()
            }
        }
        benchN("first-render(prewarmed) Latin+Arabic+Emoji+CJK", warmup = 0, measure = 5) {
            runBlocking {
                val face = HbFace.fromBytes(primaryBytes)
                val font = face.toFont(POINT_SIZE)
                val stack = HbFontStack(font, system = SystemFallback.Match(preferColorEmoji = true))
                try {
                    stack.shapeParagraph("Hello مرحبا 你好 😀")
                } finally {
                    stack.close()
                    font.close()
                    face.close()
                }
            }
        }
    }

    private fun readPlatformLatinFontBytes(ctx: android.content.Context): ByteArray? {
        if (Build.VERSION.SDK_INT < 29) return null
        // Pick the first font whose path looks like a regular Latin face.
        // We don't need a perfect match - any Roboto/Sans-Serif weight
        // is good enough for the primary, the system fallback walks
        // everything else as needed.
        val fonts = android.graphics.fonts.SystemFonts.getAvailableFonts()
        val candidate = fonts.firstOrNull { font ->
            val name = font.file?.nameWithoutExtension?.lowercase() ?: return@firstOrNull false
            "roboto" in name || "sans" in name
        } ?: fonts.firstOrNull()
        return runCatching { candidate?.file?.readBytes() }.getOrNull()
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
        val unit = if (timesNs[measure / 2] > 1_000_000) "ms" else "µs"
        val divisor = if (unit == "ms") 1_000_000.0 else 1_000.0
        val msg = "%-50s min=%7.1f%s  median=%7.1f%s  p95=%7.1f%s  max=%7.1f%s".format(
            label,
            timesNs.first() / divisor, unit,
            timesNs[measure / 2] / divisor, unit,
            timesNs[(measure * 95 / 100).coerceAtMost(measure - 1)] / divisor, unit,
            timesNs.last() / divisor, unit,
        )
        // Logcat AND test stdout, so the number is visible whether the
        // user reads `adb logcat` or the connectedAndroidTest task output.
        android.util.Log.i(LOG_TAG, msg)
        println(msg)
    }

    private fun log(label: String, ns: Long) {
        val unit = if (ns > 1_000_000) "ms" else "µs"
        val divisor = if (unit == "ms") 1_000_000.0 else 1_000.0
        val msg = "%-50s %.2f%s".format(label, ns / divisor, unit)
        android.util.Log.i(LOG_TAG, msg)
        println(msg)
    }

    /**
     * Best-effort detection of whether `libharfbuzz_jni` is already
     * loaded in this process. We try a cheap native call; success means
     * loaded, `UnsatisfiedLinkError` means it isn't yet. Mirrors the
     * sibling helper in [ColdInitBenchmarks].
     */
    private fun isNativeLibLoaded(): Boolean = runCatching {
        HarfbuzzNative.nativeVersion()
    }.isSuccess

    companion object {
        private const val POINT_SIZE = 16f
        private const val LOG_TAG = "HbColdBench"
    }
}
