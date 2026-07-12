package com.mohamedrejeb.harfbuzz.core

import android.content.Context
import androidx.startup.Initializer
import kotlin.coroutines.EmptyCoroutineContext

/**
 * androidx.startup [Initializer] that pre-loads `libharfbuzz_jni.so`
 * and the [SystemFontCandidates] metadata snapshot on the HarfBuzz
 * background dispatcher when the app process starts, before the first
 * composable mounts.
 *
 * Without this, the first call to [harfBuzzInit] from a `LaunchedEffect`
 * pays the full `System.loadLibrary` cost (30–80 ms on Android cold
 * start: APK scan, mmap, dynamic-symbol resolution, `JNI_OnLoad`) before
 * the suspend can resume - and that delay overlaps the cold-paint
 * window. Pre-warming during process init pushes that cost off the
 * cold-paint timeline entirely; subsequent [harfBuzzInit] calls find
 * the library already loaded and return immediately.
 *
 * Dispatching the prewarm onto `harfbuzzDispatcher` (the single-threaded
 * `harfbuzz-bg` daemon lane every runtime caller already uses) instead
 * of a one-off prewarm thread has two benefits over a separate daemon
 * thread:
 *  - One fewer thread allocated at process init.
 *  - Subsequent shape work that hops to the same dispatcher queues
 *    naturally **behind** the prewarm Runnable. The harfbuzz-bg thread
 *    is already pinned to executing that prewarm; the next caller's
 *    continuation just runs after it via the dispatcher's FIFO queue,
 *    without ever entering the JVM-monitor wait inside
 *    `HarfbuzzNative.ensureLoaded`.
 *
 * Consumers who want explicit control over startup time (or use a
 * custom AppStartup pipeline) can disable this with
 * `tools:node="remove"` on the registered `<meta-data>` element.
 */
public class HarfBuzzInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Capture the application context for later asset access (e.g. reading
        // the EmojiCompat-bundled `NotoColorEmojiCompat.ttf` so HarfBuzz Text
        // renders the same emoji glyphs Compose Text does).
        HarfBuzzAppContext.set(context.applicationContext)
        harfbuzzDispatcher.dispatch(EmptyCoroutineContext) {
            runCatching { HarfbuzzNative.ensureLoaded() }
            // Pre-pay the SystemFonts metadata walk here too: on API 29/30
            // the framework hashes every font file's full buffer during
            // `getAvailableFonts()` (seconds on low-end devices), and the
            // first system-fallback shape - possibly issued from the main
            // thread - would otherwise pay it inline. See [SystemFontCandidates].
            runCatching { SystemFontCandidates.warmUp() }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * Process-wide handle to the [Context] captured by [HarfBuzzInitializer].
 * Used by the system-font resolver to read assets bundled by the consumer
 * (specifically `androidx.emoji2:emoji2-bundled`'s `NotoColorEmojiCompat.ttf`).
 *
 * Holding the *application* context only - never an Activity - so this
 * doesn't leak any UI state.
 */
internal object HarfBuzzAppContext {
    @Volatile private var context: Context? = null
    fun set(ctx: Context) { context = ctx }
    fun get(): Context? = context
}
