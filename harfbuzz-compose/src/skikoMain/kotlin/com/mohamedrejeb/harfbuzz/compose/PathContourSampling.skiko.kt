package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath
import org.jetbrains.skia.PathMeasure as SkiaPathMeasure

internal actual fun samplePathContours(path: Path, sampleStep: Float): List<FloatArray> {
    val skPath = path.asSkiaPath()
    val measure = SkiaPathMeasure(skPath, forceClosed = false, resScale = 1f)
    val result = ArrayList<FloatArray>()

    do {
        val len = measure.length
        if (len <= 0f) continue

        val capacity = ((len / sampleStep).toInt() + 2).coerceAtLeast(2)
        val arr = FloatArray(capacity * 2)
        var d = 0f
        var idx = 0
        var finished = false
        while (!finished) {
            val sampleDist = if (d > len) len else d
            val p = measure.getPosition(sampleDist)
            if (p != null) {
                if (idx + 2 > arr.size) break
                arr[idx++] = p.x
                arr[idx++] = p.y
            }
            if (sampleDist >= len) finished = true else d += sampleStep
        }
        if (idx > 0) result.add(arr.copyOf(idx))
    } while (measure.nextContour())

    return result
}
