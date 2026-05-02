// kotlin-harfbuzz JNI bridge — public header.
//
// Exposes the HarfBuzz shaping API to Kotlin via a stable C ABI. All entry
// points use JNI's `Java_<class>_<method>` naming for `HarfbuzzNative`.
//
// Pointer-style handles cross the boundary as `jlong`. Memory ownership:
// every `*Create` returns a new handle the Kotlin side owns; every `*Destroy`
// releases it. HarfBuzz refcounts internally so destroying a face that still
// has a font referencing it is safe.

#ifndef KOTLIN_HARFBUZZ_JNI_H
#define KOTLIN_HARFBUZZ_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KH_FN(name) Java_com_mohamedrejeb_harfbuzz_core_HarfbuzzNative_##name

JNIEXPORT jstring JNICALL KH_FN(nativeVersion)(JNIEnv* env, jclass);

// ───── Blob ──────────────────────────────────────────────────────────────
JNIEXPORT jlong  JNICALL KH_FN(blobCreate)(JNIEnv* env, jclass, jbyteArray bytes);
// mmap-backed blob: opens `path` and creates a HarfBuzz blob over the file's
// kernel-shared pages — no malloc, no memcpy, pages fault in on demand as
// HarfBuzz reads them. Returns 0 on open/mmap failure or empty file.
JNIEXPORT jlong  JNICALL KH_FN(blobCreateFromPath)(JNIEnv* env, jclass, jstring path);
JNIEXPORT void   JNICALL KH_FN(blobDestroy)(JNIEnv* env, jclass, jlong blobPtr);
JNIEXPORT jint   JNICALL KH_FN(blobLength)(JNIEnv* env, jclass, jlong blobPtr);

// ───── Face ──────────────────────────────────────────────────────────────
// Returns 0 if face_index is out of range or the bytes don't parse.
JNIEXPORT jlong  JNICALL KH_FN(faceCreate)(JNIEnv* env, jclass, jlong blobPtr, jint faceIndex);
JNIEXPORT void   JNICALL KH_FN(faceDestroy)(JNIEnv* env, jclass, jlong facePtr);
JNIEXPORT jint   JNICALL KH_FN(faceUpem)(JNIEnv* env, jclass, jlong facePtr);
JNIEXPORT jint   JNICALL KH_FN(faceCountInBlob)(JNIEnv* env, jclass, jlong blobPtr);
// Writes feature tags as packed UInts into outArr; returns number of tags found
// (caller calls again with a larger array if return > capacity).
JNIEXPORT jint   JNICALL KH_FN(faceFeatureTags)(JNIEnv* env, jclass, jlong facePtr, jintArray outArr);

// Variable-fonts axis enumeration. Writes 4 parallel arrays per axis:
//  outTags[i]      — packed UInt tag (`wght`, `wdth`, …)
//  outDefaults[i]  — default value
//  outMins[i]      — min value
//  outMaxs[i]      — max value
//  outFlags[i]     — fvar axis flags (bit 0 = HIDDEN_AXIS).
// Each `out*` array is at most `capacity` long. Returns the total axis count
// (caller can re-call with larger arrays if return > capacity). Pass null for
// every out array to query the count without writing anything.
JNIEXPORT jint   JNICALL KH_FN(faceVariationAxisInfos)(
    JNIEnv* env, jclass, jlong facePtr,
    jintArray outTags, jfloatArray outDefaults,
    jfloatArray outMins, jfloatArray outMaxs,
    jintArray outFlags, jint capacity);

// ───── Font ──────────────────────────────────────────────────────────────
JNIEXPORT jlong  JNICALL KH_FN(fontCreate)(JNIEnv* env, jclass, jlong facePtr, jfloat pointSize);
// Like fontCreate but applies an array of variation axis values:
//  varTags[i]   — packed UInt tag
//  varValues[i] — value for that axis
// Pass null arrays to mean "no variations" (equivalent to fontCreate).
JNIEXPORT jlong  JNICALL KH_FN(fontCreateWithVariations)(
    JNIEnv* env, jclass, jlong facePtr, jfloat pointSize,
    jintArray varTags, jfloatArray varValues);
JNIEXPORT void   JNICALL KH_FN(fontDestroy)(JNIEnv* env, jclass, jlong fontPtr);
JNIEXPORT void   JNICALL KH_FN(fontSetPointSize)(JNIEnv* env, jclass, jlong fontPtr, jfloat pointSize);
JNIEXPORT jint   JNICALL KH_FN(fontGlyphForCodepoint)(JNIEnv* env, jclass, jlong fontPtr, jint codepoint);
JNIEXPORT jfloat JNICALL KH_FN(fontGlyphHAdvance)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId);
// outArr[0..3] = xBearing, yBearing, width, height. Returns 1 on success, 0 if extents unknown.
JNIEXPORT jint   JNICALL KH_FN(fontGlyphExtents)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId, jfloatArray outArr);
// Batched glyphExtents — writes 4 floats per glyph (xBearing, yBearing, width,
// height) into outFlat, in the same order as glyphIds. Glyphs whose extents
// HarfBuzz couldn't resolve get NaN in the xBearing slot as a sentinel; the
// other three slots for that glyph are 0 (callers should check NaN first).
// outFlat must have capacity >= count * 4. Saves ~count JNI border-crossings
// per paragraph compared with calling fontGlyphExtents in a loop.
JNIEXPORT void   JNICALL KH_FN(fontGlyphExtentsBatch)(
    JNIEnv* env, jclass, jlong fontPtr,
    jintArray glyphIds, jfloatArray outFlat, jint count);
// Reads a CSS-style numeric style value from the font (OS/2 + STAT). `tag` is an
// HB_TAG four-cc packed as int — 'w','g','h','t' for weight, 'i','t','a','l' for
// italic, 'w','d','t','h' for width. Returns the registered default (e.g. 400 for
// weight, 0 for italic, 1.0 for width) when the font doesn't expose it.
JNIEXPORT jfloat JNICALL KH_FN(fontStyleValue)(JNIEnv* env, jclass, jlong fontPtr, jint tag);
// Writes path opcodes (M=1, L=2, Q=3, C=4, Z=5) into outOpCodes and coordinates
// into outCoords. Returns number of opcodes written; if capacity insufficient,
// returns -required so caller can grow and retry.
JNIEXPORT jint   JNICALL KH_FN(fontDrawGlyph)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId,
                                              jbyteArray outOpCodes, jfloatArray outCoords, jint capacity);

// ───── Color paint (COLR v1) ──────────────────────────────────────────────
// 1 if the face has a COLR v1 paint table, 0 otherwise.
JNIEXPORT jint   JNICALL KH_FN(faceHasColorPaint)(JNIEnv* env, jclass, jlong facePtr);
// Walks the paint tree of `glyphId` and returns a serialised byte buffer
// (native byte order) describing every paint operation. Returns null when
// the face has no paint table or the glyph yields no operations. The
// Kotlin side decodes this into HbPaintSink callbacks.
JNIEXPORT jbyteArray JNICALL KH_FN(fontPaintGlyph)(JNIEnv* env, jclass, jlong fontPtr,
                                                    jint glyphId, jint foreground, jint paletteIndex);

// ───── SVG-in-OT ──────────────────────────────────────────────────────────
// 1 if the face has an `SVG ` (SVG-in-OpenType) table.
JNIEXPORT jint   JNICALL KH_FN(faceHasColorSvg)(JNIEnv* env, jclass, jlong facePtr);
// SVG document bytes for `glyphId`, or null when the face has no SVG
// table or the glyph isn't covered by any document range. Bytes are
// already decompressed if the source was SVGZ.
JNIEXPORT jbyteArray JNICALL KH_FN(fontGlyphSvg)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId);
// Same as fontGlyphSvg but extracts the per-glyph `<g id="glyphN">`
// subtree in C++ before crossing JNI. For multi-glyph SVG documents
// (Noto Color Emoji ships single 3 MB documents covering thousands
// of glyphs each) this avoids both the cost of copying the whole
// document into a Java byte[] and the cost of re-scanning it in
// Kotlin via SvgGlyphSlicer (~95 ms per emoji-bearing paragraph on
// desktop). Single-glyph documents (Aref Ruqaa Ink) and slicing
// failures fall through to returning the whole document so callers
// can still render.
JNIEXPORT jbyteArray JNICALL KH_FN(fontGlyphSvgSliced)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId);

// ───── Buffer ────────────────────────────────────────────────────────────
JNIEXPORT jlong  JNICALL KH_FN(bufferCreate)(JNIEnv* env, jclass);
JNIEXPORT void   JNICALL KH_FN(bufferDestroy)(JNIEnv* env, jclass, jlong bufferPtr);
JNIEXPORT void   JNICALL KH_FN(bufferReset)(JNIEnv* env, jclass, jlong bufferPtr);
JNIEXPORT void   JNICALL KH_FN(bufferSetText)(JNIEnv* env, jclass, jlong bufferPtr, jstring text);
JNIEXPORT void   JNICALL KH_FN(bufferSetDirection)(JNIEnv* env, jclass, jlong bufferPtr, jint direction);
JNIEXPORT void   JNICALL KH_FN(bufferSetScript)(JNIEnv* env, jclass, jlong bufferPtr, jint scriptTag);
JNIEXPORT void   JNICALL KH_FN(bufferSetLanguage)(JNIEnv* env, jclass, jlong bufferPtr, jstring bcp47);
JNIEXPORT jint   JNICALL KH_FN(bufferGlyphCount)(JNIEnv* env, jclass, jlong bufferPtr);
JNIEXPORT jint   JNICALL KH_FN(bufferReadGlyphInfo)(JNIEnv* env, jclass, jlong bufferPtr, jintArray outArr, jint capacity);
JNIEXPORT jint   JNICALL KH_FN(bufferReadGlyphPositions)(JNIEnv* env, jclass, jlong bufferPtr, jfloatArray outArr, jint capacity);

// ───── Shape ─────────────────────────────────────────────────────────────
// Performs hb_shape on the buffer with the given font and packed feature list.
// `featuresPacked` is laid out [tag, value, start, end] per feature.
JNIEXPORT void   JNICALL KH_FN(shape)(JNIEnv* env, jclass, jlong fontPtr, jlong bufferPtr,
                                      jintArray featuresPacked, jint featureCount);

#undef KH_FN

#ifdef __cplusplus
}
#endif

#endif  // KOTLIN_HARFBUZZ_JNI_H
