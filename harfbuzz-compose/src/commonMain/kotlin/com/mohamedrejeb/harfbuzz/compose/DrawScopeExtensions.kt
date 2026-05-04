package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mohamedrejeb.harfbuzz.core.ColorLayer
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
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
    drawShapedTextInternal(
        measured = measured,
        topLeft = topLeft,
        color = color,
        brush = null,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
        forceForegroundColor = forceForegroundColor,
        shadow = shadow,
        spacingScale = 1f,
    )
}

/**
 * Brush variant of [drawShapedText]: monochrome glyph silhouettes (and
 * COLR v0 foreground-color layers) are filled / stroked with [brush]
 * instead of a solid color. Designed-color glyphs - SVG-in-OT bitmaps,
 * COLR v1 paint trees, and COLR v0 layers with palette colors - render
 * unchanged so emoji and SVG-painted fonts (e.g. Aref Ruqaa Ink) keep
 * their authored appearance.
 *
 * The brush is applied to a single combined path holding every monochrome
 * glyph in the line, so a [Brush.linearGradient] spans the entire line
 * rather than repeating per glyph.
 *
 * Pass [forceForegroundColor] = true to route color-font glyphs through
 * the silhouette outline as well, making the brush paint every glyph
 * (including emoji silhouettes).
 */
public fun DrawScope.drawShapedText(
    measured: MeasuredText,
    brush: Brush,
    topLeft: Offset = Offset.Zero,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    drawShapedTextInternal(
        measured = measured,
        topLeft = topLeft,
        color = Color.Black,
        brush = brush,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
        forceForegroundColor = forceForegroundColor,
        shadow = shadow,
        spacingScale = 1f,
    )
}

/**
 * Styled-text variant of [drawShapedText]. Each glyph in [measured]
 * resolves to a paint via the cluster-position heuristic over
 * [styledText]'s spans; monochrome glyphs are bucketed by resolved
 * paint and filled one combined silhouette path per bucket. Color
 * glyphs (COLR v0 / v1, SVG-in-OT) keep their authored colors except
 * for COLR v0 foreground-color layers, which take the resolved paint
 * (or, if [forceForegroundColor] is `true`, the silhouette also
 * paints with the resolved paint).
 *
 * The chars in [styledText].text MUST equal the source string that
 * produced [measured]; cluster ids would otherwise not line up with
 * span ranges. Composables construct both from one source string and
 * never break this invariant.
 *
 * When [styledText].spans is empty this delegates to the existing
 * uniform-paint draw path so the no-spans output is byte-identical
 * to calling the [String]-text overload.
 */
public fun DrawScope.drawShapedText(
    measured: MeasuredText,
    styledText: StyledText,
    topLeft: Offset = Offset.Zero,
    color: Color = Color.Black,
    brush: Brush? = null,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    if (styledText.spans.isEmpty()) {
        drawShapedTextInternal(
            measured = measured,
            topLeft = topLeft,
            color = color,
            brush = brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            forceForegroundColor = forceForegroundColor,
            shadow = shadow,
            spacingScale = 1f,
        )
        return
    }
    drawShapedTextStyledInternal(
        measured = measured,
        styledText = styledText,
        topLeft = topLeft,
        color = color,
        brush = brush,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
        forceForegroundColor = forceForegroundColor,
        shadow = shadow,
    )
}

/**
 * Layout-aware overload: places [measured] within an [availableWidth]
 * slot and applies single-line [alignment] + [overflow]. The text is
 * assumed to be already justified (use [rememberMeasuredText] with a
 * non-[com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy.None]
 * strategy to bake Kashida / thin-space inserts into the [MeasuredText]).
 *
 * - [alignment] resolves against [direction] for Start / End. The line's
 *   ink box is what's centred / pinned, not its raw advance, so glyphs
 *   with side bearings (cursive Arabic, italic Latin) align to their
 *   visual edges instead of the typesetter's whitespace.
 * - [overflow] is [ShapedTextOverflow.Clip] (clip painted output to
 *   `[topLeft.x, topLeft.x + availableWidth]`), [ShapedTextOverflow.Visible]
 *   (no clip), or [ShapedTextOverflow.Compress] (scale per-glyph
 *   spacing so the line fits the slot exactly).
 */
public fun DrawScope.drawShapedText(
    measured: MeasuredText,
    availableWidth: Float,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    direction: HbDirection = measured.paragraph.baseDirection,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    topLeft: Offset = Offset.Zero,
    color: Color = Color.Black,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    drawShapedTextLayoutAware(
        measured = measured,
        availableWidth = availableWidth,
        alignment = alignment,
        direction = direction,
        overflow = overflow,
        topLeft = topLeft,
        color = color,
        brush = null,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
        forceForegroundColor = forceForegroundColor,
        shadow = shadow,
    )
}

/**
 * Layout-aware brush variant. Same alignment / overflow handling as the
 * color overload, but [brush] paints monochrome glyphs and COLR v0
 * foreground-color layers; designed-color glyphs (SVG-in-OT, COLR v1,
 * COLR v0 palette layers) render unchanged unless [forceForegroundColor]
 * is true.
 *
 * The brush spans the line's combined silhouette path, so a
 * [Brush.linearGradient] flows from the line's leading edge to its
 * trailing edge regardless of [alignment].
 */
public fun DrawScope.drawShapedText(
    measured: MeasuredText,
    availableWidth: Float,
    brush: Brush,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    direction: HbDirection = measured.paragraph.baseDirection,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
    topLeft: Offset = Offset.Zero,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    forceForegroundColor: Boolean = false,
    shadow: Shadow? = null,
) {
    drawShapedTextLayoutAware(
        measured = measured,
        availableWidth = availableWidth,
        alignment = alignment,
        direction = direction,
        overflow = overflow,
        topLeft = topLeft,
        color = Color.Black,
        brush = brush,
        alpha = alpha,
        style = style,
        blendMode = blendMode,
        forceForegroundColor = forceForegroundColor,
        shadow = shadow,
    )
}

/**
 * Mask an [image] through the line's silhouette so it paints only
 * inside glyph ink. The clip uses [MeasuredText.silhouettePath] (cached
 * once on first call) translated by [topLeft], and the image draw
 * inherits the slot's antialiasing.
 *
 * Default [dstOffset] / [dstSize] place the image at [topLeft] at its
 * natural pixel size; pass an explicit [dstSize] to stretch the image
 * across the line's bounds (e.g.
 * `dstSize = IntSize(measured.advance.toInt(), measured.lineHeight.toInt())`).
 *
 * Glyphs without a base outline silhouette - notably Noto Color Emoji's
 * COLR v1 paint trees - contribute no clip area, so they appear as
 * gaps in the masked image. Use [drawShapedText] without the image
 * fill if you want emoji to render with their authored colors.
 */
public fun DrawScope.drawShapedTextWithImageFill(
    measured: MeasuredText,
    image: ImageBitmap,
    topLeft: Offset = Offset.Zero,
    dstOffset: IntOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
    dstSize: IntSize = IntSize(image.width, image.height),
    srcOffset: IntOffset = IntOffset.Zero,
    srcSize: IntSize = IntSize(image.width, image.height),
    alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
    filterQuality: FilterQuality = FilterQuality.High,
) {
    if (measured.isEmpty) return
    val silhouette = measured.silhouettePath
    val translated = if (topLeft == Offset.Zero) {
        silhouette
    } else {
        Path().also { it.addPath(silhouette, topLeft) }
    }
    clipPath(translated) {
        drawImage(
            image = image,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = dstOffset,
            dstSize = dstSize,
            alpha = alpha,
            blendMode = blendMode,
            filterQuality = filterQuality,
        )
    }
}

private fun DrawScope.drawShapedTextLayoutAware(
    measured: MeasuredText,
    availableWidth: Float,
    alignment: ParagraphAlignment,
    direction: HbDirection,
    overflow: ShapedTextOverflow,
    topLeft: Offset,
    color: Color,
    brush: Brush?,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
    forceForegroundColor: Boolean,
    shadow: Shadow?,
) {
    if (measured.isEmpty) return
    // The natural extent of a line is whichever is larger between the
    // pen advance and the rightmost ink: cursive scripts (Arabic) often
    // have glyph ink that reaches past the next pen origin (the joins
    // are designed to overlap), so checking `advance` alone misses
    // those overflow cases.
    val naturalExtent = maxOf(measured.advance, measured.ink.right)
    val overflowing = naturalExtent > availableWidth
    // Compress: shrink the spacing between glyph origins so every
    // glyph's ink lands inside the slot. Walking once is the only way
    // to get this right - the "tightest" glyph isn't always the last
    // one (for Arabic Naskh the cursive join glyph at index n-2 often
    // has more ink overshoot than the terminal glyph). For a glyph
    // whose origin lands at `cumAdvance * scale + xOffset`, the
    // constraint is
    //   cumAdvance * scale + xOffset + path.bounds.right <= availableWidth
    // which solves to
    //   scale <= (availableWidth - xOffset - path.bounds.right) / cumAdvance.
    // Take the min across all glyphs (and clamp by `availableWidth /
    // advance` so the pen also lands inside the slot).
    val spacingScale = if (
        overflow == ShapedTextOverflow.Compress &&
        overflowing &&
        availableWidth > 0f &&
        measured.advance > 0f
    ) computeCompressScale(measured, availableWidth) else 1f

    val xOffset = if (spacingScale < 1f) {
        // The compressed line spans exactly [0, availableWidth] from the
        // pen, so alignment offset is zero - the line is anchored to the
        // slot's leading edge regardless of [alignment].
        0f
    } else {
        computeAlignmentOffset(measured, availableWidth, alignment, direction)
    }
    val penTopLeft = Offset(topLeft.x + xOffset, topLeft.y)

    // Skip clipping when the line already fits within the slot - clipping
    // a non-overflowing line trims AA pixels from glyph side bearings and
    // changes pixel-exact output for no semantic gain. Compress always
    // fits by definition, so it never needs clipping.
    val needsClip = overflow == ShapedTextOverflow.Clip && overflowing
    if (!needsClip) {
        drawShapedTextInternal(
            measured = measured,
            topLeft = penTopLeft,
            color = color,
            brush = brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            forceForegroundColor = forceForegroundColor,
            shadow = shadow,
            spacingScale = spacingScale,
        )
        return
    }
    clipRect(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + availableWidth,
        bottom = topLeft.y + measured.lineHeight,
    ) {
        drawShapedTextInternal(
            measured = measured,
            topLeft = penTopLeft,
            color = color,
            brush = brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
            forceForegroundColor = forceForegroundColor,
            shadow = shadow,
            spacingScale = spacingScale,
        )
    }
}

/**
 * Largest scale `s` such that every glyph in [measured] - drawn at
 * `(cumAdvance_k * s) + xOffset_k` with its natural ink extent - lands
 * inside `[0, availableWidth]`. Walks every glyph once, looks up its
 * silhouette path's bounds, and takes the min across both the pen
 * constraint (`availableWidth / advance`) and the per-glyph ink
 * constraint. Glyphs with no available silhouette (color SVG / COLR
 * bitmaps) fall back to the glyph's `xAdvance` as the ink-right
 * estimate, which is correct for non-cursive content.
 */
private fun computeCompressScale(measured: MeasuredText, availableWidth: Float): Float {
    var scale = availableWidth / measured.advance
    var cumAdvance = 0f
    for (run in measured.paragraph.runs) {
        val font = run.font ?: measured.font
        val paths = measured.flippedPathsByFont[font]
        for (i in run.glyphs.indices) {
            val pos = run.positions[i]
            val gid = run.glyphs[i].glyphId
            val inkRightOffset = paths?.get(gid)?.getBounds()?.right ?: pos.xAdvance
            val totalRight = pos.xOffset + inkRightOffset
            if (cumAdvance > 0f) {
                val maxForGlyph = (availableWidth - totalRight) / cumAdvance
                if (maxForGlyph < scale) scale = maxForGlyph
            }
            cumAdvance += pos.xAdvance
        }
    }
    return scale.coerceIn(0f, 1f)
}

/**
 * Single-line alignment offset. Uses the line's typographic advance,
 * not its ink rect, so the result matches what `BasicText` does (and
 * what existing single-line callers already rely on): Start lands the
 * pen at the slot edge, Right pins the trailing edge of the advance,
 * Center distributes the leftover slack symmetrically. Side bearings
 * stay on the same side of the slot they had before alignment ran -
 * this is intentional, since callers who want ink-on-edge alignment
 * already have access to [MeasuredText.ink] and can adjust [topLeft]
 * directly.
 */
private fun computeAlignmentOffset(
    measured: MeasuredText,
    availableWidth: Float,
    alignment: ParagraphAlignment,
    direction: HbDirection,
): Float {
    val advance = measured.advance
    val slack = availableWidth - advance
    return when (alignment) {
        ParagraphAlignment.Start ->
            if (direction == HbDirection.RTL) slack else 0f
        ParagraphAlignment.End ->
            if (direction == HbDirection.RTL) 0f else slack
        ParagraphAlignment.Left -> 0f
        ParagraphAlignment.Right -> slack
        ParagraphAlignment.Center -> slack / 2f
        // Justify widens upstream so [advance] already approaches
        // [availableWidth]; the residual slack is rounding only and
        // the line pins to the start edge.
        ParagraphAlignment.Justify ->
            if (direction == HbDirection.RTL) slack else 0f
    }
}

internal fun DrawScope.drawShapedTextInternal(
    measured: MeasuredText,
    topLeft: Offset,
    color: Color,
    brush: Brush?,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
    forceForegroundColor: Boolean,
    shadow: Shadow?,
    spacingScale: Float,
    /**
     * Caller-provided accumulator for monochrome silhouettes. When
     * non-null, the per-glyph silhouette is appended here and the
     * final brush draw is the caller's responsibility (paragraph mode
     * walks every line into the same path so a gradient spans the
     * whole paragraph instead of repeating per line). When null, this
     * function allocates its own path and paints it at the end -
     * matching the standalone single-line draw behaviour.
     */
    externalBrushPath: Path? = null,
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
            spacingScale = spacingScale,
        )
    }

    // Brush mode accumulates monochrome silhouettes (and COLR v0
    // foreground-color layers) into a single path so the brush spans
    // the whole line - drawing each glyph individually would map a
    // gradient to per-glyph bounds, repeating it. Color glyphs (SVG,
    // COLR v1, COLR v0 palette) still render in place with their
    // designed colors so emoji and SVG fonts paint unchanged.
    val ownsBrushPath = externalBrushPath == null
    val combinedBrushPath = externalBrushPath ?: if (brush != null) Path() else null

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
            // Compose's translate top is Y-down, so negate. The glyph
            // is drawn at its full size; only the spacing between
            // glyphs is scaled by [spacingScale] (Compress overflow).
            val drawX = localX + pos.xOffset
            val drawY = localY - pos.yOffset

            if (combinedBrushPath != null) {
                drawOneGlyphForBrush(
                    runFont = runFont,
                    sizePx = measured.sizePx,
                    caches = caches,
                    glyphId = gid,
                    drawX = drawX,
                    drawY = drawY,
                    color = color,
                    alpha = alpha,
                    style = style,
                    blendMode = blendMode,
                    combinedPath = combinedBrushPath,
                )
            } else {
                translate(left = drawX, top = drawY) {
                    drawOneGlyphAtOrigin(
                        runFont = runFont,
                        sizePx = measured.sizePx,
                        caches = caches,
                        glyphId = gid,
                        color = color,
                        alpha = alpha,
                        style = style,
                        blendMode = blendMode,
                    )
                }
            }

            localX += pos.xAdvance * spacingScale
            localY += pos.yAdvance
        }
        loopX = localX
        loopY = localY
    }

    if (ownsBrushPath && combinedBrushPath != null && brush != null) {
        drawCombinedBrushPath(
            path = combinedBrushPath,
            brush = brush,
            alpha = alpha,
            style = style,
            blendMode = blendMode,
        )
    }
}

/**
 * Draw [path] under [brush] with the brush sized to the path's bounding
 * box. Bypasses [DrawScope.drawPath]'s default sizing (which uses
 * [DrawScope.size]) - inside `Modifier.drawBehind` the inner element
 * may report a size of zero (an empty Box used as a paint surface),
 * which collapses [Brush.horizontalGradient] / etc. into a degenerate
 * shader. Translating the path to the origin and the canvas back keeps
 * the painted pixels where they were while letting the shader's
 * reference frame line up with the line's leading edge.
 */
internal fun DrawScope.drawCombinedBrushPath(
    path: Path,
    brush: Brush,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
) {
    val bounds = path.getBounds()
    if (bounds.isEmpty) return
    path.translate(Offset(-bounds.left, -bounds.top))
    drawIntoCanvas { canvas ->
        canvas.save()
        val paint = Paint().apply {
            this.alpha = alpha
            this.blendMode = blendMode
            applyDrawStyleToPaint(this, style)
        }
        brush.applyTo(Size(bounds.width, bounds.height), paint, alpha)
        canvas.translate(bounds.left, bounds.top)
        canvas.drawPath(path, paint)
        canvas.restore()
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
    spacingScale: Float,
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
                localX += pos.xAdvance * spacingScale
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
    sizePx: Float,
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
            pointSize = sizePx,
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
 * Brush-aware counterpart of [drawOneGlyphAtOrigin]: route the glyph
 * through the same 5-strategy ladder, but instead of drawing the
 * monochrome silhouette in place, append it (translated to the glyph's
 * pen origin) to [combinedPath] so the brush draw pass at the end of
 * [drawShapedTextInternal] paints every silhouette under one shader.
 *
 * Designed-color paths - SVG bitmaps, COLR v1 paint trees, and COLR v0
 * palette layers - bypass the combined path entirely and render in
 * place at `(drawX, drawY)` with their authored colors. COLR v0
 * foreground-color layers (where `layer.argb == null`) join the
 * combined silhouette so the brush tints them like any other monochrome
 * glyph.
 */
private fun DrawScope.drawOneGlyphForBrush(
    runFont: HbFont,
    sizePx: Float,
    caches: RunGlyphCaches,
    glyphId: Int,
    drawX: Float,
    drawY: Float,
    color: Color,
    alpha: Float,
    style: DrawStyle,
    blendMode: BlendMode,
    combinedPath: Path,
) {
    val svgRender = if (caches.useSvg) caches.svgCache?.get(glyphId) else null
    val paintTree = if (svgRender == null && caches.useV1Paint) {
        caches.paintTrees?.get(glyphId)
    } else null
    val v0Layers = if (svgRender == null && paintTree == null && caches.useV0Layers) {
        caches.colorLayers?.get(glyphId)
    } else null

    if (svgRender != null) {
        translate(left = drawX, top = drawY) {
            drawSvgGlyphBitmap(
                render = svgRender,
                pointSize = sizePx,
                upem = runFont.face.upem,
                alpha = alpha,
                blendMode = blendMode,
            )
        }
    } else if (paintTree != null && paintTree.isNotEmpty()) {
        translate(left = drawX, top = drawY) {
            drawV1PaintTree(
                rawPaths = caches.rawPaths,
                ops = paintTree,
                color = color,
                alpha = alpha,
                blendMode = blendMode,
            )
        }
    } else if (v0Layers != null && v0Layers.isNotEmpty()) {
        for (layer in v0Layers) {
            val layerPath = caches.flippedPaths[layer.glyphId] ?: continue
            val layerArgb = layer.argb
            if (layerArgb != null) {
                translate(left = drawX, top = drawY) {
                    drawPath(
                        path = layerPath,
                        color = Color(layerArgb.toLong() and 0xFFFFFFFFL),
                        alpha = alpha,
                        style = style,
                        blendMode = blendMode,
                    )
                }
            } else {
                combinedPath.addPath(layerPath, Offset(drawX, drawY))
            }
        }
    } else {
        val path = caches.flippedPaths[glyphId]
        if (path != null) {
            combinedPath.addPath(path, Offset(drawX, drawY))
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
