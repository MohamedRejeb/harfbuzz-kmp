package com.mohamedrejeb.harfbuzz.core

/**
 * Shape [text] and produce only the metric bounds - no per-glyph paths,
 * no COLR, no SVG. The fast path that powers `rememberTextBounds` and
 * the bounds-only branch of `rememberPathTextBounds`.
 *
 * Returns the [ShapedParagraph] alongside the bounds so callers (notably
 * `rememberPathTextBounds`) can reuse the shape output to walk glyphs
 * along a path without re-shaping.
 *
 * Compared to `buildMeasured` / `MeasuredText`, this skips `parseFontPass`
 * entirely (per-glyph path SVG decode, COLR v0/v1 parsing, SVG-in-OT
 * slicing) and on Wasm avoids the heavy worker round-trip for the glyph
 * snapshot. For a typical multi-font Arabic+emoji paragraph this is
 * 10–50× faster - enough to run in a layout-pre-pass.
 */
public suspend fun HbFontStack.shapeOnlyBounds(
    text: String,
    baseDirection: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
): MeasuredBoundsResult {
    val paragraph = shapeParagraph(text, baseDirection, features, language)

    // Mirror the fallback rule from RememberMeasuredText.kt:163-165 so the
    // metrics here match what MeasuredText would compute.
    val ext = runCatching { primary.hExtents }.getOrNull()
    val ascent = ext?.ascender ?: (primary.pointSize * 0.8f)
    val descent = ext?.let { -it.descender } ?: (primary.pointSize * 0.2f)
    val lineGap = ext?.lineGap ?: 0f
    val baseline = ascent

    val advance = paragraph.totalAdvance
    val logical = paragraph.logical

    val bounds = MeasuredBounds(
        ink = paragraph.ink,
        logical = logical,
        advance = advance,
        ascent = ascent,
        descent = descent,
        lineGap = lineGap,
        baseline = baseline,
    )
    return MeasuredBoundsResult(bounds = bounds, paragraph = paragraph)
}
