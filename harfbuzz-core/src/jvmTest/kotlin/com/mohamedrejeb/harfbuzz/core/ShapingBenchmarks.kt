package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Hand-rolled benchmark suite for the JVM shaping path. Each case runs
 * a configurable number of warmup + measurement iterations and prints
 * `min / median / p95 / max` in microseconds.
 *
 * Default test runs **skip** every benchmark (via [Assume.assumeTrue])
 * so CI doesn't waste minutes on micro-bench noise. Run them manually:
 *
 * ```
 * ./gradlew :harfbuzz-core:jvmTest -PrunBenchmarks --tests "*ShapingBenchmarks*"
 * ```
 *
 * The numbers are wall-clock and include coroutine dispatch overhead -
 * they're meant for tracking *deltas* across optimisation work, not
 * absolute throughput. For absolute numbers use a real harness (JMH
 * or kotlinx-benchmark); the cost there is plumbing, not signal.
 */
class ShapingBenchmarks {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val robotoFile = File(sampleFonts, "Roboto-Regular.ttf")
    private val arabicFile = File(sampleFonts, "NotoNaskhArabic-Regular.ttf")
    private val emojiFile = File(sampleFonts, "NotoColorEmoji-Regular.ttf")

    private lateinit var roboto: HbFont
    private lateinit var arabic: HbFont
    private lateinit var emoji: HbFont
    private lateinit var stack: HbFontStack
    private lateinit var boringStack: HbFontStack

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "Run benchmarks with -PrunBenchmarks (e.g. " +
                "'./gradlew :harfbuzz-core:jvmTest -PrunBenchmarks --tests \"*ShapingBenchmarks*\"')",
            System.getProperty("runBenchmarks") == "true",
        )
        require(robotoFile.exists()) { "Missing $robotoFile - run from repo root." }
        runBlocking {
            roboto = HbFace.fromBytes(robotoFile.readBytes()).toFont()
            arabic = HbFace.fromBytes(arabicFile.readBytes()).toFont()
            emoji = HbFace.fromBytes(emojiFile.readBytes()).toFont()
        }
        stack = HbFontStack(roboto, fallbacks = listOf(arabic, emoji))
        // No fallbacks → exercises the boring fast path when text is also
        // boring. Default `system = SystemFallback.Match()` is preserved so
        // this matches what `HbFontStack(font)` produces in real Compose
        // apps (the predicate intentionally fires there too).
        boringStack = HbFontStack(roboto)
    }

    @AfterTest
    fun tearDown() {
        if (::boringStack.isInitialized) boringStack.close()
        if (::stack.isInitialized) stack.close()
        if (::emoji.isInitialized) emoji.close()
        if (::arabic.isInitialized) arabic.close()
        if (::roboto.isInitialized) roboto.close()
    }

    @Test
    fun `bench identity int array alloc large paragraph length`() = runBlocking {
        // Two `IntArray(n) { it }` allocations per paragraph become a
        // map lookup once warmed. Demonstrates the per-paragraph
        // allocation savings on long text where the IntArrays are non-
        // trivial (~10 KB each at n=2500).
        val length = 2500
        bench("identity-IntArray-alloc raw (n=$length)") {
            IntArray(length) { it } // baseline allocation
        }
        // Warm the cache so the timed loop measures the steady-state hit.
        identityIntArray(length)
        bench("identity-IntArray cached (n=$length)") {
            identityIntArray(length)
        }
    }

    @Test
    fun `bench bidi resolve Latin Arabic mixed`() = runBlocking {
        // BidiResolver.resolve() routes through `java.text.Bidi` on
        // JVM/Android via `resolveBidiPlatform`; this benches the entry
        // point so the platform delegation is part of the measurement.
        val resolver = BidiResolver()
        bench("bidi-resolve Latin (80c)") {
            resolver.resolve(LATIN_PARAGRAPH, HbDirection.LTR)
        }
        bench("bidi-resolve Arabic (38c)") {
            resolver.resolve(ARABIC_PARAGRAPH, HbDirection.AUTO)
        }
        bench("bidi-resolve mixed (Latin+Arabic+CJK+emoji)") {
            resolver.resolve(MIXED_PARAGRAPH, HbDirection.AUTO)
        }
    }

    @Test
    fun `bench shape Latin paragraph via boring stack fast path`() = runBlocking {
        // Same input as `bench shape Latin paragraph` but routed through a
        // single-font `HbFontStack`: exercises the boring fast path
        // (skips BiDi resolver + cluster walk) instead of the direct
        // `font.shapeParagraph` call that already inlines BiDi.
        bench("shape-Latin-stack-boring (80c, fallbacks=[], boring text)") {
            boringStack.shapeParagraph(LATIN_PARAGRAPH, sizePx = POINT_SIZE)
        }
    }

    @Test
    fun `bench shape Latin paragraph`() = runBlocking {
        bench("shape-Latin (80c, warm)") {
            roboto.shapeParagraph(LATIN_PARAGRAPH, sizePx = POINT_SIZE)
        }
    }

    @Test
    fun `bench shape Arabic paragraph`() = runBlocking {
        bench("shape-Arabic (38c, warm)") {
            arabic.shapeParagraph(ARABIC_PARAGRAPH, sizePx = POINT_SIZE)
        }
    }

    @Test
    fun `bench shape mixed Latin Arabic emoji via stack`() = runBlocking {
        bench("shape-mixed (Latin+Arabic+emoji via stack)") {
            stack.shapeParagraph(MIXED_PARAGRAPH, sizePx = POINT_SIZE)
        }
    }

    @Test
    fun `bench shape Latin emoji whitespace fragmentation`() = runBlocking {
        // "Approximate" whitespace/punctuation should stay in primary
        // instead of triggering fallback recursion. Logs the produced run
        // count alongside timings so a regression in fragmentation is
        // visible without re-running with a debugger.
        val runCount = stack.shapeParagraph(EMOJI_FRAGMENT_PARAGRAPH, sizePx = POINT_SIZE).runs.size
        println("shape-fragment runCount=$runCount  (text=\"$EMOJI_FRAGMENT_PARAGRAPH\")")
        bench("shape-fragment (Hello 🙂 world 🙃 byes via stack)") {
            stack.shapeParagraph(EMOJI_FRAGMENT_PARAGRAPH, sizePx = POINT_SIZE)
        }
    }

    @Test
    fun `bench shape 50 small paragraphs sequential`() = runBlocking {
        // Every shape currently queues on the single-thread harfbuzz-bg
        // dispatcher, so sequential and concurrent variants should be ~equal
        // today. A future multi-worker dispatcher would let the concurrent
        // variant drop.
        bench("shape-many-sequential (50 short)") {
            for (text in SMALL_PARAGRAPHS) {
                roboto.shapeParagraph(text, sizePx = POINT_SIZE)
            }
        }
    }

    @Test
    fun `bench shape 50 small paragraphs concurrent`() = runBlocking {
        bench("shape-many-async (50 short, await all)") {
            coroutineScope {
                SMALL_PARAGRAPHS.map { text ->
                    async { roboto.shapeParagraph(text, sizePx = POINT_SIZE) }
                }.awaitAll()
            }
        }
    }

    @Test
    fun `bench glyph extents batch vs loop`() = runBlocking {
        // Pre-build a representative gid array by shaping a long string
        // and harvesting its glyph IDs.
        val shaped = roboto.shapeParagraph(GIDS_SOURCE, sizePx = POINT_SIZE)
        val gids = shaped.runs.flatMap { it.glyphs }.map { it.glyphId }.toIntArray()
        require(gids.size >= 100) { "expected ≥100 glyphs from source string, got ${gids.size}" }

        bench("glyphExtentsBatch (${gids.size} gids)") {
            roboto.glyphExtentsBatch(gids, sizePx = POINT_SIZE)
        }
        bench("glyphExtents loop (${gids.size} gids)") {
            for (gid in gids) roboto.glyphExtents(gid, sizePx = POINT_SIZE)
        }
    }

    @Test
    fun `bench resolver fontFor cold then cached`() = runBlocking {
        // Cold hit must load the file + parse the face; warm hit is a
        // pure HashMap lookup once X12 lands. The ratio between the two
        // tells you what the resolver hot path costs vs the disk hop.
        bench("resolver-cold (Arabic codepoint)") {
            val resolver = JvmSystemFontResolver(
                SystemFallback.Match(),
                candidatePaths = listOf(arabicFile),
            )
            try {
                resolver.fontFor(codepoint = 0x0644)
            } finally {
                resolver.close()
            }
        }

        // Warm: build once, measure repeat queries.
        val warmResolver = JvmSystemFontResolver(
            SystemFallback.Match(),
            candidatePaths = listOf(arabicFile),
        )
        try {
            warmResolver.fontFor(codepoint = 0x0644)
            bench("resolver-warm (cached Arabic codepoint)") {
                warmResolver.fontFor(codepoint = 0x0644)
            }
        } finally {
            warmResolver.close()
        }
    }

    private fun <T> bench(label: String, block: suspend () -> T) = runBlocking {
        // Warmup discards startup transients (JIT, dispatcher cold-start,
        // first JNI border-cross). We don't compute warmup stats - that
        // would obscure the steady-state numbers we actually care about.
        repeat(WARMUP_ITERATIONS) { block() }

        val timesNs = LongArray(MEASURE_ITERATIONS)
        for (i in 0 until MEASURE_ITERATIONS) {
            val t0 = System.nanoTime()
            block()
            timesNs[i] = System.nanoTime() - t0
        }
        timesNs.sort()
        val min = timesNs.first()
        val median = timesNs[MEASURE_ITERATIONS / 2]
        val p95 = timesNs[(MEASURE_ITERATIONS * 95 / 100).coerceAtMost(MEASURE_ITERATIONS - 1)]
        val max = timesNs.last()
        println(
            "%-50s min=%6.1fµs  median=%6.1fµs  p95=%6.1fµs  max=%6.1fµs".format(
                label,
                min / 1_000.0,
                median / 1_000.0,
                p95 / 1_000.0,
                max / 1_000.0,
            ),
        )
    }

    companion object {
        private const val POINT_SIZE = 16f
        private const val WARMUP_ITERATIONS = 50
        private const val MEASURE_ITERATIONS = 200

        private const val LATIN_PARAGRAPH =
            "The quick brown fox jumps over the lazy dog while five hex wizards run the test."
        private const val ARABIC_PARAGRAPH =
            "في الجزائر، تشرق الشمس على الجبال الزرقاء كل صباح."
        private const val MIXED_PARAGRAPH =
            "Hello مرحبا 😀 - bonjour, привет, 你好."
        private const val EMOJI_FRAGMENT_PARAGRAPH =
            "Hello 🙂 world 🙃 byes 😀 again 🙃."

        // 200-char string covering Latin alphanumerics + spaces - a
        // reasonable proxy for real text in terms of glyph count.
        private const val GIDS_SOURCE =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor " +
                "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis " +
                "nostrud exercitation."

        // 50 short snippets - typical for a screen full of UI labels,
        // captions, button text. Hand-picked to avoid shaping cache hits
        // dominating the result.
        private val SMALL_PARAGRAPHS: List<String> = listOf(
            "Open", "Close", "Save", "Cancel", "Retry",
            "OK", "Done", "Next", "Back", "Help",
            "Sign in", "Sign up", "Log out", "Settings", "Profile",
            "Notifications", "Search…", "Inbox", "Sent", "Drafts",
            "New message", "Reply", "Forward", "Delete", "Archive",
            "Star", "Mark unread", "Move to…", "Filter by…", "Sort",
            "Loading", "Error", "Offline", "Sync now", "Version",
            "About", "Privacy", "Terms", "Licenses", "Open source",
            "12 unread", "Drafts (3)", "Last sync 5m ago", "All caught up",
            "Welcome back", "Try again", "Continue", "Skip", "Got it",
            "Refresh", "Reload",
        )
    }
}
