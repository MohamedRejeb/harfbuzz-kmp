package com.mohamedrejeb.harfbuzz.compose

/**
 * Synchronizes the annotated method on Android/desktop, where UI-thread
 * draws and background renders share the process-wide caches. No-op on
 * targets without an actual (single-threaded wasm, dispatcher-confined
 * iOS callers).
 */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
internal expect annotation class JvmSynchronized()
