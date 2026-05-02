package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.mohamedrejeb.harfbuzz.core.HbFont
import kotlin.math.PI

/**
 * Lay out [measured]'s shaped paragraph along [path], rendering each
 * glyph perpendicular to the local tangent.
 *
 * Mirrors the shape of [drawShapedText]: takes a pre-built `MeasuredText`
 * (shape once, draw many) and walks its runs in visual order. Per-glyph
 * rendering goes through the same 5-strategy ladder as `drawShapedText`
 * (forceForegroundColor → SVG-in-OT → COLR v1 → COLR v0 → monochrome),
 * so every color-glyph format the library supports works on path text
 * without special cases.
 *
 * Multi-contour paths: only the first contour is walked. Multi-contour
 * traversal is future work.
 */
public fun DrawScope.drawTextAlongPath(
    measured: MeasuredText,
    path: Path,
    color: Color = Color.Black,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    startOffset: Float = 0f,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    autoFlip: Boolean = false,
    forceForegroundColor: Boolean = false,
) {
    if (measured.isEmpty) return

    // Resolve per-run caches up-front for every font that contributes to
    // the paragraph so the per-glyph emit lambda hits one constant-time
    // map lookup per glyph instead of recomputing the four outer
    // `Map<HbFont, _>` lookups + three render-strategy booleans every
    // call. Typical paragraphs touch 1–3 fonts, so this map stays tiny.
    val cachesByFont = HashMap<HbFont, RunGlyphCaches>()
    for (run in measured.paragraph.runs) {
        val font = run.font ?: measured.font
        if (font !in cachesByFont) {
            cachesByFont[font] = measured.runCachesFor(font, forceForegroundColor)
        }
    }

    walkPathText(
        paragraph = measured.paragraph,
        primaryFont = measured.font,
        path = path,
        startOffset = startOffset,
        side = side,
        alignment = alignment,
        overflow = overflow,
        autoFlip = autoFlip,
    ) { p ->
        val pos = p.glyphPosition
        val caches = cachesByFont[p.runFont]
            ?: measured.runCachesFor(p.runFont, forceForegroundColor)
        // Apply the transform stack from spec §7.2 (innermost → outermost).
        translate(left = p.position.x, top = p.position.y) {
            rotateRad(radians = p.angleRadians, pivot = Offset.Zero) {
                val whenFlip = if (p.flipDueToAutoFlip) PI.toFloat() else 0f
                rotateRad(radians = whenFlip, pivot = Offset.Zero) {
                    val sideScaleY = if (side == TextOnPathSide.Below) -1f else 1f
                    scale(scaleX = 1f, scaleY = sideScaleY, pivot = Offset.Zero) {
                        translate(
                            left = -pos.xAdvance / 2f + pos.xOffset,
                            top = -pos.yOffset,
                        ) {
                            drawOneGlyphAtOrigin(
                                runFont = p.runFont,
                                caches = caches,
                                glyphId = p.run.glyphs[p.glyphIndexInRun].glyphId,
                                color = color,
                                alpha = alpha,
                                style = style,
                                blendMode = blendMode,
                            )
                        }
                    }
                }
            }
        }
    }
}
