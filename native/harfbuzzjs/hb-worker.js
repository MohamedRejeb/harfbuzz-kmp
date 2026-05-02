// kotlin-harfbuzz Wasm worker.
// Hosts hb.wasm + the Emscripten Module + face/font/buffer registries.
// Communicates with the main thread via the envelope:
//   { id: number, type: string, payload: any, transfer?: Transferable[] }
//
// Replies have the same { id, type, payload } shape. Errors come back as
// { id, type: 'error', payload: { message, stack } }.
//
// Loaded as a classic worker (not `type: 'module'`) so we can pull in the
// UMD `hb.js` (Emscripten output) and `hbjs.js` via importScripts. Both
// install their top-level binding on the worker global via outer `var` /
// `function` declarations — `self.createHarfBuzz` is the Emscripten module
// factory, `self.hbjs` is the harfbuzzjs wrapper. We reference them through
// `self.*` everywhere so we don't shadow them with a local lexical binding
// (a top-level `const createHarfBuzz` here clashes with hb.js's `var
// createHarfBuzz` at parse time and the worker fails to load).

importScripts('./hb.js', './hbjs.js');

let hbPromise = null;     // Promise resolving to the HarfBuzz module (hbjs wrapper + __module)
let nextFaceId = 1;
let nextFontId = 1;
const faces = new Map();  // faceId -> { js: HbFaceJs, blob: HbBlobJs, bytes: Uint8Array }
const fonts = new Map();  // fontId -> { js: HbFontJs }

function ensureHb() {
    if (!hbPromise) {
        hbPromise = self.createHarfBuzz({}).then((module) => {
            const h = self.hbjs(module);
            h.__module = module;
            return h;
        });
    }
    return hbPromise;
}

function reply(id, type, payload, transfer) {
    self.postMessage({ id, type, payload }, transfer || []);
}

// Mirror of the main-thread profiling helper in HbProfile.wasmJs.kt — wraps
// each handler with performance.mark/measure so worker-thread work shows up
// in the DevTools Performance recording's worker User Timing track. Names
// stay aligned with the main-side ones (hb.shapeParagraph, hb.snapshotGlyphs)
// so the two traces are easy to read side-by-side.
let __markCounter = 0;
function __perfStart(name) {
    try {
        const id = name + ':start#' + (++__markCounter);
        performance.mark(id);
        return id;
    } catch (e) { return null; }
}
function __perfEnd(name, startMark) {
    if (startMark == null) return;
    try { performance.measure(name, startMark); } catch (e) {}
}

self.onmessage = async (event) => {
    const { id, type, payload } = event.data;
    const __mark = __perfStart('hb.worker.' + type);
    try {
        switch (type) {
            case 'init':
                await ensureHb();
                reply(id, 'ok', { ready: true });
                break;
            case 'createFace':
                reply(id, 'ok', await rpcCreateFace(payload));
                break;
            case 'destroyFace':
                rpcDestroyFace(payload);
                reply(id, 'ok', {});
                break;
            case 'createFont':
                reply(id, 'ok', await rpcCreateFont(payload));
                break;
            case 'destroyFont':
                rpcDestroyFont(payload);
                reply(id, 'ok', {});
                break;
            case 'shapeParagraph':
                reply(id, 'ok', await rpcShapeParagraph(payload));
                break;
            case 'snapshotGlyphs': {
                const { payload: snap, transfer } = await rpcSnapshotGlyphs(payload);
                reply(id, 'ok', snap, transfer);
                break;
            }
            case 'buildMeasured': {
                const { payload: built, transfer } = await rpcBuildMeasured(payload);
                reply(id, 'ok', built, transfer);
                break;
            }
            case 'shapeRun':
                reply(id, 'ok', await rpcShapeRun(payload));
                break;
            case 'getGlyphIdForCodepoint':
                reply(id, 'ok', await rpcGlyphIdForCodepoint(payload));
                break;
            case 'getGlyphAdvance':
                reply(id, 'ok', await rpcGlyphAdvance(payload));
                break;
            case 'getGlyphExtents':
                reply(id, 'ok', await rpcGlyphExtents(payload));
                break;
            case 'getGlyphPath':
                reply(id, 'ok', await rpcGlyphPath(payload));
                break;
            case 'getGlyphPaint':
                reply(id, 'ok', await rpcGlyphPaint(payload));
                break;
            case 'getGlyphColorLayers':
                reply(id, 'ok', await rpcGlyphColorLayers(payload));
                break;
            case 'getGlyphSvg':
                reply(id, 'ok', await rpcGlyphSvg(payload));
                break;
            default:
                reply(id, 'error', { message: `unknown rpc type: ${type}` });
        }
    } catch (err) {
        reply(id, 'error', {
            message: err && err.message ? err.message : String(err),
            stack: err && err.stack ? err.stack : null,
        });
    } finally {
        __perfEnd('hb.worker.' + type, __mark);
    }
};

// RPC implementations. shapeParagraph/snapshotGlyphs land in Phase 4d/4e.

async function rpcCreateFace({ bytes, index }) {
    const h = await ensureHb();
    // `bytes` arrives as a Uint8Array (transferred zero-copy from main).
    // Wrapping it again with `new Uint8Array(...)` would force a copy of
    // the entire font; use the incoming view directly. ArrayBuffer is
    // tolerated for forward-compatibility with callers that haven't been
    // updated yet.
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const blob = h.createBlob(u8);
    const face = h.createFace(blob, index | 0);
    const faceId = nextFaceId++;
    faces.set(faceId, { js: face, blob: blob, bytes: u8 });

    // Snapshot face-level aggregates so main can serve them sync.
    const m = h.__module;
    const facePtr = face.ptr;
    const upem = face.upem;
    const hasColorLayers = m._hb_ot_color_has_layers(facePtr) ? 1 : 0;
    const hasColorPaint = m._hb_ot_color_has_paint(facePtr) ? 1 : 0;
    const hasColorSvg = m._hb_ot_color_has_svg(facePtr) ? 1 : 0;
    const axes = collectAxes(m, facePtr);

    return { faceId, upem, axes, hasColorLayers, hasColorPaint, hasColorSvg };
}

function rpcDestroyFace({ faceId }) {
    const entry = faces.get(faceId);
    if (!entry) return;
    entry.js.destroy();
    entry.blob.destroy();
    faces.delete(faceId);
}

// Port of jsHbVariationAxisInfos from HarfBuzzWasm.kt — same packed
// 32-byte hb_ot_var_axis_info_t layout, but already decoded into a JS
// array of { tag, defaultValue, minValue, maxValue, hidden }.
function collectAxes(m, facePtr) {
    const STRIDE = 32;
    const cPtr = m._malloc(4);
    m.HEAPU32[cPtr >> 2] = 0;
    const total = m._hb_ot_var_get_axis_infos(facePtr, 0, cPtr, 0);
    m._free(cPtr);
    if (total === 0) return [];
    const arrPtr = m._malloc(STRIDE * total);
    const cPtr2 = m._malloc(4);
    m.HEAPU32[cPtr2 >> 2] = total;
    m._hb_ot_var_get_axis_infos(facePtr, 0, cPtr2, arrPtr);
    const written = m.HEAPU32[cPtr2 >> 2];
    m._free(cPtr2);
    // Layout per axis: axis_index(u32), tag(u32), name_id(u32),
    //                  flags(u32), min_value(f32), default_value(f32),
    //                  max_value(f32), reserved(u32).
    const axes = [];
    const HIDDEN_AXIS_FLAG = 0x00000001;
    for (let i = 0; i < written; i++) {
        const base = arrPtr + i * STRIDE;
        const tag = m.HEAPU32[(base + 4) >> 2] >>> 0;
        const flags = m.HEAPU32[(base + 12) >> 2] >>> 0;
        const minValue = m.HEAPF32[(base + 16) >> 2];
        const defaultValue = m.HEAPF32[(base + 20) >> 2];
        const maxValue = m.HEAPF32[(base + 24) >> 2];
        axes.push({
            tag,
            defaultValue,
            minValue,
            maxValue,
            hidden: (flags & HIDDEN_AXIS_FLAG) !== 0,
        });
    }
    m._free(arrPtr);
    return axes;
}

async function rpcCreateFont({ faceId, sizePx, variations }) {
    const h = await ensureHb();
    const face = faces.get(faceId);
    if (!face) throw new Error(`unknown faceId ${faceId}`);
    const font = h.createFont(face.js);
    const scale = (sizePx * 64) | 0;
    font.setScale(scale, scale);
    if (variations && variations.length) {
        applyVariations(h.__module, font.ptr, variations);
    }
    const fontId = nextFontId++;
    fonts.set(fontId, { js: font, facePtr: face.js.ptr });
    const ext = font.hExtents();
    return {
        fontId,
        ascender: ext.ascender / 64,
        descender: ext.descender / 64,
        lineGap: ext.lineGap / 64,
    };
}

function rpcDestroyFont({ fontId }) {
    const entry = fonts.get(fontId);
    if (!entry) return;
    entry.js.destroy();
    fonts.delete(fontId);
}

// Port of jsHbFontSetVariations — pack { tag, value } pairs into a
// wasm-side hb_variation_t[] (u32 tag + f32 value, 8-byte stride) and
// hand off to hb_font_set_variations.
function applyVariations(m, fontPtr, variations) {
    const STRIDE = 8;
    const ptr = m._malloc(STRIDE * variations.length);
    for (let i = 0; i < variations.length; i++) {
        const v = variations[i];
        m.HEAPU32[(ptr + i * STRIDE) >> 2] = v.tag >>> 0;
        m.HEAPF32[(ptr + i * STRIDE + 4) >> 2] = v.value;
    }
    m._hb_font_set_variations(fontPtr, ptr, variations.length);
    m._free(ptr);
}

// ───── Paragraph-level shaping (per-run shape) ─────────────────────────────
// BiDi resolution stays on the main side (pure Kotlin, no JS interop).
// The worker receives a pre-resolved sequence of bidi runs and shapes each
// run with harfbuzzjs. This is a line-by-line port of WasmShaping.shapeParagraph
// and HbFont.shape from harfbuzz-core/src/wasmJsMain/.../HbStubs.wasmJs.kt.

async function rpcShapeParagraph({ fontStack, text, baseDirection, features, language, bidiRuns }) {
    const h = await ensureHb();
    if (text.length === 0) {
        return {
            runs: [],
            totalAdvance: 0,
            ink: { left: 0, top: 0, right: 0, bottom: 0, isEmpty: true },
            baseDirection,
        };
    }
    // Single-font BiDi for v1: take fontStack[0]. Multi-font fallback
    // shaping (HbFontStack) lands in a follow-up PR — keeping parity
    // with what HbStubs.wasmJs.kt does today.
    const fontEntry = fonts.get(fontStack[0]);
    if (!fontEntry) throw new Error(`unknown fontId ${fontStack[0]}`);

    const buf = h.createBuffer();
    try {
        const visualOrder = isRtlBaseDirection(baseDirection, bidiRuns)
            ? bidiRuns.slice().reverse()
            : bidiRuns;

        const runs = [];
        let totalAdvance = 0;
        let inkLeft = Infinity, inkTop = Infinity, inkRight = -Infinity, inkBottom = -Infinity;

        for (const run of visualOrder) {
            const runText = text.substring(run.start, run.end);
            if (runText.length === 0) continue;

            buf.reset();
            buf.addText(runText);
            buf.setDirection(run.isRtl ? 'rtl' : 'ltr');
            if (language && language !== 'auto') buf.setLanguage(language);
            buf.guessSegmentProperties();

            const shaped = runShape(h, fontEntry.js, buf, features, run);

            // Offset glyph clusters by run.start (parity with WasmShaping.kt).
            // Cluster lives at index gi+1 in the packed Int32 layout.
            const gbuf = shaped.glyphsBuf;
            const gn = shaped.glyphCount;
            const offset = run.start | 0;
            if (offset !== 0) {
                for (let i = 0; i < gn; i++) gbuf[i * 3 + 1] += offset;
            }
            runs.push(shaped);

            if (!shaped.ink.isEmpty) {
                inkLeft = Math.min(inkLeft, totalAdvance + shaped.ink.left);
                inkTop = Math.min(inkTop, shaped.ink.top);
                inkRight = Math.max(inkRight, totalAdvance + shaped.ink.right);
                inkBottom = Math.max(inkBottom, shaped.ink.bottom);
            }
            totalAdvance += shaped.totalAdvance;
        }

        const ink = inkRight > inkLeft
            ? { left: inkLeft, top: inkTop, right: inkRight, bottom: inkBottom, isEmpty: false }
            : { left: 0, top: 0, right: 0, bottom: 0, isEmpty: true };

        return {
            runs,
            totalAdvance,
            ink,
            baseDirection: baseDirection === 'auto'
                ? (bidiRuns[0] && bidiRuns[0].isRtl ? 'rtl' : 'ltr')
                : baseDirection,
        };
    } finally {
        buf.destroy();
    }
}

function isRtlBaseDirection(baseDirection, bidiRuns) {
    if (baseDirection === 'rtl') return true;
    if (baseDirection === 'auto' && bidiRuns[0] && bidiRuns[0].isRtl) return true;
    return false;
}

function runShape(h, fontJs, buf, features, run) {
    // Mirror of HbFont.shape body in HbStubs.wasmJs.kt:225-294 — apply
    // features, call h.shape, walk getGlyphInfosAndPositions, build the
    // ink rect by querying glyphExtents per glyph (Y-up to Y-down flip).
    //
    // Per-glyph data is written into two flat typed arrays so the main
    // side decodes each run with one bulk wasm-memory copy per array
    // (see decodeShapedRun in HbStubs.wasmJs.kt). The previous
    // arrays-of-objects shape forced a JS↔Wasm crossing for every
    // GlyphInfo / GlyphPosition field — 7 reads × N glyphs — and that
    // dominated main-thread time on first scroll for large paragraphs.
    h.shape(fontJs, buf, packFeatures(features));
    const pairs = buf.getGlyphInfosAndPositions();
    const n = pairs.length;
    const glyphsBuf = new Int32Array(n * 3);   // [gid, cluster, flags] × n
    const positionsBuf = new Float32Array(n * 4); // [xAdv, yAdv, xOff, yOff] × n
    let totalAdvance = 0;
    let inkLeft = Infinity, inkTop = Infinity, inkRight = -Infinity, inkBottom = -Infinity;
    let penX = 0, penY = 0;

    for (let i = 0; i < n; i++) {
        const gp = pairs[i];
        const xAdv = gp.x_advance / 64;
        const yAdv = gp.y_advance / 64;
        const xOff = gp.x_offset / 64;
        const yOff = gp.y_offset / 64;

        const gi = i * 3;
        glyphsBuf[gi]     = gp.codepoint | 0;
        glyphsBuf[gi + 1] = gp.cluster | 0;
        glyphsBuf[gi + 2] = 0; // flags placeholder — parity with the Kotlin builder

        const pi = i * 4;
        positionsBuf[pi]     = xAdv;
        positionsBuf[pi + 1] = yAdv;
        positionsBuf[pi + 2] = xOff;
        positionsBuf[pi + 3] = yOff;

        const ext = fontJs.glyphExtents(gp.codepoint);
        if (ext) {
            const gx = penX + xOff + ext.xBearing / 64;
            const gy = penY - yOff - ext.yBearing / 64;
            const gw = ext.width / 64;
            const gh = -ext.height / 64;
            inkLeft = Math.min(inkLeft, gx);
            inkTop = Math.min(inkTop, gy);
            inkRight = Math.max(inkRight, gx + gw);
            inkBottom = Math.max(inkBottom, gy + gh);
        }

        penX += xAdv;
        penY += yAdv;
        totalAdvance += xAdv;
    }

    const ink = inkRight > inkLeft
        ? { left: inkLeft, top: inkTop, right: inkRight, bottom: inkBottom, isEmpty: false }
        : { left: 0, top: 0, right: 0, bottom: 0, isEmpty: true };

    return {
        glyphCount: n,
        glyphsBuf,
        positionsBuf,
        direction: run.isRtl ? 'rtl' : 'ltr',
        script: 'auto',
        totalAdvance,
        ink,
        logical: { left: 0, top: 0, right: totalAdvance, bottom: 0, isEmpty: totalAdvance <= 0 },
    };
}

function packFeatures(features) {
    if (!features || !features.length) return null;
    return features.map(f => `${f.value === 0 ? '-' : '+'}${f.tag}=${f.value}`).join(',');
}

// ───── Single-run shape (mirror of HbFont.shape on main) ──────────────────

async function rpcShapeRun({ fontId, text, direction, scriptTag, language, features }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const buf = h.createBuffer();
    try {
        if (text && text.length > 0) buf.addText(text);
        if (direction && direction !== 'auto') {
            buf.setDirection(direction);
        }
        if (scriptTag) {
            // scriptTag is the packed 4-CC HbScript tag.raw value. We re-build
            // the 4-char ASCII string the buffer setter expects.
            const s = String.fromCharCode(
                (scriptTag >> 24) & 0xFF,
                (scriptTag >> 16) & 0xFF,
                (scriptTag >> 8) & 0xFF,
                scriptTag & 0xFF
            );
            buf.setScript(s);
        }
        if (language && language !== 'auto') buf.setLanguage(language);
        buf.guessSegmentProperties();
        // Synthetic bidi run object so runShape can label `direction` correctly.
        const run = { isRtl: direction === 'rtl' };
        return runShape(h, fontEntry.js, buf, features, run);
    } finally {
        buf.destroy();
    }
}

// ───── Per-glyph one-shot RPCs (parity with batched snapshotGlyphs) ──────

async function rpcGlyphIdForCodepoint({ fontId, codepoint }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const cp = codepoint >>> 0;
    let s;
    if (cp <= 0xFFFF) {
        s = String.fromCharCode(cp);
    } else {
        const v = cp - 0x10000;
        const hi = ((v >> 10) + 0xD800) & 0xFFFF;
        const lo = ((v & 0x3FF) + 0xDC00) & 0xFFFF;
        s = String.fromCharCode(hi, lo);
    }
    const buf = h.createBuffer();
    try {
        buf.addText(s);
        buf.guessSegmentProperties();
        h.shape(fontEntry.js, buf, null);
        const pairs = buf.getGlyphInfosAndPositions();
        const glyphId = pairs.length > 0 ? (pairs[0].codepoint | 0) : 0;
        return { glyphId };
    } finally {
        buf.destroy();
    }
}

async function rpcGlyphAdvance({ fontId, gid }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    return { advance: fontEntry.js.glyphHAdvance(gid | 0) / 64 };
}

async function rpcGlyphExtents({ fontId, gid }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const ext = fontEntry.js.glyphExtents(gid | 0);
    if (!ext) return { ok: 0 };
    return {
        ok: 1,
        xBearing: ext.xBearing / 64,
        yBearing: ext.yBearing / 64,
        width: ext.width / 64,
        height: ext.height / 64,
    };
}

async function rpcGlyphPath({ fontId, gid }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const svg = String(fontEntry.js.glyphToPath(gid | 0));
    return { svg };
}

async function rpcGlyphPaint({ fontId, gid, foreground, paletteIndex }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const buf = paintGlyph(h, fontEntry.js.ptr, gid | 0, foreground | 0, paletteIndex | 0);
    return { bytes: buf };
}

async function rpcGlyphColorLayers({ fontId, gid }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const layers = collectColorLayers(h.__module, fontEntry.facePtr, gid | 0);
    return { layers };
}

async function rpcGlyphSvg({ fontId, gid }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const bytes = collectSvg(h.__module, fontEntry.facePtr, gid | 0);
    return { bytes };
}

// ───── Per-glyph snapshot (batched glyph data) ─────────────────────────────
// Direct port of three @JsFun bodies in HarfBuzzWasm.kt: jsHbPaintGlyph,
// jsHbGlyphColorLayers, jsHbGlyphSvg. Same wire format so the existing
// PaintBufferParser, ColorLayer decoder, and SVG handling on the Kotlin
// side consume the output unchanged.

async function rpcSnapshotGlyphs({ fontId, gids, flags }) {
    const h = await ensureHb();
    const fontEntry = fonts.get(fontId);
    if (!fontEntry) throw new Error(`unknown fontId ${fontId}`);
    const font = fontEntry.js;
    const facePtr = fontEntry.facePtr;
    const m = h.__module;

    const results = {
        flippedPaths: {},
        rawPaths: {},
        colorLayers: {},
        paintTrees: {},
        svgBytes: {},
    };

    // Granular marks for each per-glyph table — these aggregate up so the
    // DevTools recording shows whether path extraction, paint walking, or
    // SVG copy is the dominant cost inside snapshotGlyphs.
    const pathMark = (flags.flippedPath || flags.rawPath) ? __perfStart('hb.worker.snapshot.path') : null;
    if (flags.flippedPath || flags.rawPath) {
        for (const gid of gids) {
            const svgPath = font.glyphToPath(gid);
            if (flags.flippedPath) results.flippedPaths[gid] = svgPath;
            if (flags.rawPath) results.rawPaths[gid] = svgPath;
        }
    }
    __perfEnd('hb.worker.snapshot.path', pathMark);

    if (flags.colorLayers) {
        const layersMark = __perfStart('hb.worker.snapshot.colorLayers');
        for (const gid of gids) results.colorLayers[gid] = collectColorLayers(m, facePtr, gid);
        __perfEnd('hb.worker.snapshot.colorLayers', layersMark);
    }
    if (flags.paintTree) {
        const paintMark = __perfStart('hb.worker.snapshot.paintTree(' + gids.length + ')');
        for (const gid of gids) {
            const buf = paintGlyph(h, font.ptr, gid, 0xFF000000 | 0, 0);
            if (buf) results.paintTrees[gid] = buf;
        }
        __perfEnd('hb.worker.snapshot.paintTree(' + gids.length + ')', paintMark);
    }
    if (flags.svg) {
        const svgMark = __perfStart('hb.worker.snapshot.svg');
        for (const gid of gids) {
            const svg = collectSvg(m, facePtr, gid);
            if (svg) results.svgBytes[gid] = svg;
        }
        __perfEnd('hb.worker.snapshot.svg', svgMark);
    }
    return { payload: results, transfer: [] };
}

// ─── OT-SVG slicer (worker-side mirror of SvgGlyphSlicer.kt) ─────────
// OT-SVG's SVGDocumentRecord may cover a glyph range — Noto Color Emoji
// ships *one* ~14 MB document containing thousands of <g id="glyphN">
// siblings, and HarfBuzz hands the same blob back for every glyph in
// that range. Sending the full document per glyph turned a single
// emoji paragraph into tens of MB of postMessage traffic + matching
// per-glyph allocations on main. By slicing in wasm linear memory
// here we cut both: the per-glyph reply payload drops to the single
// <g> subtree (typically <50 KB).
//
// Behaviour mirrors the Kotlin slicer exactly so the failure modes
// (single-glyph doc, glyph not present, malformed input) fall through
// to the main-side fallback unchanged.

const __SVG_GLYPH_OPEN_PREFIX = __ascii('<g id="glyph');
const __SVG_TAG_G_OPEN = __ascii('<g');
const __SVG_END_G = __ascii('</g>');
const __SVG_DEFS_OPEN = __ascii('<defs');
const __SVG_DEFS_CLOSE = __ascii('</defs>');
const __SVG_HEAD_BYTES = __ascii(
    '<svg xmlns="http://www.w3.org/2000/svg" ' +
    'xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1">'
);
const __SVG_TAIL_BYTES = __ascii('</svg>');

function __ascii(s) {
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xFF;
    return out;
}

function __indexOfBytes(heap, start, end, pattern) {
    const patLen = pattern.length;
    if (patLen === 0) return start;
    if (end - start < patLen) return -1;
    const limit = end - patLen;
    for (let i = start; i <= limit; i++) {
        let matched = true;
        for (let j = 0; j < patLen; j++) {
            if (heap[i + j] !== pattern[j]) { matched = false; break; }
        }
        if (matched) return i;
    }
    return -1;
}

function __indexOfByte(heap, start, end, byte) {
    for (let i = start; i < end; i++) {
        if (heap[i] === byte) return i;
    }
    return -1;
}

// Early-exits on the second match — callers only need to know whether
// the document carries multiple <g id="glyph...> siblings.
function __countGlyphGroupsAtMost2(heap, start, end) {
    const pattern = __SVG_GLYPH_OPEN_PREFIX;
    const patLen = pattern.length;
    if (end - start < patLen) return 0;
    let count = 0;
    let i = start;
    const limit = end - patLen;
    while (i <= limit) {
        let matched = true;
        for (let j = 0; j < patLen; j++) {
            if (heap[i + j] !== pattern[j]) { matched = false; break; }
        }
        if (matched) {
            count++;
            if (count > 1) return count;
            i += patLen;
        } else {
            i++;
        }
    }
    return count;
}

function __findMatchingClose(heap, startIdx, end) {
    let depth = 1;
    let i = startIdx;
    while (i < end) {
        const nextOpen = __indexOfBytes(heap, i, end, __SVG_TAG_G_OPEN);
        const nextClose = __indexOfBytes(heap, i, end, __SVG_END_G);
        if (nextClose < 0) return -1;
        if (nextOpen >= 0 && nextOpen < nextClose) {
            const tagEnd = __indexOfByte(heap, nextOpen, end, 0x3E /* > */);
            if (tagEnd < 0) return -1;
            const isSelfClosing = heap[tagEnd - 1] === 0x2F /* / */;
            if (!isSelfClosing) depth++;
            i = tagEnd + 1;
        } else {
            depth--;
            if (depth === 0) return nextClose;
            i = nextClose + __SVG_END_G.length;
        }
    }
    return -1;
}

function __extractDefsRange(heap, start, end) {
    const open = __indexOfBytes(heap, start, end, __SVG_DEFS_OPEN);
    if (open < 0) return null;
    const openEnd = __indexOfByte(heap, open, end, 0x3E /* > */);
    if (openEnd < 0) return null;
    if (heap[openEnd - 1] === 0x2F /* / */) return null; // self-closing
    const close = __indexOfBytes(heap, openEnd + 1, end, __SVG_DEFS_CLOSE);
    if (close < 0) return null;
    return { start: open, end: close + __SVG_DEFS_CLOSE.length };
}

// Slice the <g id="glyph${gid}"> subtree out of the full OT-SVG
// document at wasm address [dataPtr, dataPtr + length) and wrap it in
// a fresh minimal SVG root + the document's <defs>. Returns a fresh
// Uint8Array (its own ArrayBuffer, transferable) or null when the
// document is single-glyph / target glyph not found / boundaries
// malformed.
function __sliceSvgInWasm(m, dataPtr, length, gid) {
    const heap = m.HEAPU8;
    const start = dataPtr;
    const end = dataPtr + length;

    if (__countGlyphGroupsAtMost2(heap, start, end) <= 1) return null;

    // Build the gid-specific opening pattern. The terminator check
    // afterwards (must be `"` or space) prevents glyph1 from matching
    // glyph10 when the doc holds adjacent ids.
    const gidStr = String(gid);
    const prefixLen = __SVG_GLYPH_OPEN_PREFIX.length;
    const openTagPrefix = new Uint8Array(prefixLen + gidStr.length);
    openTagPrefix.set(__SVG_GLYPH_OPEN_PREFIX);
    for (let i = 0; i < gidStr.length; i++) {
        openTagPrefix[prefixLen + i] = gidStr.charCodeAt(i) & 0xFF;
    }

    const openIdx = __indexOfBytes(heap, start, end, openTagPrefix);
    if (openIdx < 0) return null;

    const afterPrefix = openIdx + openTagPrefix.length;
    if (afterPrefix >= end) return null;
    const term = heap[afterPrefix];
    if (term !== 0x22 /* " */ && term !== 0x20 /* space */) return null;

    const openTagEnd = __indexOfByte(heap, openIdx, end, 0x3E /* > */);
    if (openTagEnd < 0) return null;

    const closeIdx = __findMatchingClose(heap, openTagEnd + 1, end);
    if (closeIdx < 0) return null;
    const groupEnd = closeIdx + __SVG_END_G.length;

    const defs = __extractDefsRange(heap, start, end);

    const headLen = __SVG_HEAD_BYTES.length;
    const tailLen = __SVG_TAIL_BYTES.length;
    const defsLen = defs ? (defs.end - defs.start) : 0;
    const groupLen = groupEnd - openIdx;
    const out = new Uint8Array(headLen + defsLen + groupLen + tailLen);
    let pos = 0;
    out.set(__SVG_HEAD_BYTES, pos); pos += headLen;
    if (defs) {
        out.set(heap.subarray(defs.start, defs.end), pos);
        pos += defsLen;
    }
    out.set(heap.subarray(openIdx, groupEnd), pos); pos += groupLen;
    out.set(__SVG_TAIL_BYTES, pos);
    return out;
}

// Port of jsHbGlyphSvg (HarfBuzzWasm.kt:305-325). Returns the SVG
// bytes that should be handed to Skia for [gid]: either the per-glyph
// slice (when the document covers multiple glyphs) or the full
// document (when slicing fails or the document is already minimal).
// Bytes are produced before the blob is destroyed so the buffer stays
// stable across subsequent HB allocations.
function collectSvg(m, facePtr, gid) {
    const blob = m._hb_ot_color_glyph_reference_svg(facePtr, gid);
    if (!blob) return null;
    const lenPtr = m._malloc(4);
    m.HEAPU32[lenPtr >> 2] = 0;
    const dataPtr = m._hb_blob_get_data(blob, lenPtr);
    const length = m.HEAPU32[lenPtr >> 2];
    m._free(lenPtr);
    if (length === 0 || dataPtr === 0) {
        m._hb_blob_destroy(blob);
        return null;
    }
    let out = __sliceSvgInWasm(m, dataPtr, length, gid);
    if (!out) {
        out = new Uint8Array(length);
        out.set(m.HEAPU8.subarray(dataPtr, dataPtr + length));
    }
    m._hb_blob_destroy(blob);
    return out;
}

// Port of jsHbGlyphColorLayers (HarfBuzzWasm.kt:336-388). Returns a flat
// Int32Array `[count, glyph0, argb0, glyph1, argb1, ...]` where argb of
// 0 means "use foreground color"; non-zero values are 0xAARRGGBB. Capped
// at 64 layers per glyph to mirror the upstream behaviour.
function collectColorLayers(m, facePtr, gid) {
    // Probe count.
    const countPtr = m._malloc(4);
    m.HEAPU32[countPtr / 4] = 0;
    m._hb_ot_color_glyph_get_layers(facePtr, gid, 0, countPtr, 0);
    const total = m.HEAPU32[countPtr / 4];
    if (total === 0) {
        m._free(countPtr);
        return new Int32Array([0]);
    }

    const capped = Math.min(total, 64);
    // hb_ot_color_layer_t = { uint32 glyph; uint32 color_index } = 8 bytes
    const layersPtr = m._malloc(capped * 8);
    m.HEAPU32[countPtr / 4] = capped;
    m._hb_ot_color_glyph_get_layers(facePtr, gid, 0, countPtr, layersPtr);
    const read = m.HEAPU32[countPtr / 4];

    const colorPtr = m._malloc(4);
    const cntPtr = m._malloc(4);
    const out = new Int32Array(1 + read * 2);
    out[0] = read;
    const FOREGROUND = 0xFFFFFFFF >>> 0;
    for (let i = 0; i < read; i++) {
        const layerGlyph = m.HEAPU32[(layersPtr + i * 8) / 4];
        const colorIdx   = m.HEAPU32[(layersPtr + i * 8 + 4) / 4];
        out[1 + i * 2] = layerGlyph | 0;
        if (colorIdx === FOREGROUND) {
            out[1 + i * 2 + 1] = 0;          // sentinel: foreground
        } else {
            m.HEAPU32[cntPtr / 4] = 1;
            m._hb_ot_color_palette_get_colors(facePtr, 0, colorIdx, cntPtr, colorPtr);
            const c = m.HEAPU32[colorPtr / 4];
            // hb_color_t is BGRA-packed: B at high byte, A at low byte
            // (mirrors the HB_COLOR(b,g,r,a) macro).
            const b = (c >>> 24) & 0xFF;
            const g = (c >>> 16) & 0xFF;
            const r = (c >>> 8) & 0xFF;
            const a = c & 0xFF;
            let argb = ((a << 24) | (r << 16) | (g << 8) | b) | 0;
            if (argb === 0) argb = 0x01000000;
            out[1 + i * 2 + 1] = argb;
        }
    }
    m._free(layersPtr);
    m._free(countPtr);
    m._free(colorPtr);
    m._free(cntPtr);
    return out;
}

// Port of jsHbPaintGlyph (HarfBuzzWasm.kt:427-600). Drives
// hb_font_paint_glyph with an hb_paint_funcs_t whose 12 callbacks
// serialise every emitted op into a JS Uint8Array using the same wire
// format the JVM JNI emits — see harfbuzz_jni.cpp + PaintBufferParser
// for the canonical layout. Returns null if the snapshot is empty.
//
// The paint funcs object is lazy-initialised on first use: each
// callback gets a function pointer via Module.addFunction, which adds
// an entry to the wasm function table. Those table entries are
// permanent for the module's lifetime, so we cache h.__paintFuncs and
// reuse it for every subsequent paintGlyph call.
function paintGlyph(h, fontPtr, glyph, foreground, paletteIndex) {
    const m = h.__module;

    if (!h.__paintFuncs) {
        // Single growable scratch buffer reused across calls. JS is
        // single-threaded and hb_font_paint_glyph runs synchronously,
        // so a shared buffer is safe — the closure-captured `offset`
        // resets on every walk.
        let scratch = new Uint8Array(4096);
        let offset = 0;
        const ensure = (extra) => {
            if (offset + extra <= scratch.length) return;
            let n = scratch.length;
            while (n < offset + extra) n *= 2;
            const grown = new Uint8Array(n);
            grown.set(scratch);
            scratch = grown;
        };
        const writeU8 = (v) => { ensure(1); scratch[offset++] = v & 0xFF; };
        const writeI32 = (v) => {
            ensure(4);
            // Little-endian to match the JVM JNI's native byte order
            // on x86/ARM. Wasm is always LE so this is safe.
            scratch[offset++] = v & 0xFF;
            scratch[offset++] = (v >>> 8) & 0xFF;
            scratch[offset++] = (v >>> 16) & 0xFF;
            scratch[offset++] = (v >>> 24) & 0xFF;
        };
        const f32buf = new ArrayBuffer(4);
        const f32view = new Float32Array(f32buf);
        const i32view = new Int32Array(f32buf);
        const writeF32 = (v) => {
            ensure(4);
            f32view[0] = v;
            writeI32(i32view[0]);
        };
        const argbFromHb = (c) => {
            // hb_color_t is BGRA-packed (HB_COLOR macro): high byte = B,
            // low byte = A. Re-pack to 0xAARRGGBB for the parser.
            const b = (c >>> 24) & 0xFF;
            const g = (c >>> 16) & 0xFF;
            const r = (c >>> 8) & 0xFF;
            const a = c & 0xFF;
            return ((a << 24) | (r << 16) | (g << 8) | b) | 0;
        };
        const writeStops = (linePtr) => {
            const extend = m._hb_color_line_get_extend(linePtr);
            writeU8(extend);
            const cntPtr = m._malloc(4);
            m.HEAPU32[cntPtr >> 2] = 0;
            // Probe: return value carries the total available count;
            // *count out-param holds the number actually written into
            // the (here null) buffer (= 0). Reading *count after this
            // probe call gives 0 — we must use the return value.
            const total = m._hb_color_line_get_color_stops(linePtr, 0, cntPtr, 0);
            const capped = Math.min(total, 256);
            writeI32(capped);
            if (capped === 0) { m._free(cntPtr); return; }
            // hb_color_stop_t = { float offset; hb_bool_t is_foreground; hb_color_t color }
            // = 12 bytes. Layout assumes natural alignment, which matches
            // emscripten's wasm32 ABI.
            const stopsPtr = m._malloc(capped * 12);
            m.HEAPU32[cntPtr >> 2] = capped;
            m._hb_color_line_get_color_stops(linePtr, 0, cntPtr, stopsPtr);
            const got = Math.min(m.HEAPU32[cntPtr >> 2], capped);
            for (let i = 0; i < got; i++) {
                const off    = m.HEAPF32[(stopsPtr + i * 12)     >> 2];
                const isFg   = m.HEAP32 [(stopsPtr + i * 12 + 4) >> 2];
                const color  = m.HEAPU32[(stopsPtr + i * 12 + 8) >> 2];
                writeF32(off);
                writeU8(isFg ? 1 : 0);
                writeI32(argbFromHb(color));
            }
            m._free(stopsPtr);
            m._free(cntPtr);
        };

        // Emscripten function-pointer signatures: first char is the
        // return type, rest are args. 'v'=void, 'i'=i32 (also for
        // pointers in wasm32), 'f'=f32. Each HB callback ends with a
        // user_data 'i'.
        const fp = m._hb_paint_funcs_create();

        const pushTransform = m.addFunction(
            (funcs, paintData, xx, yx, xy, yy, dx, dy, userData) => {
                writeU8(1);
                writeF32(xx); writeF32(yx);
                writeF32(xy); writeF32(yy);
                writeF32(dx); writeF32(dy);
            }, 'viiffffffi');
        const popTransform = m.addFunction(
            (funcs, paintData, userData) => { writeU8(2); }, 'viii');
        const pushClipGlyph = m.addFunction(
            (funcs, paintData, glyphId, font, userData) => {
                writeU8(3); writeI32(glyphId | 0);
            }, 'viiiii');
        const pushClipRect = m.addFunction(
            (funcs, paintData, xMin, yMin, xMax, yMax, userData) => {
                writeU8(4);
                writeF32(xMin); writeF32(yMin);
                writeF32(xMax); writeF32(yMax);
            }, 'viiffffi');
        const popClip = m.addFunction(
            (funcs, paintData, userData) => { writeU8(5); }, 'viii');
        const colorOp = m.addFunction(
            (funcs, paintData, isForeground, c, userData) => {
                writeU8(6); writeU8(isForeground ? 1 : 0); writeI32(argbFromHb(c));
            }, 'viiiii');
        const imageOp = m.addFunction(
            (funcs, paintData, img, w, hh, fmt, slant, extents, userData) =>
                0,                              // not handled — defer SVG/PNG
            'iiiiiiifii');
        const linearGradient = m.addFunction(
            (funcs, paintData, line, x0, y0, x1, y1, x2, y2, userData) => {
                writeU8(7);
                writeF32(x0); writeF32(y0);
                writeF32(x1); writeF32(y1);
                writeF32(x2); writeF32(y2);
                writeStops(line);
            }, 'viiiffffffi');
        const radialGradient = m.addFunction(
            (funcs, paintData, line, x0, y0, r0, x1, y1, r1, userData) => {
                writeU8(8);
                writeF32(x0); writeF32(y0); writeF32(r0);
                writeF32(x1); writeF32(y1); writeF32(r1);
                writeStops(line);
            }, 'viiiffffffi');
        const sweepGradient = m.addFunction(
            (funcs, paintData, line, x0, y0, startAngle, endAngle, userData) => {
                writeU8(9);
                writeF32(x0); writeF32(y0);
                writeF32(startAngle); writeF32(endAngle);
                writeStops(line);
            }, 'viiiffffi');
        const pushGroup = m.addFunction(
            (funcs, paintData, userData) => { writeU8(10); }, 'viii');
        const popGroup = m.addFunction(
            (funcs, paintData, mode, userData) => {
                writeU8(11); writeI32(mode);
            }, 'viiii');

        m._hb_paint_funcs_set_push_transform_func    (fp, pushTransform,    0, 0);
        m._hb_paint_funcs_set_pop_transform_func     (fp, popTransform,     0, 0);
        m._hb_paint_funcs_set_push_clip_glyph_func   (fp, pushClipGlyph,    0, 0);
        m._hb_paint_funcs_set_push_clip_rectangle_func(fp, pushClipRect,    0, 0);
        m._hb_paint_funcs_set_pop_clip_func          (fp, popClip,          0, 0);
        m._hb_paint_funcs_set_color_func             (fp, colorOp,          0, 0);
        m._hb_paint_funcs_set_image_func             (fp, imageOp,          0, 0);
        m._hb_paint_funcs_set_linear_gradient_func   (fp, linearGradient,   0, 0);
        m._hb_paint_funcs_set_radial_gradient_func   (fp, radialGradient,   0, 0);
        m._hb_paint_funcs_set_sweep_gradient_func    (fp, sweepGradient,    0, 0);
        m._hb_paint_funcs_set_push_group_func        (fp, pushGroup,        0, 0);
        m._hb_paint_funcs_set_pop_group_func         (fp, popGroup,         0, 0);
        m._hb_paint_funcs_make_immutable(fp);

        h.__paintFuncs = fp;
        h.__paintReset = () => { offset = 0; };
        h.__paintSnapshot = () => scratch.slice(0, offset);
    }

    // Compose 0xAARRGGBB → hb_color_t (BGRA). Equivalent to the
    // HB_COLOR(b, g, r, a) macro in C.
    const a = (foreground >>> 24) & 0xFF;
    const r = (foreground >>> 16) & 0xFF;
    const g = (foreground >>> 8) & 0xFF;
    const b = foreground & 0xFF;
    const fg = ((b << 24) | (g << 16) | (r << 8) | a) >>> 0;

    h.__paintReset();
    m._hb_font_paint_glyph(fontPtr, glyph >>> 0, h.__paintFuncs, 0, paletteIndex >>> 0, fg);
    const buf = h.__paintSnapshot();
    return buf.length === 0 ? null : buf;
}

// ───── Mega-RPC: buildMeasured ────────────────────────────────────────────
// Single round-trip per Compose buildMeasuredText. Mirrors the pre-existing
// pipeline (BiDi-resolved shape with multi-font fallback, then per-font
// snapshot of paths / paint trees / SVG bytes / color layers, then a
// follow-up resolve for clip-glyph references in paint trees and COLR v0
// base layer references). Doing it inside ONE worker handler eliminates
// the queue-saturation pile-up that turned the second `snapshotGlyphs`
// call (for paint-tree clip glyphs) into a 13-second main-thread wait
// when many paragraphs initialise at once — the diagnosed root cause of
// the `feature/background-harfbuzz` long-task report.

async function rpcBuildMeasured(payload) {
    const { fontStack, text, baseDirection, language, features, bidiRuns, perFontFlags } = payload;
    const h = await ensureHb();

    if (!fontStack || fontStack.length === 0 || !text || text.length === 0 ||
        !bidiRuns || bidiRuns.length === 0) {
        return {
            payload: {
                runs: [],
                totalAdvance: 0,
                ink: { left: 0, top: 0, right: 0, bottom: 0, isEmpty: true },
                fonts: [],
                baseDirection,
            },
            transfer: [],
        };
    }

    // Resolve every font handle up front so the inner shape loop never
    // crosses a Map lookup.
    const entries = new Array(fontStack.length);
    for (let i = 0; i < fontStack.length; i++) {
        const e = fonts.get(fontStack[i]);
        if (!e) throw new Error(`unknown fontId ${fontStack[i]}`);
        entries[i] = e;
    }

    const visualOrder = isRtlBaseDirection(baseDirection, bidiRuns)
        ? bidiRuns.slice().reverse()
        : bidiRuns;

    // Step 1: shape with multi-font fallback per direction-run.
    const buf = h.createBuffer();
    const outRuns = [];
    let totalAdvance = 0;
    let inkLeft = Infinity, inkTop = Infinity, inkRight = -Infinity, inkBottom = -Infinity;
    const gidsByFontIndex = new Map();
    try {
        for (const dirRun of visualOrder) {
            const runText = text.substring(dirRun.start, dirRun.end);
            if (runText.length === 0) continue;
            const subRuns = shapeWithFallback(
                h, buf, runText, !!dirRun.isRtl, language, features, entries, 0,
            );
            const offset = dirRun.start | 0;
            for (const sub of subRuns) {
                const gn = sub.glyphCount;
                const gbuf = sub.glyphsBuf;
                if (offset !== 0) {
                    for (let i = 0; i < gn; i++) gbuf[i * 3 + 1] += offset;
                }
                let gset = gidsByFontIndex.get(sub.fontIndex);
                if (!gset) { gset = new Set(); gidsByFontIndex.set(sub.fontIndex, gset); }
                for (let i = 0; i < gn; i++) {
                    const gid = gbuf[i * 3];
                    if (gid !== 0) gset.add(gid);
                }
                if (!sub.ink.isEmpty) {
                    inkLeft = Math.min(inkLeft, totalAdvance + sub.ink.left);
                    inkTop = Math.min(inkTop, sub.ink.top);
                    inkRight = Math.max(inkRight, totalAdvance + sub.ink.right);
                    inkBottom = Math.max(inkBottom, sub.ink.bottom);
                }
                totalAdvance += sub.totalAdvance;
                outRuns.push(sub);
            }
        }
    } finally {
        buf.destroy();
    }

    const ink = inkRight > inkLeft
        ? { left: inkLeft, top: inkTop, right: inkRight, bottom: inkBottom, isEmpty: false }
        : { left: 0, top: 0, right: 0, bottom: 0, isEmpty: true };

    // Step 2: per contributing font, resolve every per-glyph cache the
    // Compose layer asks for. Crucially, paint-tree clip-glyph references
    // (op id 3) and COLR v0 base-layer references are resolved IN-LINE in
    // the same pass — the previous architecture deferred these to a second
    // `snapshotGlyphs` round-trip that landed at the back of the worker
    // message queue once 12+ paragraphs had each enqueued ~3 messages.
    const m = h.__module;
    const fontsOut = [];
    const transfer = [];

    gidsByFontIndex.forEach((gidSet, fontIndex) => {
        const entry = entries[fontIndex];
        const fontJs = entry.js;
        const facePtr = entry.facePtr;
        const flags = (perFontFlags && perFontFlags[fontIndex]) || DEFAULT_BUILD_FLAGS;

        const flippedPaths = {};
        const rawPaths = {};
        const colorLayersOut = {};
        const paintTrees = {};
        const svgBytesOut = {};
        const extras = new Set();

        gidSet.forEach((gid) => {
            if (flags.flippedPath || flags.rawPath) {
                const svgPath = String(fontJs.glyphToPath(gid));
                if (flags.flippedPath) flippedPaths[gid] = svgPath;
                if (flags.rawPath) rawPaths[gid] = svgPath;
            }
            if (flags.colorLayers) {
                const layers = collectColorLayers(m, facePtr, gid);
                colorLayersOut[gid] = layers;
            }
            if (flags.paintTree) {
                const ptBuf = paintGlyph(h, fontJs.ptr, gid, 0xFF000000 | 0, 0);
                if (ptBuf) {
                    paintTrees[gid] = ptBuf;
                    collectClipGlyphsFromPaintBytes(ptBuf, extras);
                }
            }
            if (flags.svg) {
                const svg = collectSvg(m, facePtr, gid);
                if (svg) svgBytesOut[gid] = svg;
            }
        });

        // COLR v0 layer references — surface base glyph ids that aren't
        // already in the run set so we can pull their outlines too.
        if (flags.colorLayers) {
            const ids = Object.keys(colorLayersOut);
            for (const k of ids) {
                const arr = colorLayersOut[k];
                const cnt = arr[0] | 0;
                for (let i = 0; i < cnt; i++) {
                    const layerGid = arr[1 + i * 2] | 0;
                    if (layerGid !== 0) extras.add(layerGid);
                }
            }
        }

        // Strip extras already covered by run gids — Set has no removeAll.
        gidSet.forEach((gid) => extras.delete(gid));
        extras.delete(0);

        if (extras.size > 0 && (flags.flippedPath || flags.rawPath)) {
            extras.forEach((xgid) => {
                const svgPath = String(fontJs.glyphToPath(xgid));
                if (flags.flippedPath) flippedPaths[xgid] = svgPath;
                if (flags.rawPath) rawPaths[xgid] = svgPath;
            });
        }

        fontsOut.push({
            fontIndex,
            flippedPaths,
            rawPaths,
            colorLayers: colorLayersOut,
            paintTrees,
            svgBytes: svgBytesOut,
        });

        // Add typed-array buffers to the transfer list so postMessage
        // hands ownership over to main without structured-cloning the
        // payload bytes. Per-font this is dozens of small Uint8Arrays
        // (paint trees, SVG fragments) plus the Int32Array color-layer
        // buffers — cheap individually but the cumulative copy is real.
        for (const k of Object.keys(colorLayersOut)) {
            const v = colorLayersOut[k];
            if (v && v.buffer) transfer.push(v.buffer);
        }
        for (const k of Object.keys(paintTrees)) {
            const v = paintTrees[k];
            if (v && v.buffer) transfer.push(v.buffer);
        }
        for (const k of Object.keys(svgBytesOut)) {
            const v = svgBytesOut[k];
            if (v && v.buffer) transfer.push(v.buffer);
        }
    });

    for (const r of outRuns) {
        if (r.glyphsBuf) transfer.push(r.glyphsBuf.buffer);
        if (r.positionsBuf) transfer.push(r.positionsBuf.buffer);
    }

    return {
        payload: {
            runs: outRuns,
            totalAdvance,
            ink,
            fonts: fontsOut,
            baseDirection: baseDirection === 'auto'
                ? (bidiRuns[0] && bidiRuns[0].isRtl ? 'rtl' : 'ltr')
                : baseDirection,
        },
        transfer,
    };
}

const DEFAULT_BUILD_FLAGS = {
    flippedPath: true,
    rawPath: false,
    colorLayers: false,
    paintTree: false,
    svg: false,
};

// Multi-font fallback shaping inside the worker. Mirrors HbFontStackShape's
// `shapeRunWithFallback` but in plain JS — `entries` is a parallel array of
// already-resolved font entries (see `rpcBuildMeasured`) and `fontStartIndex`
// tracks how deep we are in the chain so each emitted sub-run can be tagged
// with its absolute font index.
//
// Note: the system-font resolver layer (SystemFallback.Match) is no-op on
// Wasm by design, so this stops at the explicit chain — same behaviour as
// the Kotlin path on Wasm.
function shapeWithFallback(h, buf, text, isRtl, language, features, entries, fontStartIndex) {
    const remaining = entries.length - fontStartIndex;
    if (remaining === 0 || text.length === 0) return [];

    const primaryEntry = entries[fontStartIndex];
    buf.reset();
    buf.addText(text);
    buf.setDirection(isRtl ? 'rtl' : 'ltr');
    if (language && language !== 'auto') buf.setLanguage(language);
    buf.guessSegmentProperties();
    const dirObj = { isRtl };
    const primaryRun = runShape(h, primaryEntry.js, buf, features, dirObj);
    primaryRun.fontIndex = fontStartIndex;

    if (remaining === 1) return [primaryRun];

    // Cluster → did any glyph resolve. Mirrors the Kotlin algorithm.
    const clusterHasGlyph = new Map();
    const gn = primaryRun.glyphCount;
    const gbuf = primaryRun.glyphsBuf;
    for (let i = 0; i < gn; i++) {
        const gid = gbuf[i * 3];
        const cluster = gbuf[i * 3 + 1];
        clusterHasGlyph.set(cluster, (clusterHasGlyph.get(cluster) === true) || gid !== 0);
    }
    if (clusterHasGlyph.size === 0) return [primaryRun];

    let anyMissing = false;
    clusterHasGlyph.forEach((v) => { if (!v) anyMissing = true; });
    if (!anyMissing) return [primaryRun];

    // [start, end, isNotdef] intervals from cluster keys.
    const sortedClusters = Array.from(clusterHasGlyph.keys()).sort((a, b) => a - b);
    const intervals = [];
    for (let i = 0; i < sortedClusters.length; i++) {
        const s = sortedClusters[i];
        const e = i + 1 < sortedClusters.length ? sortedClusters[i + 1] : text.length;
        if (e <= s) continue;
        intervals.push({ start: s, end: e, isNotdef: clusterHasGlyph.get(s) !== true });
    }
    if (intervals.length === 0) return [primaryRun];

    // Merge adjacent same-status intervals.
    const merged = [];
    for (const iv of intervals) {
        const last = merged[merged.length - 1];
        if (last && last.isNotdef === iv.isNotdef && last.end === iv.start) {
            merged[merged.length - 1] = { start: last.start, end: iv.end, isNotdef: last.isNotdef };
        } else {
            merged.push(iv);
        }
    }

    const sourceOrder = [];
    for (const iv of merged) {
        const sub = text.substring(iv.start, iv.end);
        let pieces;
        if (!iv.isNotdef) {
            // Re-shape the resolved interval with the primary alone — keeps
            // HarfBuzz's per-substring auto-detection of script/language and
            // matches the Kotlin algorithm.
            buf.reset();
            buf.addText(sub);
            buf.setDirection(isRtl ? 'rtl' : 'ltr');
            if (language && language !== 'auto') buf.setLanguage(language);
            buf.guessSegmentProperties();
            const r = runShape(h, primaryEntry.js, buf, features, dirObj);
            r.fontIndex = fontStartIndex;
            pieces = [r];
        } else {
            pieces = shapeWithFallback(
                h, buf, sub, isRtl, language, features, entries, fontStartIndex + 1,
            );
        }
        const startOff = iv.start | 0;
        for (const p of pieces) {
            if (startOff !== 0) {
                const pgn = p.glyphCount;
                const pbuf = p.glyphsBuf;
                for (let i = 0; i < pgn; i++) pbuf[i * 3 + 1] += startOff;
            }
            sourceOrder.push(p);
        }
    }

    return isRtl ? sourceOrder.reverse() : sourceOrder;
}

// Walk a paint-tree wire buffer (canonical format defined in
// PaintBufferParser.kt + the writer in `paintGlyph` above) and add every
// `PushClipGlyph` (op id 3) target into [outSet]. Used by buildMeasured to
// surface clip-glyph references that need outline data alongside their
// paint-tree owner — kept here because the wire format originates here.
function collectClipGlyphsFromPaintBytes(bytes, outSet) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const n = bytes.length;
    let off = 0;
    while (off < n) {
        const op = bytes[off++];
        switch (op) {
            case 1: off += 24; break;          // pushTransform: 6 f32
            case 2: break;                      // popTransform
            case 3:                             // pushClipGlyph: i32 glyphId
                if (off + 4 > n) return;
                outSet.add(dv.getInt32(off, true) >>> 0);
                off += 4;
                break;
            case 4: off += 16; break;          // pushClipRect: 4 f32
            case 5: break;                      // popClip
            case 6: off += 5; break;           // colorOp: u8 isFg + i32 argb
            case 7:                             // linearGradient: 6 f32 + stops
            case 8: {                           // radialGradient: 6 f32 + stops
                off += 24;                      // 6 f32
                if (off >= n) return;
                off += 1;                       // extend u8
                if (off + 4 > n) return;
                const cnt = dv.getInt32(off, true);
                off += 4;
                off += cnt * 9;                 // stop = f32 + u8 + i32 = 9 bytes
                break;
            }
            case 9: {                           // sweepGradient: 4 f32 + stops
                off += 16;                      // 4 f32
                if (off >= n) return;
                off += 1;                       // extend u8
                if (off + 4 > n) return;
                const cnt = dv.getInt32(off, true);
                off += 4;
                off += cnt * 9;
                break;
            }
            case 10: break;                     // pushGroup
            case 11: off += 4; break;          // popGroup: i32 mode
            default: return;                    // unknown op — abort safely
        }
    }
}
