package com.mohamedrejeb.harfbuzz.core.paragraph

import com.mohamedrejeb.harfbuzz.core.paragraph.ArabicTextUtils.KASHIDA
import com.mohamedrejeb.harfbuzz.core.paragraph.ArabicTextUtils.findKashidaInsertionPoints
import com.mohamedrejeb.harfbuzz.core.paragraph.ArabicTextUtils.isArabicText

/**
 * Result of one line's Kashida justification.
 *
 * [justifiedText] is the line with extra Kashida characters inserted at
 * legal join positions; re-shaping it produces a wider line with the
 * same letter sequence. [originalToJustifiedIndex] maps each char index
 * in the original line to the corresponding index in [justifiedText],
 * which downstream callers (cursor / selection / glyph-position queries)
 * use to translate user-space offsets through the justification step.
 */
public class KashidaResult internal constructor(
    public val justifiedText: String,
    public val originalToJustifiedIndex: IntArray,
)

/**
 * Arabic-aware line justifier. Inserts Kashida (tatweel, U+0640) at the
 * legal join positions returned by [findKashidaInsertionPoints] until
 * the line's measured width approaches `targetWidth`.
 *
 * Kashida elongates the connection stroke between two joining Arabic
 * letters, so a line stretched this way preserves the script's visual
 * flow - matching what `CTLineCreateJustifiedLine` does on iOS and
 * unlike Android's plain inter-word stretching. Use this together with
 * a fallback word-spacing justifier for non-Arabic lines (see
 * [JustificationStrategy]).
 *
 * Cost: O(n) over the line text plus a single pass over the insertion
 * points. No `Map` allocations, no boxing - the per-position Kashida
 * counts live in a primitive `IntArray` keyed by char index.
 */
public object KashidaJustifier {

    /**
     * Insert Kashida glyphs into [text] until the re-shaped width
     * approaches [targetWidth]. Returns an identity result (text
     * unchanged) when the line cannot or should not be justified.
     *
     * Bail-out cases:
     *  - The line already fills (or exceeds) the target width.
     *  - [kashidaGlyphWidth] is non-positive (caller forgot to measure).
     *  - The line has no Arabic content.
     *  - No legal Kashida insertion points exist (e.g. an isolated
     *    right-join-only letter sequence like `ا د`).
     *
     * `extraSpace / kashidaGlyphWidth` is **floored**, never rounded:
     * one extra Kashida always inflates the line above target by
     * `kashidaGlyphWidth`, and visibly overflowing the line is worse
     * than leaving a small gap. The minimum-of-1 floor ensures that any
     * sub-Kashida gap still produces a visually-justified line.
     */
    public fun justifyArabicLine(
        text: String,
        currentWidth: Float,
        targetWidth: Float,
        kashidaGlyphWidth: Float,
    ): KashidaResult {
        if (targetWidth <= currentWidth || kashidaGlyphWidth <= 0f) return identity(text)
        if (!isArabicText(text)) return identity(text)

        val insertionPoints = findKashidaInsertionPoints(text)
        if (insertionPoints.isEmpty()) return identity(text)

        val extraSpace = targetWidth - currentWidth
        val totalKashidas = maxOf(1, (extraSpace / kashidaGlyphWidth).toInt())

        val perPoint = distributeKashidas(totalKashidas, insertionPoints.size)

        // Per-source-index count of Kashidas to insert AFTER that index.
        // Most positions have 0 - using a primitive IntArray sized to the
        // line is a couple-hundred-byte allocation vs. a HashMap with
        // boxing per insertion point.
        val insertCount = IntArray(text.length)
        for (i in insertionPoints.indices) {
            insertCount[insertionPoints[i].index] += perPoint[i]
        }

        val builder = StringBuilder(text.length + totalKashidas)
        val mapping = IntArray(text.length)
        for (i in text.indices) {
            mapping[i] = builder.length
            builder.append(text[i])
            val extra = insertCount[i]
            if (extra > 0) repeat(extra) { builder.append(KASHIDA) }
        }
        return KashidaResult(builder.toString(), mapping)
    }

    /**
     * Spread [total] Kashidas across [slots] insertion points as evenly
     * as possible. Earlier slots (= higher priority after sort) absorb
     * the remainder so the strongest-looking joins get extras first.
     *
     * `distribute(7, 3) = [3, 2, 2]`. `distribute(2, 5) = [1, 1, 0, 0, 0]`.
     */
    internal fun distributeKashidas(total: Int, slots: Int): IntArray {
        if (slots <= 0) return IntArray(0)
        val base = total / slots
        val remainder = total % slots
        return IntArray(slots) { i -> if (i < remainder) base + 1 else base }
    }

    private fun identity(text: String): KashidaResult =
        KashidaResult(text, IntArray(text.length) { it })
}
