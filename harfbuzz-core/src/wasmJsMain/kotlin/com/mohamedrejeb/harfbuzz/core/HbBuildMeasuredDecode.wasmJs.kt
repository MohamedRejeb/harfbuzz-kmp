@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mohamedrejeb.harfbuzz.core

/**
 * Reply decoder for the `buildMeasured` worker RPC. The wire format is
 * defined in `native/harfbuzzjs/hb-worker.js#rpcBuildMeasured` and mirrors
 * the existing `shapeParagraph` + `snapshotGlyphs` shapes - runs use the
 * same packed Int32Array/Float32Array glyph layout, per-font caches use
 * the same `{ [gid]: <bytes|string|Int32Array> }` map shape.
 *
 * Decoded output: a [MeasuredPass] with the [ShapedParagraph] and one
 * [MeasuredFontPass] per contributing font. Each font's `fontIndex` field
 * (set by the worker on emitted runs and font sub-objects) maps back to
 * an index into the original [HbFontStack.fonts] list.
 */

@JsFun(
    """
    (o) => {
        if (o == null) return new Int32Array(0);
        const ks = Object.keys(o);
        const out = new Int32Array(ks.length);
        for (let i = 0; i < ks.length; i++) out[i] = ks[i] | 0;
        return out;
    }
    """
)
internal external fun jsObjectKeysAsInts(o: JsAny?): JsAny

internal suspend fun decodeBuildMeasured(
    reply: JsAny,
    text: String,
    fontStackFonts: List<HbFont>,
    resolvedBaseDirection: HbDirection,
): MeasuredPass {
    val totalAdvance = jsGetFloatField(reply, "totalAdvance")
    val ink = decodeRect(jsGetField(reply, "ink"))

    val runsJs = jsGetField(reply, "runs")
    val runCount = if (runsJs != null) jsArrayLength(runsJs) else 0
    val runs: List<ShapedRun> = if (runCount == 0) {
        emptyList()
    } else {
        ArrayList<ShapedRun>(runCount).apply {
            for (i in 0 until runCount) {
                val runJs = jsArrayGet(runsJs!!, i)
                val fontIndex = jsGetIntField(runJs, "fontIndex")
                val font = fontStackFonts.getOrNull(fontIndex) ?: fontStackFonts.firstOrNull()
                val decoded = decodeShapedRun(
                    reply = runJs,
                    fallbackDirection = resolvedBaseDirection,
                    fallbackScript = HbScript.AUTO,
                )
                add(if (font != null) decoded.copy(font = font) else decoded)
            }
        }
    }

    val n = text.length
    val paragraph = ShapedParagraph(
        runs = runs,
        baseDirection = resolvedBaseDirection,
        totalAdvance = totalAdvance,
        ink = ink,
        logical = HbRect(0f, 0f, totalAdvance, 0f),
        logicalToVisual = identityIntArray(n),
        visualToLogical = identityIntArray(n),
    )

    val fontsJs = jsGetField(reply, "fonts")
    val fontCount = if (fontsJs != null) jsArrayLength(fontsJs) else 0
    val fontPasses: List<MeasuredFontPass> = if (fontCount == 0) {
        emptyList()
    } else {
        val list = ArrayList<MeasuredFontPass>(fontCount)
        for (i in 0 until fontCount) {
            // Each font pass walks 5 per-glyph maps with one wasm↔JS hop per
            // entry. A color-emoji paragraph with 100+ glyphs and full SVG/
            // paint-tree coverage measured at ~1s in one task; yielding
            // between fonts splits the work across event-loop turns so a
            // single big paragraph can't pin main on its own.
            if (i > 0) yieldToBrowser()
            val entryJs = jsArrayGet(fontsJs!!, i)
            val fontIndex = jsGetIntField(entryJs, "fontIndex")
            val font = fontStackFonts.getOrNull(fontIndex) ?: continue
            list.add(decodeFontPass(entryJs, font))
        }
        list
    }

    return MeasuredPass(paragraph, fontPasses, svgBytesPreSliced = true)
}

private suspend fun decodeFontPass(entryJs: JsAny, font: HbFont): MeasuredFontPass {
    val flippedJs = jsGetField(entryJs, "flippedPaths")
    val rawJs = jsGetField(entryJs, "rawPaths")
    val layersJs = jsGetField(entryJs, "colorLayers")
    val paintJs = jsGetField(entryJs, "paintTrees")
    val svgJs = jsGetField(entryJs, "svgBytes")

    val flippedPathSvg = decodeStringMap(flippedJs)
    val rawPathSvg = decodeStringMap(rawJs)
    val colorLayers = decodeColorLayersMap(layersJs)
    val paintTreeBytes = decodeBytesMap(paintJs)
    val svgBytes = decodeBytesMap(svgJs)

    return MeasuredFontPass(
        font = font,
        flippedPathSvg = flippedPathSvg,
        rawPathSvg = rawPathSvg,
        colorLayers = colorLayers,
        paintTreeBytes = paintTreeBytes,
        svgBytes = svgBytes,
    )
}

// Per-entry count batch keeps a typical Latin paragraph (≤30 glyphs
// total, kilobyte-scale paint-tree blobs) yielding once or twice -
// enough to interleave a paint frame without thrashing the event loop.
private const val DECODE_YIELD_BATCH = 16

// Per-byte budget for the bytes-map decoder. OT-SVG documents commonly
// run 1–3 MB per glyph (NotoColorEmoji single-document, Aref Ruqaa Ink
// per-glyph) - a paragraph with 4 such glyphs would otherwise process
// all 12 MB inside one event-loop task. Yielding after every ~256 KB
// lets paint and pointer events interleave between the per-entry
// `jsUint8ArrayToByteArray` calls.
private const val DECODE_YIELD_BYTES = 256 * 1024

private suspend fun decodeStringMap(map: JsAny?): Map<Int, String> {
    if (map == null) return emptyMap()
    val keysJs = jsObjectKeysAsInts(map)
    val n = jsTypedArrayLength(keysJs)
    if (n == 0) return emptyMap()
    val keys = jsInt32ArrayToIntArray(keysJs)
    val out = HashMap<Int, String>(n)
    var counter = 0
    for (k in keys) {
        val v = jsGetMapStringByKey(map, k)
        if (v != null) out[k] = v
        if (++counter % DECODE_YIELD_BATCH == 0) yieldToBrowser()
    }
    return out
}

private suspend fun decodeColorLayersMap(map: JsAny?): Map<Int, List<ColorLayer>> {
    if (map == null) return emptyMap()
    val keysJs = jsObjectKeysAsInts(map)
    val n = jsTypedArrayLength(keysJs)
    if (n == 0) return emptyMap()
    val keys = jsInt32ArrayToIntArray(keysJs)
    val out = HashMap<Int, List<ColorLayer>>(n)
    var counter = 0
    for (k in keys) {
        val v = jsGetMapEntryByKey(map, k) ?: continue
        out[k] = decodeColorLayers(v)
        if (++counter % DECODE_YIELD_BATCH == 0) yieldToBrowser()
    }
    return out
}

private suspend fun decodeBytesMap(map: JsAny?): Map<Int, ByteArray> {
    if (map == null) return emptyMap()
    val keysJs = jsObjectKeysAsInts(map)
    val n = jsTypedArrayLength(keysJs)
    if (n == 0) return emptyMap()
    val keys = jsInt32ArrayToIntArray(keysJs)
    val out = HashMap<Int, ByteArray>(n)
    var counter = 0
    var bytesSinceYield = 0
    for (k in keys) {
        val v = jsGetMapEntryByKey(map, k) ?: continue
        val bytes = jsUint8ArrayToByteArray(v)
        if (bytes.isNotEmpty()) out[k] = bytes
        bytesSinceYield += bytes.size
        val countTrigger = ++counter % DECODE_YIELD_BATCH == 0
        val byteTrigger = bytesSinceYield >= DECODE_YIELD_BYTES
        if (countTrigger || byteTrigger) {
            yieldToBrowser()
            bytesSinceYield = 0
        }
    }
    return out
}
