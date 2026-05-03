package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Paint
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

internal actual fun configureShadowBlur(paint: Paint, blurRadiusPx: Float) {
    if (blurRadiusPx <= 0f) {
        paint.asFrameworkPaint().maskFilter = null
        return
    }
    // Skia takes a Gaussian sigma, not a CSS-style radius. The /2 ratio
    // is the same one Compose Multiplatform's text-shadow path uses, so
    // a Shadow.blurRadius matches roughly between BasicText and our
    // shaped renderer at the same value.
    paint.asFrameworkPaint().maskFilter =
        MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma = blurRadiusPx / 2f)
}
