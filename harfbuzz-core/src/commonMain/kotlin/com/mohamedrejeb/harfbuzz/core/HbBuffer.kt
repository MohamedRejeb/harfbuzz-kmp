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
    public var direction: HbDirection
    public var script: HbScript
    public var language: HbLanguage
    public var features: List<HbFeature>
    public val isClosed: Boolean

    public fun reset()
    override fun close()
}
