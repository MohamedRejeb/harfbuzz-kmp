package com.mohamedrejeb.harfbuzz.core

/**
 * iOS: HarfBuzz is statically linked into the Kotlin/Native binary, so no
 * runtime gate is needed.
 */
public actual suspend fun harfBuzzInit() {
    // No-op.
}
