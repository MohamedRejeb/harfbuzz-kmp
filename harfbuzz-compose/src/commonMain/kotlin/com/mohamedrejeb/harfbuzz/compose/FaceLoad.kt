package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import com.mohamedrejeb.harfbuzz.core.HbFace

/**
 * State surfaced by [rememberHbFace] when the face is loaded asynchronously
 * (e.g. from a Compose Multiplatform `FontResource`). Pattern-match before
 * calling `face.toFont(...)` or `rememberHbFont(face, ...)`.
 *
 * Mirrors [FontLoad] for callers that need an [HbFace] without a particular
 * point size yet - typical when one face produces several `HbFont` instances
 * over time (variable fonts, per-axis sliders, etc.).
 */
@Immutable
public sealed interface FaceLoad {
    public data object Loading : FaceLoad
    public data class Ready(val face: HbFace) : FaceLoad
    public data class Failed(val cause: Throwable) : FaceLoad
}
