package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable

/**
 * What happens when a single shaped line's natural advance exceeds the
 * available slot width.
 *
 *  - [Clip] (default): paint glyphs at their natural positions and clip
 *    the painted output to `[topLeft.x, topLeft.x + slotWidth]`. Glyphs
 *    that fall entirely outside the slot are not drawn; partially
 *    overlapping glyphs are trimmed at the edge.
 *  - [Visible]: paint every glyph at its natural position with no clip.
 *    Glyphs paint past the slot bounds and overlap whatever is drawn
 *    next to the line.
 *  - [Compress]: scale per-glyph spacing uniformly so the line fits the
 *    slot exactly. Glyphs keep their original size and may overlap -
 *    this is intentional. No effect when the line already fits. Heavy
 *    compression ratios visually break Arabic shaping joins (the joins
 *    are designed for full-width spacing); use sparingly for cursive
 *    scripts.
 */
@Immutable
public enum class ShapedTextOverflow {
    Clip,
    Visible,
    Compress,
}
