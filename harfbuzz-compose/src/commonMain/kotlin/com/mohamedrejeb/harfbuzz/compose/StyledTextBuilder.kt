package com.mohamedrejeb.harfbuzz.compose

/**
 * Mutable builder for [StyledText]. Use [buildStyledText] to
 * construct - the builder is internal-only because direct mutation
 * outside the DSL block has no use case.
 *
 * - [append] appends raw text; if a [withStyle] block is active, the
 *   appended range receives that style.
 * - [append] with an explicit [SpanStyle] always tags the appended
 *   range with that style (independent of any active stack entry).
 * - [addStyle] tags an existing range without mutating the text.
 * - [withStyle] pushes a style onto the active stack for the
 *   duration of [block]; nested blocks shadow outer styles for their
 *   own appends, then pop on exit.
 */
public class StyledTextBuilder internal constructor(initialText: String = "") {
    private val sb: StringBuilder = StringBuilder(initialText)
    private val spans: MutableList<StyleRange> = mutableListOf()
    private val styleStack: ArrayDeque<SpanStyle> = ArrayDeque()

    public fun append(text: String): StyledTextBuilder {
        val start = sb.length
        sb.append(text)
        val end = sb.length
        styleStack.lastOrNull()?.let { active ->
            if (end > start) spans.add(StyleRange(start, end, active))
        }
        return this
    }

    public fun append(text: String, style: SpanStyle): StyledTextBuilder {
        val start = sb.length
        sb.append(text)
        val end = sb.length
        if (end > start) spans.add(StyleRange(start, end, style))
        return this
    }

    public fun addStyle(style: SpanStyle, start: Int, end: Int) {
        spans.add(StyleRange(start, end, style))
    }

    public fun withStyle(
        style: SpanStyle,
        block: StyledTextBuilder.() -> Unit,
    ) {
        styleStack.addLast(style)
        try {
            block()
        } finally {
            styleStack.removeLast()
        }
    }

    internal fun build(): StyledText = StyledText(
        text = sb.toString(),
        spans = spans.toList(),
    )
}

/** Build a [StyledText] from scratch via the [StyledTextBuilder] DSL. */
public fun buildStyledText(
    block: StyledTextBuilder.() -> Unit,
): StyledText = StyledTextBuilder().apply(block).build()

/**
 * Build a [StyledText] starting from [text]; the [block] can call
 * [StyledTextBuilder.addStyle] without re-appending the text first.
 */
public fun buildStyledText(
    text: String,
    block: StyledTextBuilder.() -> Unit,
): StyledText = StyledTextBuilder(text).apply(block).build()
