@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

/**
 * JS interop helpers for building [HbWorker] request payloads and parsing
 * the JsAny replies the worker hands back. Kept in one file so the wire
 * format is easy to audit against `native/harfbuzzjs/hb-worker.js`.
 *
 * Naming: `build*Payload(...)` produce request payloads; `jsGet*Field`
 * unpack scalar fields from a reply object; bigger reply shapes have
 * dedicated decoders in [HbStubs.wasmJs.kt].
 */

// ───── Field accessors on plain JS objects ────────────────────────────────

@JsFun("(o, k) => (o == null ? null : o[k])")
internal external fun jsGetField(o: JsAny, k: String): JsAny?

@JsFun("(o, k) => { const v = (o == null ? 0 : o[k]); return v == null ? 0 : (v | 0); }")
internal external fun jsGetIntField(o: JsAny, k: String): Int

@JsFun("(o, k) => { const v = (o == null ? 0 : o[k]); return v == null ? 0 : +v; }")
internal external fun jsGetFloatField(o: JsAny, k: String): Float

@JsFun("(o, k) => { const v = (o == null ? '' : o[k]); return v == null ? '' : String(v); }")
internal external fun jsGetStringField(o: JsAny, k: String): String

/**
 * Read `map[gid]` where `map` is the worker's `{ [gid]: <bytes|string|...> }`
 * shape, returning either the JS value or null when the key is absent. Number
 * keys flow through `String(gid)` because JS object keys are strings.
 */
@JsFun("(map, gid) => (map == null ? null : map[gid] == null ? null : map[gid])")
internal external fun jsGetMapEntryByKey(map: JsAny, gid: Int): JsAny?

@JsFun(
    """
    (map, gid) => {
        if (map == null) return null;
        const v = map[gid];
        return v == null ? null : String(v);
    }
    """
)
internal external fun jsGetMapStringByKey(map: JsAny, gid: Int): String?

// ───── Payload builders ────────────────────────────────────────────────────

@JsFun(
    """
    (bytes, index) => ({ bytes: bytes, index: index | 0 })
    """
)
internal external fun jsBuildCreateFacePayload(bytes: JsAny, index: Int): JsAny

internal fun buildCreateFacePayload(bytes: ByteArray, faceIndex: Int): JsAny =
    jsBuildCreateFacePayload(bytes.toJsUint8Array(), faceIndex)

@JsFun("(payload) => payload.bytes.buffer")
internal external fun jsExtractCreateFaceTransfer(payload: JsAny): JsAny

@JsFun(
    """
    (faceId, sizePx, varTags, varValues, count) => {
        const variations = [];
        for (let i = 0; i < count; i++) {
            variations.push({ tag: varTags[i] >>> 0, value: varValues[i] });
        }
        return { faceId: faceId | 0, sizePx: +sizePx, variations: variations };
    }
    """
)
internal external fun jsBuildCreateFontPayload(
    faceId: Int,
    sizePx: Float,
    varTags: JsAny?,
    varValues: JsAny?,
    count: Int,
): JsAny

internal fun buildCreateFontPayload(
    faceId: Int,
    pointSize: Float,
    variations: List<HbVariation>,
): JsAny {
    if (variations.isEmpty()) {
        return jsBuildCreateFontPayload(faceId, pointSize, null, null, 0)
    }
    val tags = IntArray(variations.size) { variations[it].tag.raw.toInt() }
    val values = FloatArray(variations.size) { variations[it].value }
    return jsBuildCreateFontPayload(
        faceId = faceId,
        sizePx = pointSize,
        varTags = tags.toJsInt32Array(),
        varValues = values.toJsFloat32Array(),
        count = variations.size,
    )
}

@JsFun("(faceId) => ({ faceId: faceId | 0 })")
internal external fun buildDestroyFacePayload(faceId: Int): JsAny

@JsFun("(fontId) => ({ fontId: fontId | 0 })")
internal external fun buildDestroyFontPayload(fontId: Int): JsAny

@JsFun(
    """
    (fontId, gid) => ({ fontId: fontId | 0, gid: gid | 0 })
    """
)
internal external fun buildGlyphIdPayload(fontId: Int, gid: Int): JsAny

@JsFun(
    """
    (fontId, codepoint) => ({ fontId: fontId | 0, codepoint: codepoint | 0 })
    """
)
internal external fun buildGlyphIdForCodepointPayload(fontId: Int, codepoint: Int): JsAny

@JsFun(
    """
    (fontId, gid, foreground, paletteIndex) => ({
        fontId: fontId | 0,
        gid: gid | 0,
        foreground: foreground | 0,
        paletteIndex: paletteIndex | 0
    })
    """
)
internal external fun buildPaintGlyphPayload(
    fontId: Int,
    gid: Int,
    foreground: Int,
    paletteIndex: Int,
): JsAny

@JsFun(
    """
    (fontId, gids, flippedPath, rawPath, colorLayers, paintTree, svg) => ({
        fontId: fontId | 0,
        gids: Array.from(gids),
        flags: {
            flippedPath: !!flippedPath,
            rawPath: !!rawPath,
            colorLayers: !!colorLayers,
            paintTree: !!paintTree,
            svg: !!svg
        }
    })
    """
)
internal external fun jsBuildSnapshotGlyphsPayload(
    fontId: Int,
    gids: JsAny,
    flippedPath: Boolean,
    rawPath: Boolean,
    colorLayers: Boolean,
    paintTree: Boolean,
    svg: Boolean,
): JsAny

internal fun buildSnapshotGlyphsPayload(
    fontId: Int,
    gids: IntArray,
    flippedPath: Boolean,
    rawPath: Boolean,
    colorLayers: Boolean,
    paintTree: Boolean,
    svg: Boolean,
): JsAny = jsBuildSnapshotGlyphsPayload(
    fontId = fontId,
    gids = gids.toJsInt32Array(),
    flippedPath = flippedPath,
    rawPath = rawPath,
    colorLayers = colorLayers,
    paintTree = paintTree,
    svg = svg,
)

// ───── Shape payloads ──────────────────────────────────────────────────────

@JsFun(
    """
    (fontId, text, direction, scriptTag, language, featureTags, featureValues, featureStarts, featureEnds, count) => {
        const features = [];
        for (let i = 0; i < count; i++) {
            features.push({
                tag: featureTagToString(featureTags[i] >>> 0),
                value: featureValues[i] | 0,
                start: featureStarts[i] | 0,
                end: featureEnds[i] | 0
            });
        }
        return {
            fontId: fontId | 0,
            text: String(text),
            direction: String(direction),
            scriptTag: scriptTag | 0,
            language: String(language),
            features: features
        };

        function featureTagToString(tag) {
            return String.fromCharCode(
                (tag >> 24) & 0xFF,
                (tag >> 16) & 0xFF,
                (tag >> 8) & 0xFF,
                tag & 0xFF
            );
        }
    }
    """
)
internal external fun jsBuildShapeRunPayload(
    fontId: Int,
    text: String,
    direction: String,
    scriptTag: Int,
    language: String,
    featureTags: JsAny?,
    featureValues: JsAny?,
    featureStarts: JsAny?,
    featureEnds: JsAny?,
    count: Int,
): JsAny

internal fun buildShapeRunPayload(
    fontId: Int,
    text: String,
    direction: String,
    scriptTag: Int,
    language: String,
    features: List<HbFeature>,
): JsAny {
    if (features.isEmpty()) {
        return jsBuildShapeRunPayload(fontId, text, direction, scriptTag, language, null, null, null, null, 0)
    }
    val tags = IntArray(features.size) { features[it].tag.raw.toInt() }
    val values = IntArray(features.size) { features[it].value.toInt() }
    val starts = IntArray(features.size) { features[it].start }
    val ends = IntArray(features.size) {
        val e = features[it].end
        if (e == Int.MAX_VALUE) -1 else e
    }
    return jsBuildShapeRunPayload(
        fontId = fontId,
        text = text,
        direction = direction,
        scriptTag = scriptTag,
        language = language,
        featureTags = tags.toJsInt32Array(),
        featureValues = values.toJsInt32Array(),
        featureStarts = starts.toJsInt32Array(),
        featureEnds = ends.toJsInt32Array(),
        count = features.size,
    )
}

@JsFun(
    """
    (fontId, text, baseDirection, language, featureTags, featureValues, featureStarts, featureEnds, featureCount,
     bidiStarts, bidiEnds, bidiIsRtl, bidiCount) => {
        const features = [];
        for (let i = 0; i < featureCount; i++) {
            features.push({
                tag: featureTagToString(featureTags[i] >>> 0),
                value: featureValues[i] | 0,
                start: featureStarts[i] | 0,
                end: featureEnds[i] | 0
            });
        }
        const bidiRuns = [];
        for (let i = 0; i < bidiCount; i++) {
            bidiRuns.push({
                start: bidiStarts[i] | 0,
                end: bidiEnds[i] | 0,
                isRtl: !!bidiIsRtl[i]
            });
        }
        return {
            fontStack: [fontId | 0],
            text: String(text),
            baseDirection: String(baseDirection),
            features: features,
            language: String(language),
            bidiRuns: bidiRuns
        };

        function featureTagToString(tag) {
            return String.fromCharCode(
                (tag >> 24) & 0xFF,
                (tag >> 16) & 0xFF,
                (tag >> 8) & 0xFF,
                tag & 0xFF
            );
        }
    }
    """
)
internal external fun jsBuildShapeParagraphPayload(
    fontId: Int,
    text: String,
    baseDirection: String,
    language: String,
    featureTags: JsAny?,
    featureValues: JsAny?,
    featureStarts: JsAny?,
    featureEnds: JsAny?,
    featureCount: Int,
    bidiStarts: JsAny?,
    bidiEnds: JsAny?,
    bidiIsRtl: JsAny?,
    bidiCount: Int,
): JsAny

internal fun buildShapeParagraphPayload(
    fontId: Int,
    text: String,
    baseDirection: String,
    language: String,
    features: List<HbFeature>,
    bidiRuns: List<BidiRun>,
): JsAny {
    val featureTags: IntArray
    val featureValues: IntArray
    val featureStarts: IntArray
    val featureEnds: IntArray
    if (features.isEmpty()) {
        featureTags = IntArray(0)
        featureValues = IntArray(0)
        featureStarts = IntArray(0)
        featureEnds = IntArray(0)
    } else {
        featureTags = IntArray(features.size) { features[it].tag.raw.toInt() }
        featureValues = IntArray(features.size) { features[it].value.toInt() }
        featureStarts = IntArray(features.size) { features[it].start }
        featureEnds = IntArray(features.size) {
            val e = features[it].end
            if (e == Int.MAX_VALUE) -1 else e
        }
    }
    val bidiStarts = IntArray(bidiRuns.size) { bidiRuns[it].start }
    val bidiEnds = IntArray(bidiRuns.size) { bidiRuns[it].end }
    val bidiIsRtl = IntArray(bidiRuns.size) { if (bidiRuns[it].isRtl) 1 else 0 }
    return jsBuildShapeParagraphPayload(
        fontId = fontId,
        text = text,
        baseDirection = baseDirection,
        language = language,
        featureTags = if (features.isEmpty()) null else featureTags.toJsInt32Array(),
        featureValues = if (features.isEmpty()) null else featureValues.toJsInt32Array(),
        featureStarts = if (features.isEmpty()) null else featureStarts.toJsInt32Array(),
        featureEnds = if (features.isEmpty()) null else featureEnds.toJsInt32Array(),
        featureCount = features.size,
        bidiStarts = bidiStarts.toJsInt32Array(),
        bidiEnds = bidiEnds.toJsInt32Array(),
        bidiIsRtl = bidiIsRtl.toJsInt32Array(),
        bidiCount = bidiRuns.size,
    )
}

// ───── buildMeasured payload (mega-RPC) ───────────────────────────────────

@JsFun(
    """
    (fontIds, text, baseDirection, language,
     featureTags, featureValues, featureStarts, featureEnds, featureCount,
     bidiStarts, bidiEnds, bidiIsRtl, bidiCount,
     perFontFlags) => {
        const features = [];
        for (let i = 0; i < featureCount; i++) {
            features.push({
                tag: featureTagToString(featureTags[i] >>> 0),
                value: featureValues[i] | 0,
                start: featureStarts[i] | 0,
                end: featureEnds[i] | 0
            });
        }
        const bidiRuns = [];
        for (let i = 0; i < bidiCount; i++) {
            bidiRuns.push({
                start: bidiStarts[i] | 0,
                end: bidiEnds[i] | 0,
                isRtl: !!bidiIsRtl[i]
            });
        }
        const fontStack = [];
        for (let i = 0; i < fontIds.length; i++) fontStack.push(fontIds[i] | 0);
        const flagsArr = [];
        for (let i = 0; i < fontIds.length; i++) {
            const bits = perFontFlags[i] | 0;
            flagsArr.push({
                flippedPath: (bits & 0x01) !== 0,
                rawPath:     (bits & 0x02) !== 0,
                colorLayers: (bits & 0x04) !== 0,
                paintTree:   (bits & 0x08) !== 0,
                svg:         (bits & 0x10) !== 0,
            });
        }
        return {
            fontStack,
            text: String(text),
            baseDirection: String(baseDirection),
            language: String(language),
            features,
            bidiRuns,
            perFontFlags: flagsArr,
        };

        function featureTagToString(tag) {
            return String.fromCharCode(
                (tag >> 24) & 0xFF,
                (tag >> 16) & 0xFF,
                (tag >> 8) & 0xFF,
                tag & 0xFF
            );
        }
    }
    """
)
internal external fun jsBuildBuildMeasuredPayload(
    fontIds: JsAny,
    text: String,
    baseDirection: String,
    language: String,
    featureTags: JsAny?,
    featureValues: JsAny?,
    featureStarts: JsAny?,
    featureEnds: JsAny?,
    featureCount: Int,
    bidiStarts: JsAny?,
    bidiEnds: JsAny?,
    bidiIsRtl: JsAny?,
    bidiCount: Int,
    perFontFlags: JsAny,
): JsAny

internal fun buildBuildMeasuredPayload(
    fontIds: IntArray,
    text: String,
    baseDirection: String,
    language: String,
    features: List<HbFeature>,
    bidiRuns: List<BidiRun>,
    perFontFlags: List<GlyphSnapshotFlags>,
): JsAny {
    val featureTags: IntArray
    val featureValues: IntArray
    val featureStarts: IntArray
    val featureEnds: IntArray
    if (features.isEmpty()) {
        featureTags = IntArray(0)
        featureValues = IntArray(0)
        featureStarts = IntArray(0)
        featureEnds = IntArray(0)
    } else {
        featureTags = IntArray(features.size) { features[it].tag.raw.toInt() }
        featureValues = IntArray(features.size) { features[it].value.toInt() }
        featureStarts = IntArray(features.size) { features[it].start }
        featureEnds = IntArray(features.size) {
            val e = features[it].end
            if (e == Int.MAX_VALUE) -1 else e
        }
    }
    val bidiStarts = IntArray(bidiRuns.size) { bidiRuns[it].start }
    val bidiEnds = IntArray(bidiRuns.size) { bidiRuns[it].end }
    val bidiIsRtl = IntArray(bidiRuns.size) { if (bidiRuns[it].isRtl) 1 else 0 }
    val flagBits = IntArray(perFontFlags.size) { i ->
        val f = perFontFlags[i]
        var b = 0
        if (f.flippedPath) b = b or 0x01
        if (f.rawPath)     b = b or 0x02
        if (f.colorLayers) b = b or 0x04
        if (f.paintTree)   b = b or 0x08
        if (f.svg)         b = b or 0x10
        b
    }
    return jsBuildBuildMeasuredPayload(
        fontIds = fontIds.toJsInt32Array(),
        text = text,
        baseDirection = baseDirection,
        language = language,
        featureTags = if (features.isEmpty()) null else featureTags.toJsInt32Array(),
        featureValues = if (features.isEmpty()) null else featureValues.toJsInt32Array(),
        featureStarts = if (features.isEmpty()) null else featureStarts.toJsInt32Array(),
        featureEnds = if (features.isEmpty()) null else featureEnds.toJsInt32Array(),
        featureCount = features.size,
        bidiStarts = bidiStarts.toJsInt32Array(),
        bidiEnds = bidiEnds.toJsInt32Array(),
        bidiIsRtl = bidiIsRtl.toJsInt32Array(),
        bidiCount = bidiRuns.size,
        perFontFlags = flagBits.toJsInt32Array(),
    )
}

// ───── createFace reply decoding ──────────────────────────────────────────

@JsFun(
    """
    (axes) => {
        if (!axes) return new Float32Array(0);
        const out = new Float32Array(axes.length * 5);
        const f32view = new Float32Array(1);
        const i32view = new Int32Array(f32view.buffer);
        for (let i = 0; i < axes.length; i++) {
            const a = axes[i];
            i32view[0] = (a.tag >>> 0) | 0;
            out[i * 5 + 0] = f32view[0];
            out[i * 5 + 1] = +a.defaultValue;
            out[i * 5 + 2] = +a.minValue;
            out[i * 5 + 3] = +a.maxValue;
            i32view[0] = a.hidden ? 1 : 0;
            out[i * 5 + 4] = f32view[0];
        }
        return out;
    }
    """
)
internal external fun jsPackAxesArray(axes: JsAny?): JsAny

internal fun decodeAxesFromCreateFaceReply(reply: JsAny): List<HbVariationAxis> {
    val axesJs = jsGetField(reply, "axes") ?: return emptyList()
    val packed = jsPackAxesArray(axesJs)
    val len = jsArrayLength(packed)
    if (len == 0) return emptyList()
    val total = len / 5
    return List(total) { i ->
        val tagBits = jsArrayGetF32(packed, i * 5 + 0)
        val flagBits = jsArrayGetF32(packed, i * 5 + 4)
        HbVariationAxis(
            tag = HbTag(tagBits.toRawBits().toUInt()),
            defaultValue = jsArrayGetF32(packed, i * 5 + 1),
            minValue = jsArrayGetF32(packed, i * 5 + 2),
            maxValue = jsArrayGetF32(packed, i * 5 + 3),
            hidden = (flagBits.toRawBits() and 0x1) != 0,
        )
    }
}
