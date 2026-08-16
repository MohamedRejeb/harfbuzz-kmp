// kotlin-harfbuzz JNI bridge — implementation.
//
// Conventions:
//   - All HarfBuzz handles cross JNI as `jlong`. 0 == null.
//   - Font / buffer point sizes use HarfBuzz's `hb_font_set_scale` with units
//     proportional to the face's UPEM. We treat input `pointSize` as a scale
//     factor on `upem`; Kotlin callers multiply UPEM-relative outputs back to
//     pixel space in the Compose layer.
//   - We never throw C++ exceptions across JNI; instead we return failure
//     sentinels (0 handle, -1 size) and let Kotlin surface a clean exception.
//   - Strings come in as UTF-16 and we feed them to HarfBuzz via
//     `hb_buffer_add_utf16` which is exactly what HarfBuzz expects from Java.

#include "harfbuzz_jni.h"

#include <hb.h>
#include <hb-ot.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <utility>
#include <vector>

namespace {

inline hb_blob_t*    asBlob(jlong p)   { return reinterpret_cast<hb_blob_t*>(p); }
inline hb_face_t*    asFace(jlong p)   { return reinterpret_cast<hb_face_t*>(p); }
inline hb_font_t*    asFont(jlong p)   { return reinterpret_cast<hb_font_t*>(p); }
inline hb_buffer_t*  asBuffer(jlong p) { return reinterpret_cast<hb_buffer_t*>(p); }
inline jlong         toJlong(void* p)  { return reinterpret_cast<jlong>(p); }

// HarfBuzz scale convention: 26.6 fixed-point (1 pixel = 64 scale units).
// `hb_font_set_scale(font, pointSize * 64, pointSize * 64)` makes
// hb_position_t return values in 1/64 pixels, which we divide by 64 on the
// way out. This is the same convention Skia / FreeType / Cairo use, so any
// downstream consumer can interpret our values without surprises.
constexpr int kSubpixelShift = 6;
constexpr float kSubpixelScale = 1.0f / 64.0f;

inline float toPixels(int hbValue) {
    return static_cast<float>(hbValue) * kSubpixelScale;
}

inline void setFontScaleFromPointSize(hb_font_t* font, float pointSize) {
    int s = static_cast<int>(pointSize * 64.0f + 0.5f);
    if (s < 1) s = 1;
    hb_font_set_scale(font, s, s);
}

constexpr int8_t OP_MOVE  = 1;
constexpr int8_t OP_LINE  = 2;
constexpr int8_t OP_QUAD  = 3;
constexpr int8_t OP_CUBIC = 4;
constexpr int8_t OP_CLOSE = 5;

// Per-glyph draw context. Two-pass design: first pass measures (capacity == 0),
// second pass writes into pre-pinned arrays. We pin once per call and write
// directly via raw pointers to avoid per-callback JNI roundtrips.
struct DrawCtx {
    int8_t* opCodes;
    float*  coords;
    int     capacity;
    int     opIndex;
    int     coordIndex;
    bool    overflowed;
};

inline void emitOp(DrawCtx* ctx, int8_t op, int coordCount, const float* values) {
    if (ctx->opIndex >= ctx->capacity) {
        ctx->overflowed = true;
        ctx->opIndex++;        // count required
        ctx->coordIndex += coordCount;
        return;
    }
    ctx->opCodes[ctx->opIndex++] = op;
    for (int i = 0; i < coordCount; ++i) {
        ctx->coords[ctx->coordIndex++] = values[i];
    }
}

// hb_font_draw_glyph emits float coordinates in the font's *scaled* units —
// with our scale = pointSize × 64, that's 1/64 pixels (same 26.6 fixed-point
// convention as hb_position_t). Multiply by kSubpixelScale (= 1/64) here so
// the Kotlin side receives raw pixel-space floats consistent with what
// shape positions, glyph advances, and glyph extents already deliver.

void drawMoveTo(hb_draw_funcs_t*, void* userData, hb_draw_state_t*, float x, float y, void*) {
    float v[2] = { x * kSubpixelScale, y * kSubpixelScale };
    emitOp(static_cast<DrawCtx*>(userData), OP_MOVE, 2, v);
}
void drawLineTo(hb_draw_funcs_t*, void* userData, hb_draw_state_t*, float x, float y, void*) {
    float v[2] = { x * kSubpixelScale, y * kSubpixelScale };
    emitOp(static_cast<DrawCtx*>(userData), OP_LINE, 2, v);
}
void drawQuadTo(hb_draw_funcs_t*, void* userData, hb_draw_state_t*, float c1x, float c1y, float x, float y, void*) {
    float v[4] = {
        c1x * kSubpixelScale, c1y * kSubpixelScale,
        x   * kSubpixelScale, y   * kSubpixelScale,
    };
    emitOp(static_cast<DrawCtx*>(userData), OP_QUAD, 4, v);
}
void drawCubicTo(hb_draw_funcs_t*, void* userData, hb_draw_state_t*,
                 float c1x, float c1y, float c2x, float c2y, float x, float y, void*) {
    float v[6] = {
        c1x * kSubpixelScale, c1y * kSubpixelScale,
        c2x * kSubpixelScale, c2y * kSubpixelScale,
        x   * kSubpixelScale, y   * kSubpixelScale,
    };
    emitOp(static_cast<DrawCtx*>(userData), OP_CUBIC, 6, v);
}
void drawClose(hb_draw_funcs_t*, void* userData, hb_draw_state_t*, void*) {
    emitOp(static_cast<DrawCtx*>(userData), OP_CLOSE, 0, nullptr);
}

hb_draw_funcs_t* sharedDrawFuncs() {
    static hb_draw_funcs_t* funcs = []() {
        hb_draw_funcs_t* f = hb_draw_funcs_create();
        hb_draw_funcs_set_move_to_func(f, drawMoveTo, nullptr, nullptr);
        hb_draw_funcs_set_line_to_func(f, drawLineTo, nullptr, nullptr);
        hb_draw_funcs_set_quadratic_to_func(f, drawQuadTo, nullptr, nullptr);
        hb_draw_funcs_set_cubic_to_func(f, drawCubicTo, nullptr, nullptr);
        hb_draw_funcs_set_close_path_func(f, drawClose, nullptr, nullptr);
        hb_draw_funcs_make_immutable(f);
        return f;
    }();
    return funcs;
}

}  // namespace

#define KH_FN(name) Java_com_mohamedrejeb_harfbuzz_core_HarfbuzzNative_##name

extern "C" {

JNIEXPORT jstring JNICALL KH_FN(nativeVersion)(JNIEnv* env, jclass) {
    return env->NewStringUTF(hb_version_string());
}

// ───── Blob ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL KH_FN(blobCreate)(JNIEnv* env, jclass, jbyteArray bytes) {
    if (bytes == nullptr) return 0;
    jsize size = env->GetArrayLength(bytes);
    if (size <= 0) return 0;

    // We malloc + memcpy so HarfBuzz owns the buffer and the JNI critical
    // section is short. The cost is a one-time copy at face-load time which
    // is negligible relative to font-parsing cost.
    void* copy = std::malloc(static_cast<size_t>(size));
    if (copy == nullptr) return 0;
    env->GetByteArrayRegion(bytes, 0, size, static_cast<jbyte*>(copy));

    hb_blob_t* blob = hb_blob_create(
        static_cast<const char*>(copy),
        static_cast<unsigned int>(size),
        HB_MEMORY_MODE_WRITABLE,
        copy,
        std::free
    );
    if (hb_blob_get_length(blob) == 0) {
        hb_blob_destroy(blob);
        return 0;
    }
    return toJlong(blob);
}

JNIEXPORT jlong JNICALL KH_FN(blobCreateFromPath)(JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return 0;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return 0;
    // hb_blob_create_from_file_or_fail mmaps the file (when supported by
    // the platform — true on Linux/Android/macOS/iOS) and creates a blob
    // backed by those kernel-shared pages. Returns nullptr on open or
    // mmap failure; the older _from_file variant returns an empty blob
    // on failure which is harder to distinguish from a 0-byte file.
    hb_blob_t* blob = hb_blob_create_from_file_or_fail(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    if (blob == nullptr) return 0;
    if (hb_blob_get_length(blob) == 0) {
        hb_blob_destroy(blob);
        return 0;
    }
    return toJlong(blob);
}

JNIEXPORT void JNICALL KH_FN(blobDestroy)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return;
    hb_blob_destroy(asBlob(p));
}

JNIEXPORT jint JNICALL KH_FN(blobLength)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return 0;
    return static_cast<jint>(hb_blob_get_length(asBlob(p)));
}

// ───── Face ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL KH_FN(faceCreate)(JNIEnv*, jclass, jlong blobPtr, jint faceIndex) {
    if (blobPtr == 0) return 0;
    hb_blob_t* blob = asBlob(blobPtr);
    unsigned int total = hb_face_count(blob);
    if (faceIndex < 0 || static_cast<unsigned int>(faceIndex) >= total) return 0;
    hb_face_t* face = hb_face_create(blob, static_cast<unsigned int>(faceIndex));
    return toJlong(face);
}

JNIEXPORT void JNICALL KH_FN(faceDestroy)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return;
    hb_face_destroy(asFace(p));
}

JNIEXPORT jint JNICALL KH_FN(faceUpem)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return 0;
    return static_cast<jint>(hb_face_get_upem(asFace(p)));
}

JNIEXPORT jint JNICALL KH_FN(faceCountInBlob)(JNIEnv*, jclass, jlong blobPtr) {
    if (blobPtr == 0) return 0;
    return static_cast<jint>(hb_face_count(asBlob(blobPtr)));
}

JNIEXPORT jint JNICALL KH_FN(faceFeatureTags)(JNIEnv* env, jclass, jlong facePtr, jintArray outArr) {
    if (facePtr == 0) return 0;
    hb_face_t* face = asFace(facePtr);

    // Collect tags from GSUB and GPOS, then dedupe in-place. HarfBuzz exposes
    // these via `hb_ot_layout_table_get_feature_tags`.
    constexpr unsigned int CAP = 128;
    hb_tag_t scratch[CAP];

    unsigned int total = 0;
    {
        unsigned int cnt = CAP;
        hb_ot_layout_table_get_feature_tags(face, HB_OT_TAG_GSUB, 0, &cnt, scratch);
        total = cnt;
    }
    {
        unsigned int rem = (CAP > total) ? (CAP - total) : 0;
        unsigned int cnt = rem;
        hb_ot_layout_table_get_feature_tags(face, HB_OT_TAG_GPOS, 0, &cnt, scratch + total);
        total += cnt;
    }
    // Dedupe (small array, O(n^2) is fine).
    unsigned int unique = 0;
    for (unsigned int i = 0; i < total; ++i) {
        bool seen = false;
        for (unsigned int j = 0; j < unique; ++j) {
            if (scratch[j] == scratch[i]) { seen = true; break; }
        }
        if (!seen) scratch[unique++] = scratch[i];
    }

    if (outArr != nullptr) {
        jsize cap = env->GetArrayLength(outArr);
        unsigned int writeN = unique < static_cast<unsigned int>(cap) ? unique : static_cast<unsigned int>(cap);
        env->SetIntArrayRegion(outArr, 0, static_cast<jsize>(writeN),
                               reinterpret_cast<const jint*>(scratch));
    }
    return static_cast<jint>(unique);
}

// ───── Font ──────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL KH_FN(fontCreate)(JNIEnv*, jclass, jlong facePtr, jfloat pointSize) {
    if (facePtr == 0) return 0;
    hb_font_t* font = hb_font_create(asFace(facePtr));
    setFontScaleFromPointSize(font, pointSize);
    return toJlong(font);
}

JNIEXPORT jlong JNICALL KH_FN(fontCreateWithVariations)(
    JNIEnv* env, jclass, jlong facePtr, jfloat pointSize,
    jintArray varTags, jfloatArray varValues) {
    if (facePtr == 0) return 0;
    hb_font_t* font = hb_font_create(asFace(facePtr));
    setFontScaleFromPointSize(font, pointSize);
    if (varTags != nullptr && varValues != nullptr) {
        const jsize n = env->GetArrayLength(varTags);
        const jsize nv = env->GetArrayLength(varValues);
        const jsize count = (n < nv) ? n : nv;
        if (count > 0) {
            // Build hb_variation_t[]: parallel arrays of tag (uint32) and value (f32).
            std::vector<hb_variation_t> vars(static_cast<size_t>(count));
            jint*   tagsPtr   = env->GetIntArrayElements(varTags, nullptr);
            jfloat* valuesPtr = env->GetFloatArrayElements(varValues, nullptr);
            for (jsize i = 0; i < count; ++i) {
                vars[i].tag   = static_cast<hb_tag_t>(static_cast<uint32_t>(tagsPtr[i]));
                vars[i].value = static_cast<float>(valuesPtr[i]);
            }
            env->ReleaseIntArrayElements(varTags, tagsPtr, JNI_ABORT);
            env->ReleaseFloatArrayElements(varValues, valuesPtr, JNI_ABORT);
            hb_font_set_variations(font, vars.data(), static_cast<unsigned int>(count));
        }
    }
    return toJlong(font);
}

JNIEXPORT jint JNICALL KH_FN(faceVariationAxisInfos)(
    JNIEnv* env, jclass, jlong facePtr,
    jintArray outTags, jfloatArray outDefaults,
    jfloatArray outMins, jfloatArray outMaxs,
    jintArray outFlags, jint capacity) {
    if (facePtr == 0) return 0;
    hb_face_t* face = asFace(facePtr);

    // Probe count first; if no axes, the face is non-variable.
    const unsigned int total = hb_ot_var_get_axis_count(face);
    if (total == 0) return 0;

    if (capacity <= 0 || outTags == nullptr || outDefaults == nullptr ||
        outMins == nullptr || outMaxs == nullptr || outFlags == nullptr) {
        return static_cast<jint>(total);
    }

    const unsigned int request = (capacity < static_cast<jint>(total))
        ? static_cast<unsigned int>(capacity) : total;
    std::vector<hb_ot_var_axis_info_t> axes(request);
    unsigned int actual = request;
    hb_ot_var_get_axis_infos(face, 0, &actual, axes.data());

    // Pack into the parallel out arrays.
    std::vector<jint>   tags(actual);
    std::vector<jfloat> defaults(actual);
    std::vector<jfloat> mins(actual);
    std::vector<jfloat> maxs(actual);
    std::vector<jint>   flags(actual);
    for (unsigned int i = 0; i < actual; ++i) {
        tags[i]     = static_cast<jint>(axes[i].tag);
        defaults[i] = static_cast<jfloat>(axes[i].default_value);
        mins[i]     = static_cast<jfloat>(axes[i].min_value);
        maxs[i]     = static_cast<jfloat>(axes[i].max_value);
        flags[i]    = static_cast<jint>(axes[i].flags);
    }
    env->SetIntArrayRegion(outTags, 0, static_cast<jsize>(actual), tags.data());
    env->SetFloatArrayRegion(outDefaults, 0, static_cast<jsize>(actual), defaults.data());
    env->SetFloatArrayRegion(outMins, 0, static_cast<jsize>(actual), mins.data());
    env->SetFloatArrayRegion(outMaxs, 0, static_cast<jsize>(actual), maxs.data());
    env->SetIntArrayRegion(outFlags, 0, static_cast<jsize>(actual), flags.data());
    return static_cast<jint>(total);
}

JNIEXPORT void JNICALL KH_FN(fontDestroy)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return;
    hb_font_destroy(asFont(p));
}

JNIEXPORT void JNICALL KH_FN(fontSetPointSize)(JNIEnv*, jclass, jlong fontPtr, jfloat pointSize) {
    if (fontPtr == 0) return;
    setFontScaleFromPointSize(asFont(fontPtr), pointSize);
}

JNIEXPORT jint JNICALL KH_FN(fontGlyphForCodepoint)(JNIEnv*, jclass, jlong fontPtr, jint cp) {
    if (fontPtr == 0) return 0;
    hb_codepoint_t glyph = 0;
    hb_font_get_glyph(asFont(fontPtr), static_cast<hb_codepoint_t>(cp), 0, &glyph);
    return static_cast<jint>(glyph);
}

JNIEXPORT jfloat JNICALL KH_FN(fontGlyphHAdvance)(JNIEnv*, jclass, jlong fontPtr, jint glyphId) {
    if (fontPtr == 0) return 0.0f;
    hb_position_t adv = hb_font_get_glyph_h_advance(asFont(fontPtr), static_cast<hb_codepoint_t>(glyphId));
    return toPixels(adv);
}

JNIEXPORT jint JNICALL KH_FN(fontGlyphExtents)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId, jfloatArray outArr) {
    if (fontPtr == 0 || outArr == nullptr) return 0;
    hb_font_t* font = asFont(fontPtr);
    hb_glyph_extents_t ext;
    if (!hb_font_get_glyph_extents(font, static_cast<hb_codepoint_t>(glyphId), &ext)) {
        return 0;
    }
    jfloat values[4] = {
        toPixels(ext.x_bearing),
        toPixels(ext.y_bearing),
        toPixels(ext.width),
        toPixels(ext.height),
    };
    env->SetFloatArrayRegion(outArr, 0, 4, values);
    return 1;
}

JNIEXPORT void JNICALL KH_FN(fontGlyphExtentsBatch)(
        JNIEnv* env, jclass,
        jlong fontPtr,
        jintArray glyphIds,
        jfloatArray outFlat,
        jint count) {
    if (fontPtr == 0 || glyphIds == nullptr || outFlat == nullptr || count <= 0) return;
    hb_font_t* font = asFont(fontPtr);

    // Pull all glyph ids across the JNI border in one call. Without this,
    // a 200-glyph paragraph would do 200 individual fontGlyphExtents JNI
    // hops just to populate run ink boxes during shape — the batch turns
    // it into one inbound + one outbound array region copy.
    std::vector<jint> gids(static_cast<size_t>(count));
    env->GetIntArrayRegion(glyphIds, 0, count, gids.data());

    std::vector<jfloat> out(static_cast<size_t>(count) * 4);
    constexpr jfloat kNoExtents = std::numeric_limits<jfloat>::quiet_NaN();
    for (jint i = 0; i < count; ++i) {
        hb_glyph_extents_t ext;
        const size_t base = static_cast<size_t>(i) * 4;
        if (hb_font_get_glyph_extents(font, static_cast<hb_codepoint_t>(gids[i]), &ext)) {
            out[base + 0] = toPixels(ext.x_bearing);
            out[base + 1] = toPixels(ext.y_bearing);
            out[base + 2] = toPixels(ext.width);
            out[base + 3] = toPixels(ext.height);
        } else {
            // NaN in the xBearing slot signals "no extents" to the Kotlin
            // caller. The other three slots stay 0 so misuse fails loud.
            out[base + 0] = kNoExtents;
            out[base + 1] = 0.0f;
            out[base + 2] = 0.0f;
            out[base + 3] = 0.0f;
        }
    }
    env->SetFloatArrayRegion(outFlat, 0, count * 4, out.data());
}

JNIEXPORT jfloat JNICALL KH_FN(fontStyleValue)(JNIEnv*, jclass, jlong fontPtr, jint tag) {
    if (fontPtr == 0) return 0.0f;
    return hb_style_get_value(asFont(fontPtr), static_cast<hb_style_tag_t>(tag));
}

JNIEXPORT jint JNICALL KH_FN(fontHExtents)(JNIEnv* env, jclass, jlong fontPtr, jfloatArray outArr) {
    if (fontPtr == 0 || outArr == nullptr) return 0;
    hb_font_t* font = asFont(fontPtr);
    hb_font_extents_t ext;
    if (!hb_font_get_h_extents(font, &ext)) return 0;
    jfloat values[3] = {
        toPixels(ext.ascender),
        toPixels(ext.descender),
        toPixels(ext.line_gap),
    };
    env->SetFloatArrayRegion(outArr, 0, 3, values);
    return 1;
}

// ───── Color layers (COLR v0) ─────────────────────────────────────────────

JNIEXPORT jint JNICALL KH_FN(faceHasColorLayers)(JNIEnv*, jclass, jlong facePtr) {
    if (facePtr == 0) return 0;
    return hb_ot_color_has_layers(asFace(facePtr)) ? 1 : 0;
}

/**
 * Read up to `capacity` color layers for `glyphId`. Fills `outGlyphIds[i]`
 * with the layer's base glyph and `outColors[i]` with the resolved palette
 * color in 0xAARRGGBB packing. A 0 in `outColors[i]` (fully transparent —
 * never used by real palettes) means "use the foreground color"
 * (HB_OT_COLOR_PALETTE_COLOR_FOREGROUND). Returns the total layer count
 * the face reports for this glyph (may be more than `capacity`); the
 * caller can grow and re-call.
 */
JNIEXPORT jint JNICALL KH_FN(fontGlyphColorLayers)(
    JNIEnv* env, jclass, jlong fontPtr, jint glyphId,
    jintArray outGlyphIds, jintArray outColors, jint capacity
) {
    if (fontPtr == 0) return 0;
    hb_font_t* font = asFont(fontPtr);
    hb_face_t* face = hb_font_get_face(font);

    // Probe layer count.
    unsigned int totalProbe = 0;
    hb_ot_color_glyph_get_layers(face, static_cast<hb_codepoint_t>(glyphId), 0, &totalProbe, nullptr);
    if (totalProbe == 0) return 0;

    if (outGlyphIds == nullptr || outColors == nullptr || capacity <= 0) {
        return static_cast<jint>(totalProbe);
    }

    unsigned int read = static_cast<unsigned int>(capacity);
    if (read > totalProbe) read = totalProbe;
    hb_ot_color_layer_t layers[64];
    if (read > 64) read = 64;
    hb_ot_color_glyph_get_layers(face, static_cast<hb_codepoint_t>(glyphId), 0, &read, layers);

    constexpr unsigned int FOREGROUND = 0xFFFFFFFFu;   // HB_OT_COLOR_PALETTE_COLOR_FOREGROUND
    constexpr unsigned int DEFAULT_PALETTE = 0;

    jint gids[64];
    jint argbs[64];
    for (unsigned int i = 0; i < read; ++i) {
        gids[i] = static_cast<jint>(layers[i].glyph);
        if (layers[i].color_index == FOREGROUND) {
            argbs[i] = 0;   // sentinel meaning "use caller foreground"
            continue;
        }
        hb_color_t c = 0;
        unsigned int cnt = 1;
        hb_ot_color_palette_get_colors(
            face, DEFAULT_PALETTE, layers[i].color_index, &cnt, &c
        );
        unsigned int r = hb_color_get_red(c);
        unsigned int g = hb_color_get_green(c);
        unsigned int b = hb_color_get_blue(c);
        unsigned int a = hb_color_get_alpha(c);
        unsigned int packed = (a << 24) | (r << 16) | (g << 8) | b;
        // Avoid colliding with the foreground sentinel (0). Fully transparent
        // colors aren't used by real palettes; bump alpha to 1 if we ever hit it.
        if (packed == 0) packed = 0x01000000u;
        argbs[i] = static_cast<jint>(packed);
    }
    env->SetIntArrayRegion(outGlyphIds, 0, static_cast<jsize>(read), gids);
    env->SetIntArrayRegion(outColors, 0, static_cast<jsize>(read), argbs);

    return static_cast<jint>(totalProbe);
}

// ───── Color paint (COLR v1) ─────────────────────────────────────────────

namespace {

// Wire format mirrored on the Kotlin side in HbStubs.jvmCommon.kt's parser.
// All multibyte values use the host's native byte order — the Kotlin parser
// uses ByteOrder.nativeOrder(). Both ends are guaranteed to be on the same
// machine (this is JNI), so endian conversion is unnecessary.
//
// PUSH_TRANSFORM    : op + 6 × f32 (xx, yx, xy, yy, dx, dy)
// POP_TRANSFORM     : op
// PUSH_CLIP_GLYPH   : op + i32 glyph
// PUSH_CLIP_RECT    : op + 4 × f32 (xMin, yMin, xMax, yMax)
// POP_CLIP          : op
// COLOR             : op + u8 isForeground + i32 argb
// LINEAR_GRADIENT   : op + 6 × f32 + u8 extend + i32 stopCount + stopCount × stop
// RADIAL_GRADIENT   : op + 6 × f32 + u8 extend + i32 stopCount + stopCount × stop
// SWEEP_GRADIENT    : op + 4 × f32 + u8 extend + i32 stopCount + stopCount × stop
// PUSH_GROUP        : op
// POP_GROUP         : op + i32 mode
// IMAGE             : op + i32 width + i32 height + i32 formatTag + f32 slant
//                     + u8 hasExtents (+ 4 × f32 xBearing, yBearing, w, h)
//                     + i32 byteLen + byteLen raw bytes (PNG stream)
//
// stop : f32 offset + u8 isForeground + i32 argb (9 bytes)

constexpr uint8_t PAINT_PUSH_TRANSFORM   = 1;
constexpr uint8_t PAINT_POP_TRANSFORM    = 2;
constexpr uint8_t PAINT_PUSH_CLIP_GLYPH  = 3;
constexpr uint8_t PAINT_PUSH_CLIP_RECT   = 4;
constexpr uint8_t PAINT_POP_CLIP         = 5;
constexpr uint8_t PAINT_COLOR            = 6;
constexpr uint8_t PAINT_LINEAR_GRADIENT  = 7;
constexpr uint8_t PAINT_RADIAL_GRADIENT  = 8;
constexpr uint8_t PAINT_SWEEP_GRADIENT   = 9;
constexpr uint8_t PAINT_PUSH_GROUP       = 10;
constexpr uint8_t PAINT_POP_GROUP        = 11;
constexpr uint8_t PAINT_IMAGE            = 12;

// Hard cap on one bitmap glyph's encoded bytes. CBDT/sbix per-glyph PNGs
// are a few KB; the cap only exists so an adversarial font can't balloon
// the paint buffer.
constexpr unsigned int MAX_IMAGE_BYTES = 8u * 1024u * 1024u;

// Hard cap on stops per gradient. COLR v1 in the wild rarely exceeds ~16;
// we cap at 256 to keep adversarial fonts from blowing up the buffer.
constexpr unsigned int MAX_GRADIENT_STOPS = 256;

struct PaintCtx {
    std::vector<uint8_t>* buf;
};

inline void writeU8(PaintCtx* ctx, uint8_t v) {
    ctx->buf->push_back(v);
}

inline void writeI32(PaintCtx* ctx, int32_t v) {
    size_t off = ctx->buf->size();
    ctx->buf->resize(off + 4);
    std::memcpy(ctx->buf->data() + off, &v, 4);
}

inline void writeF32(PaintCtx* ctx, float v) {
    size_t off = ctx->buf->size();
    ctx->buf->resize(off + 4);
    std::memcpy(ctx->buf->data() + off, &v, 4);
}

inline uint32_t hbColorToArgb(hb_color_t c) {
    uint32_t r = static_cast<uint32_t>(hb_color_get_red(c));
    uint32_t g = static_cast<uint32_t>(hb_color_get_green(c));
    uint32_t b = static_cast<uint32_t>(hb_color_get_blue(c));
    uint32_t a = static_cast<uint32_t>(hb_color_get_alpha(c));
    return (a << 24) | (r << 16) | (g << 8) | b;
}

inline hb_color_t argbToHbColor(uint32_t argb) {
    uint8_t a = static_cast<uint8_t>((argb >> 24) & 0xFF);
    uint8_t r = static_cast<uint8_t>((argb >> 16) & 0xFF);
    uint8_t g = static_cast<uint8_t>((argb >>  8) & 0xFF);
    uint8_t b = static_cast<uint8_t>( argb        & 0xFF);
    return HB_COLOR(b, g, r, a);
}

void writeGradientStops(PaintCtx* ctx, hb_color_line_t* line) {
    uint8_t extend = static_cast<uint8_t>(hb_color_line_get_extend(line));
    writeU8(ctx, extend);

    // Probe with a zero-size buffer; the *return value* is the total count
    // available, while *count is the number actually written into the
    // (here, null) buffer. Reading *count after the probe gives 0, which
    // is why an earlier draft saw stops=0 even on real gradients.
    unsigned int probeCount = 0;
    unsigned int totalCount = hb_color_line_get_color_stops(line, 0, &probeCount, nullptr);
    if (totalCount > MAX_GRADIENT_STOPS) totalCount = MAX_GRADIENT_STOPS;
    writeI32(ctx, static_cast<int32_t>(totalCount));

    if (totalCount == 0) return;

    std::vector<hb_color_stop_t> stops(totalCount);
    unsigned int got = totalCount;
    hb_color_line_get_color_stops(line, 0, &got, stops.data());
    if (got > totalCount) got = totalCount;

    for (unsigned int i = 0; i < got; ++i) {
        writeF32(ctx, stops[i].offset);
        writeU8(ctx, stops[i].is_foreground ? 1 : 0);
        writeI32(ctx, static_cast<int32_t>(hbColorToArgb(stops[i].color)));
    }
}

void paintPushTransform(hb_paint_funcs_t*, void* paint_data,
                        float xx, float yx, float xy, float yy,
                        float dx, float dy, void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_PUSH_TRANSFORM);
    writeF32(ctx, xx); writeF32(ctx, yx);
    writeF32(ctx, xy); writeF32(ctx, yy);
    writeF32(ctx, dx); writeF32(ctx, dy);
}

void paintPopTransform(hb_paint_funcs_t*, void* paint_data, void*) {
    writeU8(static_cast<PaintCtx*>(paint_data), PAINT_POP_TRANSFORM);
}

void paintPushClipGlyph(hb_paint_funcs_t*, void* paint_data,
                        hb_codepoint_t glyph, hb_font_t* /*font*/, void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_PUSH_CLIP_GLYPH);
    writeI32(ctx, static_cast<int32_t>(glyph));
}

void paintPushClipRect(hb_paint_funcs_t*, void* paint_data,
                       float xmin, float ymin, float xmax, float ymax, void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_PUSH_CLIP_RECT);
    writeF32(ctx, xmin); writeF32(ctx, ymin);
    writeF32(ctx, xmax); writeF32(ctx, ymax);
}

void paintPopClip(hb_paint_funcs_t*, void* paint_data, void*) {
    writeU8(static_cast<PaintCtx*>(paint_data), PAINT_POP_CLIP);
}

void paintColor(hb_paint_funcs_t*, void* paint_data,
                hb_bool_t is_foreground, hb_color_t color, void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_COLOR);
    writeU8(ctx, is_foreground ? 1 : 0);
    writeI32(ctx, static_cast<int32_t>(hbColorToArgb(color)));
}

hb_bool_t paintImage(hb_paint_funcs_t*, void* paint_data,
                     hb_blob_t* image, unsigned int width, unsigned int height,
                     hb_tag_t format, float slant,
                     hb_glyph_extents_t* extents, void*) {
    // Bitmap glyphs (CBDT/CBLC/sbix - the format of most OS emoji fonts,
    // e.g. Samsung's and pre-Android-13 NotoColorEmoji). Only PNG payloads
    // are forwarded: the Kotlin renderers decode PNG, while SVG-in-image
    // stays on the dedicated OT-SVG pipeline (fontGlyphSvg) and raw BGRA
    // is rare enough to skip. Returning false tells HarfBuzz the layer
    // wasn't handled so it can fall through without recursing.
    if (format != HB_PAINT_IMAGE_FORMAT_PNG) return false;
    unsigned int length = 0;
    const char* data = hb_blob_get_data(image, &length);
    if (data == nullptr || length == 0 || length > MAX_IMAGE_BYTES) return false;

    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_IMAGE);
    writeI32(ctx, static_cast<int32_t>(width));
    writeI32(ctx, static_cast<int32_t>(height));
    writeI32(ctx, static_cast<int32_t>(format));
    writeF32(ctx, slant);
    writeU8(ctx, extents != nullptr ? 1 : 0);
    if (extents != nullptr) {
        writeF32(ctx, static_cast<float>(extents->x_bearing));
        writeF32(ctx, static_cast<float>(extents->y_bearing));
        writeF32(ctx, static_cast<float>(extents->width));
        writeF32(ctx, static_cast<float>(extents->height));
    }
    writeI32(ctx, static_cast<int32_t>(length));
    size_t off = ctx->buf->size();
    ctx->buf->resize(off + length);
    std::memcpy(ctx->buf->data() + off, data, length);
    return true;
}

void paintLinearGradient(hb_paint_funcs_t*, void* paint_data,
                         hb_color_line_t* color_line,
                         float x0, float y0, float x1, float y1, float x2, float y2,
                         void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_LINEAR_GRADIENT);
    writeF32(ctx, x0); writeF32(ctx, y0);
    writeF32(ctx, x1); writeF32(ctx, y1);
    writeF32(ctx, x2); writeF32(ctx, y2);
    writeGradientStops(ctx, color_line);
}

void paintRadialGradient(hb_paint_funcs_t*, void* paint_data,
                         hb_color_line_t* color_line,
                         float x0, float y0, float r0,
                         float x1, float y1, float r1,
                         void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_RADIAL_GRADIENT);
    writeF32(ctx, x0); writeF32(ctx, y0); writeF32(ctx, r0);
    writeF32(ctx, x1); writeF32(ctx, y1); writeF32(ctx, r1);
    writeGradientStops(ctx, color_line);
}

void paintSweepGradient(hb_paint_funcs_t*, void* paint_data,
                        hb_color_line_t* color_line,
                        float x0, float y0,
                        float start_angle, float end_angle, void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_SWEEP_GRADIENT);
    writeF32(ctx, x0); writeF32(ctx, y0);
    writeF32(ctx, start_angle); writeF32(ctx, end_angle);
    writeGradientStops(ctx, color_line);
}

void paintPushGroup(hb_paint_funcs_t*, void* paint_data, void*) {
    writeU8(static_cast<PaintCtx*>(paint_data), PAINT_PUSH_GROUP);
}

void paintPopGroup(hb_paint_funcs_t*, void* paint_data,
                   hb_paint_composite_mode_t mode, void*) {
    PaintCtx* ctx = static_cast<PaintCtx*>(paint_data);
    writeU8(ctx, PAINT_POP_GROUP);
    writeI32(ctx, static_cast<int32_t>(mode));
}

hb_paint_funcs_t* sharedPaintFuncs() {
    static hb_paint_funcs_t* funcs = []() {
        hb_paint_funcs_t* f = hb_paint_funcs_create();
        hb_paint_funcs_set_push_transform_func   (f, paintPushTransform,   nullptr, nullptr);
        hb_paint_funcs_set_pop_transform_func    (f, paintPopTransform,    nullptr, nullptr);
        hb_paint_funcs_set_push_clip_glyph_func  (f, paintPushClipGlyph,   nullptr, nullptr);
        hb_paint_funcs_set_push_clip_rectangle_func(f, paintPushClipRect,  nullptr, nullptr);
        hb_paint_funcs_set_pop_clip_func         (f, paintPopClip,         nullptr, nullptr);
        hb_paint_funcs_set_color_func            (f, paintColor,           nullptr, nullptr);
        hb_paint_funcs_set_image_func            (f, paintImage,           nullptr, nullptr);
        hb_paint_funcs_set_linear_gradient_func  (f, paintLinearGradient,  nullptr, nullptr);
        hb_paint_funcs_set_radial_gradient_func  (f, paintRadialGradient,  nullptr, nullptr);
        hb_paint_funcs_set_sweep_gradient_func   (f, paintSweepGradient,   nullptr, nullptr);
        hb_paint_funcs_set_push_group_func       (f, paintPushGroup,       nullptr, nullptr);
        hb_paint_funcs_set_pop_group_func        (f, paintPopGroup,        nullptr, nullptr);
        hb_paint_funcs_make_immutable(f);
        return f;
    }();
    return funcs;
}

}  // namespace

JNIEXPORT jint JNICALL KH_FN(faceHasColorPaint)(JNIEnv*, jclass, jlong facePtr) {
    if (facePtr == 0) return 0;
    return hb_ot_color_has_paint(asFace(facePtr)) ? 1 : 0;
}

JNIEXPORT jint JNICALL KH_FN(faceHasColorPng)(JNIEnv*, jclass, jlong facePtr) {
    if (facePtr == 0) return 0;
    return hb_ot_color_has_png(asFace(facePtr)) ? 1 : 0;
}

// ───── SVG-in-OT ──────────────────────────────────────────────────────────

// Step out of `extern "C"` so the helpers below can return C++ types
// (`std::pair`, `std::vector`) without triggering -Wreturn-type-c-linkage.
// Re-open the extern block right after the namespace closes so the JNI
// entry points keep their stable C ABI.
}  // close file-wide extern "C"

namespace svgslice {

// Markers from the Kotlin slicer, kept identical so output is bit-equivalent.
constexpr char kGlyphOpenPrefix[] = "<g id=\"glyph";  // 12 chars (no NUL)
constexpr char kTagGOpen[]        = "<g";
constexpr char kTagGClose[]       = "</g>";
constexpr char kDefsOpen[]        = "<defs";
constexpr char kDefsClose[]       = "</defs>";
constexpr char kSvgHead[]         =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" "
    "xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\">";
constexpr char kSvgTail[]         = "</svg>";
constexpr size_t kGlyphOpenPrefixLen = sizeof(kGlyphOpenPrefix) - 1;
constexpr size_t kTagGOpenLen        = sizeof(kTagGOpen) - 1;
constexpr size_t kTagGCloseLen       = sizeof(kTagGClose) - 1;
constexpr size_t kDefsOpenLen        = sizeof(kDefsOpen) - 1;
constexpr size_t kDefsCloseLen       = sizeof(kDefsClose) - 1;
constexpr size_t kSvgHeadLen         = sizeof(kSvgHead) - 1;
constexpr size_t kSvgTailLen         = sizeof(kSvgTail) - 1;

constexpr size_t kNotFound = static_cast<size_t>(-1);

// memchr + memcmp two-step: portable, fast (memchr is heavily vectorised
// in libc), and avoids depending on the GNU-only memmem. Equivalent to
// the Kotlin slicer's `indexOf(haystack, needle, fromIndex)`.
inline size_t findBytes(const char* hay, size_t hayLen,
                        const char* needle, size_t needleLen,
                        size_t from) {
    if (needleLen == 0) return (from <= hayLen) ? from : kNotFound;
    if (needleLen > hayLen) return kNotFound;
    if (from > hayLen - needleLen) return kNotFound;
    const char first = needle[0];
    const char* searchEnd = hay + hayLen - needleLen + 1;
    const char* p = hay + from;
    while (p < searchEnd) {
        const char* m = static_cast<const char*>(
            std::memchr(p, static_cast<unsigned char>(first),
                        static_cast<size_t>(searchEnd - p)));
        if (m == nullptr) return kNotFound;
        if (std::memcmp(m, needle, needleLen) == 0) {
            return static_cast<size_t>(m - hay);
        }
        p = m + 1;
    }
    return kNotFound;
}

inline size_t findByte(const char* hay, size_t hayLen, char needle, size_t from) {
    if (from >= hayLen) return kNotFound;
    const char* m = static_cast<const char*>(
        std::memchr(hay + from, static_cast<unsigned char>(needle), hayLen - from));
    return (m == nullptr) ? kNotFound : static_cast<size_t>(m - hay);
}

// Returns 0, 1, or 2. Early-exits on the second hit. Mirrors the Kotlin
// slicer's countGlyphGroupsBytes — we only need to distinguish "single-
// glyph document" (≤1) from "multi-glyph document" (>1).
inline int countGlyphGroups(const char* data, size_t length) {
    if (length < kGlyphOpenPrefixLen) return 0;
    int count = 0;
    size_t i = 0;
    while (true) {
        size_t hit = findBytes(data, length, kGlyphOpenPrefix, kGlyphOpenPrefixLen, i);
        if (hit == kNotFound) return count;
        ++count;
        if (count > 1) return count;
        i = hit + kGlyphOpenPrefixLen;
    }
}

// Walk `<g>` / `</g>` / self-closing `<g .../>` from `startIdx` and return
// the index of the `</g>` closing the depth-1 outer group. Mirrors
// findMatchingClose in the Kotlin slicer.
inline size_t findMatchingGClose(const char* data, size_t length, size_t startIdx) {
    int depth = 1;
    size_t i = startIdx;
    while (i < length) {
        size_t nextOpen  = findBytes(data, length, kTagGOpen,  kTagGOpenLen,  i);
        size_t nextClose = findBytes(data, length, kTagGClose, kTagGCloseLen, i);
        if (nextClose == kNotFound) return kNotFound;
        if (nextOpen != kNotFound && nextOpen < nextClose) {
            size_t tagEnd = findByte(data, length, '>', nextOpen);
            if (tagEnd == kNotFound) return kNotFound;
            const bool selfClosing = tagEnd > 0 && data[tagEnd - 1] == '/';
            if (!selfClosing) ++depth;
            i = tagEnd + 1;
        } else {
            --depth;
            if (depth == 0) return nextClose;
            i = nextClose + kTagGCloseLen;
        }
    }
    return kNotFound;
}

// Returns the byte range of `<defs>...</defs>` (inclusive of both tags).
// `(kNotFound, 0)` when the document has no `<defs>`.
inline std::pair<size_t, size_t> extractDefsRange(const char* data, size_t length) {
    size_t open = findBytes(data, length, kDefsOpen, kDefsOpenLen, 0);
    if (open == kNotFound) return { kNotFound, 0 };
    size_t openEnd = findByte(data, length, '>', open);
    if (openEnd == kNotFound) return { kNotFound, 0 };
    if (openEnd > 0 && data[openEnd - 1] == '/') return { kNotFound, 0 };  // self-closing
    size_t close = findBytes(data, length, kDefsClose, kDefsCloseLen, openEnd + 1);
    if (close == kNotFound) return { kNotFound, 0 };
    return { open, close + kDefsCloseLen };
}

enum class SliceResult : int {
    kKeepWhole = 0,  // single-glyph doc OR slicing failed — caller returns input as-is
    kSliced    = 1,  // `out` holds the sliced subtree
    kNotFound  = 2,  // empty input or guarantee-failure; caller returns null
};

// Build a minimal SVG containing just `<g id="glyph$gid">` and the source
// document's `<defs>`. Output bytes are appended to `out`; returns
// kSliced on success, kKeepWhole if the document only has one
// glyph group (callers should pass the original bytes), kNotFound if the
// glyph isn't present or the structural scan fails on a malformed input.
SliceResult sliceSvgGlyph(const char* data, size_t length, int glyphId,
                          std::vector<uint8_t>& out) {
    if (length == 0) return SliceResult::kNotFound;
    if (countGlyphGroups(data, length) <= 1) return SliceResult::kKeepWhole;

    // Build the lookup prefix `<g id="glyphN` (no closing quote/space yet —
    // the Kotlin slicer also defers checking the next char so a request
    // for glyph 1 doesn't accidentally match `glyph10`).
    char prefix[64];
    int prefixLen = std::snprintf(prefix, sizeof(prefix), "%s%d",
                                   kGlyphOpenPrefix, glyphId);
    if (prefixLen <= 0 || prefixLen >= static_cast<int>(sizeof(prefix))) {
        return SliceResult::kNotFound;
    }

    size_t openIdx = findBytes(data, length, prefix,
                                static_cast<size_t>(prefixLen), 0);
    if (openIdx == kNotFound) return SliceResult::kNotFound;

    size_t afterPrefix = openIdx + static_cast<size_t>(prefixLen);
    if (afterPrefix >= length) return SliceResult::kNotFound;
    const char terminator = data[afterPrefix];
    if (terminator != '"' && terminator != ' ') return SliceResult::kNotFound;

    size_t tagEnd = findByte(data, length, '>', openIdx);
    if (tagEnd == kNotFound) return SliceResult::kNotFound;

    size_t closeIdx = findMatchingGClose(data, length, tagEnd + 1);
    if (closeIdx == kNotFound) return SliceResult::kNotFound;

    size_t groupStart = openIdx;
    size_t groupEnd   = closeIdx + kTagGCloseLen;

    auto defs = extractDefsRange(data, length);
    const bool hasDefs = defs.first != kNotFound;
    const size_t defsLen  = hasDefs ? defs.second - defs.first : 0;
    const size_t groupLen = groupEnd - groupStart;
    const size_t outLen   = kSvgHeadLen + defsLen + groupLen + kSvgTailLen;

    out.resize(outLen);
    uint8_t* p = out.data();
    std::memcpy(p, kSvgHead, kSvgHeadLen); p += kSvgHeadLen;
    if (hasDefs) {
        std::memcpy(p, data + defs.first, defsLen); p += defsLen;
    }
    std::memcpy(p, data + groupStart, groupLen); p += groupLen;
    std::memcpy(p, kSvgTail, kSvgTailLen);
    return SliceResult::kSliced;
}

}  // namespace svgslice

extern "C" {  // re-open the file-wide C ABI for the remaining JNI entries

JNIEXPORT jint JNICALL KH_FN(faceHasColorSvg)(JNIEnv*, jclass, jlong facePtr) {
    if (facePtr == 0) return 0;
    return hb_ot_color_has_svg(asFace(facePtr)) ? 1 : 0;
}

/**
 * Returns the SVG document for `glyphId` as a Java byte[], or null when
 * either the face has no SVG table or the glyph isn't covered by any of
 * its SVGDocumentRecord ranges. HarfBuzz's reference_svg already returns
 * decompressed bytes (SVGZ → SVG handled internally).
 */
JNIEXPORT jbyteArray JNICALL KH_FN(fontGlyphSvg)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId) {
    if (fontPtr == 0) return nullptr;
    hb_face_t* face = hb_font_get_face(asFont(fontPtr));
    if (!hb_ot_color_has_svg(face)) return nullptr;

    hb_blob_t* blob = hb_ot_color_glyph_reference_svg(face, static_cast<hb_codepoint_t>(glyphId));
    if (blob == nullptr) return nullptr;

    unsigned int length = 0;
    const char* data = hb_blob_get_data(blob, &length);
    if (length == 0 || data == nullptr) {
        hb_blob_destroy(blob);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(length));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte*>(data));
    }
    hb_blob_destroy(blob);
    return out;
}

/**
 * Like `fontGlyphSvg`, but slices the per-glyph `<g id="glyphN">` subtree
 * (plus the document's `<defs>`) inside this JNI call. Multi-glyph
 * documents (Noto Color Emoji ships 3 MB documents covering thousands
 * of glyphs each) are reduced to a tiny per-glyph fragment before the
 * bytes ever cross JNI; single-glyph documents (Aref Ruqaa Ink) and
 * slicing failures fall through to returning the whole document, so
 * callers can render either way without further branching.
 *
 * Output is bit-identical to `SvgGlyphSlicer.sliceGlyph(...)?: bytes`
 * in the Compose layer — same `<defs>` extraction, same `<svg ...>`
 * wrapper bytes — so MeasuredPass.svgBytesPreSliced can flip to true
 * on JVM/Android and skip the Kotlin slicer entirely.
 */
JNIEXPORT jbyteArray JNICALL KH_FN(fontGlyphSvgSliced)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId) {
    if (fontPtr == 0) return nullptr;
    hb_face_t* face = hb_font_get_face(asFont(fontPtr));
    if (!hb_ot_color_has_svg(face)) return nullptr;

    hb_blob_t* blob = hb_ot_color_glyph_reference_svg(face, static_cast<hb_codepoint_t>(glyphId));
    if (blob == nullptr) return nullptr;

    unsigned int length = 0;
    const char* data = hb_blob_get_data(blob, &length);
    if (length == 0 || data == nullptr) {
        hb_blob_destroy(blob);
        return nullptr;
    }

    std::vector<uint8_t> sliced;
    svgslice::SliceResult result = svgslice::sliceSvgGlyph(
        data, static_cast<size_t>(length), static_cast<int>(glyphId), sliced);

    jbyteArray out = nullptr;
    if (result == svgslice::SliceResult::kSliced) {
        const jsize outLen = static_cast<jsize>(sliced.size());
        out = env->NewByteArray(outLen);
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, outLen,
                                    reinterpret_cast<const jbyte*>(sliced.data()));
        }
    } else {
        // kKeepWhole (single-glyph document) and kNotFound (slice scan
        // failed on a multi-glyph document) both fall back to returning
        // the whole document, mirroring the Kotlin layer's `?: bytes`
        // fallback — Skia / AndroidSVG can render the full document in
        // both cases, just at a higher per-call cost.
        out = env->NewByteArray(static_cast<jsize>(length));
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(length),
                                    reinterpret_cast<const jbyte*>(data));
        }
    }
    hb_blob_destroy(blob);
    return out;
}

JNIEXPORT jbyteArray JNICALL KH_FN(fontPaintGlyph)(JNIEnv* env, jclass, jlong fontPtr,
                                                    jint glyphId, jint foreground, jint paletteIndex) {
    if (fontPtr == 0) return nullptr;
    hb_font_t* font = asFont(fontPtr);
    hb_face_t* face = hb_font_get_face(font);
    // COLR paint trees OR bitmap (CBDT/sbix) glyphs both walk the paint
    // funcs; bitmap-only faces emit PAINT_IMAGE ops via paintImage.
    if (!hb_ot_color_has_paint(face) && !hb_ot_color_has_png(face)) return nullptr;

    std::vector<uint8_t> buf;
    buf.reserve(256);                              // typical glyph fits comfortably
    PaintCtx ctx{ &buf };

    hb_color_t fg = argbToHbColor(static_cast<uint32_t>(foreground));
    unsigned int palette = paletteIndex < 0 ? 0u : static_cast<unsigned int>(paletteIndex);

    hb_font_paint_glyph(font, static_cast<hb_codepoint_t>(glyphId),
                        sharedPaintFuncs(), &ctx, palette, fg);

    if (buf.empty()) return nullptr;

    jsize size = static_cast<jsize>(buf.size());
    jbyteArray out = env->NewByteArray(size);
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

JNIEXPORT jint JNICALL KH_FN(fontDrawGlyph)(JNIEnv* env, jclass, jlong fontPtr, jint glyphId,
                                            jbyteArray outOpCodes, jfloatArray outCoords, jint capacity) {
    if (fontPtr == 0) return 0;

    int8_t* opCodes = nullptr;
    float*  coords  = nullptr;
    if (capacity > 0) {
        if (outOpCodes == nullptr || outCoords == nullptr) return 0;
        opCodes = reinterpret_cast<int8_t*>(env->GetPrimitiveArrayCritical(outOpCodes, nullptr));
        if (opCodes == nullptr) return 0;
        coords  = reinterpret_cast<float*>(env->GetPrimitiveArrayCritical(outCoords, nullptr));
        if (coords == nullptr) {
            env->ReleasePrimitiveArrayCritical(outOpCodes, opCodes, JNI_ABORT);
            return 0;
        }
    }

    DrawCtx ctx{ opCodes, coords, capacity, 0, 0, false };
    hb_font_draw_glyph(asFont(fontPtr), static_cast<hb_codepoint_t>(glyphId), sharedDrawFuncs(), &ctx);

    if (capacity > 0) {
        env->ReleasePrimitiveArrayCritical(outCoords, coords,  ctx.overflowed ? JNI_ABORT : 0);
        env->ReleasePrimitiveArrayCritical(outOpCodes, opCodes, ctx.overflowed ? JNI_ABORT : 0);
    }
    if (ctx.overflowed) {
        return -ctx.opIndex;            // negative = required size
    }
    return ctx.opIndex;
}

// ───── Buffer ────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL KH_FN(bufferCreate)(JNIEnv*, jclass) {
    hb_buffer_t* buf = hb_buffer_create();
    return toJlong(buf);
}

JNIEXPORT void JNICALL KH_FN(bufferDestroy)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return;
    hb_buffer_destroy(asBuffer(p));
}

JNIEXPORT void JNICALL KH_FN(bufferReset)(JNIEnv*, jclass, jlong p) {
    if (p == 0) return;
    hb_buffer_reset(asBuffer(p));
}

JNIEXPORT void JNICALL KH_FN(bufferSetText)(JNIEnv* env, jclass, jlong bufferPtr, jstring text) {
    if (bufferPtr == 0 || text == nullptr) return;
    hb_buffer_t* buf = asBuffer(bufferPtr);
    hb_buffer_clear_contents(buf);

    jsize length = env->GetStringLength(text);
    const jchar* chars = env->GetStringCritical(text, nullptr);
    if (chars == nullptr) return;

    // jchar is uint16_t; HarfBuzz's UTF-16 add reads them directly.
    hb_buffer_add_utf16(
        buf,
        reinterpret_cast<const uint16_t*>(chars),
        static_cast<int>(length),
        0,
        static_cast<int>(length)
    );
    env->ReleaseStringCritical(text, chars);
}

JNIEXPORT void JNICALL KH_FN(bufferSetTextWithContext)(JNIEnv* env, jclass, jlong bufferPtr,
                                                       jstring text, jint itemOffset, jint itemLength) {
    if (bufferPtr == 0 || text == nullptr) return;
    hb_buffer_t* buf = asBuffer(bufferPtr);
    hb_buffer_clear_contents(buf);

    jsize length = env->GetStringLength(text);
    if (itemOffset < 0 || itemLength < 0 || itemOffset + itemLength > length) return;
    const jchar* chars = env->GetStringCritical(text, nullptr);
    if (chars == nullptr) return;

    // The full string is loaded so HarfBuzz sees pre/post context around
    // the slice, but only [itemOffset, itemOffset + itemLength) is shaped.
    // Cluster values come back relative to the full string.
    hb_buffer_add_utf16(
        buf,
        reinterpret_cast<const uint16_t*>(chars),
        static_cast<int>(length),
        static_cast<unsigned int>(itemOffset),
        static_cast<int>(itemLength)
    );
    env->ReleaseStringCritical(text, chars);
}

JNIEXPORT void JNICALL KH_FN(bufferSetDirection)(JNIEnv*, jclass, jlong bufferPtr, jint direction) {
    if (bufferPtr == 0) return;
    hb_buffer_set_direction(asBuffer(bufferPtr), static_cast<hb_direction_t>(direction));
}

JNIEXPORT void JNICALL KH_FN(bufferSetScript)(JNIEnv*, jclass, jlong bufferPtr, jint scriptTag) {
    if (bufferPtr == 0) return;
    hb_buffer_set_script(
        asBuffer(bufferPtr),
        hb_script_from_iso15924_tag(static_cast<hb_tag_t>(scriptTag))
    );
}

JNIEXPORT void JNICALL KH_FN(bufferSetLanguage)(JNIEnv* env, jclass, jlong bufferPtr, jstring bcp47) {
    if (bufferPtr == 0) return;
    hb_buffer_t* buf = asBuffer(bufferPtr);
    if (bcp47 == nullptr) {
        hb_buffer_set_language(buf, HB_LANGUAGE_INVALID);
        return;
    }
    const char* utf = env->GetStringUTFChars(bcp47, nullptr);
    if (utf == nullptr) return;
    hb_buffer_set_language(buf, hb_language_from_string(utf, -1));
    env->ReleaseStringUTFChars(bcp47, utf);
}

JNIEXPORT jint JNICALL KH_FN(bufferGlyphCount)(JNIEnv*, jclass, jlong bufferPtr) {
    if (bufferPtr == 0) return 0;
    return static_cast<jint>(hb_buffer_get_length(asBuffer(bufferPtr)));
}

JNIEXPORT jint JNICALL KH_FN(bufferReadGlyphInfo)(JNIEnv* env, jclass, jlong bufferPtr,
                                                  jintArray outArr, jint capacity) {
    if (bufferPtr == 0 || outArr == nullptr) return 0;
    hb_buffer_t* buf = asBuffer(bufferPtr);
    unsigned int n = 0;
    hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buf, &n);
    if (n == 0) return 0;
    int writeN = static_cast<int>(n);
    if (writeN > capacity) writeN = capacity;

    jint* out = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(outArr, nullptr));
    if (out == nullptr) return 0;
    for (int i = 0; i < writeN; ++i) {
        out[i * 3 + 0] = static_cast<jint>(infos[i].codepoint);
        out[i * 3 + 1] = static_cast<jint>(infos[i].cluster);
        out[i * 3 + 2] = static_cast<jint>(infos[i].mask);
    }
    env->ReleasePrimitiveArrayCritical(outArr, out, 0);
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL KH_FN(bufferReadGlyphPositions)(JNIEnv* env, jclass, jlong bufferPtr,
                                                       jfloatArray outArr, jint capacity) {
    if (bufferPtr == 0 || outArr == nullptr) return 0;
    hb_buffer_t* buf = asBuffer(bufferPtr);
    unsigned int n = 0;
    hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(buf, &n);
    if (n == 0) return 0;
    int writeN = static_cast<int>(n);
    if (writeN > capacity) writeN = capacity;

    // Convert UPEM-relative HarfBuzz positions to pixel-space floats. We need
    // the originating font for the scale, but since this is per-buffer we use
    // the cached scale info embedded in glyph_position itself (which is already
    // in font-scale units after shape).
    // hb_buffer_get_glyph_positions returns values already at the font's
    // current scale, so we just convert to float without an extra multiply.
    // The Compose layer already knows pointSize.
    jfloat* out = reinterpret_cast<jfloat*>(env->GetPrimitiveArrayCritical(outArr, nullptr));
    if (out == nullptr) return 0;
    for (int i = 0; i < writeN; ++i) {
        out[i * 4 + 0] = toPixels(positions[i].x_advance);
        out[i * 4 + 1] = toPixels(positions[i].y_advance);
        out[i * 4 + 2] = toPixels(positions[i].x_offset);
        out[i * 4 + 3] = toPixels(positions[i].y_offset);
    }
    env->ReleasePrimitiveArrayCritical(outArr, out, 0);
    return static_cast<jint>(n);
}

// ───── Shape ─────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL KH_FN(shape)(JNIEnv* env, jclass, jlong fontPtr, jlong bufferPtr,
                                    jintArray featuresPacked, jint featureCount) {
    if (fontPtr == 0 || bufferPtr == 0) return;
    hb_font_t* font = asFont(fontPtr);
    hb_buffer_t* buf = asBuffer(bufferPtr);

    // If direction/script are still INVALID at this point, ask HarfBuzz to
    // guess from the buffer contents (mirrors AUTO behavior in the Kotlin
    // surface).
    hb_buffer_guess_segment_properties(buf);

    if (featureCount <= 0 || featuresPacked == nullptr) {
        hb_shape(font, buf, nullptr, 0);
        return;
    }
    hb_feature_t feats[32];
    int n = featureCount > 32 ? 32 : featureCount;

    jint* packed = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(featuresPacked, nullptr));
    if (packed == nullptr) {
        hb_shape(font, buf, nullptr, 0);
        return;
    }
    for (int i = 0; i < n; ++i) {
        feats[i].tag   = static_cast<hb_tag_t>(packed[i * 4 + 0]);
        feats[i].value = static_cast<uint32_t>(packed[i * 4 + 1]);
        feats[i].start = static_cast<unsigned int>(packed[i * 4 + 2]);
        feats[i].end   = static_cast<unsigned int>(packed[i * 4 + 3]);
    }
    env->ReleasePrimitiveArrayCritical(featuresPacked, packed, JNI_ABORT);

    hb_shape(font, buf, feats, n);
}

}  // extern "C"
