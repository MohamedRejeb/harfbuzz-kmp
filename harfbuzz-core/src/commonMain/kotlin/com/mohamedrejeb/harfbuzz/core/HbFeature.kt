package com.mohamedrejeb.harfbuzz.core

/**
 * An OpenType feature toggle applied at shape time. A [value] of `0u` disables
 * the feature; non-zero values enable it (and select an alternate, for tags
 * that take an index such as `"aalt"`). [start] and [end] scope the toggle to
 * a codepoint range; the defaults cover the entire input.
 */
public data class HbFeature(
    public val tag: HbTag,
    public val value: UInt = 1u,
    public val start: Int = 0,
    public val end: Int = Int.MAX_VALUE,
) {
    public constructor(
        tag: String,
        value: UInt = 1u,
        start: Int = 0,
        end: Int = Int.MAX_VALUE,
    ) : this(HbTag.of(tag), value, start, end)
}
