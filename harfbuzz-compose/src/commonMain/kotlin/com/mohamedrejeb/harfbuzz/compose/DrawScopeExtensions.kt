package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mohamedrejeb.harfbuzz.core.ColorLayer
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.replay

/**
 * Draw a previously-measured paragraph at [topLeft]. Walks every run in
 * visual order, advances by the cached per-glyph metrics, and picks one
 * of five render strategies per glyph (in priority order):
 *
 *  1. **forceForegroundColor** - every glyph paints with [color] only.
 *     Bypasses every color path.
 *  2. **SVG-in-OT bitmap** - stamps the cached `ImageBitmap` produced by
 *     [renderSvgGlyph] from the font's `SVG ` table. Highest visual
 *     fidelity for fonts that ship hand-tuned SVG (e.g. Aref Ruqaa Ink
 *     in Google Fonts). Skipped on Android where skiko's `SVGDOM` isn't
 *     available.
 *  3. **COLR v1 paint tree** - replays the cached paint tree through
 *     [ComposePaintSink], rendering gradients, transforms, and composite
 *     layers exactly as the font designed them.
 *  4. **COLR v0 layer stack** - composites the recorded layer stack with
 *     palette colors. Used for legacy color fonts without paint trees.
 *  5. **Monochrome** - fall-through: draw the glyph outline with [color].
 *
 * A non-Fill [style] (e.g. [Stroke]) applies to the monochrome outline
 * path: SVG bitmaps and COLR paint trees fall through to the silhouette
 * outline so the stroke is the user's stroke, not a degenerate frame
 * around a clip rectangle. Pass [forceForegroundColor] = true with
 * [Fill] if you want a flat colored render of a color font.
 *
 * If [shadow] is non-null, every glyph's silhouette path is drawn first
 * with the shadow color and a Gaussian blur underneath the main render.
 * The shadow follows [style], so a stroked text gets a stroked shadow.
 * For color glyphs the shadow uses the silhouette only, which matches
 * Compose's `BasicText` `TextStyle.shadow` convention.
 */
public fun DrawScope.drawShapedText(
    measured: MeasuredText,
    topLeft: Offset = Offset.Zero,
    color: Color = Color.Black,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    if (measured.isEmpty) return

    val baselineY = topLeft.y + measured.baseline
    val penX = topLeft.x
    val penY = baselineY

    if (shadow != null) {
        drawShadowPass(
            measured = measured,
            penX = penX + shadow.offset.x,
            penY = penY + shadow.offset.y,
            shadow = shadow,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
        )
    }

    var loopX = penX
    var loopY = penY

    for (run in measured.paragraph.runs) {
        // Each run is shaped by exactly one font (its [ShapedRun.font]).
        // Multi-font fallback puts every cluster span into its own run with
        // its own font, so cache lookups here MUST go through the run's
        // font - never the MeasuredText-wide one - because glyph ids are
        // font-specific.
        val runFont = run.font ?: measured.font

        // Hoist the four `Map<HbFont, _>` lookups + the three render-strategy
        // booleans out of the per-glyph loop - they're invariant for every
        // glyph in this run, but `drawOneGlyphAtOrigin` used to recompute
        // them per call. For a 200-glyph paragraph this saves ~800 outer-map
        // lookups per draw frame.
        val caches = measured.runCachesFor(runFont, forceForegroundColor, style)

        var localX = loopX
        var localY = loopY
        for (i in run.glyphs.indices) {
            val gid = run.glyphs[i].glyphId
            val pos = run.positions[i]

            // HarfBuzz emits y_offset / y_advance in Y-up convention;
            // Compose's translate top is Y-down, so negate.
            val drawX = localX + pos.xOffset
            val drawY = localY - pos.yOffset

            translate(left = drawX, top = drawY) {
                drawOneGlyphAtOrigin(
                    runFont = runFont,
                    caches = caches,
                    glyphId = gid,
                    color = color,
                    alpha = alpha,
                    style = style,
                    blendMode = blendMode,
                )
            }

            localX += pos.xAdvance
            localY += pos.yAdvance
        }
        loopX = localX
        loopY = localY
    }
}

/**
 * Walk every glyph in [measured] and stamp its silhouette path under a
 * paint configured with [shadow]'s color, alpha multiplier, blend mode,
 * and Gaussian blur. [penX] / [penY] is the shadow's pen origin (already
 * offset by `shadow.offset`).
 */
private fun DrawScope.drawShadowPass(
    measured: MeasuredText,
    penX: Float,
    penY: Float,
    shadow: Shadow,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
) {
    val paint = Paint().apply {
        color = shadow.color
        this.alpha = alpha
        this.blendMode = blendMode
        applyDrawStyleToPaint(this, style)
        configureShadowBlur(this, shadow.blurRadius)
    }
    drawIntoCanvas { canvas ->
        var localPenX = penX
        var localPenY = penY
        for (run in measured.paragraph.runs) {
            val runFont = run.font ?: measured.font
            val flipped = measured.flippedPathsByFont[runFont] ?: emptyMap()
            var localX = localPenX
            var localY = localPenY
            for (i in run.glyphs.indices) {
                val gid = run.glyphs[i].glyphId
                val pos = run.positions[i]
                val drawX = localX + pos.xOffset
                val drawY = localY - pos.yOffset
                val path = flipped[gid]
                if (path != null) {
                    canvas.save()
                    canvas.translate(drawX, drawY)
                    canvas.drawPath(path, paint)
                    canvas.restore()
                }
                localX += pos.xAdvance
                localY += pos.yAdvance
            }
            localPenX = localX
            localPenY = localY
        }
    }
}

internal fun applyDrawStyleToPaint(paint: Paint, style: DrawStyle) {
    when (style) {
        is Fill -> paint.style = PaintingStyle.Fill
        is Stroke -> {
            paint.style = PaintingStyle.Stroke
            paint.strokeWidth = style.width
            paint.strokeMiterLimit = style.miter
            paint.strokeCap = style.cap
            paint.strokeJoin = style.join
            paint.pathEffect = style.pathEffect
        }
    }
}

/**
 * Render a single glyph at the current `DrawScope` origin (caller has
 * already applied the per-glyph transform). Implements the 5-strategy
 * ladder shared by [drawShapedText] and [drawTextAlongPath]. The
 * caller is expected to have already resolved [caches] for [runFont]
 * once at the start of the run via [MeasuredText.runCachesFor].
 */
internal fun DrawScope.drawOneGlyphAtOrigin(
    runFont: HbFont,
    caches: RunGlyphCaches,
    glyphId: Int,
    color: Color,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
) {
    val svgRender = if (caches.useSvg) caches.svgCache?.get(glyphId) else null
    val paintTree = if (svgRender == null && caches.useV1Paint) {
        caches.paintTrees?.get(glyphId)
    } else null
    val v0Layers = if (svgRender == null && paintTree == null && caches.useV0Layers) {
        caches.colorLayers?.get(glyphId)
    } else null

    if (svgRender != null) {
        drawSvgGlyphBitmap(
            render = svgRender,
            pointSize = runFont.pointSize,
            upem = runFont.face.upem,
            alpha = alpha,
            blendMode = blendMode,
        )
    } else if (paintTree != null && paintTree.isNotEmpty()) {
        drawV1PaintTree(
            rawPaths = caches.rawPaths,
            ops = paintTree,
            color = color,
            alpha = alpha,
            blendMode = blendMode,
        )
    } else if (v0Layers != null && v0Layers.isNotEmpty()) {
        for (layer in v0Layers) {
            val layerPath = caches.flippedPaths[layer.glyphId] ?: continue
            val layerColor = layer.argb
                ?.let { Color(it.toLong() and 0xFFFFFFFFL) }
                ?: color
            drawPath(
                path = layerPath,
                color = layerColor,
                alpha = alpha,
                style = style,
                blendMode = blendMode,
            )
        }
    } else {
        val path = caches.flippedPaths[glyphId]
        if (path != null) {
            drawPath(
                path = path,
                color = color,
                alpha = alpha,
                style = style,
                blendMode = blendMode,
            )
        }
    }
}

/**
 * Stamp a pre-rendered SVG glyph at the current pen origin (which the
 * caller has already translated to). [SvgGlyphRender] carries the
 * design-space rectangle the bitmap covers, so we just convert that
 * rect into pixel space using `pointSize / upem` and use it as the
 * draw destination. SVG-in-OT's y-axis points DOWN, matching the
 * direction we draw on screen - no flip needed.
 */
private fun DrawScope.drawSvgGlyphBitmap(
    render: SvgGlyphRender,
    pointSize: Float,
    upem: Int,
    alpha: Float,
    blendMode: BlendMode,
) {
    if (pointSize <= 0f || upem <= 0) return
    val designToPx = pointSize / upem
    val dstX = render.designOffsetX * designToPx
    val dstY = render.designOffsetY * designToPx
    val dstW = render.designWidth * designToPx
    val dstH = render.designHeight * designToPx
    drawImage(
        image = render.bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(render.bitmap.width, render.bitmap.height),
        dstOffset = IntOffset(dstX.toInt(), dstY.toInt()),
        dstSize = IntSize(dstW.toInt(), dstH.toInt()),
        alpha = alpha,
        blendMode = blendMode,
        filterQuality = FilterQuality.High,
    )
}

/**
 * Replay a recorded COLR v1 paint tree against the current [DrawScope].
 *
 * Coordinate setup: HarfBuzz emits paint coordinates in HB scale (=
 * pixel × 64, the 26.6 fixed-point convention used by hb_position_t).
 * We apply `canvas.scale(1/64, -1/64)` so the entire paint walk works
 * in HB scale - the 1/64 brings coords down to pixels and the negative
 * Y flips HarfBuzz's Y-up convention onto Compose's Y-down screen.
 *
 * This means the cached glyph clip paths and the cover rect both have
 * to be in HB scale (see [ComposeRawPathSink]) - otherwise their values
 * shrink 64× when the canvas applies its 1/64 scale.
 */
private fun DrawScope.drawV1PaintTree(
    rawPaths: Map<Int, Path>,
    ops: List<RecordedPaintOp>,
    color: Color,
    alpha: Float,
    blendMode: BlendMode,
) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.scale(1f / HB_SCALE_FACTOR, -1f / HB_SCALE_FACTOR)
        val sink = ComposePaintSink(
            canvas = canvas,
            glyphRawPaths = rawPaths,
            foreground = color,
            alphaMultiplier = alpha,
            blendMode = blendMode,
            coverBounds = PAINT_TREE_COVER_BOUNDS,
        )
        ops.replay(sink)
        canvas.restore()
    }
}

/**
 * Per-run cache view over a [MeasuredText]. Holds the four font-keyed
 * map slices and the three render-strategy booleans the per-glyph draw
 * path consumes - all of which are constant for every glyph in a run,
 * so resolving them once at the run boundary turns each per-glyph step
 * from "4 outer Map<HbFont, _> lookups + 3 boolean recompute" into
 * "1 inner Map<Int, _> lookup".
 *
 * `useSvg` / `useV1Paint` / `useV0Layers` honour both the
 * `forceForegroundColor` precedence and the `style` precedence: only
 * `Fill` keeps the color-glyph paths active. Any non-Fill style (e.g.
 * `Stroke`) flips every flag to `false` so [drawOneGlyphAtOrigin] falls
 * through to the silhouette outline and the caller's stroke applies to
 * the actual glyph shape.
 */
internal class RunGlyphCaches(
    val flippedPaths: Map<Int, Path>,
    val rawPaths: Map<Int, Path>,
    val paintTrees: Map<Int, List<RecordedPaintOp>>?,
    val colorLayers: Map<Int, List<ColorLayer>>?,
    val svgCache: LazySvgGlyphCache?,
    val useSvg: Boolean,
    val useV1Paint: Boolean,
    val useV0Layers: Boolean,
)

internal fun MeasuredText.runCachesFor(
    runFont: HbFont,
    forceForegroundColor: Boolean,
    style: DrawStyle,
): RunGlyphCaches {
    val flipped = flippedPathsByFont[runFont] ?: emptyMap()
    val raw = rawPathsByFont[runFont] ?: emptyMap()
    val paintTrees = paintTreesByFont[runFont]
    val v0Layers = colorLayersByFont[runFont]
    val svgCache = svgGlyphCachesByFont[runFont]
    // SVG bitmaps are raster (no path to stroke) and the COLR v1 painter
    // floods a 1M-px cover rectangle clipped to the glyph - stroking that
    // would draw a frame around the cover, not the glyph. For non-Fill
    // styles we fall through to the silhouette outline so the caller's
    // stroke applies to the actual glyph shape. v0 layers follow the
    // same rule for consistency: a stroked emoji renders as a stroked
    // silhouette, not a stack of stroked colored layers.
    val isFill = style is Fill
    val useSvg = isFill && !forceForegroundColor && svgCache?.hasAnyGlyphs == true
    val useV1Paint = isFill && !forceForegroundColor && !useSvg && !paintTrees.isNullOrEmpty()
    val useV0Layers = isFill && !forceForegroundColor && !useSvg && !useV1Paint && !v0Layers.isNullOrEmpty()
    return RunGlyphCaches(
        flippedPaths = flipped,
        rawPaths = raw,
        paintTrees = paintTrees,
        colorLayers = v0Layers,
        svgCache = svgCache,
        useSvg = useSvg,
        useV1Paint = useV1Paint,
        useV0Layers = useV0Layers,
    )
}

/** HarfBuzz 26.6 fixed-point conversion: 1 pixel = 64 hb_position_t units. */
private const val HB_SCALE_FACTOR = 64f

/**
 * Cover bounds for COLR v1 paint replay (in HB scale): the canvas's
 * active clip stack masks the fill down to the visible region, so
 * oversizing is harmless and ensures any cumulative inner scale
 * (typically up to ~2.56 in COLR v1) still leaves the rect well
 * beyond the glyph. Hoisted to a module-level constant so we don't
 * allocate a fresh Rect for every V1-painted glyph drawn.
 */
private val PAINT_TREE_COVER_BOUNDS: Rect = Rect(-1_000_000f, -1_000_000f, 1_000_000f, 1_000_000f)
