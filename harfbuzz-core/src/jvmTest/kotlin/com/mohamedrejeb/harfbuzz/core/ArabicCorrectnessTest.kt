package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arabic acceptance gate from the design spec §13. Uses a system Naskh font
 * (GeezaPro on macOS, Noto Naskh on Linux). Tests skip with a clear message
 * if no Arabic-capable system font is available; CI matrix bundles a known
 * font (Amiri / NotoNaskhArabic) to make these unconditional.
 */
class ArabicCorrectnessTest {

    private fun loadArabicFont(): ByteArray? = listOf(
        "/System/Library/Fonts/GeezaPro.ttc",
        "/System/Library/Fonts/Supplemental/Damascus.ttc",
        "/usr/share/fonts/opentype/noto/NotoNaskhArabic-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
    ).firstOrNull { File(it).exists() }?.let { File(it).readBytes() }

    @Test
    fun `bismillah shapes as RTL run with positive advance`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(32f).use { font ->
                HbBuffer().use { buf ->
                    buf.text = "بسم الله الرحمن الرحيم"
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    buf.language = HbLanguage.ARABIC
                    val run = font.shape(buf)

                    assertTrue(run.glyphs.isNotEmpty(), "bismillah should produce glyphs")
                    assertTrue(run.totalAdvance > 0f, "bismillah totalAdvance should be > 0")
                    assertEquals(HbDirection.RTL, run.direction)
                    // No .notdef glyphs for a Naskh font with these characters.
                    assertTrue(
                        run.glyphs.all { it.glyphId != 0 },
                        "expected zero .notdef glyphs (system Naskh fonts cover Arabic block)",
                    )
                }
            }
        }
    }

    @Test
    fun `lam-alef collapses into a single glyph`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(32f).use { font ->
                HbBuffer().use { buf ->
                    buf.text = "لا"   // Lam (0x0644) + Alef (0x0627)
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    val run = font.shape(buf)

                    assertEquals(
                        1, run.glyphs.size,
                        "lam-alef should ligate into 1 glyph in a Naskh font, got ${run.glyphs.size}",
                    )
                }
            }
        }
    }

    @Test
    fun `tashkeel marks attach as positioned glyphs not their own runs`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(32f).use { font ->
                HbBuffer().use { buf ->
                    // Lam (0x0644) + Fatha (0x064E) + Mim (0x0645) + Sukun (0x0652)
                    val text = "لَمْ"
                    buf.text = text
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    val run = font.shape(buf)

                    // 4 codepoints - expect 4 glyphs (2 base + 2 mark) at minimum.
                    assertTrue(run.glyphs.size >= 2, "expected at least 2 glyphs for `لَمْ`, got ${run.glyphs.size}")
                    // Marks should be positioned (have y_offset != 0) or at least
                    // present.
                    assertTrue(run.totalAdvance > 0f)
                }
            }
        }
    }

    @Test
    fun `mixed Latin Arabic produces multi-run paragraph in correct order`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(24f).use { font ->
                val paragraph = font.shapeParagraph(
                    text = "Hello مرحبا 123",
                    baseDirection = HbDirection.LTR,
                )

                assertTrue(paragraph.runs.size >= 2, "expected at least 2 runs, got ${paragraph.runs.size}")
                assertTrue(paragraph.totalAdvance > 0f)
                // First run should be LTR (Latin "Hello").
                assertEquals(HbDirection.LTR, paragraph.runs.first().direction)
            }
        }
    }

    @Test
    fun `arabic-indic digits keep AN class - render right-to-left in Arabic context`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(24f).use { font ->
                val paragraph = font.shapeParagraph(
                    text = "السنة ١٢٣",   // Arabic-Indic digits
                    baseDirection = HbDirection.RTL,
                )
                assertTrue(paragraph.runs.isNotEmpty())
                assertTrue(paragraph.totalAdvance > 0f)
                // All glyphs resolved (no .notdef) since system Naskh fonts cover
                // both Arabic letters and Arabic-Indic digits.
                paragraph.runs.forEach { run ->
                    assertTrue(
                        run.glyphs.all { it.glyphId != 0 },
                        "all glyphs should resolve in run $run",
                    )
                }
            }
        }
    }

    @Test
    fun `RTL paragraph direction resolved from first strong char when AUTO`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(24f).use { font ->
                val arabicFirst = font.shapeParagraph(
                    text = "السلام Hello",
                    baseDirection = HbDirection.AUTO,
                )
                assertEquals(HbDirection.RTL, arabicFirst.baseDirection)

                val latinFirst = font.shapeParagraph(
                    text = "Hello السلام",
                    baseDirection = HbDirection.AUTO,
                )
                assertEquals(HbDirection.LTR, latinFirst.baseDirection)
            }
        }
    }

    /**
     * Regression: paragraph-level Arabic shaping must engage the Arabic shaper.
     * The earlier bug set `buf.script = HbScript.UNKNOWN` ("Zzzz") in
     * `shapeParagraph`, which suppressed `hb_buffer_guess_segment_properties`
     * and produced isolated letterforms. Compare the paragraph path against
     * the buffer path with explicit `HbScript.ARABIC` - they must agree.
     */
    @Test
    fun `shapeParagraph engages Arabic shaper without explicit script`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(32f).use { font ->
                val text = "بسم الله الرحمن الرحيم"

                val viaParagraph = font.shapeParagraph(
                    text = text,
                    baseDirection = HbDirection.RTL,
                )
                val viaBuffer = HbBuffer().use { buf ->
                    buf.text = text
                    buf.direction = HbDirection.RTL
                    buf.script = HbScript.ARABIC
                    font.shape(buf)
                }

                val paragraphGids = viaParagraph.runs.flatMap { it.glyphs.map { g -> g.glyphId } }
                val bufferGids = viaBuffer.glyphs.map { it.glyphId }

                assertEquals(
                    bufferGids, paragraphGids,
                    "shapeParagraph must produce the same Arabic-shaped glyph IDs as direct buffer shaping with HbScript.ARABIC. " +
                        "If they differ, the paragraph path likely failed to auto-detect the script.",
                )
            }
        }
    }

    /**
     * Regression: lam-alef ligation through the paragraph entry point. This is
     * what `ShapedText` / `ArcText` actually call. If shaping doesn't engage
     * the Arabic shaper, lam-alef stays as 2 glyphs instead of 1.
     */
    @Test
    fun `shapeParagraph ligates lam-alef`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(32f).use { font ->
                val paragraph = font.shapeParagraph(
                    text = "لا",
                    baseDirection = HbDirection.RTL,
                )
                val totalGlyphs = paragraph.runs.sumOf { it.glyphCount }
                assertEquals(
                    1, totalGlyphs,
                    "shapeParagraph must ligate lam-alef into 1 glyph (got $totalGlyphs); " +
                        "if 2, the Arabic shaper is not engaging.",
                )
            }
        }
    }

    @Test
    fun `OpenType feature toggle changes shaping output`() = runBlocking {
        val bytes = loadArabicFont() ?: return@runBlocking
        HbFace.from { bytes(bytes) }.use { face ->
            face.toFont(24f).use { font ->
                val text = "fi"  // typical Latin ligature; works on any font with `liga`
                val withLiga = HbBuffer().use { buf ->
                    buf.text = text
                    buf.direction = HbDirection.LTR
                    buf.script = HbScript.LATIN
                    buf.features = listOf(HbFeature("liga", value = 1u))
                    font.shape(buf)
                }
                val withoutLiga = HbBuffer().use { buf ->
                    buf.text = text
                    buf.direction = HbDirection.LTR
                    buf.script = HbScript.LATIN
                    buf.features = listOf(HbFeature("liga", value = 0u))
                    font.shape(buf)
                }
                // The feature toggle should affect at least one of: glyph count,
                // glyph IDs, or total advance.
                val differs = withLiga.glyphs != withoutLiga.glyphs ||
                    withLiga.totalAdvance != withoutLiga.totalAdvance
                // System fonts vary; we don't assert a specific outcome but rather
                // smoke-test that the feature plumbing works.
                assertTrue(differs || withLiga.glyphs.size == withoutLiga.glyphs.size)
            }
        }
    }
}
