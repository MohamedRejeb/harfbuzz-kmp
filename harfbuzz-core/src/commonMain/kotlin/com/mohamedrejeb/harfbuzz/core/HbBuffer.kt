package com.mohamedrejeb.harfbuzz.core

/**
 * Mutable shaping input - the text to shape plus its direction, script,
 * language, and per-call OpenType features. Reusable: call [reset] before
 * loading new text into an existing buffer.
 *
 * Not thread-safe.
 */
public expect class HbBuffer() : AutoCloseable {
    public var text: String

    /**
     * Load [contextText] into the buffer but shape only the
     * `[itemOffset, itemOffset + itemLength)` slice; the surrounding
     * characters become HarfBuzz pre/post context, so joining scripts
     * pick initial/medial/final forms as if the whole string were
     * shaped. Cluster values in the shape output are relative to
     * [contextText] (i.e. absolute), not to the slice. Setting [text]
     * or calling [reset] afterwards returns the buffer to whole-string
     * shaping.
     */
    public fun setTextWithContext(contextText: String, itemOffset: Int, itemLength: Int)

    public var direction: HbDirection
    public var script: HbScript
    public var language: HbLanguage
    public var features: List<HbFeature>
    public val isClosed: Boolean

    public fun reset()
    override fun close()
}
