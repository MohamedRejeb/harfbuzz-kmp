package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.ShapedRun
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
 * A non-Fill [style] (e.g. `Stroke`) routes every glyph to the silhouette
 * outline so the stroke applies to the actual glyph shape - same rule
 * as [drawShapedText].
 *
 * If [shadow] is non-null, the silhouette of every placed glyph is
 * stamped with a Gaussian-blurred copy underneath, translated by
 * `shadow.offset` in screen space. The shadow rotates with each glyph's
 * tangent, matching how Compose's `BasicText` text-on-path shadow works.
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
    shadow: Shadow? = null,
    /**
     * Optional styled source. Spans resolve a per-glyph fill color using
     * the same cluster mapping as [drawShapedText]'s styled path, so
     * tashkeel / per-range coloring works on path text too. Only the
     * span's *color* applies here (brush spans fall back to their
     * resolved color); color glyphs (emoji) paint their own colors and
     * are unaffected. `styledText.text` MUST equal the shaped source
     * string of [measured].
     */
    styledText: StyledText? = null,
) {
    if (measured.isEmpty) return

    val resolver = if (styledText != null && styledText.spans.isNotEmpty()) {
        PaintResolver(
            spans = styledText.spans,
            clusterEnds = clusterEndArray(measured.paragraph, measured.textLength),
            clusterText = styledText.text,
            defaultColor = color,
            defaultBrush = null,
        )
    } else {
        null
    }
    // Per-run cluster indexing for the resolver, computed once per run
    // instead of per glyph.
    val clusterIndexingByRun = if (resolver != null) {
        HashMap<ShapedRun, Pair<IntArray, IntArray>>().apply {
            for (run in measured.paragraph.runs) {
                this[run] = clusterIndexingForRun(run.glyphs, run.direction == HbDirection.RTL)
            }
        }
    } else {
        null
    }

    // Resolve per-run caches up-front for every font that contributes to
    // the paragraph so the per-glyph emit lambda hits one constant-time
    // map lookup per glyph instead of recomputing the four outer
    // `Map<HbFont, _>` lookups + three render-strategy booleans every
    // call. Typical paragraphs touch 1–3 fonts, so this map stays tiny.
    val cachesByFont = HashMap<HbFont, RunGlyphCaches>()
    for (run in measured.paragraph.runs) {
        val font = run.font ?: measured.font
        if (font !in cachesByFont) {
            cachesByFont[font] = measured.runCachesFor(font, forceForegroundColor, style)
        }
    }

    if (shadow != null) {
        drawShadowAlongPath(
            measured = measured,
            path = path,
            shadow = shadow,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            startOffset = startOffset,
            side = side,
            alignment = alignment,
            overflow = overflow,
            autoFlip = autoFlip,
        )
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
            ?: measured.runCachesFor(p.runFont, forceForegroundColor, style)
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
                            val glyphColor = if (resolver != null && clusterIndexingByRun != null) {
                                val (glyphsInCluster, indexInCluster) =
                                    clusterIndexingByRun.getValue(p.run)
                                resolver.resolve(
                                    clusterStart = p.run.glyphs[p.glyphIndexInRun].cluster,
                                    glyphIndexInCluster = indexInCluster[p.glyphIndexInRun],
                                    glyphsInCluster = glyphsInCluster[p.glyphIndexInRun],
                                ).color
                            } else {
                                color
                            }
                            drawOneGlyphAtOrigin(
                                runFont = p.runFont,
                                sizePx = measured.sizePx,
                                caches = caches,
                                glyphId = p.run.glyphs[p.glyphIndexInRun].glyphId,
                                color = glyphColor,
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

/**
 * Walk the path text once and stamp each glyph's silhouette under a
 * shadow paint. The shadow is offset in screen space (via the outer
 * canvas translate), so it follows each glyph's tangent rotation but
 * does not rotate itself relative to the screen.
 */
private fun DrawScope.drawShadowAlongPath(
    measured: MeasuredText,
    path: Path,
    shadow: Shadow,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
    startOffset: Float,
    side: TextOnPathSide,
    alignment: TextOnPathAlignment,
    overflow: TextOnPathOverflow,
    autoFlip: Boolean,
) {
    val paint = Paint().apply {
        color = shadow.color
        // Keep the shadow's own opacity (color.alpha) — assigning alpha alone drops it.
        this.alpha = alpha * shadow.color.alpha
        this.blendMode = blendMode
        applyDrawStyleToPaint(this, style)
        configureShadowBlur(this, shadow.blurRadius)
    }
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(shadow.offset.x, shadow.offset.y)
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
            val flipped = measured.flippedPathsByFont[p.runFont] ?: emptyMap()
            val gid = p.run.glyphs[p.glyphIndexInRun].glyphId
            val glyphPath = flipped[gid] ?: return@walkPathText
            canvas.save()
            canvas.translate(p.position.x, p.position.y)
            canvas.rotate(p.angleRadians * (180f / PI.toFloat()))
            if (p.flipDueToAutoFlip) canvas.rotate(180f)
            val sideScaleY = if (side == TextOnPathSide.Below) -1f else 1f
            if (sideScaleY != 1f) canvas.scale(1f, sideScaleY)
            canvas.translate(-pos.xAdvance / 2f + pos.xOffset, -pos.yOffset)
            canvas.drawPath(glyphPath, paint)
            canvas.restore()
        }
        canvas.restore()
    }
}
