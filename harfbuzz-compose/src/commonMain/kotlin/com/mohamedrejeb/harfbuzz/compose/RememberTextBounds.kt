package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.MeasuredBounds
import com.mohamedrejeb.harfbuzz.core.MeasuredBoundsResult
import com.mohamedrejeb.harfbuzz.core.runShapingWork
import com.mohamedrejeb.harfbuzz.core.shapeOnlyBounds
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fast-path bounds for shaped text - runs only `shapeParagraph` plus an
 * `hExtents` read. Skips the per-glyph path / COLR / SVG decoding that
 * [rememberMeasuredText] does, so it returns 10–50× faster on
 * color-heavy paragraphs and is suitable for layout-pre-pass sizing
 * (column widths, container fit, hit-testing rects).
 *
 * If you intend to *draw* the text, use [rememberMeasuredText] instead -
 * this function returns nothing the renderer can consume.
 */
@Composable
public fun rememberTextBounds(
    text: String,
    font: HbFont,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
): State<MeasuredBoundsLoad> {
    val stack = remember(font) { HbFontStack(font) }
    return rememberTextBounds(text, stack, features, direction, language)
}

@Composable
public fun rememberTextBounds(
    text: String,
    fontStack: HbFontStack,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
): State<MeasuredBoundsLoad> = produceState<MeasuredBoundsLoad>(
    initialValue = MeasuredBoundsLoad.Loading,
    text, fontStack, features, direction, language,
) {
    value = MeasuredBoundsLoad.Loading
    try {
        val result: MeasuredBoundsResult = runShapingWork {
            fontStack.shapeOnlyBounds(text, direction, features, language)
        }
        value = MeasuredBoundsLoad.Ready(
            bounds = result.bounds,
            paragraph = result.paragraph,
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (cause: Throwable) {
        if (isStaleHbHandleForBounds(cause)) {
            // Same stale-handle race as RememberMeasuredText.kt:74:
            // the previous HbFont was disposed while this job still held
            // a reference. The produceState relaunch (keyed on the new
            // fontStack) will take over from here.
            return@produceState
        }
        println("[kotlin-harfbuzz] shapeOnlyBounds failed: $cause")
        cause.printStackTrace()
        value = MeasuredBoundsLoad.Failed(cause)
    }
}

private fun isStaleHbHandleForBounds(cause: Throwable): Boolean =
    cause is IllegalStateException && cause.message == "hb object disposed"

/** Compose `Rect` view of [MeasuredBounds.ink] for ergonomics. */
public val MeasuredBounds.composeInk: Rect
    get() = Rect(ink.left, ink.top, ink.right, ink.bottom)

/** Compose `Rect` view of [MeasuredBounds.logical] for ergonomics. */
public val MeasuredBounds.composeLogical: Rect
    get() = Rect(logical.left, logical.top, logical.right, logical.bottom)
