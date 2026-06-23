package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.GlyphPosition

/**
 * Extra ink width (px) this glyph's Kashida stretch must add. The scaled
 * [GlyphPosition.xAdvance] equals `naturalAdvance * xScale`, so the natural
 * advance is `xAdvance / xScale` and the added width is `xAdvance - natural`.
 * Zero when the glyph is not stretched.
 */
internal fun GlyphPosition.kashidaAddedWidth(): Float =
    if (xScale == 1f || xScale <= 0f) 0f else xAdvance - xAdvance / xScale

/**
 * A copy of this glyph outline whose INK is lengthened by [addedWidth] px
 * horizontally, **with the glyph's side bearings preserved**: the ink-left edge
 * stays put and only the stroke between the bearings stretches. Returns the
 * receiver unchanged when [addedWidth] is 0.
 *
 * This is the continuous-Kashida primitive. A tatweel is a horizontal baseline
 * stroke, so lengthening its ink elongates the connector at constant thickness
 * while keeping the join overlap/gap with neighbouring letters at its **designed
 * size**. Uniformly scaling the whole advance box instead multiplies the side
 * bearings by the scale factor — which opens a growing white gap on fonts whose
 * tatweel ink is narrower than its advance (positive bearing, e.g. PHKhalid) and
 * over-grows the overlap when it's wider (negative bearing). Scaling only the
 * ink keeps the gap at the font's natural bearing regardless of stretch.
 */
internal fun Path.stretchInkHorizontally(addedWidth: Float): Path {
    if (addedWidth == 0f) return this
    val bounds = getBounds()
    val inkWidth = bounds.width
    if (inkWidth <= 0f) return this
    val sx = (inkWidth + addedWidth) / inkWidth
    if (sx <= 0f) return this
    val out = Path()
    out.addPath(this)
    // Scale in x about the ink-left edge so the left bearing is unchanged and
    // the added width lands entirely inside the stroke.
    out.transform(
        Matrix().apply {
            translate(x = bounds.left)
            scale(x = sx, y = 1f)
            translate(x = -bounds.left)
        },
    )
    return out
}
