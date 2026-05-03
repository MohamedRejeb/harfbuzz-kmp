package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Result of one line's word-spacing justification.
 *
 * [justifiedText] is the line with extra thin-space (U+2009) characters
 * inserted at word boundaries. [originalToJustifiedIndex] is the same
 * char-mapping table as [KashidaResult.originalToJustifiedIndex].
 */
public class WordSpacingResult internal constructor(
    public val justifiedText: String,
    public val originalToJustifiedIndex: IntArray,
)

/**
 * Script-agnostic line justifier: distributes thin-space (U+2009)
 * characters across word boundaries until the re-shaped line width
 * approaches `targetWidth`. This mirrors what Compose's `BasicText`
 * does for `TextAlign.Justify` (which only stretches inter-word
 * whitespace) and is the right choice when a project wants
 * platform-uniform Latin-style justification regardless of script.
 *
 * For Arabic content, [KashidaJustifier] yields a far more natural
 * result by elongating letter joins instead of widening word gaps;
 * pick a strategy via [JustificationStrategy].
 *
 * Cost: O(n) - one pass to find space positions, one pass to build the
 * justified string. No boxing.
 */
public object WordSpacingJustifier {

    /** Unicode Thin Space (U+2009): the elongation unit for inter-word stretch. */
    public const val THIN_SPACE: Char = '\u2009'

    /**
     * Insert thin-space glyphs between words until the re-shaped width
     * approaches [targetWidth]. Returns identity when the line is
     * already wide enough, [thinSpaceWidth] is non-positive, or the
     * line has no word boundaries to stretch.
     */
    public fun justifyLine(
        text: String,
        currentWidth: Float,
        targetWidth: Float,
        thinSpaceWidth: Float,
    ): WordSpacingResult {
        if (targetWidth <= currentWidth || thinSpaceWidth <= 0f) return identity(text)

        val spaceIndices = collectSpaceIndices(text)
        if (spaceIndices.isEmpty()) return identity(text)

        val extraSpace = targetWidth - currentWidth
        val totalThinSpaces = maxOf(1, (extraSpace / thinSpaceWidth).toInt())

        val perSpace = distribute(totalThinSpaces, spaceIndices.size)

        val insertCount = IntArray(text.length)
        for (i in spaceIndices.indices) {
            insertCount[spaceIndices[i]] = perSpace[i]
        }

        val builder = StringBuilder(text.length + totalThinSpaces)
        val mapping = IntArray(text.length)
        for (i in text.indices) {
            mapping[i] = builder.length
            builder.append(text[i])
            val extra = insertCount[i]
            if (extra > 0) repeat(extra) { builder.append(THIN_SPACE) }
        }
        return WordSpacingResult(builder.toString(), mapping)
    }

    internal fun distribute(total: Int, slots: Int): IntArray {
        if (slots <= 0) return IntArray(0)
        val base = total / slots
        val remainder = total % slots
        return IntArray(slots) { i -> if (i < remainder) base + 1 else base }
    }

    private fun collectSpaceIndices(text: String): IntArray {
        // Two-pass: count, then fill. Avoids the boxing cost of an
        // ArrayList<Int> on lines with many words.
        var count = 0
        for (i in text.indices) if (text[i] == ' ') count++
        if (count == 0) return EMPTY
        val result = IntArray(count)
        var k = 0
        for (i in text.indices) if (text[i] == ' ') result[k++] = i
        return result
    }

    private fun identity(text: String): WordSpacingResult =
        WordSpacingResult(text, IntArray(text.length) { it })

    private val EMPTY = IntArray(0)
}
