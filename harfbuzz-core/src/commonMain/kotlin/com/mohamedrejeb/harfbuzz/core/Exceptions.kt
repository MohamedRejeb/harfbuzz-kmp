package com.mohamedrejeb.harfbuzz.core

/** Base class for HarfBuzz-related runtime errors. */
public open class HbException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Thrown when font bytes cannot be parsed into a face. */
public open class HbFaceLoadException(
    message: String,
    cause: Throwable? = null,
) : HbException(message, cause)

/** Specifically: the bytes don't look like a font HarfBuzz can read. */
public class HbInvalidFontDataException(
    cause: Throwable? = null,
) : HbFaceLoadException("Bytes are not a valid font", cause)

/**
 * Specifically: a `.ttc` collection was opened with a face index outside the
 * collection's range.
 */
public class HbFaceIndexOutOfRangeException(
    public val requested: Int,
    public val available: Int,
) : HbFaceLoadException("Face index $requested out of range (available: $available)")

/**
 * Thrown when the native HarfBuzz library (the JNI shared library on
 * Android / Desktop JVM, or the cinterop framework on iOS) cannot be loaded.
 * Almost always indicates a packaging or platform mismatch.
 */
public class HbNativeLoadException(
    message: String,
    cause: Throwable? = null,
) : HbException(message, cause)
