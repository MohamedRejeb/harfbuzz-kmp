package com.mohamedrejeb.harfbuzz.compose

import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Pure diagnostic: prints the glyph topology Saudi-Regular emits for
 * the LAM-FATHA-ALEF cluster so we can see what `PaintResolver` is
 * working against. Not an assertion test - the partner spec does the
 * assertion side. Keep until the cluster heuristic is well-tested.
 */
class SaudiLamFathaAlefDiagnosticJvmTest {

    @Test
    fun dump_lam_fatha_alef_clusters() = runBlocking {
        val text = "لَا" // LAM (0644) + FATHA (064E) + ALEF (0627)
        HbFace.from { bytes(TestFonts.saudiRegular()) }.use { face ->
            face.toFont().use { font ->
                val stack = HbFontStack(font)
                val shape = stack.shapeParagraph(
                    text = text,
                    sizePx = 64f,
                    baseDirection = HbDirection.AUTO,
                )
                println("--- Saudi-Regular shape of \"\\u0644\\u064E\\u0627\" (لَا) ---")
                println("baseDirection=${shape.baseDirection}, totalAdvance=${shape.totalAdvance}")
                shape.runs.forEachIndexed { ri, run ->
                    println(" run[$ri]: dir=${run.direction}, glyphs=${run.glyphs.size}, advance=${run.totalAdvance}")
                    for (i in run.glyphs.indices) {
                        val g = run.glyphs[i]
                        val p = run.positions[i]
                        println("  g[$i]: gid=${g.glyphId}, cluster=${g.cluster}, " +
                            "xAdv=${p.xAdvance}, yAdv=${p.yAdvance}, " +
                            "xOff=${p.xOffset}, yOff=${p.yOffset}")
                    }
                }
            }
        }
    }
}
