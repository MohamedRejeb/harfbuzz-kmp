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
 * Slice authored runs down to the text range `[start, end)`, rebased to
 * range-local coordinates: the returned runs address
 * `text.substring(start, end)`. Input runs may be unnormalized; the
 * same clamp and last-wins rules as the shaping entry points apply
 * first. Useful when a caller plans lines itself and needs each line's
 * runs in line-local space before shaping the line text.
 */
public fun sliceFontRuns(fontRuns: List<FontRun>, start: Int, end: Int): List<FontRun> {
    if (fontRuns.isEmpty() || end <= start) return emptyList()
    return sliceNormalizedFontRuns(normalizeFontRuns(fontRuns, end), start, end)
}

/** [sliceFontRuns] without the normalize pass, for already-normalized input. */
internal fun sliceNormalizedFontRuns(
    normalizedRuns: List<FontRun>,
    start: Int,
    end: Int,
): List<FontRun> {
    if (normalizedRuns.isEmpty() || end <= start) return emptyList()
    val out = ArrayList<FontRun>()
    for (run in normalizedRuns) {
        if (run.end <= start) continue
        if (run.start >= end) break
        out.add(FontRun(maxOf(run.start, start) - start, minOf(run.end, end) - start, run.font))
    }
    return out
}

/**
 * Project authored runs through a connector-insertion mapping (the
 * `originalToJustifiedIndex` a justifier reports): entry `i` is the
 * position of original char `i` in the widened text. A connector
 * inserted between original chars `i` and `i + 1` lands with the run
 * that contains `i`, because an inserted Kashida joins to the letter
 * before it.
 *
 * Callers that plan lines themselves and insert connectors into a
 * line's text before re-shaping should: slice the paragraph runs with
 * [sliceFontRuns], run their justifier to get the widened text plus
 * mapping, remap the sliced runs with this function, and shape the
 * widened text with the remapped runs.
 */
public fun remapFontRuns(
    fontRuns: List<FontRun>,
    originalToJustifiedIndex: IntArray,
    justifiedLength: Int,
): List<FontRun> {
    if (fontRuns.isEmpty()) return fontRuns
    val originalLength = originalToJustifiedIndex.size
    return fontRuns.mapNotNull { run ->
        val s = run.start.coerceIn(0, originalLength)
        val e = run.end.coerceIn(0, originalLength)
        if (e <= s) return@mapNotNull null
        val js = originalToJustifiedIndex[s]
        val je = if (e >= originalLength) justifiedLength else originalToJustifiedIndex[e]
        if (je <= js) null else FontRun(js, je, run.font)
    }
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
