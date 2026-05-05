package com.mohamedrejeb.harfbuzz.compose

import android.graphics.PathMeasure as AndroidPathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath

internal actual fun samplePathContours(path: Path, sampleStep: Float): List<FloatArray> {
    val nativePath = path.asAndroidPath()
    val measure = AndroidPathMeasure(nativePath, false)
    val result = ArrayList<FloatArray>()
    val pos = FloatArray(2)

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
            if (measure.getPosTan(sampleDist, pos, null)) {
                if (idx + 2 > arr.size) break
                arr[idx++] = pos[0]
                arr[idx++] = pos[1]
            }
            if (sampleDist >= len) finished = true else d += sampleStep
        }
        if (idx > 0) result.add(arr.copyOf(idx))
    } while (measure.nextContour())

    return result
}
