package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import com.mohamedrejeb.harfbuzz.core.GlyphExtents
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.MeasuredBoundsResult
import com.mohamedrejeb.harfbuzz.core.ShapedParagraph
import com.mohamedrejeb.harfbuzz.core.ShapedRun
import com.mohamedrejeb.harfbuzz.core.glyphExtentsBatch
import com.mohamedrejeb.harfbuzz.core.runShapingWork
import com.mohamedrejeb.harfbuzz.core.shapeOnlyBounds
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tight bounds of [text] shaped with [font] and laid along [path].
 * Returns a single outer `Rect` (parity with `MeasuredText.ink`) - for
 * substring selection regions on curves see Future work in the spec.
 */
@Composable
public fun rememberPathTextBounds(
    text: String,
    font: HbFont,
    path: Path,
    startOffset: Float = 0f,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    autoFlip: Boolean = false,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
): State<PathTextBoundsLoad> {
    val stack = remember(font) { HbFontStack(font) }
    return rememberPathTextBounds(
        text, stack, path, startOffset, side, alignment, overflow, autoFlip,
        features, direction, language,
    )
}

@Composable
public fun rememberPathTextBounds(
    text: String,
    fontStack: HbFontStack,
    path: Path,
    startOffset: Float = 0f,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    autoFlip: Boolean = false,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
): State<PathTextBoundsLoad> = produceState<PathTextBoundsLoad>(
    initialValue = PathTextBoundsLoad.Loading,
    text, fontStack, path, startOffset, side, alignment, overflow, autoFlip,
    features, direction, language,
) {
    value = PathTextBoundsLoad.Loading
    try {
        val computed = runShapingWork {
            val shaped: MeasuredBoundsResult = fontStack.shapeOnlyBounds(
                text = text,
                baseDirection = direction,
                features = features,
                language = language,
            )
            computePathTextBounds(
                paragraph = shaped.paragraph,
                primaryFont = fontStack.primary,
                path = path,
                startOffset = startOffset,
                side = side,
                alignment = alignment,
                overflow = overflow,
                autoFlip = autoFlip,
            )
        }
        value = PathTextBoundsLoad.Ready(computed)
    } catch (ce: CancellationException) {
        throw ce
    } catch (cause: Throwable) {
        if (cause is IllegalStateException && cause.message == "hb object disposed") {
            return@produceState
        }
        println("[kotlin-harfbuzz] rememberPathTextBounds failed: $cause")
        cause.printStackTrace()
        value = PathTextBoundsLoad.Failed(cause)
    }
}

/**
 * Compute tight outer ink bounds for text laid along [path]. Mirrors the
 * per-glyph transform stack used by `drawTextAlongPath` and unions every
 * placed glyph's transformed ink box. `internal` so jvmTest can call it
 * directly without needing Compose UI test infrastructure.
 */
internal suspend fun computePathTextBounds(
    paragraph: ShapedParagraph,
    primaryFont: HbFont,
    path: Path,
    startOffset: Float,
    side: TextOnPathSide,
    alignment: TextOnPathAlignment,
    overflow: TextOnPathOverflow,
    autoFlip: Boolean,
): PathTextBounds {
    val pathLength = PathMeasure().apply { setPath(path, forceClosed = false) }.length
    if (pathLength <= 0f || paragraph.runs.isEmpty()) {
        return PathTextBounds(ink = Rect.Zero, advance = 0f, pathLength = pathLength)
    }

    // Pre-fetch extents per run so the walk doesn't suspend per glyph.
    val extentsByRun = HashMap<ShapedRun, List<GlyphExtents?>>(paragraph.runs.size)
    for (run in paragraph.runs) {
        val runFont = run.font ?: primaryFont
        val ids = IntArray(run.glyphCount) { run.glyphs[it].glyphId }
        extentsByRun[run] = runFont.glyphExtentsBatch(ids)
    }

    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    var placedAdvance = 0f

    walkPathText(
        paragraph = paragraph,
        primaryFont = primaryFont,
        path = path,
        startOffset = startOffset,
        side = side,
        alignment = alignment,
        overflow = overflow,
        autoFlip = autoFlip,
    ) { p ->
        val extents = extentsByRun[p.run]?.getOrNull(p.glyphIndexInRun) ?: return@walkPathText
        val xb = extents.xBearing
        val yb = extents.yBearing
        val w = extents.width
        // HarfBuzz returns `height` as a negative value in its y-up convention
        // (top→bottom going *down* in y-up means decreasing y). Negate for
        // screen y-down, matching ShapingHelpers' `gh = -extents.height`.
        val hScreen = -extents.height
        val pos = p.glyphPosition

        // Local coords: same as draw arm - translate(-xAdvance/2 + xOffset, -yOffset)
        // is applied innermost. We compute the 4 corners relative to that origin.
        val localOriginX = -pos.xAdvance / 2f + pos.xOffset
        val localOriginY = -pos.yOffset

        // Glyph ink rectangle in local space (after y-flip from HB y-up).
        val rectLeft = localOriginX + xb
        val rectRight = localOriginX + xb + w
        val rectTop = localOriginY - yb            // -yb because HB y-up
        val rectBottom = localOriginY - yb + hScreen

        val sideY = if (side == TextOnPathSide.Below) -1f else 1f
        val flipSign = if (p.flipDueToAutoFlip) -1f else 1f
        val cosA = cos(p.angleRadians)
        val sinA = sin(p.angleRadians)

        val corners = arrayOf(
            rectLeft to rectTop,
            rectRight to rectTop,
            rectRight to rectBottom,
            rectLeft to rectBottom,
        )
        for ((cx, cyRaw) in corners) {
            val cy = cyRaw * sideY
            val fx = cx * flipSign
            val fy = cy * flipSign
            val rx = fx * cosA - fy * sinA
            val ry = fx * sinA + fy * cosA
            val gx = rx + p.position.x
            val gy = ry + p.position.y
            left = min(left, gx); top = min(top, gy)
            right = max(right, gx); bottom = max(bottom, gy)
        }

        placedAdvance += pos.xAdvance
    }

    val ink = if (right > left && bottom > top) Rect(left, top, right, bottom) else Rect.Zero
    return PathTextBounds(ink = ink, advance = placedAdvance, pathLength = pathLength)
}
