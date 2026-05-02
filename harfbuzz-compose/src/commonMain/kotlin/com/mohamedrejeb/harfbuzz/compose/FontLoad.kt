package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import com.mohamedrejeb.harfbuzz.core.HbFont

/**
 * State surfaced by [rememberHbFont] when the font is loaded asynchronously
 * (e.g. from a Compose Multiplatform `FontResource`). Pattern-match before
 * passing the font to [ShapedText], [ArcText], or any of the `DrawScope`
 * extensions.
 */
@Immutable
public sealed interface FontLoad {
    public data object Loading : FontLoad
    public data class Ready(val font: HbFont) : FontLoad
    public data class Failed(val cause: Throwable) : FontLoad
}
