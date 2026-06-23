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

class KashidaStretchJustifierTest {

    private companion object {
        const val TATWEEL = 'ـ'
        const val OTHER = 'ب'
    }

    @Test
    fun identity_when_paragraph_empty() {
        val empty = ShapedParagraph.EMPTY
        assertSame(empty, KashidaStretchJustifier.stretchToWidth(empty, 100f, ""))
    }

    @Test
    fun identity_when_target_equals_current() {
        // current 28 -> scale 1 -> no-op (same instance).
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, 28f, text(3, setOf(1))))
    }

    @Test
    fun identity_when_target_at_or_below_nonTatweel_width() {
        // nonTatweel = 20 -> target <= 20 is unreachable by any tatweel width.
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        val st = text(3, setOf(1))
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, 20f, st))
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, 12f, st))
    }

    @Test
    fun identity_when_target_non_finite() {
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        val st = text(3, setOf(1))
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, Float.NaN, st))
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, Float.POSITIVE_INFINITY, st))
    }

    @Test
    fun identity_when_no_kashida_in_shaped_text_even_if_target_larger() {
        // No glyph maps to a U+0640 source char -> nothing to stretch.
        val p = line(advances = listOf(10f, 10f, 10f), tatweelIndices = emptySet())
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, 100f, text(3, emptySet())))
    }

    @Test
    fun identity_when_glyph_id_matches_but_source_char_is_not_tatweel() {
        // Robustness: matching is by source char, not glyph id. A line with no
        // U+0640 in its text is never stretched even though glyphs carry advance.
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        assertSame(p, KashidaStretchJustifier.stretchToWidth(p, 40f, "ابج"))
    }

    @Test
    fun shrinks_single_overshooting_tatweel_to_subtatweel_target() {
        // current 28, nonTatweel 20, tatweel 8 -> s = (25-20)/8 = 0.625 (shrink).
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        val out = KashidaStretchJustifier.stretchToWidth(p, 25f, text(3, setOf(1)))
        assertNotSame(p, out)
        assertEquals(25f, out.totalAdvance, 1e-3f)
        val pos = out.runs.first().positions
        assertEquals(5f, pos[1].xAdvance, 1e-3f)
        assertEquals(0.625f, pos[1].xScale, 1e-3f)
    }

    @Test
    fun single_tatweel_scaled_to_land_on_target() {
        // current 28, non-tatweel 20, tatweel 8 -> s = (40-20)/8 = 2.5
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        val out = KashidaStretchJustifier.stretchToWidth(p, 40f, text(3, setOf(1)))
        assertNotSame(p, out)
        assertEquals(40f, out.totalAdvance, 1e-3f)
        val pos = out.runs.first().positions
        assertEquals(20f, pos[1].xAdvance, 1e-3f)
        assertEquals(2.5f, pos[1].xScale, 1e-3f)
        assertEquals(10f, pos[0].xAdvance, 1e-3f)
        assertEquals(10f, pos[2].xAdvance, 1e-3f)
        assertEquals(1f, pos[0].xScale, 1e-3f)
        assertEquals(1f, pos[2].xScale, 1e-3f)
    }

    @Test
    fun multiple_tatweels_share_one_uniform_scale() {
        // current 42, non-tatweel 30, tatweel 12 -> s = (54-30)/12 = 2.0
        val p = line(advances = listOf(10f, 6f, 10f, 6f, 10f), tatweelIndices = setOf(1, 3))
        val out = KashidaStretchJustifier.stretchToWidth(p, 54f, text(5, setOf(1, 3)))
        val advances = out.runs.first().positions.map { it.xAdvance }
        val scales = out.runs.first().positions.map { it.xScale }
        assertEquals(listOf(10f, 12f, 10f, 12f, 10f), advances)
        assertEquals(listOf(1f, 2f, 1f, 2f, 1f), scales)
        assertEquals(54f, out.totalAdvance, 1e-3f)
    }

    @Test
    fun lands_exactly_on_target_so_lines_match() {
        val a = line(advances = listOf(10f, 7f, 10f), tatweelIndices = setOf(1))
        val b = line(advances = listOf(10f, 5f, 10f, 5f, 12f), tatweelIndices = setOf(1, 3))
        val outA = KashidaStretchJustifier.stretchToWidth(a, 60f, text(3, setOf(1)))
        val outB = KashidaStretchJustifier.stretchToWidth(b, 60f, text(5, setOf(1, 3)))
        assertEquals(60f, outA.totalAdvance, 1e-4f)
        assertEquals(60f, outB.totalAdvance, 1e-4f)
        assertEquals(outA.totalAdvance, outB.totalAdvance, 1e-4f)
    }

    @Test
    fun width_is_continuous_in_target_no_quantization() {
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1))
        val st = text(3, setOf(1))
        val out1 = KashidaStretchJustifier.stretchToWidth(p, 40.0f, st)
        val out2 = KashidaStretchJustifier.stretchToWidth(p, 40.5f, st)
        assertEquals(0.5f, out2.totalAdvance - out1.totalAdvance, 1e-3f)
    }

    @Test
    fun preserves_glyph_ids_offsets_and_y_advance() {
        val p = line(
            advances = listOf(10f, 8f, 10f),
            tatweelIndices = setOf(1),
            xOffsets = listOf(1f, 2f, 3f),
            yOffsets = listOf(4f, 5f, 6f),
        )
        val out = KashidaStretchJustifier.stretchToWidth(p, 40f, text(3, setOf(1)))
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
    fun ink_and_logical_extend_by_growth() {
        val p = line(advances = listOf(10f, 8f, 10f), tatweelIndices = setOf(1)) // ink right = 28
        val out = KashidaStretchJustifier.stretchToWidth(p, 40f, text(3, setOf(1)))
        assertEquals(40f, out.ink.right, 1e-3f)
        assertEquals(0f, out.ink.left, 1e-3f)
        assertEquals(40f, out.logical.right - out.logical.left, 1e-3f)
    }

    @Test
    fun tatweels_across_two_runs_share_scale_and_land_exactly() {
        // current 36, non-tatweel 24, tatweel 12 -> s = (52-24)/12.
        // Global clusters: run0 -> 0,1 ; run1 -> 2,3,4. Tatweel at clusters 1 and 3.
        val run0 = run(advances = listOf(10f, 6f), clusters = listOf(0, 1))
        val run1 = run(advances = listOf(10f, 6f, 4f), clusters = listOf(2, 3, 4))
        val total = run0.totalAdvance + run1.totalAdvance
        val p = ShapedParagraph(
            runs = listOf(run0, run1),
            baseDirection = HbDirection.LTR,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            logicalToVisual = IntArray(0),
            visualToLogical = IntArray(0),
        )
        val out = KashidaStretchJustifier.stretchToWidth(p, 52f, text(5, setOf(1, 3)))
        assertEquals(52f, out.totalAdvance, 1e-3f)
        val s = (52f - 24f) / 12f
        assertEquals(6f * s, out.runs[0].positions[1].xAdvance, 1e-3f)
        assertEquals(6f * s, out.runs[1].positions[1].xAdvance, 1e-3f)
        assertEquals(s, out.runs[0].positions[1].xScale, 1e-3f)
        assertEquals(s, out.runs[1].positions[1].xScale, 1e-3f)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Shaped-text string of [glyphCount] chars: U+0640 at [tatweelIndices], else a letter. */
    private fun text(glyphCount: Int, tatweelIndices: Set<Int>): String =
        buildString { repeat(glyphCount) { append(if (it in tatweelIndices) TATWEEL else OTHER) } }

    private fun line(
        advances: List<Float>,
        tatweelIndices: Set<Int>,
        xOffsets: List<Float>? = null,
        yOffsets: List<Float>? = null,
    ): ShapedParagraph {
        // Single run: glyph i has cluster i, matching `text(...)` indexing.
        val r = run(
            advances = advances,
            clusters = advances.indices.toList(),
            xOffsets = xOffsets,
            yOffsets = yOffsets,
        )
        val total = r.totalAdvance
        return ShapedParagraph(
            runs = listOf(r),
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
        clusters: List<Int>,
        xOffsets: List<Float>? = null,
        yOffsets: List<Float>? = null,
    ): ShapedRun {
        val glyphs = advances.indices.map { i ->
            GlyphInfo(glyphId = 1000 + i, cluster = clusters[i], flags = 0)
        }
        val positions = advances.indices.map { i ->
            GlyphPosition(
                xAdvance = advances[i],
                yAdvance = 0f,
                xOffset = xOffsets?.get(i) ?: 0f,
                yOffset = yOffsets?.get(i) ?: 0f,
            )
        }
        val total = advances.sum()
        return ShapedRun(
            glyphs = glyphs,
            positions = positions,
            direction = HbDirection.LTR,
            script = HbScript.ARABIC,
            totalAdvance = total,
            ink = HbRect(0f, -10f, total, 0f),
            logical = HbRect(0f, -10f, total, 2f),
            font = null,
        )
    }
}
