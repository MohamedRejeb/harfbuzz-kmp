package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Returns a copy of this [StyledText] with [color] applied to every
 * Arabic combining-mark codepoint:
 *
 *  - U+064B - U+065F  Arabic harakat (fatha, damma, kasra, shadda,
 *                     sukun, tanwin variants, hamza-related marks)
 *  - U+0670           Arabic Letter Superscript Alef (dagger alef)
 *  - U+06D6 - U+06DC  Quranic small high marks
 *  - U+06DF - U+06E4  Quranic small high marks (continuation)
 *  - U+06E7 - U+06E8  Quranic small high marks
 *  - U+06EA - U+06ED  Quranic small low marks
 *
 * U+0640 (Tatweel / Kashida) is intentionally excluded: it is a
 * stretch character, not a diacritic.
 *
 * Existing spans are preserved; per the per-attribute later-wins
 * merge rule, a tashkeel color span will override an earlier color
 * span on the same character.
 *
 * The generated spans set [SpanStyle.clearBrush] so a tashkeel color
 * paints solid even when the outer call carries a [Brush] (e.g. a
 * paragraph-wide gradient). Without this, the bucket draw would
 * still paint the gradient over every glyph and the per-mark color
 * would be invisible. Pass [participatesInOuterBrush] = true to opt
 * back into the v0 behaviour where tashkeel chars participate in the
 * outer brush.
 */
public fun StyledText.withTashkeelColor(
    color: Color,
    participatesInOuterBrush: Boolean = false,
): StyledText = appendTashkeelSpans(
    SpanStyle(color = color, clearBrush = !participatesInOuterBrush),
)

/** Brush counterpart of [withTashkeelColor]. */
public fun StyledText.withTashkeelBrush(brush: Brush): StyledText =
    appendTashkeelSpans(SpanStyle(brush = brush))

private fun StyledText.appendTashkeelSpans(style: SpanStyle): StyledText {
    if (text.isEmpty()) return this
    val newSpans = spans.toMutableList()
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val cp: Int
        val width: Int
        if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            cp = 0x10000 + ((ch.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            width = 2
        } else {
            cp = ch.code
            width = 1
        }
        if (isTashkeelCodepoint(cp)) {
            newSpans.add(StyleRange(i, i + width, style))
        }
        i += width
    }
    return copy(spans = newSpans)
}

internal fun isTashkeelCodepoint(cp: Int): Boolean = when {
    cp in 0x064B..0x065F -> true
    cp == 0x0670 -> true
    cp in 0x06D6..0x06DC -> true
    cp in 0x06DF..0x06E4 -> true
    cp in 0x06E7..0x06E8 -> true
    cp in 0x06EA..0x06ED -> true
    else -> false
}
