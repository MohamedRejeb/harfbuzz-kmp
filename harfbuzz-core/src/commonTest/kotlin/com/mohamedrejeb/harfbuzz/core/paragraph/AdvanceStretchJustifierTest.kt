package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.GlyphInfo
import com.mohamedrejeb.harfbuzz.core.GlyphPosition
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbRect
import com.mohamedrejeb.harfbuzz.core.HbScript
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.ShapedRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AdvanceStretchJustifierTest {

    @Test
    fun identity_when_paragraph_empty() {
        val empty = ShapedParagraph.EMPTY
        assertSame(empty, AdvanceStretchJustifier.stretch(empty, 100f))
    }

    @Test
    fun identity_when_target_at_or_below_current() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f)))
        assertSame(p, AdvanceStretchJustifier.stretch(p, 30f))
        assertSame(p, AdvanceStretchJustifier.stretch(p, 25f))
    }

    @Test
    fun identity_when_target_non_finite_or_non_positive() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f)))
        assertSame(p, AdvanceStretchJustifier.stretch(p, 0f))
        assertSame(p, AdvanceStretchJustifier.stretch(p, -10f))
        assertSame(p, AdvanceStretchJustifier.stretch(p, Float.POSITIVE_INFINITY))
        assertSame(p, AdvanceStretchJustifier.stretch(p, Float.NaN))
    }

    @Test
    fun identity_when_only_one_glyph() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f)))
        assertSame(p, AdvanceStretchJustifier.stretch(p, 50f))
    }

    @Test
    fun stretch_to_target_distributes_uniformly_across_gaps() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f, 10f)))
        val out = AdvanceStretchJustifier.stretch(p, 50f)
        assertNotSame(p, out)
        assertEquals(50f, out.totalAdvance, 1e-3f)
        val advances = out.runs.first().positions.map { it.xAdvance }
        // 4 glyphs, current 40, target 50 -> delta 10 spread over 3 gaps
        // (the trailing edge of the last glyph stays unchanged).
        val expected = 10f / 3f
        assertEquals(10f + expected, advances[0], 1e-3f)
        assertEquals(10f + expected, advances[1], 1e-3f)
        assertEquals(10f + expected, advances[2], 1e-3f)
        assertEquals(10f, advances[3], 1e-3f)
    }

    @Test
    fun stretch_across_two_runs_distributes_globally() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f), listOf(10f, 10f)))
        val out = AdvanceStretchJustifier.stretch(p, 60f)
        assertEquals(60f, out.totalAdvance, 1e-3f)
        // 4 glyphs total, current 40, target 60 -> delta 20 spread over 3 gaps.
        val expected = 20f / 3f
        val all = out.runs.flatMap { run -> run.positions.map { it.xAdvance } }
        assertEquals(10f + expected, all[0], 1e-3f)
        assertEquals(10f + expected, all[1], 1e-3f)
        assertEquals(10f + expected, all[2], 1e-3f)
        assertEquals(10f, all[3], 1e-3f)
    }

    @Test
    fun stretch_preserves_glyph_ids_offsets_and_y_advance() {
        val p = paragraph(
            advancesPerRun = listOf(listOf(10f, 10f)),
            yAdvances = listOf(listOf(0f, 0f)),
            xOffsets = listOf(listOf(1f, 2f)),
            yOffsets = listOf(listOf(3f, 4f)),
        )
        val out = AdvanceStretchJustifier.stretch(p, 30f)
        val before = p.runs.first()
        val after = out.runs.first()
        assertEquals(before.glyphs, after.glyphs)
        for (i in before.positions.indices) {
            assertEquals(before.positions[i].yAdvance, after.positions[i].yAdvance)
            assertEquals(before.positions[i].xOffset, after.positions[i].xOffset)
            assertEquals(before.positions[i].yOffset, after.positions[i].yOffset)
        }
    }

    @Test
    fun stretch_run_helper_respects_gap_count() {
        val run = run(advances = listOf(10f, 10f, 10f))
        val stretched = AdvanceStretchJustifier.stretchRun(run, extraPerGap = 2f, gapsInRun = 2)
        assertEquals(34f, stretched.totalAdvance, 1e-3f)
        assertEquals(listOf(12f, 12f, 10f), stretched.positions.map { it.xAdvance })
    }

    @Test
    fun stretch_run_helper_clamps_gap_count_to_glyph_count() {
        val run = run(advances = listOf(10f, 10f))
        val stretched = AdvanceStretchJustifier.stretchRun(run, extraPerGap = 5f, gapsInRun = 999)
        // Clamped to 2 glyphs -> +5 per glyph -> +10 total.
        assertEquals(30f, stretched.totalAdvance, 1e-3f)
    }

    @Test
    fun stretch_run_helper_identity_for_zero_or_negative_extra() {
        val run = run(advances = listOf(10f, 10f))
        assertSame(run, AdvanceStretchJustifier.stretchRun(run, extraPerGap = 0f, gapsInRun = 1))
        assertSame(run, AdvanceStretchJustifier.stretchRun(run, extraPerGap = -1f, gapsInRun = 1))
        assertSame(run, AdvanceStretchJustifier.stretchRun(run, extraPerGap = 5f, gapsInRun = 0))
    }

    @Test
    fun logical_rect_extends_with_total_advance() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f)))
        val out = AdvanceStretchJustifier.stretch(p, 60f)
        assertEquals(60f, out.logical.right - out.logical.left, 1e-3f)
        assertTrue(out.logical.right >= p.logical.right)
    }

    // --- applyLetterSpacing (signed, per-cluster) ---

    @Test
    fun letterSpacing_identity_for_zero_or_non_finite() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f)))
        assertSame(p, AdvanceStretchJustifier.applyLetterSpacing(p, 0f))
        assertSame(p, AdvanceStretchJustifier.applyLetterSpacing(p, Float.NaN))
        assertSame(p, AdvanceStretchJustifier.applyLetterSpacing(p, Float.POSITIVE_INFINITY))
    }

    @Test
    fun letterSpacing_identity_for_single_cluster() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f)))
        assertSame(p, AdvanceStretchJustifier.applyLetterSpacing(p, 5f))
    }

    @Test
    fun letterSpacing_positive_total_is_perGap_times_glyphCount() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f, 10f)))
        val out = AdvanceStretchJustifier.applyLetterSpacing(p, 5f)
        assertNotSame(p, out)
        // iOS parity: total added = perGap(5) * glyphCount(4) = 20, spread over
        // the 3 inter-cluster gaps; the last glyph is unchanged.
        assertEquals(60f, out.totalAdvance, 1e-3f)
        val advances = out.runs.first().positions.map { it.xAdvance }
        assertEquals(10f, advances[3], 1e-3f)
        val perGap = 5f * 4f / 3f
        assertEquals(10f + perGap, advances[0], 1e-3f)
        assertEquals(10f + perGap, advances[1], 1e-3f)
        assertEquals(10f + perGap, advances[2], 1e-3f)
    }

    @Test
    fun letterSpacing_negative_tightens_gap_last_unchanged() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f)))
        val out = AdvanceStretchJustifier.applyLetterSpacing(p, -3f)
        // total = -3 * glyphCount(2) = -6 on the single gap; last unchanged.
        assertEquals(listOf(4f, 10f), out.runs.first().positions.map { it.xAdvance })
        assertEquals(14f, out.totalAdvance, 1e-3f)
    }

    @Test
    fun letterSpacing_negative_floored_at_fraction_of_advance() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f)))
        // -100 would invert the glyph; floored at 0.1 * 10 = 1f.
        val out = AdvanceStretchJustifier.applyLetterSpacing(p, -100f, minClusterAdvanceFraction = 0.1f)
        assertEquals(listOf(1f, 10f), out.runs.first().positions.map { it.xAdvance })
    }

    @Test
    fun letterSpacing_is_per_cluster_not_per_glyph() {
        // Two glyphs share cluster 0 (base + mark); one glyph is cluster 1.
        val run = clusteredRun(advances = listOf(10f, 10f, 10f), clusters = listOf(0, 0, 1))
        val total = run.totalAdvance
        val p = ShapedParagraph(
            runs = listOf(run),
            baseDirection = HbDirection.LTR,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
        val out = AdvanceStretchJustifier.applyLetterSpacing(p, 4f)
        // total = perGap(4) * glyphCount(3) = 12, all in the single cluster gap
        // (cluster 0 -> cluster 1, glyph index 1). No spacing inside the shared
        // cluster (glyph 0), none after the last glyph.
        assertEquals(listOf(10f, 22f, 10f), out.runs.first().positions.map { it.xAdvance })
        assertEquals(42f, out.totalAdvance, 1e-3f)
    }

    @Test
    fun letterSpacing_across_two_runs_spaces_run_boundary() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f), listOf(10f, 10f)))
        val out = AdvanceStretchJustifier.applyLetterSpacing(p, 2f)
        // total = perGap(2) * glyphCount(4) = 8, over 3 gaps (incl. the run
        // boundary); last unchanged.
        assertEquals(48f, out.totalAdvance, 1e-3f)
        val all = out.runs.flatMap { run -> run.positions.map { it.xAdvance } }
        assertEquals(10f, all[3], 1e-3f)
        val perGap = 2f * 4f / 3f
        assertEquals(10f + perGap, all[0], 1e-3f)
        assertEquals(10f + perGap, all[1], 1e-3f)
        assertEquals(10f + perGap, all[2], 1e-3f)
    }

    // ── fillToWidth (exact paragraph justification) ──────────────────────────

    @Test
    fun fillToWidth_identity_when_target_at_or_below_current() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f)))
        assertSame(p, AdvanceStretchJustifier.fillToWidth(p, 30f))
        assertSame(p, AdvanceStretchJustifier.fillToWidth(p, 20f))
        assertSame(p, AdvanceStretchJustifier.fillToWidth(p, Float.NaN))
    }

    @Test
    fun fillToWidth_lands_exactly_on_target_across_cluster_gaps() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f, 10f)))
        val out = AdvanceStretchJustifier.fillToWidth(p, 50f)
        assertNotSame(p, out)
        // Total lands on the target exactly; the last glyph's trailing edge is untouched.
        assertEquals(50f, out.totalAdvance)
        val advances = out.runs.first().positions.map { it.xAdvance }
        assertEquals(10f, advances[3], 1e-3f)
        assertEquals(10f + 10f / 3f, advances[0], 1e-3f)
        assertEquals(10f + 10f / 3f, advances[1], 1e-3f)
        assertEquals(10f + 10f / 3f, advances[2], 1e-3f)
        // The fp remainder is absorbed by the last gap so the sum is exact.
        assertEquals(50f, advances.sum(), 1e-3f)
    }

    @Test
    fun fillToWidth_distributes_only_into_explicit_gaps() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f, 10f, 10f)))
        // Only glyph index 1 is elastic (e.g. the single inter-word space).
        val out = AdvanceStretchJustifier.fillToWidth(p, 50f, elasticGlyphIndices = intArrayOf(1))
        // 10px of fill, all onto glyph 1's trailing gap.
        assertEquals(listOf(10f, 20f, 10f, 10f), out.runs.first().positions.map { it.xAdvance })
        assertEquals(50f, out.totalAdvance)
    }

    @Test
    fun fillToWidth_updates_ink_trailing_edge_ltr() {
        val p = paragraph(advancesPerRun = listOf(listOf(10f, 10f))) // ink right = 20
        val out = AdvanceStretchJustifier.fillToWidth(p, 40f)
        // +20 of fill extends the LTR ink right edge by the same amount.
        assertEquals(40f, out.ink.right, 1e-3f)
        assertEquals(0f, out.ink.left, 1e-3f)
    }

    @Test
    fun fillToWidth_rtl_grows_trailing_ink_edge_like_ltr() {
        val run = run(advances = listOf(10f, 10f)).copy(direction = HbDirection.RTL)
        val total = run.totalAdvance
        val p = ShapedParagraph(
            runs = listOf(run),
            baseDirection = HbDirection.RTL,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
        val out = AdvanceStretchJustifier.fillToWidth(p, 40f)
        assertEquals(40f, out.totalAdvance, 1e-3f)
        // RTL content also occupies pen-local [0, advance] (RTL-ness is in glyph
        // order + alignment, not negative pen coords), so filling grows ink.right
        // — NOT ink.left. The old left-edge behaviour mis-positioned RTL justified
        // lines (caught by JustifyScreenshotTest).
        assertEquals(0f, out.ink.left, 1e-3f)
        assertEquals(40f, out.ink.right, 1e-3f)
    }

    private fun clusteredRun(advances: List<Float>, clusters: List<Int>): ShapedRun {
        val glyphs = advances.indices.map { GlyphInfo(glyphId = it + 1, cluster = clusters[it], flags = 0) }
        val positions = advances.map { GlyphPosition(xAdvance = it, yAdvance = 0f, xOffset = 0f, yOffset = 0f) }
        val total = advances.sum()
        return ShapedRun(
            glyphs = glyphs,
            positions = positions,
            direction = HbDirection.LTR,
            script = HbScript.LATIN,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            font = null,
        )
    }

    private fun paragraph(
        advancesPerRun: List<List<Float>>,
        yAdvances: List<List<Float>>? = null,
        xOffsets: List<List<Float>>? = null,
        yOffsets: List<List<Float>>? = null,
    ): ShapedParagraph {
        val runs = advancesPerRun.mapIndexed { runIndex, advances ->
            run(
                advances = advances,
                yAdvances = yAdvances?.get(runIndex),
                xOffsets = xOffsets?.get(runIndex),
                yOffsets = yOffsets?.get(runIndex),
            )
        }
        val total = runs.sumOf { it.totalAdvance.toDouble() }.toFloat()
        return ShapedParagraph(
            runs = runs,
            baseDirection = HbDirection.LTR,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
    }

    private fun run(
        advances: List<Float>,
        yAdvances: List<Float>? = null,
        xOffsets: List<Float>? = null,
        yOffsets: List<Float>? = null,
    ): ShapedRun {
        val glyphs = advances.indices.map { GlyphInfo(glyphId = it + 1, cluster = it, flags = 0) }
        val positions = advances.indices.map { i ->
            GlyphPosition(
                xAdvance = advances[i],
                yAdvance = yAdvances?.get(i) ?: 0f,
                xOffset = xOffsets?.get(i) ?: 0f,
                yOffset = yOffsets?.get(i) ?: 0f,
            )
        }
        val total = advances.sum()
        return ShapedRun(
            glyphs = glyphs,
            positions = positions,
            direction = HbDirection.LTR,
            script = HbScript.LATIN,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            font = null,
        )
    }
}
