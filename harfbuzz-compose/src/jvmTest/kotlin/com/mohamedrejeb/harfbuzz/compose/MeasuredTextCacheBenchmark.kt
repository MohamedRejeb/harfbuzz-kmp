package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.SystemFallback
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tracks the perf delta of the process-wide MeasuredText cache. Repeated
 * calls with identical inputs should collapse to a map lookup once the
 * cache is wired in; the negative-control case (fresh key every call)
 * confirms the lookup itself doesn't add measurable overhead on the miss
 * path.
 *
 * Run:
 * ```
 * ./gradlew :harfbuzz-compose:jvmTest -PrunBenchmarks --tests "*MeasuredTextCacheBenchmark*"
 * ```
 */
class MeasuredTextCacheBenchmark {

    private lateinit var face: HbFace
    private lateinit var font: HbFont
    private lateinit var stack: HbFontStack
    private var emojiFace: HbFace? = null
    private var emojiFont: HbFont? = null
    private var emojiStack: HbFontStack? = null

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "Run benchmarks with -PrunBenchmarks",
            System.getProperty("runBenchmarks") == "true",
        )
        runBlocking {
            harfBuzzInit()
            face = HbFace.fromBytes(TestFonts.robotoRegular())
            font = face.toFont()
            // Optional NotoColorEmoji fixture loaded from the sample dir
            // (the compose test resources only ship Roboto). Skip the
            // emoji benches when the font isn't reachable, but still run
            // the rest of the suite.
            val emojiFile = File("../sample/src/commonMain/composeResources/font/NotoColorEmoji-Regular.ttf")
            if (emojiFile.exists()) {
                emojiFace = HbFace.fromBytes(emojiFile.readBytes())
                emojiFont = emojiFace?.toFont()
                emojiFont?.let { emojiStack = HbFontStack(it, system = SystemFallback.None) }
            }
        }
        // SystemFallback.None keeps the benchmark hermetic - no platform
        // resolver lookups bleeding into the timed region.
        stack = HbFontStack(font, system = SystemFallback.None)
        // Drop any state left by a sibling benchmark so each case starts
        // from a known-empty cache. Warmup then populates the cache for
        // the cases that expect to measure hits.
        clearMeasuredTextCacheForTest()
    }

    @AfterTest
    fun tearDown() {
        emojiStack?.close()
        emojiFont?.close()
        emojiFace?.close()
        if (::stack.isInitialized) stack.close()
        if (::font.isInitialized) font.close()
        if (::face.isInitialized) face.close()
    }

    @Test
    fun `bench buildMeasuredText emoji paragraph cold`() = runBlocking {
        Assume.assumeTrue(
            "NotoColorEmoji-Regular.ttf not available - skipping emoji bench",
            emojiStack != null,
        )
        val target = requireNotNull(emojiStack)
        // `buildMeasuredText` cost on an emoji-heavy paragraph is dominated
        // by per-glyph paint-tree decode in `parseFontPass`. With lazy
        // decoding, the paint trees and outline paths decode on first
        // draw, so the build call drops to shape + paint-tree-bytes copy.
        //
        // Cold-build measurement: every iteration clears the cache so the
        // full pipeline runs (no MeasuredTextCache hit). Without this
        // clear, the cache would collapse iterations 2..N to a map lookup.
        bench("buildMeasured-emoji-cold (10 emoji, fresh build)") {
            clearMeasuredTextCacheForTest()
            buildMeasuredText(
                text = EMOJI_PARAGRAPH,
                fontStack = target,
                sizePx = POINT_SIZE,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
        }
    }

    @Test
    fun `bench buildMeasuredText same text repeated`() = runBlocking {
        // After warmup the cache (when present) is populated; the timed
        // region therefore measures cache-hit cost. Without the cache,
        // every call re-runs the full shape+snapshot pipeline.
        bench("buildMeasured-repeat (Latin 80c, same key)") {
            buildMeasuredText(
                text = LATIN_PARAGRAPH,
                fontStack = stack,
                sizePx = POINT_SIZE,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
        }
    }

    @Test
    fun `bench buildMeasuredText distinct text every call`() = runBlocking {
        // Negative control: every call has a fresh key, so the cache
        // can never hit. Used to confirm the lookup overhead on miss
        // is negligible compared to the shaping cost.
        var counter = 0
        bench("buildMeasured-distinct (Latin, fresh key per call)") {
            buildMeasuredText(
                text = "$LATIN_PARAGRAPH ${counter++}",
                fontStack = stack,
                sizePx = POINT_SIZE,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
        }
    }

    @Test
    fun `bench buildMeasuredText fresh stack each call same content`() = runBlocking {
        // The recomposition case: caller drops `remember(font) { HbFontStack(font) }`
        // and rebuilds the stack on every pass. With the cache keyed on
        // (face, pointSize) per font, this case still hits - that's the
        // whole point. Without [HbFaceCache], an HbFontStack rebuild that
        // also re-parses the face would miss; this benchmark holds face
        // stable.
        bench("buildMeasured-stackChurn (same face, new stack)") {
            val freshStack = HbFontStack(font, system = SystemFallback.None)
            try {
                buildMeasuredText(
                    text = LATIN_PARAGRAPH,
                    fontStack = freshStack,
                    sizePx = POINT_SIZE,
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
        // Auto-pick µs vs ms - cache-hit measurements drop into sub-µs;
        // cold shape on Latin is ~hundreds of µs.
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
        private const val POINT_SIZE = 16f
        private const val WARMUP = 10
        private const val MEASURE = 100
        private const val LATIN_PARAGRAPH =
            "The quick brown fox jumps over the lazy dog while five hex wizards run the test."
        // 10 distinct emoji glyphs - `parseFontPass` would eagerly decode
        // 10 paint trees before C; with C, decode is deferred until draw.
        private const val EMOJI_PARAGRAPH = "🙂🙃😀😁😂🤣😅😆😉😊"
    }
}
