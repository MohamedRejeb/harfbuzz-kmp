package com.mohamedrejeb.harfbuzz

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.installLongTaskObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Subscribe to PerformanceObserver `longtask` entries so any JS task on
    // the main thread that pins the page for >50 ms gets logged with its
    // duration + start time. The matching `performance.measure` ranges
    // emitted by HbProfile.wasmJs.kt + hb-worker.js show up alongside in
    // the DevTools Performance panel's User Timing track - together they
    // pinpoint which span is responsible for an observed block.
    installLongTaskObserver()

    // Kick off the HarfBuzz worker boot (fetch hb-worker.js, importScripts
    // hb.js + hbjs.js, instantiate hb.wasm) before Compose mounts, so it
    // overlaps with skiko init + first paint instead of delaying the first
    // font load. Failures are surfaced again when the first composable that
    // actually needs HB calls harfBuzzInit() - no need to handle them here.
    CoroutineScope(Dispatchers.Default).launch {
        runCatching { harfBuzzInit() }
    }
    ComposeViewport {
        // Warm Compose's resolver cache for the three NotoNaskhArabic weights
        // used by `harfBuzzSampleTypography()` BEFORE App() composes - the
        // bytes are already in flight from index.html's `<link rel="preload">`,
        // and `preloadFont(...)` makes them visible to FontFamily.Resolver in
        // time for the first Material text widget that paints. Web-only on
        // purpose: JVM/Android/iOS read bundled assets fast enough that an
        // explicit warm-up is unnecessary first-paint work.
        PreloadMaterialTypographyFonts()
        App()
    }
}