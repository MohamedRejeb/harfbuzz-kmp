package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable

/**
 * A piece of text plus zero or more paint-override [spans]. Same
 * shaping as the no-spans `String` overload of [ShapedText] - spans
 * only affect paint at draw time via the cluster-position heuristic.
 *
 * Spans layer in declaration order with per-attribute later-wins
 * semantics (see [SpanStyle]). The composable's outer `color` /
 * `brush` parameters supply the default paint for chars no span
 * covers.
 */
@Immutable
public data class StyledText(
    public val text: String,
    public val spans: List<StyleRange> = emptyList(),
) {
    public val length: Int get() = text.length

    public companion object {
        public val EMPTY: StyledText = StyledText(text = "")
    }
}
