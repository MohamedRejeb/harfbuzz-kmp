# Color glyph cookbook

Color fonts ship with one or more of the OpenType color tables
(`COLR` v0, `COLR` v1, `CPAL`, `SVG `, `CBDT/CBLC`, `sbix`). Each
encodes the same visible glyph differently, and a renderer picks
whichever it can express at the highest fidelity.

`drawShapedText` does that picking automatically. This guide is for
when you want to know **what** it picked or **why** the result looks
the way it does.

---

## The render priority

For every glyph in the paragraph, `drawShapedText` checks tables in
this order:

| # | Path                       | Source table | Triggered by                                    |
|---|----------------------------|--------------|-------------------------------------------------|
| 1 | Foreground override        | none         | `forceForegroundColor = true`                   |
| 2 | SVG-in-OT bitmap           | `SVG `       | `face.hasColorSvg() && skiko target`            |
| 3 | COLR v1 paint tree         | `COLR` v1    | `face.hasColorPaint()`                          |
| 4 | COLR v0 layer stack        | `COLR` v0    | `face.hasColorLayers()`                         |
| 5 | Monochrome outline         | `glyf`/`CFF` | always available                                |

The first match wins. So a font that ships **both** SVG and COLR v1
(Aref Ruqaa Ink, Noto Color Emoji) renders via SVG on JVM/iOS/Wasm
and falls back to COLR v1 on Android (no skiko `SVGDOM` there).

To force the monochrome path explicitly:

```kotlin
ShapedText(
    text = "نص عربي تجريبي للاختبار",
    font = arefRuqaaInk,
    color = Color(0xFFB00020),
    forceForegroundColor = true,    // bypass every color table
)
```

---

## What each table looks like

### COLR v0 - layered glyphs with palette colors

A list of `(layerGlyphId, paletteColorIndex)` pairs per base glyph.
The renderer stacks the layers in order, each painted with its
palette color (or the caller-provided foreground when the entry
references `HB_OT_COLOR_PALETTE_COLOR_FOREGROUND`).

```kotlin
val layers: List<ColorLayer> = font.glyphColorLayers(glyphId)
// layers = [
//   ColorLayer(glyphId = 200, argb = 0xFF222222),  // dark base
//   ColorLayer(glyphId = 201, argb = 0xFFAA1133),  // red overlay
//   ColorLayer(glyphId = 202, argb = null),        // foreground tint
// ]
```

This is the original color-font format. Modern fonts mostly ship v1
(see below); v0 is still supported for compatibility.

### COLR v1 - paint trees

A *DAG of paint operations* per base glyph. Each tree can include
linear / radial / sweep gradients, affine transforms, clip-by-glyph,
clip-by-rectangle, and composite groups. Walk it with
`HbFont.paintGlyph`, dispatching every op into an `HbPaintSink`:

```kotlin
val sink = RecordingPaintSink()
font.paintGlyph(
    glyphId = gid,
    foreground = 0xFFB00020.toInt(),    // ARGB; substituted whenever
                                        // a stop is `is_foreground`
    paletteIndex = 0,                   // CPAL palette to resolve against
    sink = sink,
)
sink.ops.forEach { op -> /* push/pop/color/gradient ... */ }
```

`drawShapedText` uses an internal `ComposePaintSink` that translates
each op into a Compose `Canvas` operation (gradient → `Brush`,
transform → `Matrix.concat`, clip-glyph → cached `Path` clip).

### SVG-in-OT - full SVG documents

A complete SVG document per glyph (or per glyph range). Used by
fonts that want effects beyond what COLR can express - soft-edged
strokes, intricate gradients-along-paths, complex clipping.

```kotlin
val svgBytes: ByteArray? = font.glyphSvg(gid)
// Renderer parses with skiko's SVGDOM and rasterises to an
// ImageBitmap sized to the glyph's design space.
```

OT-SVG documents can be **multi-glyph** - Noto Color Emoji ships
*one* 14 MB SVG containing ~2700 `<g id="glyph123">` siblings. The
renderer's `SvgGlyphSlicer` extracts the target `<g>` plus the
shared `<defs>` into a per-glyph minimal SVG before handing it to
`SVGDOM` - without that, every glyph in the document would render
on top of the target.

### Monochrome - `glyf`/`CFF` outlines

The default. `font.drawGlyph(gid, sink)` emits draw commands that
the Compose layer turns into a `Path`, then fills with the caller's
color.

---

## Per-platform support

| Path                  | JVM | iOS | Wasm | Android |
|-----------------------|-----|-----|------|---------|
| Foreground override   | ✓   | ✓   | ✓    | ✓       |
| SVG-in-OT             | ✓   | ✓   | ✓    | ✗ (1)   |
| COLR v1 paint tree    | ✓   | ✓   | ✓    | ✓       |
| COLR v0 layer stack   | ✓   | ✓   | ✓    | ✓       |
| Monochrome outline    | ✓   | ✓   | ✓    | ✓       |

(1) Android's bundled Skia doesn't expose `SVGDOM`. Color fonts
that only ship SVG (rare) fall back to monochrome on Android. Fonts
that ship both SVG and COLR (Aref Ruqaa Ink, Noto Color Emoji)
render via COLR there.

---

## Inspecting a face

```kotlin
HbFace.from { bytes(fontBytes) }.use { face ->
    println("hasColorLayers (COLR v0):  ${face.hasColorLayers()}")
    println("hasColorPaint  (COLR v1):  ${face.hasColorPaint()}")
    println("hasColorSvg    (SVG-in-OT):${face.hasColorSvg()}")
}
```

For Aref Ruqaa Ink:

```
hasColorLayers (COLR v0):  false
hasColorPaint  (COLR v1):  true
hasColorSvg    (SVG-in-OT):true
```

For Noto Color Emoji: same - both COLR v1 and SVG. For a Naskh
font without color tables: all three return false.

---

## Wasm specifics

The published `harfbuzzjs` npm package compiles HarfBuzz with
`-DHB_TINY`, which silently strips `hb_ot_color_*`, `hb_paint_*`,
and `hb_font_paint_glyph` from the wasm export list. Calling them
on the upstream package fails with `TypeError`.

This repo ships a forked build at `native/harfbuzzjs/` that
re-enables the color/paint API:

- `config-override.h` - `#undef HB_NO_COLOR`, `#undef HB_NO_PAINT`,
  `#undef HB_NO_SVG`.
- `hb.symbols` - adds 25+ paint/color exports.
- `Makefile` - drives `em++` against the bundled HarfBuzz submodule.

The committed `hb.wasm` is ~420 KB (vs. ~390 KB upstream). To
rebuild after touching the HarfBuzz submodule:

```bash
./gradlew :harfbuzz-core:buildHarfBuzzJs   # requires emsdk on PATH
```

---

## Performance notes

- **Per-glyph caching.** `MeasuredText` rasterises SVG bitmaps
  once per unique glyph during `rememberMeasuredText`. Subsequent
  redraws of the same paragraph are pure bitmap stamps. Same for
  COLR v1 - the paint tree is recorded once into a sealed-class IR
  (`RecordedPaintOp`) and replayed against `ComposePaintSink` at
  draw time, never touching native code per frame.
- **JNI/cinterop boundary.** The COLR v1 paint walk serialises ops
  into a flat byte buffer in C++ and bulk-copies it across the
  language boundary once per glyph. Per-callback round-trips
  (50–200 ops on a complex emoji) would otherwise dominate.
- **Wasm `Module.addFunction`.** Paint callbacks are registered
  exactly once per module load and reused for every subsequent
  glyph. The Emscripten function table grows by ~12 entries
  (one per callback) and stays at that size.
