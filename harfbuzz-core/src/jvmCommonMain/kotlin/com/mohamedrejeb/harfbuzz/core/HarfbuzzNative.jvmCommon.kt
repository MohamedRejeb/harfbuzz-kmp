package com.mohamedrejeb.harfbuzz.core

/**
 * Entry point for the JNI bridge - loads `libharfbuzz_jni` and exposes the C
 * ABI declared in `native/jni/harfbuzz_jni.h`. Internal: consumers go through
 * [HbFace] / [HbFont] / [HbBuffer], not this object.
 *
 * Threading: the underlying HarfBuzz objects are not internally locked
 * (per design §12), so the JNI layer is also unsynchronized.
 */
internal object HarfbuzzNative {

    @Volatile
    private var loaded: Boolean = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                NativeLibraryLoader.load("harfbuzz_jni")
                loaded = true
            } catch (cause: UnsatisfiedLinkError) {
                throw HbNativeLoadException(
                    "Failed to load native HarfBuzz library 'harfbuzz_jni'.",
                    cause,
                )
            } catch (cause: Throwable) {
                throw HbNativeLoadException(
                    "Failed to load native HarfBuzz library 'harfbuzz_jni': ${cause.message}",
                    cause,
                )
            }
        }
    }

    @JvmStatic external fun nativeVersion(): String

    // Blob
    @JvmStatic external fun blobCreate(bytes: ByteArray): Long
    @JvmStatic external fun blobCreateFromPath(path: String): Long
    @JvmStatic external fun blobDestroy(blobPtr: Long)
    @JvmStatic external fun blobLength(blobPtr: Long): Int

    // Face
    @JvmStatic external fun faceCreate(blobPtr: Long, faceIndex: Int): Long
    @JvmStatic external fun faceDestroy(facePtr: Long)
    @JvmStatic external fun faceUpem(facePtr: Long): Int
    @JvmStatic external fun faceCountInBlob(blobPtr: Long): Int
    @JvmStatic external fun faceFeatureTags(facePtr: Long, outArr: IntArray): Int
    @JvmStatic external fun faceVariationAxisInfos(
        facePtr: Long,
        outTags: IntArray?,
        outDefaults: FloatArray?,
        outMins: FloatArray?,
        outMaxs: FloatArray?,
        outFlags: IntArray?,
        capacity: Int,
    ): Int

    // Font
    @JvmStatic external fun fontCreate(facePtr: Long, pointSize: Float): Long
    @JvmStatic external fun fontCreateWithVariations(
        facePtr: Long,
        pointSize: Float,
        varTags: IntArray?,
        varValues: FloatArray?,
    ): Long
    @JvmStatic external fun fontDestroy(fontPtr: Long)
    @JvmStatic external fun fontSetPointSize(fontPtr: Long, pointSize: Float)
    @JvmStatic external fun fontGlyphForCodepoint(fontPtr: Long, codepoint: Int): Int
    @JvmStatic external fun fontGlyphHAdvance(fontPtr: Long, glyphId: Int): Float
    @JvmStatic external fun fontGlyphExtents(fontPtr: Long, glyphId: Int, outArr: FloatArray): Int
    @JvmStatic external fun fontGlyphExtentsBatch(
        fontPtr: Long,
        glyphIds: IntArray,
        outFlat: FloatArray,
        count: Int,
    )
    @JvmStatic external fun fontStyleValue(fontPtr: Long, tag: Int): Float
    @JvmStatic external fun fontHExtents(fontPtr: Long, outArr: FloatArray): Int
    @JvmStatic external fun faceHasColorLayers(facePtr: Long): Int
    @JvmStatic external fun fontGlyphColorLayers(
        fontPtr: Long,
        glyphId: Int,
        outGlyphIds: IntArray?,
        outColors: IntArray?,
        capacity: Int,
    ): Int
    @JvmStatic external fun fontDrawGlyph(
        fontPtr: Long,
        glyphId: Int,
        outOpCodes: ByteArray?,
        outCoords: FloatArray?,
        capacity: Int,
    ): Int

    // Color paint (COLR v1)
    @JvmStatic external fun faceHasColorPaint(facePtr: Long): Int
    @JvmStatic external fun faceHasColorPng(facePtr: Long): Int
    @JvmStatic external fun fontPaintGlyph(
        fontPtr: Long,
        glyphId: Int,
        foreground: Int,
        paletteIndex: Int,
    ): ByteArray?

    // SVG-in-OT
    @JvmStatic external fun faceHasColorSvg(facePtr: Long): Int
    @JvmStatic external fun fontGlyphSvg(fontPtr: Long, glyphId: Int): ByteArray?
    @JvmStatic external fun fontGlyphSvgSliced(fontPtr: Long, glyphId: Int): ByteArray?

    // Buffer
    @JvmStatic external fun bufferCreate(): Long
    @JvmStatic external fun bufferDestroy(bufferPtr: Long)
    @JvmStatic external fun bufferReset(bufferPtr: Long)
    @JvmStatic external fun bufferSetText(bufferPtr: Long, text: String)
    @JvmStatic external fun bufferSetTextWithContext(
        bufferPtr: Long,
        text: String,
        itemOffset: Int,
        itemLength: Int,
    )
    @JvmStatic external fun bufferSetDirection(bufferPtr: Long, direction: Int)
    @JvmStatic external fun bufferSetScript(bufferPtr: Long, scriptTag: Int)
    @JvmStatic external fun bufferSetLanguage(bufferPtr: Long, bcp47: String?)
    @JvmStatic external fun bufferGlyphCount(bufferPtr: Long): Int
    @JvmStatic external fun bufferReadGlyphInfo(bufferPtr: Long, outArr: IntArray, capacity: Int): Int
    @JvmStatic external fun bufferReadGlyphPositions(bufferPtr: Long, outArr: FloatArray, capacity: Int): Int

    // Shape
    @JvmStatic external fun shape(fontPtr: Long, bufferPtr: Long, featuresPacked: IntArray?, featureCount: Int)
}

/** HarfBuzz `hb_direction_t` mirror - kept in sync with HarfBuzz's enum values. */
internal object HbDirectionInt {
    const val INVALID = 0
    const val LTR = 4
    const val RTL = 5
    const val TTB = 6
    const val BTT = 7
}

internal fun HbDirection.toNative(): Int = when (this) {
    HbDirection.AUTO -> HbDirectionInt.INVALID
    HbDirection.LTR -> HbDirectionInt.LTR
    HbDirection.RTL -> HbDirectionInt.RTL
    HbDirection.TTB -> HbDirectionInt.TTB
    HbDirection.BTT -> HbDirectionInt.BTT
}
