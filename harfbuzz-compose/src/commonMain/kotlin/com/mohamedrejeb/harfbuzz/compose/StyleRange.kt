package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable

/**
 * One paint-override range inside a [StyledText]. Indices are UTF-16
 * char offsets in the [StyledText.text]; [start] is inclusive and
 * [end] is exclusive. Empty ranges (`start == end`) are accepted but
 * contribute no paint.
 */
@Immutable
public data class StyleRange(
    public val start: Int,
    public val end: Int,
    public val style: SpanStyle,
) {
    init {
        require(start >= 0 && start <= end) {
            "Invalid range: start=$start, end=$end"
        }
    }
}
