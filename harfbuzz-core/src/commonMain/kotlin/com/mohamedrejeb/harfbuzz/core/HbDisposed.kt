package com.mohamedrejeb.harfbuzz.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Disposal racing in-flight shaping work is a lifecycle event, not a
 * programmer error: surface it as [CancellationException] so callers'
 * structured concurrency unwinds instead of crashing the app. Extends
 * IllegalStateException, so existing handlers keep working.
 */
internal fun throwIfDisposed(closed: Boolean) {
    if (closed) throw CancellationException("hb object disposed")
}
