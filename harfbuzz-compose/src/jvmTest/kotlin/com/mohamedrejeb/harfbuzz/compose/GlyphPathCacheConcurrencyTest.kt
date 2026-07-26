package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * Hammers [GlyphPathCache] from multiple threads through eviction churn.
 * Production draws hit the cache from the UI thread while background
 * renders (layer baking, export) populate it concurrently; an unlocked
 * LinkedHashMap throws ConcurrentModificationException during eviction
 * (seen in production via TextDrawableLayer.drawParagraphLines).
 */
class GlyphPathCacheConcurrencyTest {

    @Test
    fun `concurrent put and get across eviction churn does not throw`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        clearGlyphPathCacheForTest()

        val threads = 4
        val opsPerThread = 50_000
        val keySpace = 1024
        val errors = ConcurrentLinkedQueue<Throwable>()

        val workers = (0 until threads).map { t ->
            thread(name = "glyph-cache-hammer-$t") {
                try {
                    repeat(opsPerThread) { i ->
                        val key = GlyphPathCache.Key(
                            font = font,
                            glyphId = (i * threads + t) % keySpace,
                            sizePx = 24f,
                            flipY = false,
                        )
                        if (GlyphPathCache.get(key) == null) {
                            GlyphPathCache.put(key, Path())
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        workers.forEach { it.join() }

        font.close()
        face.close()
        if (errors.isNotEmpty()) {
            fail("GlyphPathCache raced: ${errors.first()}")
        }
    }
}
