package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Variable-fonts smoke tests. Verifies the API plumbing on a known
 * non-variable face (Roboto-Regular) and confirms the surface degrades
 * gracefully when variations are passed but ignored. A second test
 * exercises the API against the system Skia font on macOS - `SF NS`
 * exposes a `wght` axis on every Apple Silicon machine - but that
 * test is a no-op when the font isn't available.
 */
class VariableFontsTest {

    private val robotoFile = File(
        "../sample/src/commonMain/composeResources/font/Roboto-Regular.ttf",
    )

    private lateinit var robotoFace: HbFace

    @BeforeTest
    fun setUp() {
        require(robotoFile.exists()) { "Roboto missing at ${robotoFile.absolutePath}" }
        robotoFace = HbFace.from { bytes(robotoFile.readBytes()) }
    }

    @AfterTest
    fun tearDown() {
        robotoFace.close()
    }

    @Test
    fun `non-variable face reports no axes`() {
        assertEquals(emptyList(), robotoFace.variationAxes())
    }

    @Test
    fun `passing variations to non-variable face is a no-op`() = runBlocking {
        // Roboto-Regular is not variable. Asking for wght=900 silently does
        // nothing - HarfBuzz ignores variations on non-variable fonts. The
        // call must NOT throw.
        val font = robotoFace.toFont(
            pointSize = 24f,
            variations = listOf(HbVariation.of("wght", 900f)),
        )
        try {
            // Sanity: the font still functions, glyph lookup works.
            assertTrue(font.glyphIdForCodepoint('A'.code) != 0)
            assertTrue(font.pointSize == 24f)
        } finally {
            font.close()
        }
    }

    @Test
    fun `HbVariation factory builds tag from 4-char string`() {
        val v = HbVariation.of("wght", 500f)
        assertEquals(HbTag.of("wght"), v.tag)
        assertEquals(500f, v.value)
    }

    @Test
    fun `HbVariationAxis clamp respects bounds`() {
        val axis = HbVariationAxis(
            tag = HbTag.of("wght"),
            defaultValue = 400f,
            minValue = 100f,
            maxValue = 900f,
        )
        assertEquals(100f, axis.clamp(50f))
        assertEquals(900f, axis.clamp(2000f))
        assertEquals(450f, axis.clamp(450f))
    }

    /**
     * Macos system SF font is variable; if we can find it, exercise the
     * full path including a non-empty axis list and a `wght`-pinned
     * font. Skipped silently when the file isn't present (Linux / CI).
     */
    @Test
    fun `system SF compact rounded reports wght axis when available`() = runBlocking {
        val candidates = listOf(
            "/System/Library/Fonts/SFCompactRounded.ttf",
            "/System/Library/Fonts/SFCompact.ttf",
            "/System/Library/Fonts/SFNS.ttf",
        ).map(::File)
        val sf = candidates.firstOrNull { it.exists() } ?: return@runBlocking

        HbFace.from { bytes(sf.readBytes()) }.use { face ->
            val axes = face.variationAxes()
            // Apple's variable system fonts expose at least `wght`.
            val wght = axes.firstOrNull { it.tag == HbTag.of("wght") } ?: return@use
            assertTrue(wght.minValue <= wght.defaultValue)
            assertTrue(wght.defaultValue <= wght.maxValue)

            // Pinning the axis at min vs max produces fonts whose
            // glyph metrics differ - a heavyweight glyph is wider than
            // its lightweight counterpart for most letters.
            val light = face.toFont(
                pointSize = 32f,
                variations = listOf(HbVariation(wght.tag, wght.minValue)),
            )
            val heavy = face.toFont(
                pointSize = 32f,
                variations = listOf(HbVariation(wght.tag, wght.maxValue)),
            )
            try {
                val gid = light.glyphIdForCodepoint('M'.code)
                if (gid == 0) return@use
                val lightAdv = light.glyphAdvance(gid)
                val heavyAdv = heavy.glyphAdvance(gid)
                // Variable axes should change at least *something* in
                // glyph metrics - a strict equal across min/max would
                // mean variations didn't take effect. We don't assert
                // direction (heavy could be wider OR narrower) since
                // that depends on the font's design.
                assertTrue(
                    lightAdv != heavyAdv,
                    "wght min=$lightAdv vs max=$heavyAdv should differ if variations applied",
                )
            } finally {
                light.close()
                heavy.close()
            }
        }
    }
}
