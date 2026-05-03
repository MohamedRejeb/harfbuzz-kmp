# Arabic with harfbuzz-kmp

The library's primary motivation is correct Arabic text rendering. Compose's
built-in text stack has known issues with cursive joining, tashkeel
positioning, and OT feature control; HarfBuzz handles all of this natively
and this library exposes that to your KMP/Compose code.

## What you get for free

- **Cursive joining** (initial / medial / final / isolated forms).
- **Standard ligatures**: `لا` → LAM_ALEF and any other ligation rules
  the font's `liga`, `rlig`, and `dlig` lookups expose.
- **Tashkeel positioning**: fatha, kasra, damma, sukun, shadda combine
  correctly above/below the appropriate base letters.
- **Bidi handling**: mixed Arabic + Latin + numbers laid out in the correct
  visual order via the pure-Kotlin UAX#9 resolver in `harfbuzz-core`.
- **Stylistic OT features**: any feature your font advertises (`ss01`,
  `liga`, `calt`, `rlig`, `init/medi/fina/isol`) can be toggled per shape
  call.
- **Arabic-Indic vs European digits**: digits keep their Bidi_Class (AN vs
  EN) and shape correctly inside RTL paragraphs.

## Picking a font

The system fonts the sample uses (GeezaPro, NotoNaskhArabic, DroidNaskh)
are all decent for prose. For curated typography you'll typically want one
of:

- **Amiri** - the de-facto Quranic Naskh, with rich ss01 / ss02 / aalt
  feature sets.
- **Noto Naskh Arabic** - Google's general-purpose Arabic Naskh.
- **Vazirmatn** - variable-axis Arabic / Persian (variable-axis support
  arrives in v1.1).

Bundle the font as a Compose Multiplatform Resource (`fonts/...`) and load
it via `rememberHbFont({ Res.readBytes(...) })`.

## Common pitfalls

### Setting direction explicitly when known

`HbDirection.AUTO` works for the common cases via first-strong detection.
For server-side or test code where the input is known Arabic, set
`buf.direction = HbDirection.RTL` and `buf.script = HbScript.ARABIC`
explicitly - it's faster and removes an ambiguity.

### Disabling ligatures

If you're showing letter-by-letter typography lessons, disable `liga` and
`calt`:

```kotlin
buf.features = listOf(
    HbFeature("liga", value = 0u),
    HbFeature("calt", value = 0u),
)
```

### Mixing Arabic + emoji

Today, codepoints not present in your Arabic font emit `.notdef` (boxes).
v1.1 adds `HbFontStack` for fallback chains:

```kotlin
val stack = HbFontStack(
    primary = arabicFont,
    fallbacks = listOf(emojiFont, cjkFont),
)
```

In v1, supply the bytes of a font that covers everything you need (or
restrict your input to the font's supported Unicode range).

### Caret hit-testing in mixed bidi

`MeasuredText.caretRectAt(charIndex)` works for the common cases. Edge
cases at run boundaries in mixed RTL/LTR text need the directional level
+ caret affinity model from CSS Writing Modes Level 4; that lands in v1.1.

## Acceptance gate

The Arabic correctness criteria from the design spec are tested in
[`harfbuzz-core/src/jvmTest/.../ArabicCorrectnessTest.kt`](../../harfbuzz-core/src/jvmTest/kotlin/com/mohamedrejeb/harfbuzz/core/ArabicCorrectnessTest.kt):

- A multi-word Arabic sample shapes as a single RTL run with positive advance.
- Lam-alef collapses to a single ligated glyph.
- Tashkeel marks attach as positioned glyphs (not their own runs).
- Mixed Latin/Arabic paragraphs split into multiple runs in correct visual
  order.
- Arabic-Indic digits (١٢٣) resolve as AN.
- AUTO base direction follows the first strong character.
- OT feature toggles propagate.

All seven cases pass on macOS aarch64 against system GeezaPro.
