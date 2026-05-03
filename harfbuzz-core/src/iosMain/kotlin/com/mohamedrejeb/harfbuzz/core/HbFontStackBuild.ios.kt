package com.mohamedrejeb.harfbuzz.core

/**
 * iOS has no fast path today: the cinterop bindings are sync per-call and
 * the platform-neutral slow path in `HbFontStackBuild.kt` already runs
 * every primitive on a background dispatcher. Returning `null` here
 * defers to that slow path.
 */
internal actual suspend fun HbFontStack.tryBuildMeasuredFastPath(
    text: String,
    sizePx: Float,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    perFontFlags: List<GlyphSnapshotFlags>,
): MeasuredPass? = null

/** iOS: cinterop returns whole SVG documents; the Compose layer's slicer still runs. */
internal actual val nativeSlicesSvgInOt: Boolean = false
