package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.runShapingWork
import kotlin.coroutines.cancellation.CancellationException

/**
 * Snapshot-swap variant of [rememberHbFont] that never blanks the UI on
 * cold load.
 *
 * Returns a [State] of [HbFont] whose initial value is [fallback] and
 * whose value swaps to the loaded font once [bytesProvider] resolves.
 * Mirrors how Compose's `FontFamilyResolver.resolve(fontFamily)` returns
 * a `State<Any>` that starts with the platform fallback typeface and
 * snapshot-swaps to the requested face when async load completes - UI
 * never goes through a `Loading` state.
 *
 * Compare with the existing [rememberHbFont] overload that returns
 * `State<FontLoad>`: that one forces the caller to render a placeholder
 * (or branch on `Loading`) until the load lands. This one renders
 * immediately in [fallback] and re-renders in the requested font on the
 * second frame. Pick this when first-paint correctness can be relaxed
 * (the difference is invisible on warm starts; on cold starts the user
 * sees the same string in a slightly different face for one frame, far
 * better than a blank or spinner).
 *
 * Failures keep the state pinned at [fallback] and surface the cause to
 * the platform console - there's no error type in the returned state.
 * Callers that need explicit failure UX should stay on [rememberHbFont].
 *
 * Lifecycle:
 *  - [fallback] is owned by the caller - not closed here. Typical pattern
 *    is to share one process-wide fallback across many call sites.
 *  - The async-loaded `HbFont` is closed when this composable leaves
 *    composition or when [key] / [sizePx] change.
 *  - The underlying `HbFace` is owned by [HbFaceCache] and survives
 *    across recompositions for the process lifetime.
 *
 * @param fallback Font shown before the async load resolves and after
 *   any load failure. Required - pick a process-wide default (e.g. a
 *   bundled system-ish face) and reuse it across screens.
 * @param bytesProvider Suspend lambda that produces the requested
 *   font's bytes. Called once per `(key, sizePx)` pair.
 * @param sizePx Point size for the loaded font.
 * @param key Memoization key for the load. Defaults to identity of
 *   [bytesProvider]; pass an explicit string when [bytesProvider] is
 *   a fresh lambda each recomposition (the common Compose Multiplatform
 *   `Res.readBytes(...)` pattern).
 */
@Composable
public fun rememberHbFontAsync(
    fallback: HbFont,
    bytesProvider: suspend () -> ByteArray,
    sizePx: Float = DEFAULT_FONT_SIZE_PX,
    key: Any? = bytesProvider,
): State<HbFont> {
    val state: MutableState<HbFont> = remember(fallback, key, sizePx) { mutableStateOf(fallback) }
    val holder = remember(key, sizePx) { AsyncFontHolder() }

    LaunchedEffect(key, sizePx) {
        // Reset to fallback every time the key/size flips so a new resource
        // doesn't briefly show the previous frame's font.
        state.value = fallback
        var loadedFont: HbFont? = null
        try {
            // Same single-bracket pattern as the bytes-overload of
            // `rememberHbFont` - every step (init + bytes read + parse +
            // toFont) lands on the harfbuzz background dispatcher in one
            // hop.
            val font = runShapingWork {
                harfBuzzInit()
                val bytes = bytesProvider()
                val face = HbFaceCache.get(bytes)
                face.toFont(sizePx)
            }
            loadedFont = font
            holder.font = font
            state.value = font
        } catch (ce: CancellationException) {
            loadedFont?.close()
            throw ce
        } catch (cause: Throwable) {
            loadedFont?.close()
            if (isStaleHbHandleAsync(cause)) return@LaunchedEffect
            // Stay on fallback - surface to platform console for debugging.
            println("[kotlin-harfbuzz] rememberHbFontAsync failed for key=$key sizePx=$sizePx: $cause")
            cause.printStackTrace()
        }
    }

    DisposableEffect(key, sizePx) {
        onDispose {
            // The async-loaded font is unique to this remember slot - close.
            // Fallback is caller-owned; HbFace stays in HbFaceCache.
            holder.font?.close()
            holder.font = null
        }
    }

    return state
}

private class AsyncFontHolder {
    var font: HbFont? = null
}

private fun isStaleHbHandleAsync(cause: Throwable): Boolean =
    cause is IllegalStateException && cause.message == "hb object disposed"
