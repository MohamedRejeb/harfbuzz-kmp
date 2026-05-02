package com.mohamedrejeb.harfbuzz.core

/**
 * JVM/Android have no fast path: the JNI bridge is sync per-call and the
 * platform-neutral slow path in `HbFontStackBuild.kt` already runs every
 * primitive on a background dispatcher. Returning `null` here defers to
 * that slow path.
 */
internal actual suspend fun HbFontStack.tryBuildMeasuredFastPath(
    text: String,
    baseDirection: HbDirection,
    features: List<HbFeature>,
    language: HbLanguage,
    perFontFlags: List<GlyphSnapshotFlags>,
): MeasuredPass? = null

/** JVM/Android: the JNI `fontGlyphSvgSliced` slices in C++ before crossing JNI. */
internal actual val nativeSlicesSvgInOt: Boolean = true
