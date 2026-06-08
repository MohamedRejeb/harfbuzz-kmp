package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Color
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.shapeParagraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Locks in tashkeel-coloring correctness for the LAM-FATHA-ALEF cluster
 * in fonts whose LAM-ALEF ligature absorbs the FATHA's surrounding
 * codepoints into a single cluster `[0, 3)` with two glyphs (the
 * combined LAM-ALEF base + the FATHA mark).
 *
 * The naive cluster-position heuristic maps the second glyph (the mark)
 * to the *trailing* source codepoint inside the cluster. For
 * `LAM (0) | FATHA (1) | ALEF (2)` that resolves the FATHA glyph's
 * source to index 2 (ALEF), so a `withTashkeelColor` span covering
 * `[1, 2)` (the FATHA codepoint) never paints the mark - the bug the
 * user reported on Saudi Regular and similar faces.
 *
 * The fix keys the override off the source-codepoint category: when the
 * heuristic lands on a non-mark codepoint inside a cluster that has a
 * combining-mark codepoint at an earlier source position, the resolver
 * walks back to that mark.
 */
class SaudiLamFathaAlefStyledJvmTest {

    @Test
    fun fatha_glyph_in_lam_alef_ligature_resolves_to_fatha_span() = runBlocking {
        val text = "لَا" // LAM (0644) + FATHA (064E) + ALEF (0627)
        HbFace.from { bytes(TestFonts.saudiRegular()) }.use { face ->
            face.toFont().use { font ->
                val stack = HbFontStack(font)
                val shape = stack.shapeParagraph(
                    text = text,
                    sizePx = 64f,
                    baseDirection = HbDirection.AUTO,
                )
                // Saudi Regular shapes "لَا" as one cluster of two
                // glyphs (LAM-ALEF base + FATHA mark). Guard the
                // assertion against fonts that ship multi-glyph
                // bases or different cluster boundaries; on those
                // the bug doesn't reproduce and the test is a no-op.
                val run = shape.runs.singleOrNull() ?: return@use
                if (run.glyphs.size != 2) return@use
                if (run.glyphs.any { it.cluster != 0 }) return@use

                val clusterEnds = clusterEndArray(shape, textLength = text.length)
                val redOnFatha = StyleRange(1, 2, SpanStyle(color = Color.Red))
                val resolver = PaintResolver(
                    spans = listOf(redOnFatha),
                    clusterEnds = clusterEnds,
                    clusterText = text,
                    defaultColor = Color.Black,
                    defaultBrush = null,
                )

                // Replicate the StyledTextDraw bookkeeping so the test
                // hits PaintResolver with the same inputs the live
                // draw path produces.
                val (glyphsInCluster, indexInCluster) = computeClusterIndexing(run)

                // Locate the FATHA glyph: in this font it is the
                // mark glyph (xAdvance == 0, non-zero yOffset).
                val fathaIdx = run.glyphs.indices.first {
                    run.positions[it].xAdvance == 0f && run.positions[it].yOffset != 0f
                }
                val baseIdx = run.glyphs.indices.first {
                    run.positions[it].xAdvance > 0f
                }
                assertTrue(fathaIdx != baseIdx, "Saudi Regular run does not have a separate mark glyph")

                val fathaPaint = resolver.resolve(
                    clusterStart = run.glyphs[fathaIdx].cluster,
                    glyphIndexInCluster = indexInCluster[fathaIdx],
                    glyphsInCluster = glyphsInCluster[fathaIdx],
                )
                assertEquals(
                    Color.Red,
                    fathaPaint.color,
                    "FATHA mark must resolve to the red span on its source codepoint",
                )

                val basePaint = resolver.resolve(
                    clusterStart = run.glyphs[baseIdx].cluster,
                    glyphIndexInCluster = indexInCluster[baseIdx],
                    glyphsInCluster = glyphsInCluster[baseIdx],
                )
                assertEquals(
                    Color.Black,
                    basePaint.color,
                    "LAM-ALEF base must keep the default color (no span on LAM/ALEF)",
                )
            }
        }
    }

    /**
     * Mirror of the per-glyph `glyphsInCluster` / `indexInCluster`
     * pre-pass in `drawShapedTextStyledInternal` so the test's resolver
     * inputs match what the live draw produces.
     */
    private fun computeClusterIndexing(run: com.mohamedrejeb.harfbuzz.core.ShapedRun): Pair<IntArray, IntArray> {
        val glyphs = run.glyphs
        val isRtl = run.direction == HbDirection.RTL
        val counts = HashMap<Int, Int>(glyphs.size)
        for (g in glyphs) counts[g.cluster] = (counts[g.cluster] ?: 0) + 1
        val seen = HashMap<Int, Int>(counts.size)
        val total = IntArray(glyphs.size)
        val index = IntArray(glyphs.size)
        for (i in glyphs.indices) {
            val cl = glyphs[i].cluster
            val totalForCluster = counts.getValue(cl)
            total[i] = totalForCluster
            val bufferIndex = seen.getOrElse(cl) { 0 }
            index[i] = if (isRtl) totalForCluster - 1 - bufferIndex else bufferIndex
            seen[cl] = bufferIndex + 1
        }
        return total to index
    }
}
