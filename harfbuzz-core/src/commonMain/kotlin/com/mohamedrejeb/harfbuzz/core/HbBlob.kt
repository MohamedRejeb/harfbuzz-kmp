package com.mohamedrejeb.harfbuzz.core

/**
 * Memory-owning wrapper around a chunk of bytes - typically a font file. On
 * native targets this references the caller's `ByteArray` without copying;
 * on Wasm the bytes are written directly into harfbuzzjs's wasm heap on
 * construction (zero-copy at the JS↔Wasm boundary).
 *
 * Closing is idempotent. After [close] every method on this object throws
 * [IllegalStateException].
 */
public expect class HbBlob : AutoCloseable {
    public val sizeBytes: Int
    public val isClosed: Boolean

    override fun close()
}
