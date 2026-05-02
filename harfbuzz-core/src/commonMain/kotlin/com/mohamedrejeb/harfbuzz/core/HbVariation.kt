package com.mohamedrejeb.harfbuzz.core

/**
 * One axis of a variable font. Each axis is identified by a 4-byte
 * [HbTag] (`wght`, `wdth`, `slnt`, `opsz`, or any custom axis the
 * designer registered) and a `[min, default, max]` triple of single-
 * precision floats clamped to the OpenType variation interpolation
 * range.
 *
 * Discover axes via [HbFace.variationAxes]; pin them by passing
 * a list of [HbVariation] to [HbFace.toFont].
 *
 * @property tag The 4-byte axis tag - common: `wght`, `wdth`, `slnt`,
 *   `opsz`, `ital`. Designer-registered axes use lowercase tags too.
 * @property defaultValue The default value the font ships at.
 * @property minValue Inclusive minimum the axis accepts.
 * @property maxValue Inclusive maximum the axis accepts.
 * @property hidden When `true`, the OpenType `axisRecord.flags`
 *   `HIDDEN_AXIS` bit is set; UI tools should typically hide this axis
 *   from end-user controls. Programmatic users can still pin it.
 */
public data class HbVariationAxis(
    public val tag: HbTag,
    public val defaultValue: Float,
    public val minValue: Float,
    public val maxValue: Float,
    public val hidden: Boolean = false,
) {
    /** Clamp [value] into `[minValue, maxValue]`. */
    public fun clamp(value: Float): Float = when {
        value < minValue -> minValue
        value > maxValue -> maxValue
        else -> value
    }
}

/**
 * One pinned axis value when constructing a variable font instance.
 * Pass a list of [HbVariation] to [HbFace.toFont] to produce an
 * [HbFont] sized at [pointSize] with the given axis values applied.
 *
 * @property tag The axis tag this value applies to.
 * @property value The axis value. Out-of-range values are clamped to
 *   the axis bounds by HarfBuzz; the [HbVariationAxis.clamp] helper
 *   exposes the same clamping if you want to do it client-side.
 */
public data class HbVariation(
    public val tag: HbTag,
    public val value: Float,
) {
    public companion object {
        /** Convenience factory: build from a 4-character ASCII tag. */
        public fun of(tag: String, value: Float): HbVariation =
            HbVariation(HbTag.of(tag), value)
    }
}
