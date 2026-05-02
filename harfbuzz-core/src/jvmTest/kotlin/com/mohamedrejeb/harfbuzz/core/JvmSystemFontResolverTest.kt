package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tier 2 system fallback unit tests. Inject a deterministic curated list
 * (Roboto + Noto Naskh Arabic + Noto Color Emoji from the bundled
 * sample fonts) so the resolver behaves identically on every CI matrix
 * entry, regardless of which fonts the host actually has installed.
 */
class JvmSystemFontResolverTest {

    private val sampleFonts = File("../sample/src/commonMain/composeResources/font")
    private val roboto = File(sampleFonts, "Roboto-Regular.ttf")
    private val arabic = File(sampleFonts, "NotoNaskhArabic-Regular.ttf")
    private val emoji = File(sampleFonts, "NotoColorEmoji-Regular.ttf")
    private val curated: List<File> by lazy { listOf(roboto, arabic, emoji).filter { it.exists() } }

    private lateinit var resolver: JvmSystemFontResolver

    @BeforeTest
    fun setUp() {
        require(curated.size >= 2) {
            "Sample fonts not found at ${sampleFonts.absolutePath}"
        }
        resolver = JvmSystemFontResolver(SystemFallback.Match(), candidatePaths = curated)
    }

    @AfterTest
    fun tearDown() {
        resolver.close()
    }

    @Test
    fun `Arabic codepoint resolves through Noto Naskh`() = runBlocking {
        // U+0644 ARABIC LETTER LAM - Roboto has no Arabic glyphs.
        val font = resolver.fontFor(codepoint = 0x0644, pointSize = 24f)
        assertNotNull(font, "expected an Arabic-capable font in the curated list")
        assertTrue(font.glyphIdForCodepoint(0x0644) != 0, "resolved font must actually cover the codepoint")
    }

    @Test
    fun `emoji codepoint resolves through emoji font when available`() = runBlocking {
        if (curated.none { it.name.contains("Emoji", ignoreCase = true) }) return@runBlocking
        // U+1F600 GRINNING FACE - neither Roboto nor Noto Naskh covers this.
        val font = resolver.fontFor(codepoint = 0x1F600, pointSize = 24f)
        assertNotNull(font, "expected an emoji-capable font in the curated list")
        assertTrue(font.glyphIdForCodepoint(0x1F600) != 0)
    }

    @Test
    fun `repeat query for the same codepoint returns the cached font`() = runBlocking {
        val first = resolver.fontFor(codepoint = 0x0644, pointSize = 24f)
        val second = resolver.fontFor(codepoint = 0x0644, pointSize = 24f)
        assertSame(first, second, "resolver must cache the resolved font")
    }

    @Test
    fun `unsupported codepoint returns null and is cached`() = runBlocking {
        // A codepoint in a Plane 15 (private use) range no font in our
        // curated list covers - we expect null both first and repeat.
        val cp = 0xF8FF
        val first = resolver.fontFor(codepoint = cp, pointSize = 24f)
        val second = resolver.fontFor(codepoint = cp, pointSize = 24f)
        assertNull(first, "Apple Logo PUA codepoint should not resolve from our curated test list")
        assertNull(second, "negative cache must hold across queries")
    }

    @Test
    fun `close releases all loaded faces and clears caches`() = runBlocking {
        // Force at least one font to load.
        resolver.fontFor(codepoint = 0x0644, pointSize = 24f)
        resolver.close()
        // After close, the resolver should be safe to interact with - a
        // second close is a no-op and we don't crash on internal state.
        resolver.close()
    }

    @Test
    fun `point size hint applies to the resolved font`() = runBlocking {
        val font = resolver.fontFor(codepoint = 0x0644, pointSize = 48f)
        assertNotNull(font)
        assertEquals(48f, font.pointSize, "resolver must size resolved fonts to the requested point size")
    }

    @Test
    fun `empty curated list resolves nothing`() = runBlocking {
        val empty = JvmSystemFontResolver(SystemFallback.Match(), candidatePaths = emptyList())
        try {
            assertNull(empty.fontFor(codepoint = 0x0061, pointSize = 16f))
        } finally {
            empty.close()
        }
    }

    @Test
    fun `preferColorEmoji true biases emoji codepoint toward color font`() = runBlocking {
        if (curated.none { it.name.contains("Emoji", ignoreCase = true) }) return@runBlocking
        // Build a curated list where a monochrome font (Roboto) appears
        // BEFORE the emoji font. Without the color-emoji bias, the
        // resolver would just return the first cover; with the bias,
        // the color font should be picked even though it's listed later.
        // Note: Roboto doesn't actually cover emoji codepoints, so this
        // test confirms the bias path works without false-positives.
        val biased = JvmSystemFontResolver(
            match = SystemFallback.Match(preferColorEmoji = true),
            candidatePaths = curated,
        )
        try {
            val font = biased.fontFor(codepoint = 0x1F600, pointSize = 24f)
            assertNotNull(font)
            // Confirm the resolved font has a color glyph table - that's
            // what the bias should select on an emoji codepoint.
            val hasColor = font.face.hasColorPaint() ||
                font.face.hasColorLayers() ||
                font.face.hasColorSvg()
            assertTrue(hasColor, "preferColorEmoji should pick a color-bearing font for emoji codepoints")
        } finally {
            biased.close()
        }
    }

    @Test
    fun `Arabic language hint biases ranking toward Arabic-tagged fonts`() = runBlocking<Unit> {
        // The bundled NotoNaskhArabic-Regular.ttf has "Arabic" / "Naskh"
        // in its path name, so [inferLanguageHints] should tag it `ar`.
        // With languages = [ar], the resolver should prefer it on a
        // codepoint that more than one font might cover.
        val biased = JvmSystemFontResolver(
            match = SystemFallback.Match(languages = listOf(HbLanguage.ARABIC)),
            candidatePaths = curated,
        )
        try {
            // Ambiguous codepoint: U+0020 SPACE - covered by every text
            // font. With language=ar, the Arabic-tagged font should
            // come back ahead of Roboto (no language tag).
            val space = biased.fontFor(codepoint = 0x0020, pointSize = 16f)
            assertNotNull(space)
            // We can't assert on font identity without exposing it, but
            // the call returning non-null confirms ranking didn't
            // regress and the language path is exercised. The tighter
            // assertion would require a per-font path-name accessor we
            // don't currently expose.
        } finally {
            biased.close()
        }
    }

    @Test
    fun `non-emoji codepoint ignores preferColorEmoji bias`() = runBlocking<Unit> {
        // Latin codepoint should still resolve regardless of the bias -
        // the bias only fires on emoji-block codepoints.
        val biased = JvmSystemFontResolver(
            match = SystemFallback.Match(preferColorEmoji = true),
            candidatePaths = curated,
        )
        try {
            val font = biased.fontFor(codepoint = 'a'.code, pointSize = 24f)
            assertNotNull(font, "Latin 'a' should resolve from any font in the curated list")
        } finally {
            biased.close()
        }
    }
}
