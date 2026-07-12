package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Unit coverage for the per-glyph effect helpers: alpha quantization,
 * effect-transformed path appends, and the quantized-alpha silhouette
 * bucketing the stroke / brush / shadow passes rely on.
 */
class GlyphEffectBucketTest {

    @Test
    fun `NONE is the identity effect`() {
        assertEquals(GlyphEffect(), GlyphEffect.NONE)
        assertEquals(1f, GlyphEffect.NONE.alpha)
        assertEquals(0f, GlyphEffect.NONE.translateX)
        assertEquals(0f, GlyphEffect.NONE.translateY)
        assertEquals(1f, GlyphEffect.NONE.scale)
    }

    @Test
    fun `alpha quantization uses floor semantics over 8 levels`() {
        assertEquals(1f, quantizeGlyphEffectAlpha(1f))
        assertEquals(1f, quantizeGlyphEffectAlpha(2f), "values above 1 clamp to the top bucket")
        assertEquals(0.875f, quantizeGlyphEffectAlpha(0.95f))
        assertEquals(0.5f, quantizeGlyphEffectAlpha(0.5f))
        assertEquals(0.5f, quantizeGlyphEffectAlpha(0.6f))
        assertEquals(0f, quantizeGlyphEffectAlpha(0.1f), "below the first level quantizes to zero")
        assertEquals(0f, quantizeGlyphEffectAlpha(0f))
        assertTrue(quantizeGlyphEffectAlpha(-1f) <= 0f, "negative alpha stays droppable")
    }

    @Test
    fun `identity provider produces a single full-alpha bucket`() = runBlocking {
        withMeasured("Hello") { measured ->
            val buckets = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, _, _ -> GlyphEffect.NONE },
            )
            assertEquals(setOf(1f), buckets.keys)
            assertTrue(!buckets.getValue(1f).isEmpty, "single bucket must hold every glyph")
        }
    }

    @Test
    fun `glyphs group by quantized alpha`() = runBlocking {
        withMeasured("Hello") { measured ->
            val buckets = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, glyphIndex, _ ->
                    if (glyphIndex % 2 == 0) GlyphEffect.NONE else GlyphEffect(alpha = 0.6f)
                },
            )
            assertEquals(setOf(1f, 0.5f), buckets.keys, "0.6 quantizes to the 0.5 bucket")
            assertTrue(!buckets.getValue(1f).isEmpty)
            assertTrue(!buckets.getValue(0.5f).isEmpty)
        }
    }

    @Test
    fun `glyphs whose quantized alpha is zero are dropped`() = runBlocking {
        withMeasured("Hello") { measured ->
            val buckets = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, _, _ -> GlyphEffect(alpha = 0.05f) },
            )
            assertTrue(buckets.isEmpty(), "sub-bucket alpha must drop every glyph")
        }
    }

    @Test
    fun `translate shifts the accumulated silhouette`() = runBlocking {
        withMeasured("Hello") { measured ->
            val plain = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, _, _ -> GlyphEffect.NONE },
            ).getValue(1f).getBounds()
            val shifted = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, _, _ -> GlyphEffect(translateX = 10f, translateY = -4f) },
            ).getValue(1f).getBounds()
            assertEquals(plain.left + 10f, shifted.left, absoluteTolerance = 0.001f)
            assertEquals(plain.right + 10f, shifted.right, absoluteTolerance = 0.001f)
            assertEquals(plain.top - 4f, shifted.top, absoluteTolerance = 0.001f)
            assertEquals(plain.bottom - 4f, shifted.bottom, absoluteTolerance = 0.001f)
        }
    }

    @Test
    fun `scale pivots at the glyph pen origin`() = runBlocking {
        // Single glyph at pen (0, 0): scaling about the pen origin must
        // scale every bound coordinate by the same factor.
        withMeasured("H") { measured ->
            val plain = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, _, _ -> GlyphEffect.NONE },
            ).getValue(1f).getBounds()
            val scaled = accumulateLineSilhouetteBuckets(
                measured = measured,
                penX = 0f,
                penY = 0f,
                spacingScale = 1f,
                glyphEffects = { _, _, _ -> GlyphEffect(scale = 2f) },
            ).getValue(1f).getBounds()
            assertEquals(plain.left * 2f, scaled.left, absoluteTolerance = 0.01f)
            assertEquals(plain.right * 2f, scaled.right, absoluteTolerance = 0.01f)
            assertEquals(plain.top * 2f, scaled.top, absoluteTolerance = 0.01f)
            assertEquals(plain.bottom * 2f, scaled.bottom, absoluteTolerance = 0.01f)
            assertTrue(abs(scaled.width - plain.width * 2f) < 0.01f)
        }
    }

    private suspend fun withMeasured(text: String, block: suspend (MeasuredText) -> Unit) {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        try {
            val measured = buildMeasuredText(
                text = text,
                fontStack = HbFontStack(font),
                sizePx = 48f,
                features = emptyList(),
                direction = HbDirection.AUTO,
                language = HbLanguage.AUTO,
            )
            block(measured)
        } finally {
            font.close()
            face.close()
        }
    }
}
