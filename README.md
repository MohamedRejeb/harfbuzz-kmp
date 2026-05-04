# harfbuzz-kmp

A Kotlin Multiplatform port of the [HarfBuzz](https://github.com/harfbuzz/harfbuzz)
text-shaping library, packaged as two first-class artifacts:

- **`harfbuzz-core`** - pure Kotlin bindings over HarfBuzz. Headless shaping,
  font introspection, OpenType feature control, glyph metrics, glyph outline
  extraction, COLR v0/v1 paint trees, SVG-in-OT documents. No Compose
  dependency.
- **`harfbuzz-compose`** - Compose Multiplatform layer on top of `harfbuzz-core`.
  `ArcText`, `ShapedText`, `MeasuredText`, `DrawScope.drawShapedText`,
  `rememberHbFont`. Renders monochrome glyphs, COLR v0 layered colors,
  COLR v1 paint trees (gradients, transforms, composite layers), and
  SVG-in-OT - picking the highest-fidelity path the font supplies.

## Why

Compose's built-in text stack has known limitations rendering Arabic - cursive
joining, tashkeel positioning, ligatures (`لا` LAM-ALEF, joined letterforms,
mark stacks), bidi mixing with Latin/digits, OpenType feature control. It
also can't render **color fonts** at all on most targets - no emoji, no
inked Arabic, no flag glyphs.
This library exposes HarfBuzz directly to KMP/Compose so:

- Arabic renders correctly out of the box.
- Color fonts render with their designed gradients, paint trees, and SVG
  artwork - same fidelity as `hb-view` and Google Fonts.
- Effects the built-in APIs don't support (arc text, path text, precise
  measurements) become available.

## Targets

- **Android** (minSdk 24)
- **iOS** (`iosArm64` + `iosSimulatorArm64`)
- **Desktop JVM** (Linux x86_64, macOS aarch64/x86_64, Windows x86_64)
- **Web** (Wasm-JS)

## Install

> Snapshots of `0.1.0-SNAPSHOT` are published to the Sonatype snapshots repo
> on every push to `main`. A first stable `0.1.0` release will follow once
> the API surface is locked.

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.mohamedrejeb.harfbuzz:harfbuzz-core:0.1.0-SNAPSHOT")
    implementation("com.mohamedrejeb.harfbuzz:harfbuzz-compose:0.1.0-SNAPSHOT")
}

repositories {
    // for snapshots; remove once a stable release is published
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
```

## Quickstart - Arabic

```kotlin
@Composable
fun ArabicSample() {
    val state by rememberHbFont(
        bytesProvider = { Res.readBytes("font/NotoNaskhArabic-Regular.ttf") },
    )
    when (val s = state) {
        FontLoad.Loading   -> CircularProgressIndicator()
        is FontLoad.Failed -> Text("Failed: ${s.cause.message}")
        is FontLoad.Ready  -> ShapedText(
            text = "نَصٌّ عَرَبِيٌّ مُشَكَّلٌ لِلْاِخْتِبَارِ",
            font = s.font,
            sizePx = 32f,
            features = listOf(HbFeature("liga"), HbFeature("calt"), HbFeature("rlig")),
        )
    }
}
```

## Quickstart - Color glyphs (emoji, Aref Ruqaa Ink, …)

```kotlin
val state by rememberHbFont(
    bytesProvider = { Res.readBytes("font/NotoColorEmoji-Regular.ttf") },
)
(state as? FontLoad.Ready)?.let {
    ShapedText("😀🌍🎉⭐", font = it.font, sizePx = 64f)
}
```

`drawShapedText` automatically picks the best render path the face supplies,
in priority order:

1. **`forceForegroundColor`** - every glyph paints with the caller's color
   only. Bypasses every color path.
2. **SVG-in-OT bitmap** - highest fidelity for fonts that ship hand-tuned
   SVG (Aref Ruqaa Ink, Noto Color Emoji, …). Skipped on Android (no
   skiko's `SVGDOM` available there); the COLR fallback covers it.
3. **COLR v1 paint tree** - gradients, transforms, composite layers.
4. **COLR v0 layer stack** - palette-colored layered glyphs.
5. **Monochrome** - fall-through.

See [`docs/guides/color-glyphs.md`](docs/guides/color-glyphs.md) for a
deeper walk through what each table looks like and how the renderer
chooses.

## Quickstart - Arc text

```kotlin
ArcText(
    text = "نص عربي تجريبي للاختبار",
    font = arabicFont,
    sizePx = 32f,
    radius = 120.dp,
    sweep = ArcSweep.Auto,
    color = MaterialTheme.colorScheme.primary,
)
```

## Architecture

`harfbuzz-compose` depends on `harfbuzz-core`. The core has zero Compose
dependencies - server-side and CLI consumers can use it for headless shaping.

Native bindings:

| Target | How |
|---|---|
| Android | NDK + CMake builds `libharfbuzz_jni.so` for arm64-v8a, armeabi-v7a, x86_64; bundled in AAR `jniLibs`. |
| iOS | CMake cross-compiles HarfBuzz to a static `.a` per arch; cinterop binds `hb.h` + `hb-ot.h`, paint callbacks register via `staticCFunction`. |
| Desktop JVM | CMake builds `libharfbuzz_jni.{dylib,so,dll}` for the host; bundled in jar resources, extracted at runtime via SHA-256-keyed cache. |
| Wasm | Emscripten builds `hb.wasm` via [`native/harfbuzzjs/`](native/harfbuzzjs/) - a local fork of the upstream npm package with COLR/CPAL/paint enabled (the upstream `harfbuzzjs` strips them via `HB_TINY`). Paint callbacks register via `Module.addFunction`. |

HarfBuzz pinned at **14.2.0**.

## Documentation

- [`docs/guides/arabic.md`](docs/guides/arabic.md) - Arabic cookbook (BiDi,
  OT features, common fonts, caveats).
- [`docs/guides/color-glyphs.md`](docs/guides/color-glyphs.md) - Color font
  cookbook (COLR v0/v1, SVG-in-OT, render priority, platform support).
- [`docs/guides/arc-text.md`](docs/guides/arc-text.md) - Arc-text cookbook.
- [`docs/guides/perf.md`](docs/guides/perf.md) - Performance and zero-copy
  contracts at platform boundaries.

## Building locally

```bash
./gradlew :harfbuzz-core:jvmTest          # 18 core tests + Arabic gate
./gradlew :sample:jvmTest                 # 44 screenshot tests covering
                                          # paint, SVG, emoji, Arabic, …
./gradlew :sample:run                     # Desktop demo
./gradlew :sample:wasmJsBrowserDevelopmentRun   # Browser demo
./gradlew :harfbuzz-core:assembleRelease  # Android AAR
```

For the Wasm target, the committed `native/harfbuzzjs/hb.{js,wasm}` are
known-good builds that work without Emscripten installed. To rebuild after
touching `config-override.h`, `hb.symbols`, or the HarfBuzz submodule:

```bash
./gradlew :harfbuzz-core:buildHarfBuzzJs   # requires `em++` (emsdk) on PATH
```

The first full build pulls Gradle, the Kotlin/Native distribution, and (on
macOS) runs Xcode's CMake plumbing to cross-compile HarfBuzz for iOS -
typical clean build is ~5 min the first time, ~10 s on incremental.

## License

Apache 2.0. HarfBuzz itself is under the [Old MIT](https://github.com/harfbuzz/harfbuzz/blob/main/COPYING)
license, which is compatible.
