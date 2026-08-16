package com.mohamedrejeb.harfbuzz.core

/**
 * An authored font assignment for a character range of the original text.
 *
 * [start] (inclusive) and [end] (exclusive) are UTF-16 code-unit offsets
 * into the text passed to the shaping entry point. Ranges outside the
 * text are clamped; inverted or empty ranges are ignored; where ranges
 * overlap, the later entry in the list wins for the overlapped span.
 *
 * The caller retains ownership of [font], exactly as with the fonts in
 * [HbFontStack]: the shaping pipeline never closes it, and the font must
 * stay alive for as long as shapes built from it are in use. Equality is
 * structural on the offsets and identity-based on [font] (an [HbFont]
 * compares by instance), which is what cache keys rely on.
 */
public data class FontRun(
    public val start: Int,
    public val end: Int,
    public val font: HbFont,
)

/** A resolved slice of text: [font] is null where no authored run applies. */
internal data class FontSegment(val start: Int, val end: Int, val font: HbFont?)

/**
 * Clamp to `[0, textLength]`, drop empty or inverted ranges, resolve
 * overlaps last-wins, and merge adjacent spans that resolved to the same
 * font instance. Output is sorted by [FontRun.start] and disjoint.
 */
internal fun normalizeFontRuns(runs: List<FontRun>, textLength: Int): List<FontRun> {
    if (runs.isEmpty() || textLength <= 0) return emptyList()
    // Paint fonts over a per-index array; later entries overwrite, which
    // makes last-wins and clamping trivially correct for paragraph-sized
    // inputs.
    val owner = arrayOfNulls<HbFont>(textLength)
    for (run in runs) {
        val s = run.start.coerceIn(0, textLength)
        val e = run.end.coerceIn(0, textLength)
        for (i in s until e) owner[i] = run.font
    }
    val out = ArrayList<FontRun>()
    var i = 0
    while (i < textLength) {
        val font = owner[i]
        if (font == null) {
            i++
            continue
        }
        var j = i + 1
        while (j < textLength && owner[j] === font) j++
        out.add(FontRun(i, j, font))
        i = j
    }
    return out
}

/**
 * Slice `[rangeStart, rangeEnd)` against [normalizedRuns] (output of
 * [normalizeFontRuns]), producing contiguous segments in source order
 * that cover the whole range. Gaps carry `font == null`.
 */
internal fun segmentRange(
    rangeStart: Int,
    rangeEnd: Int,
    normalizedRuns: List<FontRun>,
): List<FontSegment> {
    if (rangeEnd <= rangeStart) return emptyList()
    val out = ArrayList<FontSegment>()
    var cursor = rangeStart
    for (run in normalizedRuns) {
        if (run.end <= cursor) continue
        if (run.start >= rangeEnd) break
        val s = maxOf(run.start, cursor)
        val e = minOf(run.end, rangeEnd)
        if (s > cursor) out.add(FontSegment(cursor, s, null))
        out.add(FontSegment(s, e, run.font))
        cursor = e
    }
    if (cursor < rangeEnd) out.add(FontSegment(cursor, rangeEnd, null))
    return out
}
