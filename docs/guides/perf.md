# Performance

## Zero-copy at platform boundaries

This is a non-functional requirement called out in the design spec (§10).
Where HarfBuzz data crosses the Kotlin↔native boundary, it goes through
pinned / direct buffers - never through an intermediate Kotlin `ByteArray`
on a hot path.

| Boundary | Mechanism |
|---|---|
| **Wasm + harfbuzzjs** | Font bytes go directly into harfbuzzjs's `HEAPU8` via its `_malloc` interop (Wasm interop layer ships post-v0.1). |
| **JNI (Android + Desktop JVM)** | `GetPrimitiveArrayCritical` on shape-result reads, `DirectByteBuffer` for font blobs. |
| **Kotlin/Native (iOS)** | `ByteArray.usePinned { addressOf(0) }` - cinterop is zero-copy by construction. |

Don't write code that round-trips data through `nativeBuffer → Kotlin
ByteArray → another nativeBuffer` on a per-frame path.

## Shaping latency

Shaping a typical Arabic line (≤200 chars) is sub-millisecond on JVM after
HarfBuzz has been loaded. The headline observation: glyph extraction (the
hb-draw pass for outline paths) is the dominant cost when `MeasuredText` is
constructed for the first time. After that, the per-glyph Compose `Path`
cache makes subsequent draws of the same paragraph effectively free.

### Caching guidelines

- Use `rememberMeasuredText(text, font, ...)` rather than constructing a
  `MeasuredText` every recomposition. The function is `remember`-keyed on
  text + font + features + direction, so identical inputs reuse the cache.
- Keep an `HbFont` alive across many shape calls. Constructing
  `HbFace + HbFont` is cheap but not free - there's a CMake-built shaper
  plan inside HarfBuzz.

### Offloading

Shaping is synchronous by default. For very large inputs (shaping an entire
book at once), drop into `Dispatchers.Default`:

```kotlin
val measured = withContext(Dispatchers.Default) {
    measureText(longText, font, features, direction, language)
}
```

## Memory footprint

### Native binary sizes

| Platform | `libharfbuzz_jni` |
|---|---|
| Android arm64-v8a (release, stripped) | ~1.2 MB |
| Android armeabi-v7a (release, stripped) | ~900 KB |
| Android x86_64 (release, stripped) | ~1.2 MB |
| Desktop macOS aarch64 (release) | ~1.4 MB |
| iOS arm64 (static `.a`) | ~1.7 MB |

Total Android AAR with all 3 ABIs: ~1.5 MB compressed.

### Glyph path cache

`MeasuredText.glyphPaths` holds one Compose `Path` per **unique** glyph in
the paragraph. A typical Arabic paragraph has 30-50 unique glyphs (lots of
repetition); a glyph path is dozens to a few hundred path commands. Total
cache size for a paragraph is typically under 10 KB.

## Benchmarks

JMH benchmarks live in `harfbuzz-core/benchmarks/` (under construction).
Targets to track over time:

- Shape latency for a 200-char Arabic line.
- Font load latency for a 500 KB TTF.
- Path extraction throughput (glyphs/ms).
- Wasm-specific: round-trip cost of a shape call vs raw harfbuzzjs.

Bench results live alongside CI artifacts; regressions block release.
