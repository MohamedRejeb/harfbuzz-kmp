package com.mohamedrejeb.harfbuzz.core

/**
 * Force per-font HarfBuzz lazy initialisation onto the font-load
 * coroutine instead of the user's first visible shape. HarfBuzz
 * defers OT-table parsing (cmap, gsub, gpos, COLR, SVG...) and
 * per-face glyph-cache setup until the first `hb_shape` call against
 * a face. That first-shape cost is significant (heavier still for
 * COLR/SVG color fonts) and otherwise falls on the visible cold-paint
 * critical path, because [HbFace.toFont] returns before any shape
 * has run.
 *
 * Calling this from inside [HbFace.toFont]'s `withContext(
 * harfbuzzDispatcher)` block does the first-shape work on the
 * `harfbuzz-bg` thread *before* `toFont` resumes. By the time
 * `rememberHbFont` flips its [com.mohamedrejeb.harfbuzz.compose.FontLoad]
 * to [com.mohamedrejeb.harfbuzz.compose.FontLoad.Ready] (and the loader
 * spinner is still on screen), the per-font HB caches are populated;
 * the user's subsequent shapes against this font run in the
 * post-warmup ~5–15 ms range instead of paying the cold cost.
 *
 * Issued via the JNI `shape` primitive directly rather than going
 * through [HbFont.shapeParagraph] so the warmup doesn't drag in BiDi
 * resolution, segment splitting, or buffer-reset bookkeeping -
 * none of which are needed to populate the OT tables HarfBuzz
 * lazy-loads on first use. Failures are swallowed: a font that
 * can't shape a single ASCII space is broken in ways the warmup
 * shouldn't try to surface (the next real shape will fail
 * authoritatively).
 */
internal fun warmFont(font: HbFont) {
    runCatching {
        val bufferPtr = HarfbuzzNative.bufferCreate()
        try {
            HarfbuzzNative.bufferSetText(bufferPtr, " ")
            // Pinning direction skips `hb_buffer_guess_segment_properties`
            // which would otherwise add per-call overhead; we know what
            // we're shaping.
            HarfbuzzNative.bufferSetDirection(bufferPtr, HB_DIRECTION_LTR)
            HarfbuzzNative.shape(font.ptr, bufferPtr, null, 0)
        } finally {
            HarfbuzzNative.bufferDestroy(bufferPtr)
        }
    }
}

private const val HB_DIRECTION_LTR: Int = 4
