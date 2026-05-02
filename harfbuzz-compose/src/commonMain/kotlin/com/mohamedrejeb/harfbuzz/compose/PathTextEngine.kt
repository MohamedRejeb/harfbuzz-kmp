package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import com.mohamedrejeb.harfbuzz.core.GlyphPosition
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.ShapedRun
import kotlin.math.atan2

/**
 * Per-glyph placement emitted by [walkPathText]. Drawing transforms the
 * canvas using these values; bounds calculation transforms a per-glyph
 * extent rect.
 *
 * @property runFont The font that produced this glyph (matters for
 *   multi-font fallback runs - caches and metrics are font-keyed).
 * @property glyphIndexInRun Index into [run].glyphs / [run].positions.
 * @property position World-space anchor on the path (where the glyph's
 *   midpoint sits).
 * @property angleRadians Rotation to align the glyph's baseline with
 *   the local path tangent.
 * @property flipDueToAutoFlip True when `autoFlip` triggered a 180°
 *   counter-rotation for this glyph.
 * @property side Pass-through of the caller's `side` choice; the
 *   draw/bounds arms apply `scale(1, -1)` for [TextOnPathSide.Below].
 * @property glyphPosition Convenience: the underlying [GlyphPosition]
 *   so callers can apply `xOffset`/`yOffset`/`xAdvance` to the local
 *   transform without re-indexing.
 */
internal data class PathGlyphPlacement(
    val run: ShapedRun,
    val runFont: HbFont,
    val glyphIndexInRun: Int,
    val position: Offset,
    val angleRadians: Float,
    val flipDueToAutoFlip: Boolean,
    val side: TextOnPathSide,
    val glyphPosition: GlyphPosition,
)

/**
 * Walk the [paragraph] along [path] and emit a [PathGlyphPlacement] for
 * each glyph that survives the overflow rules.
 *
 *  - [TextOnPathOverflow.Clip] (default): glyphs whose midpoint falls
 *    outside `[0, pathLength]` are dropped (no emit).
 *  - [TextOnPathOverflow.Visible]: out-of-range glyphs emit at an
 *    extrapolated position derived from the start/end tangent.
 *  - [TextOnPathOverflow.Compress]: scales per-glyph spacing uniformly
 *    so the full text fits within the path; alignment is forced to
 *    Start because the text is filling the path.
 *
 * `autoFlip` triggers a 180° rotation flag for any glyph whose tangent
 * has `tx < 0` (text would otherwise read mirror-image). Heuristic;
 * see spec §7.6.
 *
 * Multi-contour paths: only the first contour is walked (matches
 * `PathMeasure`'s default behavior). Documented limitation; future work.
 */
internal inline fun walkPathText(
    paragraph: ShapedParagraph,
    primaryFont: HbFont,
    path: Path,
    startOffset: Float,
    side: TextOnPathSide,
    alignment: TextOnPathAlignment,
    overflow: TextOnPathOverflow,
    autoFlip: Boolean,
    emit: (PathGlyphPlacement) -> Unit,
) {
    if (paragraph.runs.isEmpty()) return

    val pathMeasure = PathMeasure().apply { setPath(path, forceClosed = false) }
    val pathLength = pathMeasure.length
    if (pathLength <= 0f) return

    val totalAdvance = paragraph.totalAdvance

    // Compress: scale per-glyph spacing if text overflows available path.
    val effectiveLen = pathLength - startOffset
    val spacingScale = if (
        overflow == TextOnPathOverflow.Compress &&
        totalAdvance > effectiveLen &&
        effectiveLen > 0f
    ) effectiveLen / totalAdvance else 1f

    val alignDelta: Float = if (spacingScale < 1f) 0f else when (alignment) {
        TextOnPathAlignment.Start -> 0f
        TextOnPathAlignment.Center -> (pathLength - totalAdvance * spacingScale) / 2f
        TextOnPathAlignment.End -> pathLength - totalAdvance * spacingScale
    }
    val startDistance = startOffset + alignDelta

    // Precompute end tangents once for Visible overflow.
    val startPos: Offset
    val startTan: Offset
    val endPos: Offset
    val endTan: Offset
    if (overflow == TextOnPathOverflow.Visible) {
        startPos = pathMeasure.getPosition(0f)
        startTan = pathMeasure.getTangent(0f)
        endPos = pathMeasure.getPosition(pathLength)
        endTan = pathMeasure.getTangent(pathLength)
    } else {
        startPos = Offset.Zero
        startTan = Offset(1f, 0f)
        endPos = Offset.Zero
        endTan = Offset(1f, 0f)
    }

    var cumulative = 0f
    for (run in paragraph.runs) {
        val runFont = run.font ?: primaryFont
        for (i in run.glyphs.indices) {
            val pos = run.positions[i]
            val midDist = startDistance + (cumulative + pos.xAdvance / 2f) * spacingScale
            cumulative += pos.xAdvance // advance regardless of clip/visible

            // Determine position + tangent (or skip glyph for Clip).
            val anchor: Offset
            val tangent: Offset
            when {
                midDist in 0f..pathLength -> {
                    anchor = pathMeasure.getPosition(midDist)
                    tangent = pathMeasure.getTangent(midDist)
                }
                overflow == TextOnPathOverflow.Visible && midDist < 0f -> {
                    anchor = Offset(
                        startPos.x + startTan.x * midDist,
                        startPos.y + startTan.y * midDist,
                    )
                    tangent = startTan
                }
                overflow == TextOnPathOverflow.Visible && midDist > pathLength -> {
                    val extra = midDist - pathLength
                    anchor = Offset(
                        endPos.x + endTan.x * extra,
                        endPos.y + endTan.y * extra,
                    )
                    tangent = endTan
                }
                else -> continue // Clip: drop this glyph
            }

            val angle = atan2(tangent.y, tangent.x)
            val flip = autoFlip && tangent.x < 0f

            emit(
                PathGlyphPlacement(
                    run = run,
                    runFont = runFont,
                    glyphIndexInRun = i,
                    position = anchor,
                    angleRadians = angle,
                    flipDueToAutoFlip = flip,
                    side = side,
                    glyphPosition = pos,
                ),
            )
        }
    }
}
