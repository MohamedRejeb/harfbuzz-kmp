package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.HbVariation
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression test for the variable-font cache collision: two [HbFont]
 * instances built from the same face + pointSize but with different
 * variation axes must produce different [MeasureKey]s. Earlier the key
 * was `(face, pointSize)`, which collided across variation states and
 * caused animated weight/width sliders to render the first frame's
 * glyphs forever.
 *
 * The assertion is stated at the cache-key layer rather than rendering
 * the variations end-to-end, because no variable font ships in the
 * jvmTest resources - but the cache bug is purely about key equality,
 * so this is the right level to pin it.
 */
class MeasureKeyTest {

    @Test
    fun `distinct font instances produce distinct keys even with matching face and size`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        // Roboto-Regular isn't a variable font, but `toFont` accepts a
        // `variations` list regardless and returns a fresh HbFont each
        // call. Distinct identities are exactly what the cache needs to
        // see as different keys.
        val fontA = face.toFont(listOf(HbVariation.of("wght", 400f)))
        val fontB = face.toFont(listOf(HbVariation.of("wght", 700f)))

        val keyA = measureKeyOf(
            text = "abc",
            fontStack = HbFontStack(fontA),
            sizePx = 16f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )
        val keyB = measureKeyOf(
            text = "abc",
            fontStack = HbFontStack(fontB),
            sizePx = 16f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )

        assertNotEquals(keyA, keyB, "different HbFont instances must produce different MeasureKeys")

        fontA.close()
        fontB.close()
        face.close()
    }

    @Test
    fun `same font reference reused across stacks produces equal keys`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()

        val key1 = measureKeyOf(
            text = "abc",
            fontStack = HbFontStack(font),
            sizePx = 16f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )
        val key2 = measureKeyOf(
            text = "abc",
            fontStack = HbFontStack(font),
            sizePx = 16f,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )

        assertEquals(key1, key2, "stable font reference must hit the cache across distinct stack instances")

        font.close()
        face.close()
    }

    @Test
    fun `sizePx values inside the same bucket produce equal keys`() = runBlocking {
        harfBuzzInit()
        val face = HbFace.fromBytes(TestFonts.robotoRegular())
        val font = face.toFont()
        val stack = HbFontStack(font)

        fun keyAt(sizePx: Float) = measureKeyOf(
            text = "abc",
            fontStack = stack,
            sizePx = sizePx,
            features = emptyList(),
            direction = HbDirection.AUTO,
            language = HbLanguage.AUTO,
        )

        // Slider scrub through a half-pixel bucket: every value rounds
        // to 28.0 except the upper edge, which already belongs to the
        // 28.5 bucket. Without quantization a 60 Hz slider would burn
        // the 32-entry LRU on every tick.
        assertEquals(keyAt(28.0f), keyAt(28.1f))
        assertEquals(keyAt(28.0f), keyAt(28.2f))
        assertNotEquals(keyAt(28.0f), keyAt(28.5f))
        assertEquals(keyAt(28.5f), keyAt(28.6f))
        assertEquals(keyAt(28.5f), keyAt(28.7f))

        font.close()
        face.close()
    }
}
