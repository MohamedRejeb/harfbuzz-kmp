package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.runShapingWork
import kotlin.coroutines.cancellation.CancellationException

/**
 * Construct an [HbFont] from an already-loaded [HbFace] and bind its
 * lifecycle to the calling composition.
 *
 * Returns a [State] of [FontLoad] because [HbFace.toFont] now suspends -
 * the actual call happens on the platform's HarfBuzz background dispatcher
 * (worker on Wasm, dedicated thread on JVM/Android/iOS). The font is closed
 * automatically when the composable leaves composition; the caller still
 * owns [face].
 *
 * Pattern-match on the returned state in your composable:
 *
 * ```
 * val state by rememberHbFont(face)
 * when (val s = state) {
 *     FontLoad.Loading   -> CircularProgressIndicator()
 *     is FontLoad.Failed -> Text("Failed: ${s.cause.message}")
 *     is FontLoad.Ready  -> ShapedText("Hello", font = s.font)
 * }
 * ```
 */
@Composable
public fun rememberHbFont(face: HbFace): State<FontLoad> {
    val state: MutableState<FontLoad> =
        remember(face) { mutableStateOf(FontLoad.Loading as FontLoad) }
    val holder = remember(face) { FontHolder() }

    LaunchedEffect(face) {
        state.value = FontLoad.Loading
        var built: HbFont? = null
        try {
            built = face.toFont()
            holder.font = built
            state.value = FontLoad.Ready(built)
        } catch (ce: CancellationException) {
            built?.close()
            throw ce
        } catch (cause: Throwable) {
            built?.close()
            if (isStaleHbHandle(cause)) return@LaunchedEffect
            println("[kotlin-harfbuzz] rememberHbFont(face) failed: $cause")
            cause.printStackTrace()
            state.value = FontLoad.Failed(cause)
        }
    }

    DisposableEffect(face) {
        onDispose {
            holder.font?.close()
            holder.font = null
        }
    }

    return state
}

/**
 * Asynchronously load font bytes via [bytesProvider] (e.g.
 * `Res.readBytes("font/NotoNaskhArabic.ttf")`), construct the full
 * `HbFace → HbFont` chain, and surface the result as a [FontLoad] state.
 * Closes the chain when leaving composition.
 *
 * Pattern-match on the returned state in your composable:
 *
 * ```
 * val state by rememberHbFont({ Res.readBytes("font/NotoNaskhArabic.ttf") })
 * when (val s = state) {
 *     FontLoad.Loading   -> CircularProgressIndicator()
 *     is FontLoad.Failed -> Text("Failed: ${s.cause.message}")
 *     is FontLoad.Ready  -> ShapedText("مرحبا", font = s.font)
 * }
 * ```
 *
 * [key] is used to memoize the load - pass anything that uniquely identifies
 * the underlying resource (typically the resource path string).
 */
@Composable
public fun rememberHbFont(
    bytesProvider: suspend () -> ByteArray,
    key: Any? = bytesProvider,
): State<FontLoad> {
    val state: MutableState<FontLoad> =
        remember(key) { mutableStateOf(FontLoad.Loading as FontLoad) }
    val holder = remember(key) { FontHolder() }

    LaunchedEffect(key) {
        state.value = FontLoad.Loading
        // Only the font needs cleanup tracking - the face is owned by
        // HbFaceCache and survives errors / cancellation.
        var loadedFont: HbFont? = null
        try {
            // Wrap the entire load in `runShapingWork { ... }` so every
            // step - `harfBuzzInit()` (System.loadLibrary), `bytesProvider()`
            // (font resource read), `HbFace.fromBytes`, and `toFont` - runs
            // on the HarfBuzz background dispatcher in a single hop instead
            // of bouncing back to Main between calls. Otherwise:
            //  - `bytesProvider()` runs on the LaunchedEffect's coroutine
            //    context (Main on JVM/Android/iOS) - even when the body is
            //    `Res.readBytes(...)`, *invoking* the suspend lambda happens
            //    on Main first, and not all `bytesProvider` impls hop to IO
            //    internally (a user-supplied `{ File(path).readBytes() }`
            //    would block Main outright).
            //  - The inner `withContext(harfbuzzDispatcher)` calls in
            //    `HbFace.fromBytes` / `toFont` collapse to no-ops once we're
            //    already on the dispatcher, so we save N round-trip context
            //    switches for the price of one.
            //
            // Wasm: `runShapingWork` is pass-through (HB calls cross to the
            // Web Worker via `HbWorker` RPC asynchronously regardless), so
            // this wrap is a no-op there and doesn't pin Main.
            val (face, font) = runShapingWork {
                harfBuzzInit()
                val bytes = bytesProvider()
                // Item J: route through the process-wide face cache so
                // a font-size animation that re-reads the same bytes
                // doesn't re-parse the face on every frame. Cached
                // faces are owned by HbFaceCache - DO NOT close them
                // on dispose (see DisposableEffect below).
                val newFace = HbFaceCache.get(bytes)
                val newFont = newFace.toFont()
                newFace to newFont
            }
            loadedFont = font
            holder.face = face
            holder.font = font
            state.value = FontLoad.Ready(font)
        } catch (ce: CancellationException) {
            // Don't close `loadedFace` - it's owned by HbFaceCache.
            loadedFont?.close()
            throw ce
        } catch (cause: Throwable) {
            loadedFont?.close()
            if (isStaleHbHandle(cause)) return@LaunchedEffect
            // Surface to the platform console so failures are inspectable in
            // the browser devtools / logcat / stderr - the on-screen
            // FontLoad.Failed only carries the top-level message.
            println("[kotlin-harfbuzz] rememberHbFont failed for key=$key: $cause")
            cause.printStackTrace()
            state.value = FontLoad.Failed(cause)
        }
    }

    DisposableEffect(key) {
        onDispose {
            // The font is unique to this remember slot - close it. The
            // face came from HbFaceCache and is shared across call
            // sites - do NOT close it here, the cache owns its lifetime.
            holder.font?.close()
        }
    }

    return state
}

/**
 * Asynchronously load font bytes via [bytesProvider] and construct an
 * [HbFace], surfacing the result as a [FaceLoad] state. Faces come
 * from the process-wide [HbFaceCache] - they are NOT closed when
 * leaving composition.
 *
 * Use this when the caller needs a face without committing to a point
 * size yet - typical for variable-font UIs that derive several
 * [HbFont]s from the same face (e.g. per-axis sliders, on-the-fly
 * variation tuples). For the common single-size case, prefer
 * [rememberHbFont] which gives you a ready-to-shape [HbFont].
 *
 * The full load chain - `harfBuzzInit()`, [bytesProvider] invocation,
 * and `HbFace.fromBytes` - runs inside one
 * `runShapingWork { ... }` bracket so each step lands on the
 * HarfBuzz background dispatcher (JVM/Android: `harfbuzz-bg` thread;
 * iOS: parallelism-1 view of Default; Wasm: pass-through, calls cross
 * to a Web Worker via [HbWorker] RPC). This matters on Android in
 * particular: Compose Multiplatform's `Res.readBytes(...)` does NOT
 * `withContext(Dispatchers.IO)` internally, so without this wrap the
 * asset open + read would block Main on cold start.
 *
 * Pattern-match on the returned state in your composable:
 *
 * ```
 * val state by rememberHbFace({ Res.readBytes("font/Trox.ttf") })
 * when (val s = state) {
 *     FaceLoad.Loading   -> CircularProgressIndicator()
 *     is FaceLoad.Failed -> Text("Failed: ${s.cause.message}")
 *     is FaceLoad.Ready  -> VariableFontControls(s.face)
 * }
 * ```
 *
 * [key] is used to memoize the load - pass anything that uniquely
 * identifies the underlying resource (typically the resource path
 * string).
 */
@Composable
public fun rememberHbFace(
    bytesProvider: suspend () -> ByteArray,
    key: Any? = bytesProvider,
): State<FaceLoad> {
    val state: MutableState<FaceLoad> =
        remember(key) { mutableStateOf(FaceLoad.Loading as FaceLoad) }
    val holder = remember(key) { FaceHolder() }

    LaunchedEffect(key) {
        state.value = FaceLoad.Loading
        var loadedFace: HbFace? = null
        try {
            // Same single-bracket pattern as the bytes-overload of
            // `rememberHbFont` - see that comment for the full rationale on
            // why every step needs to be inside `runShapingWork`.
            val face = runShapingWork {
                harfBuzzInit()
                val bytes = bytesProvider()
                // Item J: process-wide face cache. The face is owned
                // by HbFaceCache - see the bytes-overload of
                // rememberHbFont above for the lifecycle rules.
                HbFaceCache.get(bytes)
            }
            loadedFace = face
            holder.face = face
            state.value = FaceLoad.Ready(face)
        } catch (ce: CancellationException) {
            // Face owned by HbFaceCache - do not close.
            throw ce
        } catch (cause: Throwable) {
            if (isStaleHbHandle(cause)) return@LaunchedEffect
            println("[kotlin-harfbuzz] rememberHbFace failed for key=$key: $cause")
            cause.printStackTrace()
            state.value = FaceLoad.Failed(cause)
        }
    }

    DisposableEffect(key) {
        onDispose {
            // Face owned by HbFaceCache; do not close. Just drop the
            // reference so we don't pin the cached entry past dispose.
            holder.face = null
        }
    }

    return state
}

private class FontHolder {
    var face: HbFace? = null
    var font: HbFont? = null
}

private class FaceHolder {
    var face: HbFace? = null
}

private fun isStaleHbHandle(cause: Throwable): Boolean =
    cause is IllegalStateException && cause.message == "hb object disposed"

/** Default font size if the caller doesn't pass one. */
public const val DEFAULT_FONT_SIZE_PX: Float = 16f
