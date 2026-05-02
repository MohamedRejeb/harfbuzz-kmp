# Arc text

The `ArcText` composable lays out shaped text along a circular arc.

## Basic usage

```kotlin
@Composable
fun BookmarkSeal(font: HbFont) {
    ArcText(
        text = "بسم الله الرحمن الرحيم",
        font = font,
        radius = 120.dp,
        sweep = ArcSweep.Auto,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(320.dp),
    )
}
```

## Parameters

| Param | What |
|---|---|
| `radius` | Circle radius in `Dp`. Glyphs ride along this circle. |
| `startAngle` | Angle in degrees where the first glyph's center lands. `0f` means rightward (3 o'clock). |
| `sweep` | `ArcSweep.Auto` uses the natural shape advance; `ArcSweep.Fixed(d)` stretches glyph spacing so the text covers exactly `d` degrees. |
| `direction` | `Clockwise` or `CounterClockwise` - affects which way the angle accumulates. |
| `side` | `Outside` (text rides outside the circle) or `Inside` (rides on the inner edge with letterforms flipped vertically so they stay upright). |
| `alignment` | `Start` / `Center` / `End` - biases the start position relative to `startAngle`. |
| `features` | OT features applied at shape time. |
| `color` | Glyph fill color. |

## How it works

The composable shapes the text via `font.shapeParagraph(...)`, then for each
glyph:

1. Computes the cumulative arc length along the circle to the glyph's
   center: `cumulativeAdvance + glyphAdvance / 2`.
2. Converts to an angle: `angle = startAngle + arcLength / radius`.
3. Pulls the cached `Path` from `MeasuredText.glyphPaths`.
4. Applies a matrix: translate to `(cx + cos(angle)*r, cy + sin(angle)*r)`,
   rotate by `angle + 90°` so the glyph baseline is tangent to the circle,
   flip vertically if `side == Inside`.
5. `drawPath` per glyph.

Because per-glyph `Path` instances are cached on `MeasuredText`, redrawing
the same paragraph at a different `radius`, `startAngle`, or color is just
transforms - no re-extraction.

## Tips

- For arc text along a path that's not a full circle, use
  [`DrawScope.drawTextAlongPath`](../../harfbuzz-compose/src/commonMain/kotlin/com/mohamedrejeb/harfbuzz/compose/DrawScopeExtensions.kt)
  with an arbitrary `Path` (in v1.1).
- For Quranic-style outer rings, use `side = ArcSide.Outside` and a high
  point-size font so the letterforms stay legible at the curve.
- For inner-rim text on coins / seals, use `side = ArcSide.Inside` - the
  auto-flip keeps the letters readable instead of upside-down.
