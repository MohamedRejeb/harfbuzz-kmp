package com.mohamedrejeb.harfbuzz.core.paragraph

/**
 * Horizontal alignment of each line within a [LaidOutParagraph].
 *
 *  - [Start] / [End] resolve against the paragraph's base direction:
 *    in an LTR paragraph `Start` is left-aligned, in an RTL paragraph
 *    `Start` is right-aligned.
 *  - [Left] / [Right] are absolute regardless of base direction.
 *  - [Center] centres each line within the paragraph width.
 *  - [Justify] fills each line to `maxWidth` per the chosen
 *    [JustificationStrategy] (Kashida for Arabic, thin-space for the
 *    rest). The last line and lines ended by a hard break stay
 *    [Start]-aligned without extra width distribution.
 */
public enum class ParagraphAlignment {
    Start,
    End,
    Left,
    Right,
    Center,
    Justify,
}
