package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode an encoded bitmap glyph payload (PNG - see
 * [com.mohamedrejeb.harfbuzz.core.PaintImage]) into an [ImageBitmap].
 * Returns `null` when the payload can't be decoded; the caller skips the
 * layer rather than failing the whole glyph.
 */
internal expect fun decodePaintImage(bytes: ByteArray): ImageBitmap?
