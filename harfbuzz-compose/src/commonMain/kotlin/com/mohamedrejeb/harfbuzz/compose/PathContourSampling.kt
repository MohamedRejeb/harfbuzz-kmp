package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path

/**
 * Sample every sub-contour of [path] at [sampleStep]-pixel intervals and
 * return the resulting `(x, y)` points, one [FloatArray] per contour
 * (length is `2 * pointCount`, layout `[x0, y0, x1, y1, ...]`).
 *
 * Compose's multiplatform `PathMeasure` exposes only the first contour;
 * letters with holes (Arabic medial forms, Latin O / D / e) need every
 * sub-contour preserved or the warped result fills the holes. Each
 * platform actual unwraps to its underlying `PathMeasure` implementation
 * (`android.graphics.PathMeasure` on Android, `org.jetbrains.skia.PathMeasure`
 * everywhere else) and walks `nextContour()`.
 *
 * The trailing endpoint of every contour is always emitted - the
 * stepping ensures a final sample at exactly `length` so closed
 * contours render flush even when `length` is not a multiple of
 * [sampleStep].
 */
internal expect fun samplePathContours(path: Path, sampleStep: Float): List<FloatArray>
