package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.floor

/**
 * Per-glyph draw-time adjustment applied by the `drawShapedText` family
 * when a [GlyphEffectProvider] is supplied. [alpha] multiplies into the
 * alpha the render pass already uses, [translateX] / [translateY] offset
 * the glyph from its pen origin in pixels, and [scale] grows or shrinks
 * the glyph about its baseline pen origin so it scales in place.
 *
 * Effects are applied at draw time only: cached glyph paths are never
 * mutated, so animating effects frame by frame reuses the same shaped
 * layout and path caches.
 */
public data class GlyphEffect(
    public val alpha: Float = 1f,
    public val translateX: Float = 0f,
    public val translateY: Float = 0f,
    public val scale: Float = 1f,
) {
    public companion object {
        /** Identity effect: the glyph renders exactly as it would without a provider. */
        public val NONE: GlyphEffect = GlyphEffect()
    }
}

/**
 * Supplies a [GlyphEffect] for every glyph a draw call walks. Invoked
 * once per glyph per render pass (fill, stroke, shadow), so
 * implementations should be cheap and allocation-free where possible.
 */
public fun interface GlyphEffectProvider {
    /**
     * [runIndex] / [glyphIndex] address `measured.paragraph.runs` order;
     * [cluster] is `GlyphInfo.cluster` (source UTF-16 index), shared by
     * every glyph of one cluster, so marks can be keyed with their base.
     */
    public fun effectFor(runIndex: Int, glyphIndex: Int, cluster: Int): GlyphEffect
}

/**
 * Number of quantized alpha levels used when unified-silhouette passes
 * (stroke, brush) group glyphs by effect alpha.
 */
internal const val GLYPH_EFFECT_ALPHA_BUCKET_COUNT = 8

/**
 * Quantize [alpha] to one of [GLYPH_EFFECT_ALPHA_BUCKET_COUNT] levels
 * (floor semantics, capped at 1). Unified-silhouette passes cannot vary
 * alpha per glyph inside one path, so glyphs are grouped by this key and
 * each bucket draws one union. A quantized value at or below zero means
 * the glyph is dropped from the pass.
 */
internal fun quantizeGlyphEffectAlpha(alpha: Float): Float =
    floor(alpha.coerceAtMost(1f) * GLYPH_EFFECT_ALPHA_BUCKET_COUNT) / GLYPH_EFFECT_ALPHA_BUCKET_COUNT

/**
 * Append [glyph] to [target] at the pen origin `(drawX, drawY)` with
 * [effect]'s translate and scale applied. Scale pivots at the pen origin
 * so the glyph grows in place, matching the transform the per-glyph draw
 * passes apply. The cached [glyph] path is never mutated; a scaled copy
 * is created only when the effect actually scales.
 */
internal fun appendGlyphPathWithEffect(
    target: Path,
    glyph: Path,
    drawX: Float,
    drawY: Float,
    effect: GlyphEffect?,
) {
    if (effect == null || effect.scale == 1f) {
        val dx = drawX + (effect?.translateX ?: 0f)
        val dy = drawY + (effect?.translateY ?: 0f)
        target.addPath(glyph, Offset(dx, dy))
        return
    }
    val scaled = Path()
    scaled.addPath(glyph)
    scaled.transform(Matrix().apply { scale(x = effect.scale, y = effect.scale) })
    target.addPath(scaled, Offset(drawX + effect.translateX, drawY + effect.translateY))
}

/**
 * Run [block] under [effect]'s transform: translated to the glyph pen
 * origin plus the effect offset, then scaled about that origin. With a
 * null effect (or an identity transform) this reduces to the plain pen
 * translate the static draw path performs.
 */
internal inline fun DrawScope.withGlyphEffectTransform(
    effect: GlyphEffect?,
    drawX: Float,
    drawY: Float,
    crossinline block: DrawScope.() -> Unit,
) {
    val scaleFactor = effect?.scale ?: 1f
    val dx = drawX + (effect?.translateX ?: 0f)
    val dy = drawY + (effect?.translateY ?: 0f)
    translate(left = dx, top = dy) {
        if (scaleFactor == 1f) {
            block()
        } else {
            scale(scale = scaleFactor, pivot = Offset.Zero) {
                block()
            }
        }
    }
}
