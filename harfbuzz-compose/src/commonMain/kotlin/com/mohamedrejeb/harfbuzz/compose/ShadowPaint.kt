package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Paint

/**
 * Configure [paint] so that drawing the shadow silhouette path produces
 * the blurred drop-shadow look. The caller has already set the paint
 * color, alpha, and blend mode and translated the canvas by the shadow
 * offset; this function is responsible only for the blur.
 *
 * [blurRadiusPx] is in CSS-shadow conventions (the radius callers pass
 * via `androidx.compose.ui.graphics.Shadow.blurRadius`). Implementations
 * convert it to whichever unit the platform's blur primitive expects:
 *  - Android: `BlurMaskFilter` accepts a radius directly.
 *  - Skiko (JVM, iOS, Wasm): `MaskFilter.makeBlur` accepts a Gaussian
 *    sigma. We use `sigma = blurRadius / 2` to roughly match the
 *    visual extent Compose's own `TextStyle.shadow` produces.
 *
 * A non-positive [blurRadiusPx] is a no-op: the silhouette renders crisp
 * at the offset, which is the documented behaviour of `Shadow` with a
 * zero blur radius.
 */
internal expect fun configureShadowBlur(paint: Paint, blurRadiusPx: Float)
