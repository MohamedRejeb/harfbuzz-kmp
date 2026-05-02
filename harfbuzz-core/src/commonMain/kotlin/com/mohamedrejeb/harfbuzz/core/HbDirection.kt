package com.mohamedrejeb.harfbuzz.core

/**
 * Layout direction for a shaped run. `AUTO` resolves at shape time from the
 * first strong directional codepoint - useful when the caller doesn't know
 * the script up front.
 */
public enum class HbDirection {
    AUTO,
    LTR,
    RTL,
    TTB,
    BTT,
    ;

    public val isHorizontal: Boolean get() = this == LTR || this == RTL
    public val isVertical: Boolean get() = this == TTB || this == BTT
    public val isReverse: Boolean get() = this == RTL || this == BTT
}
