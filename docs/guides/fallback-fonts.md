# Fallback fonts cookbook

A single font rarely covers every script your users will ever paste in.
`HbFontStack` lets you combine a primary font with an explicit fallback
chain - and, optionally, the host platform's installed fonts - so a
paragraph mixing Latin, Arabic, and emoji shapes correctly without
extra work at the call site.

---

## Tier 1 - explicit fallback chain

```kotlin
val stack = HbFontStack(
    primary = roboto,
    fallbacks = listOf(notoNaskhArabic, notoColorEmoji),
)
val measured = rememberMeasuredText("Hello مرحبا 👋", stack)
ShapedText(text = "Hello مرحبا 👋", fontStack = stack, ...)
```

The shaping algorithm:

1. Shape the text with `primary`.
2. For every cluster that comes back as `.notdef` (no glyph), re-shape
   that span against the next font in the chain.
3. Emit a `ShapedRun` per resolved span, tagged with the font that
   resolved it.

When the explicit chain is exhausted on a cluster, the last attempted
font's `.notdef` glyphs are kept (no exceptions thrown).

---

## Tier 2 - system fallback

```kotlin
HbFontStack(
    primary = roboto,
    fallbacks = listOf(notoNaskhArabic),
    system = SystemFallback.Match(
        style = FontStyleHint(weight = 400, italic = true),
        preferColorEmoji = true,
        languages = listOf(HbLanguage("ar")),
    ),
).use { stack ->
    val measured = rememberMeasuredText("…", stack)
    // ...
}
```

The `system` field reaches into the host platform's installed fonts
**only when both `primary` and `fallbacks` miss a cluster**. Coverage
matrix as of v0.1:

| Platform | Backed by                                          | Status        |
|----------|----------------------------------------------------|---------------|
| JVM      | Curated path list per OS (macOS / Linux / Windows) | Implemented   |
| Android  | `android.graphics.fonts.SystemFonts` (API 29+)     | Implemented   |
| iOS      | `CTFontCreateForString` cascade                    | Implemented   |
| Wasm     | Browser sandbox - no API                           | No-op         |

> `HbFontStack` implements `AutoCloseable`. With `system = Match` it
> owns the lazy resolver; calling `close()` releases every system font
> the resolver materialised. With `system = None` (the default) `close()`
> is a no-op and you don't have to call it.

---

## Picking between explicit and system fallbacks

| You should…                                | Reach for…                                  |
|--------------------------------------------|---------------------------------------------|
| Need exact reproducibility across machines | `fallbacks` only - bundle the fonts         |
| Ship a small app, use OS fonts everywhere  | `system = Match`, empty `fallbacks`         |
| Need predictable Arabic + flexible emoji   | Bundle Arabic in `fallbacks`, emoji via OS  |
| Target the web (Wasm)                      | `fallbacks` only - no system-font API       |

Bundled fallbacks always win when the codepoint is covered: the system
layer only fires after the explicit chain is exhausted.

---

## JVM specifics

The JVM resolver walks a **curated list of common system font paths**
per OS rather than enumerating every installed font. This keeps the
first-shape latency in the millisecond range - full directory scans
with cmap parsing are too slow without OT-table-only loading, which
isn't yet exposed through HarfBuzz on JVM.

### What the curated list covers (sample)

- **macOS** - Apple Color Emoji, GeezaPro / Damascus (Arabic), Arial
  Hebrew, PingFang / Hiragino / STHeiti (CJK), AppleSDGothicNeo
  (Korean), Kohinoor (Devanagari), Thonburi (Thai), Arial Unicode.
- **Linux** - Noto Color Emoji, Noto Naskh / Sans Arabic, Noto Sans
  Hebrew, Noto Sans CJK, Noto Sans Devanagari, Noto Sans Thai,
  DejaVu Sans.
- **Windows** - Segoe UI Emoji, Traditional / Simplified Arabic,
  David (Hebrew), Microsoft YaHei / JhengHei / Malgun Gothic / Yu
  Gothic, Mangal, Nirmala UI, Arial.

### When the curated list misses

If your users have fonts in non-standard locations (Homebrew under
`/opt/homebrew/share/fonts`, NixOS under `/nix/store/...`, Flatpak,
`~/.local/share/fonts`, mounted SMB shares), the curated list won't
find them. Add them at app startup:

```kotlin
import com.mohamedrejeb.harfbuzz.core.JvmExtraSystemFontPaths

fun init() {
    JvmExtraSystemFontPaths.add(
        File("/opt/homebrew/share/fonts/AdobeArabic-Regular.otf"),
        File(System.getProperty("user.home"), ".local/share/fonts/MyEmoji.ttf"),
    )
}
```

`JvmExtraSystemFontPaths.add` registered paths are checked **before**
the curated default list, so they take priority on a per-codepoint
basis.

### Falling back to bundled fonts when the curated list misses

For the most reliable cross-platform behaviour - especially on Wasm,
inside Docker containers, or on stripped-down minimal Linux installs -
ship the fonts you actually need with your app and put them in
`HbFontStack.fallbacks`:

```kotlin
val arabicBytes = readResource("fonts/NotoNaskhArabic-Regular.ttf")
val emojiBytes = readResource("fonts/NotoColorEmoji-Regular.ttf")

val stack = HbFontStack(
    primary = userFont,
    fallbacks = listOf(
        HbFace.from { bytes(arabicBytes) }.toFont(),
        HbFace.from { bytes(emojiBytes) }.toFont(),
    ),
    // System layer remains as a last-ditch safety net for codepoints
    // even the bundled fallbacks miss.
    system = SystemFallback.Match(),
)
```

---

## What the style hint does

`FontStyleHint(weight, italic, width)` is a *hint* - every platform
maps it to its own native font-style API on a best-effort basis:

- **iOS**: `italic = true` applies `kCTFontItalicTrait` to the base
  font that seeds `CTFontCreateForString`, so the OS cascade picks
  italic-coverage fallbacks where they exist (italic Arabic, italic
  Hebrew, etc.). Weight is inherited from the base font.
- **Android**: maps to `android.graphics.fonts.FontStyle(weight, slant)`
  for ranking. Closest-weight match within the requested italic group
  wins.
- **JVM**: italic is detected from path-name heuristics (`*-Italic.ttf`
  / `*-Oblique.ttf`). Weight isn't yet ranked - Tier 2 follow-up.

---

## When to skip the resolver entirely

`SystemFallback.None` (the default) makes the stack behave purely as a
Tier 1 explicit chain. Pick it when you:

- Only ever expect text the primary + bundled fallbacks cover.
- Need bit-for-bit identical output across every machine - system fonts
  vary by OS version, locale, and what the user has installed.
- Build for Wasm (resolver is a no-op there anyway, but `None` makes
  intent explicit).
