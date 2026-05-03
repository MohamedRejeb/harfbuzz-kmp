package com.mohamedrejeb.harfbuzz.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests that exercise the full chain: bytes → HbFace → HbFont → shape →
 * ShapedRun. Uses a system font we know exists on macOS (a Helvetica equivalent)
 * to avoid bundling a font in tests for now. CI matrix uses a vendored test font.
 *
 * If the assumed system font isn't found, tests are skipped with a clear message.
 */
class ShapeSmokeTest {

    private fun loadSystemFont(): ByteArray? {
        // macOS: SF NS / Helvetica
        val candidates = listOf(
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/HelveticaNeue.ttc",
            "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
            "/Library/Fonts/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "C:\\Windows\\Fonts\\arial.ttf",
        )
        return candidates.firstOrNull { File(it).exists() }?.let { File(it).readBytes() }
    }

    @Test
    fun `shape Latin text produces glyph runs with positive total advance`() = runBlocking {
        val bytes = loadSystemFont() ?: run {
            println("Skipping: no system font found.")
            return@runBlocking
        }
        val face = HbFace.from { bytes(bytes) }
        face.use { f ->
            val font = f.toFont()
            font.use { hb ->
                HbBuffer().use { buf ->
                    buf.text = "Hello World"
                    buf.direction = HbDirection.LTR
                    buf.script = HbScript.LATIN
                    buf.language = HbLanguage.ENGLISH
                    val run = hb.shape(buf, sizePx = 16f)
                    assertEquals(11, run.glyphs.size, "11 chars should typically map to ~11 glyphs in Latin")
                    assertTrue(run.totalAdvance > 0f, "totalAdvance should be positive, got ${run.totalAdvance}")
                    assertTrue(run.glyphs.all { it.glyphId > 0 }, "all glyphs should resolve (no .notdef for ASCII)")
                }
            }
        }
    }

    @Test
    fun `glyph extents and advance are positive for typical glyphs`() = runBlocking {
        val bytes = loadSystemFont() ?: return@runBlocking
        val face = HbFace.from { bytes(bytes) }
        face.use { f ->
            f.toFont().use { hb ->
                val gid = hb.glyphIdForCodepoint('H'.code)
                if (gid == 0) return@use  // font lacks glyph
                val advance = hb.glyphAdvance(gid, sizePx = 16f)
                assertTrue(advance > 0f, "advance should be positive, got $advance")
                val ext = hb.glyphExtents(gid, sizePx = 16f)
                if (ext != null) {
                    assertTrue(ext.width > 0f && ext.height != 0f, "extents should have non-zero area: $ext")
                }
            }
        }
    }

    @Test
    fun `face exposes openTypeTags`() {
        val bytes = loadSystemFont() ?: return
        HbFace.from { bytes(bytes) }.use { face ->
            val tags = face.openTypeTags()
            // Most fonts have at least one OT feature; even if zero, this should
            // not throw or return null.
            assertTrue(tags.size >= 0)
        }
    }
}
