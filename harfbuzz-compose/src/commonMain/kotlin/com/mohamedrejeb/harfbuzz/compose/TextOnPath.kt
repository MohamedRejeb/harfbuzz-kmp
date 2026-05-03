package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage

/**
 * Composable convenience that shapes [text] with [font] and renders it
 * along [path] inside its own modifier-bound layout box.
 *
 * For finer control (custom paint style, blend mode, force-foreground
 * color), call [drawTextAlongPath] from within your own
 * `Modifier.drawBehind` and pass a pre-built [MeasuredText] from
 * [rememberMeasuredText].
 */
@Composable
public fun TextOnPath(
    text: String,
    font: HbFont,
    sizePx: Float,
    path: Path,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    startOffset: Float = 0f,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    autoFlip: Boolean = false,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
) {
    val loadState by rememberMeasuredText(text, font, sizePx, features, direction, language)
    val measured = (loadState as? MeasuredTextLoad.Ready)?.measured

    Box(
        modifier = modifier.drawBehind {
            if (measured == null || measured.isEmpty) return@drawBehind
            drawTextAlongPath(
                measured = measured,
                path = path,
                color = color,
                startOffset = startOffset,
                side = side,
                alignment = alignment,
                overflow = overflow,
                autoFlip = autoFlip,
            )
        },
        content = {},
    )
}

@Composable
public fun TextOnPath(
    text: String,
    fontStack: HbFontStack,
    sizePx: Float,
    path: Path,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    startOffset: Float = 0f,
    side: TextOnPathSide = TextOnPathSide.Above,
    alignment: TextOnPathAlignment = TextOnPathAlignment.Start,
    overflow: TextOnPathOverflow = TextOnPathOverflow.Clip,
    autoFlip: Boolean = false,
    features: List<HbFeature> = emptyList(),
    direction: HbDirection = HbDirection.AUTO,
    language: HbLanguage = HbLanguage.AUTO,
) {
    val loadState by rememberMeasuredText(text, fontStack, sizePx, features, direction, language)
    val measured = (loadState as? MeasuredTextLoad.Ready)?.measured

    Box(
        modifier = modifier.drawBehind {
            if (measured == null || measured.isEmpty) return@drawBehind
            drawTextAlongPath(
                measured = measured,
                path = path,
                color = color,
                startOffset = startOffset,
                side = side,
                alignment = alignment,
                overflow = overflow,
                autoFlip = autoFlip,
            )
        },
        content = {},
    )
}
