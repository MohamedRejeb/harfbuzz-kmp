package com.mohamedrejeb.harfbuzz

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import harfbuzz_kmp.sample.generated.resources.NotoNaskhArabic_Bold
import harfbuzz_kmp.sample.generated.resources.NotoNaskhArabic_Medium
import harfbuzz_kmp.sample.generated.resources.NotoNaskhArabic_Regular
import harfbuzz_kmp.sample.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

/**
 * Wasm-only warm-up for the three NotoNaskhArabic weights used by
 * [harfBuzzSampleTypography]. Routes the resources through Compose
 * Multiplatform's [preloadFont] API so the bytes already kicked off by
 * `index.html`'s `<link rel="preload">` land in the resolver cache before
 * the first Material text widget needs them. Without this the first frame
 * that paints Arabic Material copy stalls on a fetch that hasn't been
 * surfaced to Compose yet.
 *
 * Call this from the Wasm entry point inside [androidx.compose.ui.window.ComposeViewport]
 * before the root `App()` composable, not from `App()` itself - keeping it
 * platform-local means JVM/Android/iOS don't pay any first-paint cost for a
 * warm-up they don't need.
 *
 * See https://kotlinlang.org/docs/multiplatform/compose-web-resources.html#preload-resources-using-the-compose-multiplatform-preload-api
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun PreloadMaterialTypographyFonts() {
    preloadFont(Res.font.NotoNaskhArabic_Regular, FontWeight.Normal)
    preloadFont(Res.font.NotoNaskhArabic_Medium, FontWeight.Medium)
    preloadFont(Res.font.NotoNaskhArabic_Bold, FontWeight.Bold)
}
