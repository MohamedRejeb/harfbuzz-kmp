package com.mohamedrejeb.harfbuzz.core

/**
 * Shape [text] with this font stack - the same paragraph-level entry point
 * as [HbFont.shapeParagraph], but each cluster the [HbFontStack.primary]
 * font cannot resolve is re-shaped against the [HbFontStack.fallbacks] in
 * order. Output [ShapedParagraph.runs] preserve visual order; each run's
 * [ShapedRun.font] identifies the font that resolved its cluster span,
 * which the renderer needs because glyph ids are font-specific.
 *
 * The algorithm:
 *
 * 1. Resolve BiDi runs against [text] (UAX #9).
 * 2. For each direction-run, shape it with [HbFontStack.primary].
 * 3. Walk the resulting glyphs to find every cluster whose glyphs are
 *    all `.notdef` (id `0`). Adjacent notdef clusters merge into a
 *    single notdef interval over the source text.
 * 4. For each notdef interval, recursively shape that substring against
 *    `fonts.drop(1)`. If the chain runs dry, the last attempted font's
 *    `.notdef` glyph wins - no errors thrown.
 * 5. Stitch the resolved and fallback sub-runs back together in visual
 *    order (RTL runs reverse the source-order list).
 *
 * Performance: when [HbFontStack.fallbacks] is empty this delegates
 * directly to [HbFont.shapeParagraph] and only re-tags every run's
 * [ShapedRun.font] - no extra shaping work happens. Otherwise each
 * direction-run does at minimum one extra `font.shape()` call when
 * fallback is needed.
 *
 * @param fontRuns Authored font assignments over ranges of [text]
 *   (UTF-16 offsets). Each range's font is tried first for that range;
 *   glyphs it cannot resolve fall back through the stack's fallbacks
 *   and system resolution exactly like the primary does. Out-of-range
 *   runs are clamped, empty or inverted runs ignored, and overlaps
 *   resolve last-wins. Authored boundaries add segmentation points on
 *   top of the BiDi and coverage segmentation; every segment is shaped
 *   with the surrounding text as HarfBuzz pre/post context, so Arabic
 *   joining forms survive a boundary in the middle of a word. The
 *   caller retains ownership of the fonts, same as the stack fonts.
 */
public suspend fun HbFontStack.shapeParagraph(
    text: String,
    sizePx: Float,
    baseDirection: HbDirection = HbDirection.AUTO,
    features: List<HbFeature> = emptyList(),
    language: HbLanguage = HbLanguage.AUTO,
    fontRuns: List<FontRun> = emptyList(),
): ShapedParagraph {
    val systemResolver = systemResolverOrNull()

    // Boring fast path. Latin LTR-only text + no fallback chain + LTR
    // (or auto) base direction routes to one shape call, no BiDi resolver,
    // no cluster walk. Plain UI labels (the 90% case) skip the entire
    // paragraph orchestration pipeline.
    //
    // The probe shape is only kept when the primary resolved every glyph
    // (or there is no system resolver, in which case the full path would
    // reproduce the same notdefs anyway). A primary with no Latin coverage
    // (e.g. an Arabic-only decorative font) shaping English text produces
    // all-notdef output; returning that early would silently skip the
    // system fallback that the non-boring path consults.
    // Both fast paths emit single-font output, so any authored run
    // disables them and routes through the segmenting path below.
    if (
        fontRuns.isEmpty() &&
        text.isNotEmpty() &&
        fallbacks.isEmpty() &&
        baseDirection != HbDirection.RTL &&
        isBoringText(text)
    ) {
        val fast = HbBuffer().use { buf ->
            val run = shapeOnce(text, primary, sizePx, isRtl = false, language, features, buf)
            if (systemResolver != null && run.glyphs.any { it.glyphId == 0 }) {
                null // Primary lacks coverage - fall through to the full path.
            } else {
                ShapedParagraph(
                    runs = listOf(run),
                    baseDirection = HbDirection.LTR,
                    totalAdvance = run.totalAdvance,
                    ink = run.ink,
                    logical = HbRect(0f, 0f, run.totalAdvance, 0f),
                    logicalToVisual = identityIntArray(text.length),
                    visualToLogical = identityIntArray(text.length),
                )
            }
        }
        if (fast != null) return fast
    }

    if (fontRuns.isEmpty() && fallbacks.isEmpty() && systemResolver == null) {
        // Fast path: no fallback chain and no system resolver. Delegate to
        // single-font shaping and just tag every run with the primary font.
        val p = primary.shapeParagraph(text, sizePx, baseDirection, features, language)
        return p.copy(runs = p.runs.map { it.copy(font = primary) })
    }

    if (text.isEmpty()) {
        return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)
    }

    val normalizedRuns = normalizeFontRuns(fontRuns, text.length)

    val resolver = BidiResolver()
    val bidiRuns = resolver.resolve(text, baseDirection)
    if (bidiRuns.isEmpty()) {
        return ShapedParagraph.EMPTY.copy(baseDirection = baseDirection)
    }

    // UAX#9 P2/P3: the paragraph direction is the first STRONG character's, not
    // the first resolved run's (a leading number run would mis-resolve a line
    // like "08:00 PM" or "١٢٣ مرحبا").
    val resolvedDir = resolver.baseDirectionOf(text, baseDirection)

    // Match the visual-ordering pragma in ShapingHelpers: for an RTL base
    // we reverse the logical-order list. Proper UAX #9 L1-L4 visual
    // reordering is not yet implemented; this mirrors what single-font
    // shapeParagraph does today.
    val visualOrder = if (resolvedDir == HbDirection.RTL) bidiRuns.reversed() else bidiRuns

    return HbBuffer().use { buf ->
        val outRuns = ArrayList<ShapedRun>(visualOrder.size)
        var totalAdvance = 0f
        var inkLeft = Float.POSITIVE_INFINITY
        var inkTop = Float.POSITIVE_INFINITY
        var inkRight = Float.NEGATIVE_INFINITY
        var inkBottom = Float.NEGATIVE_INFINITY

        for (run in visualOrder) {
            if (run.end <= run.start) continue
            val segments = segmentRange(run.start, run.end, normalizedRuns)
            // Visual order within an RTL bidi run flows right-to-left, so
            // authored segments are emitted in reverse source order there.
            val visualSegments = if (run.isRtl) segments.asReversed() else segments

            for (seg in visualSegments) {
                val segText = text.substring(seg.start, seg.end)
                if (segText.isEmpty()) continue
                val segFonts = if (seg.font != null) {
                    buildList(fonts.size + 1) {
                        add(seg.font)
                        addAll(fonts)
                    }
                } else {
                    fonts
                }

                val pieces = shapeRunWithFallback(
                    text = segText,
                    isRtl = run.isRtl,
                    language = language,
                    features = features,
                    fonts = segFonts,
                    systemResolver = systemResolver,
                    sizePx = sizePx,
                    buffer = buf,
                    contextText = if (normalizedRuns.isEmpty()) null else text,
                    contextOffset = seg.start,
                )

                for (piece in pieces) {
                    // Offset clusters from segment-relative to paragraph-relative.
                    val rebased = piece.copy(
                        glyphs = piece.glyphs.map { it.copy(cluster = it.cluster + seg.start) },
                    )
                    if (!rebased.ink.isEmpty) {
                        inkLeft = minOf(inkLeft, totalAdvance + rebased.ink.left)
                        inkTop = minOf(inkTop, rebased.ink.top)
                        inkRight = maxOf(inkRight, totalAdvance + rebased.ink.right)
                        inkBottom = maxOf(inkBottom, rebased.ink.bottom)
                    }
                    outRuns.add(rebased)
                    totalAdvance += rebased.totalAdvance
                }
            }
        }

        val ink = if (inkRight > inkLeft) HbRect(inkLeft, inkTop, inkRight, inkBottom) else HbRect.EMPTY
        val n = text.length

        ShapedParagraph(
            runs = outRuns,
            baseDirection = resolvedDir,
            totalAdvance = totalAdvance,
            ink = ink,
            logical = HbRect(0f, 0f, totalAdvance, 0f),
            // First-cut maps mirror what ShapingHelpers does today; richer
            // visual-order maps will arrive when full visual reordering lands.
            logicalToVisual = identityIntArray(n),
            visualToLogical = identityIntArray(n),
        )
    }
}

/**
 * Shape a single direction-run, using [fonts] as a fallback chain and
 * optionally [systemResolver] as a final layer for codepoints no font
 * in the chain covers. Returns the run as one or more [ShapedRun]s in
 * visual order, each tagged with the font that produced it. Cluster
 * ids in returned runs are relative to [text] (offset by zero) - the
 * caller offsets them up to paragraph coordinates.
 *
 * Recursion bottoms out when only one font remains AND [systemResolver]
 * is null: that font's shape output (notdefs and all) is the result.
 * When a system resolver IS available, any cluster the chain leaves
 * unresolved is re-shaped against a system-resolved font (or stays
 * as the chain's notdef glyph if the system has nothing either).
 */
private suspend fun shapeRunWithFallback(
    text: String,
    isRtl: Boolean,
    language: HbLanguage,
    features: List<HbFeature>,
    fonts: List<HbFont>,
    systemResolver: SystemFontResolver?,
    sizePx: Float,
    buffer: HbBuffer,
    /**
     * How many system-resolved fonts this interval has already been
     * re-shaped through. A notdef interval can mix scripts (emoji
     * followed by Thai, Latin next to emoji on a wrapped line) so one
     * system font is rarely the final word — each recursion level
     * resolves the interval's next leading script. Capped by
     * [MAX_SYSTEM_FALLBACK_DEPTH] so a font that covers an interval's
     * first codepoint but never its full first cluster (e.g. missing a
     * combining mark) can't recurse forever.
     */
    systemDepth: Int = 0,
    /**
     * Non-null when this segment is a slice of a larger paragraph whose
     * surrounding characters should act as HarfBuzz pre/post context
     * (authored font runs). [contextOffset] is [text]'s absolute start
     * inside it. Null preserves the legacy standalone-slice behaviour.
     */
    contextText: String? = null,
    contextOffset: Int = 0,
): List<ShapedRun> {
    if (text.isEmpty() || fonts.isEmpty()) return emptyList()
    val primary = fonts.first()
    val primaryRun = shapeOnce(text, primary, sizePx, isRtl, language, features, buffer, contextText, contextOffset)

    // Bottom of the explicit chain AND no system resolver → accept primary.
    if (fonts.size == 1 && systemResolver == null) return listOf(primaryRun)

    // For each cluster, did EVERY glyph resolve (id != 0)? The AND matters:
    // HarfBuzz substitutes default-ignorable codepoints (VS16, ZWJ, ...)
    // with the font's invisible glyph - a nonzero gid with zero advance -
    // so an emoji sequence like U+2764 U+FE0F shaped with a non-emoji
    // primary yields [notdef, invisible] in one cluster. Any-glyph-resolved
    // (OR) semantics would call that cluster covered and pin it to the
    // primary, rendering the heart as a blank notdef instead of falling
    // back. A notdef is never emitted for an ignorable, so any notdef in
    // the cluster means some real codepoint is uncovered.
    val clusterHasGlyph = HashMap<Int, Boolean>()
    for (g in primaryRun.glyphs) {
        clusterHasGlyph[g.cluster] = (clusterHasGlyph[g.cluster] != false) && g.glyphId != 0
    }

    if (clusterHasGlyph.isEmpty() || clusterHasGlyph.values.all { it }) {
        // Empty input → no glyphs (shouldn't happen, text non-empty above);
        // or every cluster resolved → primary covers it all.
        return listOf(primaryRun)
    }

    // Walk clusters in source order, building [start, end, isNotdef) intervals.
    // Each cluster `c` covers source positions [c .. nextClusterStart) (or
    // [c .. text.length) for the last cluster).
    val sortedClusters = clusterHasGlyph.keys.sorted()
    val intervals = ArrayList<IntervalRange>(sortedClusters.size)
    for (i in sortedClusters.indices) {
        val start = sortedClusters[i]
        val end = if (i + 1 < sortedClusters.size) sortedClusters[i + 1] else text.length
        if (end <= start) continue
        intervals.add(IntervalRange(start, end, clusterHasGlyph[start] != true))
    }

    // Defensive: HarfBuzz normally covers every input position with some
    // cluster, but if there's an unexpected gap before the first cluster
    // (rare; e.g. with malformed input) we skip it - an unshaped span has
    // no glyphs anyway.
    if (intervals.isEmpty()) return listOf(primaryRun)

    // Merge adjacent same-status intervals.
    val merged = ArrayList<IntervalRange>(intervals.size)
    for (iv in intervals) {
        val last = merged.lastOrNull()
        if (last != null && last.isNotdef == iv.isNotdef && last.end == iv.start) {
            merged[merged.lastIndex] = IntervalRange(last.start, iv.end, last.isNotdef)
        } else {
            merged.add(iv)
        }
    }

    // Source-order list of sub-runs (each tagged with the font that resolved it).
    // For RTL we reverse at the end so visual order matches.
    val sourceOrder = ArrayList<ShapedRun>(merged.size)
    for ((start, end, isNotdef) in merged) {
        val sub = text.substring(start, end)
        val pieces = when {
            !isNotdef -> {
                // Re-shape the resolved interval on its own. Re-shaping (rather
                // than slicing primaryRun) keeps the algorithm simple and lets
                // HarfBuzz pick the right script per-substring; the cost is one
                // extra shape() per resolved interval which is negligible for
                // the typical mostly-resolved-or-fully-unresolved input.
                listOf(shapeOnce(sub, primary, sizePx, isRtl, language, features, buffer, contextText, contextOffset + start))
            }
            fonts.size > 1 -> {
                // Recurse into the next font in the explicit chain. The system
                // resolver propagates so it remains the final fallback even
                // through nested recursion.
                shapeRunWithFallback(
                    text = sub,
                    isRtl = isRtl,
                    language = language,
                    features = features,
                    fonts = fonts.drop(1),
                    systemResolver = systemResolver,
                    sizePx = sizePx,
                    buffer = buffer,
                    systemDepth = systemDepth,
                    contextText = contextText,
                    contextOffset = contextOffset + start,
                )
            }
            systemResolver != null -> {
                // Explicit chain exhausted - ask the platform for a font that
                // covers the first codepoint of this notdef interval. We bias
                // by `start` because clusters are codepoint-indexed and most
                // multi-codepoint clusters (graphemes, ligatures) share the
                // same script and therefore the same covering font.
                val firstCp = firstCodePoint(sub)
                val sysFont = systemResolver.fontFor(
                    firstCp,
                    emojiPresentation = hasEmojiPresentationSelector(sub, firstCp),
                )
                if (sysFont != null && systemDepth < MAX_SYSTEM_FALLBACK_DEPTH) {
                    // The system font covers the interval's FIRST codepoint,
                    // but a wrapped or mixed-script interval can carry more
                    // scripts than one font (emoji then Thai; Latin then
                    // emoji on a line the primary covers none of). Recurse
                    // with the system font as the interval's primary so its
                    // own notdefs resolve through the next system font
                    // instead of rendering invisible.
                    shapeRunWithFallback(
                        text = sub,
                        isRtl = isRtl,
                        language = language,
                        features = features,
                        fonts = listOf(sysFont),
                        systemResolver = systemResolver,
                        sizePx = sizePx,
                        buffer = buffer,
                        systemDepth = systemDepth + 1,
                        contextText = contextText,
                        contextOffset = contextOffset + start,
                    )
                } else if (sysFont != null) {
                    // Depth cap reached - accept this font's shape as-is.
                    listOf(shapeOnce(sub, sysFont, sizePx, isRtl, language, features, buffer, contextText, contextOffset + start))
                } else {
                    // System has nothing either - fall back to the primary's
                    // notdef glyphs for this interval (already shaped above as
                    // part of primaryRun, but re-shape the slice to keep the
                    // sub-run output consistent and font-tagged correctly).
                    listOf(shapeOnce(sub, primary, sizePx, isRtl, language, features, buffer, contextText, contextOffset + start))
                }
            }
            else -> {
                // Tail of the chain with no system fallback → keep notdefs.
                listOf(shapeOnce(sub, primary, sizePx, isRtl, language, features, buffer, contextText, contextOffset + start))
            }
        }
        for (p in pieces) {
            sourceOrder.add(p.copy(glyphs = p.glyphs.map { it.copy(cluster = it.cluster + start) }))
        }
    }

    return if (isRtl) sourceOrder.asReversed() else sourceOrder
}

private suspend fun shapeOnce(
    text: String,
    font: HbFont,
    sizePx: Float,
    isRtl: Boolean,
    language: HbLanguage,
    features: List<HbFeature>,
    buffer: HbBuffer,
    contextText: String? = null,
    contextOffset: Int = 0,
): ShapedRun {
    buffer.reset()
    if (contextText != null) {
        buffer.setTextWithContext(contextText, contextOffset, text.length)
    } else {
        buffer.text = text
    }
    buffer.direction = if (isRtl) HbDirection.RTL else HbDirection.LTR
    // Leave script as default so HarfBuzz auto-detects per substring (matches
    // the single-font ShapingHelpers pragma).
    buffer.language = language
    buffer.features = features
    val run = font.shape(buffer, sizePx).copy(font = font)
    if (contextText == null || contextOffset == 0) return run
    // Context shaping reports clusters relative to contextText; bring them
    // back to slice-relative so the caller's rebase arithmetic is uniform.
    return run.copy(glyphs = run.glyphs.map { it.copy(cluster = it.cluster - contextOffset) })
}

private data class IntervalRange(val start: Int, val end: Int, val isNotdef: Boolean)

/**
 * Cheap predicate for the boring fast path: returns `true` when [text] is
 * guaranteed to need no BiDi resolution and no script-aware shaping
 * orchestration - i.e., every code unit is in the BMP (no surrogates) and
 * below `U+0590` (the start of the Hebrew block, after which the BiDi
 * algorithm has real work to do).
 *
 * Conservatively excludes Latin Extended Additional, IPA, combining
 * diacritics, etc. (all `< 0x590`, but a few include codepoints HarfBuzz
 * still wants script context for) is **not** done here on purpose: the
 * cutoff is chosen to match what Skia's `BoringLayout.isBoring` excludes,
 * and combining marks shape correctly under default script auto-detection.
 *
 * `O(text.length)` worst case; bails on the first non-boring char.
 */
internal fun isBoringText(text: String): Boolean {
    for (i in text.indices) {
        val ch = text[i]
        val cp = ch.code
        if (cp >= 0x0590) return false
        if (ch.isHighSurrogate() || ch.isLowSurrogate()) return false
    }
    return true
}

/**
 * First Unicode codepoint of [text]. Manually decodes a leading
 * UTF-16 surrogate pair so this works on every Kotlin target -
 * [String.codePointAt] is JVM-only.
 */
private fun firstCodePoint(text: String): Int {
    val high = text[0]
    if (high.isHighSurrogate() && text.length > 1) {
        val low = text[1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}

/**
 * Max system fonts one notdef interval recurses through. Real intervals
 * mix at most a handful of scripts; the cap only exists so a font that
 * covers an interval's first codepoint but never completes its first
 * cluster can't recurse unboundedly.
 */
private const val MAX_SYSTEM_FALLBACK_DEPTH = 4

/** VS16 - "render the preceding character with emoji presentation". */
private const val EMOJI_PRESENTATION_SELECTOR = 0xFE0F

/**
 * `true` when [firstCp] (the code point at the start of [text]) is
 * immediately followed by VS16 - a text-default symbol explicitly
 * qualified as emoji. Passed to the system resolver so it prefers a
 * color emoji font even for codepoints outside the emoji blocks
 * (arrows, ™, ⌚-style dingbats).
 */
private fun hasEmojiPresentationSelector(text: String, firstCp: Int): Boolean {
    val next = if (firstCp > 0xFFFF) 2 else 1
    return text.length > next && text[next].code == EMOJI_PRESENTATION_SELECTOR
}
