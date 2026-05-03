package com.mohamedrejeb.harfbuzz.compose

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint

internal actual fun configureShadowBlur(paint: Paint, blurRadiusPx: Float) {
    if (blurRadiusPx <= 0f) {
        paint.asFrameworkPaint().maskFilter = null
        return
    }
    paint.asFrameworkPaint().maskFilter =
        BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
}
