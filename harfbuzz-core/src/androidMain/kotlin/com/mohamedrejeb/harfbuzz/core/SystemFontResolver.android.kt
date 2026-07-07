package com.mohamedrejeb.harfbuzz.core

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.graphics.text.TextRunShaper
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * BCP-47 script subtag for "Symbols-Emoji" (ISO-15924 `Zsye`). Lowercased
 * to match how [readLocaleList] normalises tags. Used as a tiebreaker so
 * the OS's canonical emoji font (which advertises `und-Zsye`) wins over
 * other color-bearing fonts (vendor flags, animal stickers, etc.).
 */
private const val EMOJI_SCRIPT_TAG: String = "zsye"

/**
 * How many top-ranked pending candidates to predict per codepoint when
 * pre-fetching files for [AndroidSystemFontResolver.prewarm]. The first
 * pre-ranked entry usually covers the codepoint, but a small margin
 * absorbs the case where it misses and the walk falls through to the
 * next candidate. Going higher wastes I/O on candidates the cover walk
 * never touches.
 */
private const val PREDICT_FILES_PER_CODEPOINT: Int = 2

/**
 * Android system-font resolver backed by `SystemFonts.getAvailableFonts()`
 * (API 29+). Each `android.graphics.fonts.Font` returned by the platform
 * exposes:
 *   - a backing [java.io.File] (used to load bytes into HarfBuzz),
 *   - a `FontStyle(weight, slant)` style descriptor,
 *   - a [android.os.LocaleList] of the locales the font was designed for.
 *
 * **Resolution order.** On API 31+ each cache-miss codepoint is first
 * resolved through the platform text stack itself ([TextRunShaper], see
 * [PlatformFontPicker]): minikin tells us the exact font file it would
 * use, which is what Compose Text renders with - deterministic, OEM- and
 * updatable-font-aware, no heuristics. The ranked walk below is the
 * fallback for API 29-30 and for codepoints the platform can't cover.
 * The one exception is emoji when the app ships `emoji2-bundled`: the
 * bundled face wins over the platform pick so HarfBuzz Text matches what
 * EmojiCompat substitutes into Compose Text (see [EmojiCompatBridge]).
 *
 * Combined with HarfBuzz's color-table queries, the heuristic walk gives
 * us the same ranking richness the JVM resolver achieves on desktop:
 *   1. **Color-emoji bias** - when the codepoint is in a known emoji
 *      block and `preferColorEmoji = true`, color-bearing fonts (with
 *      `COLR`/`CPAL`/`SVG ` tables) rank above monochrome ones.
 *   2. **Language match** - fonts whose locale list (or path-name
 *      heuristics) overlap with `match.languages` rank higher.
 *   3. **Italic match** - same italic flag as the requested style wins.
 *   4. **Closest weight** - smallest |fontWeight − requestedWeight|.
 *
 * **Lazy loading.** The platform exposes 200+ system fonts on most
 * devices, so reading every `.ttf`/`.otf` into memory and minting an
 * [HbFace] for each on the first fallback query freezes the UI for
 * seconds. Instead this resolver collects each font's *metadata* at
 * construction (file + weight + italic + locales - no I/O) and only
 * opens individual files when [fontFor] needs to probe coverage.
 * The first miss for a script typically loads 1-3 faces; everything
 * else stays on disk.
 *
 * On API < 29 we return `null` from [createSystemFontResolver]; callers
 * should rely on explicit [HbFontStack.fallbacks] on those devices.
 */
internal class AndroidSystemFontResolver(
    private val match: SystemFallback.Match,
    private val pendingInit: List<PendingCandidate> = emptyList(),
    /**
     * Optional in-memory emoji font seeded ahead of disk I/O. Used to
     * inject `androidx.emoji2:emoji2-bundled`'s `NotoColorEmojiCompat.ttf`
     * so HarfBuzz Text and Compose Text agree on which emoji glyphs to
     * draw - see [EmojiCompatBridge]. `null` when the consuming app
     * doesn't ship the bundled variant; the resolver then falls back to
     * the OS's emoji font (ranked first via the `und-Zsye` locale tag).
     */
    bootstrapEmojiBytes: ByteArray? = null,
) : SystemFontResolver {

    // [HbFontStack.systemResolverOrNull] normally resolves the null default
    // to the primary's style before constructing the resolver. When the
    // resolver is constructed directly (tests, custom integrations), fall
    // back to the registered defaults - same shape `Match()` had before
    // primary-inheritance was introduced.
    private val style: FontStyleHint = match.style ?: FontStyleHint()

    /**
     * Lightweight, pre-load metadata for one platform font. Holding only
     * primitive fields + a [File] keeps this list cheap to keep around
     * for every system font; [load] is what touches the disk.
     */
    internal data class PendingCandidate(
        val file: File,
        val weight: Int,
        val italic: Boolean,
        /** Best-effort guess from the file name - refined to truth after load. */
        val likelyHasColor: Boolean,
        /** BCP-47 primary subtags inferred from `Font.getLocaleList`. */
        val languageHints: Set<String>,
        /** Face index inside a `.ttc` collection; 0 for plain `.ttf`/`.otf`. */
        val ttcIndex: Int = 0,
    )

    private class Loaded(
        val face: HbFace,
        val meta: PendingCandidate,
        /** Authoritative color check via HarfBuzz tables, set at load time. */
        val hasColor: Boolean,
    ) {
        /**
         * Single sizeless [HbFont] minted on first cover-walk and reused
         * for every subsequent resolve against this face. HbFont is now
         * sizeless so the same instance shapes at any caller-supplied
         * sizePx.
         */
        var sizelessFont: HbFont? = null
    }

    /** Pre-ranked metadata queue; `pending[0]` is the best candidate to try first. */
    private var pending: ArrayDeque<PendingCandidate> = ArrayDeque(pendingInit)
    /** Faces successfully loaded so far, in load order. */
    private val loaded: MutableList<Loaded> = ArrayList()
    /** `(codepoint, italic) → loaded?`, with `null` meaning "we exhausted everything". */
    private val resolved: HashMap<Long, Loaded?> = HashMap()

    /**
     * BCP-47 primary subtags requested by [match], pre-computed once.
     * Used by both the loaded-rank comparator and the pending-rank
     * comparator so neither has to re-derive it per call.
     */
    private val requestedLanguages: Set<String> =
        match.languages.map { primaryTag(it.bcp47) }.toSet()

    /**
     * Pre-built comparator with color-emoji bias on. Used when the
     * caller asked for color emoji AND the codepoint is in a known
     * emoji block (the only per-call variable that affects ranking).
     */
    private val rankLoadedPreferColor: Comparator<Loaded> = buildLoadedComparator(preferColor = true)

    /** Pre-built comparator without color-emoji bias. */
    private val rankLoadedPlain: Comparator<Loaded> = buildLoadedComparator(preferColor = false)

    private fun buildLoadedComparator(preferColor: Boolean): Comparator<Loaded> {
        val italic = style.italic
        val weight = style.weight
        val langs = requestedLanguages
        return Comparator { a, b ->
            if (preferColor) {
                val ac = if (a.hasColor) 0 else 1
                val bc = if (b.hasColor) 0 else 1
                if (ac != bc) return@Comparator ac - bc
            }
            if (langs.isNotEmpty()) {
                val al = if (a.meta.languageHints.any { it in langs }) 0 else 1
                val bl = if (b.meta.languageHints.any { it in langs }) 0 else 1
                if (al != bl) return@Comparator al - bl
            }
            val ai = if (a.meta.italic == italic) 0 else 1
            val bi = if (b.meta.italic == italic) 0 else 1
            if (ai != bi) return@Comparator ai - bi
            val dw = abs(a.meta.weight - weight) - abs(b.meta.weight - weight)
            if (dw != 0) return@Comparator dw
            // Deterministic total order. `SystemFonts.getAvailableFonts()`
            // iterates an IdentityHashMap, so its order is random on every
            // process launch; without a final tiebreaker the stable sort
            // preserves that randomness and the fallback winner changes
            // across sessions for codepoints many fonts cover.
            val byPath = a.meta.file.path.compareTo(b.meta.file.path)
            if (byPath != 0) return@Comparator byPath
            a.meta.ttcIndex - b.meta.ttcIndex
        }
    }

    /**
     * Holds the bundled emoji bytes until the first [fontFor] call (the
     * first suspend context we're in - `HbFace.toFont` is suspend so we
     * can't load this in `init`). Cleared after seeding.
     */
    private var pendingEmojiBootstrap: ByteArray? = bootstrapEmojiBytes

    /**
     * The seeded emoji2-bundled face, held separately from [loaded] so
     * emoji lookups can prefer it over the platform pick - EmojiCompat
     * substitutes this exact font into Compose Text, so parity requires
     * it to win even though minikin would pick the OS emoji font.
     */
    private var bundledEmoji: Loaded? = null

    /**
     * Lazily load [pendingEmojiBootstrap] as a color-emoji face and
     * prepend it to [loaded]. Called from [fontFor] on the first cache
     * miss so we run on a coroutine. Failures are swallowed - losing
     * the bundled font gracefully degrades to whatever the OS exposes.
     */
    private fun seedEmojiFontIfPending() {
        val bytes = pendingEmojiBootstrap ?: return
        pendingEmojiBootstrap = null
        val face = runCatching { HbFace.from { bytes(bytes) } }.getOrNull() ?: return
        // Synthetic metadata: high color/language priority, regular weight,
        // upright. The `file` field is unused here (no lazy-load happens
        // for already-loaded faces) - point it at a sentinel so debugging
        // makes it obvious this didn't come from disk.
        val meta = PendingCandidate(
            file = File("<emoji2-bundled>"),
            weight = 400,
            italic = false,
            likelyHasColor = true,
            languageHints = setOf(EMOJI_SCRIPT_TAG),
        )
        val l = Loaded(face = face, meta = meta, hasColor = true)
        bundledEmoji = l
        loaded.add(l)
    }

    private fun key(codepoint: Int, italic: Boolean): Long =
        (codepoint.toLong() and 0xFFFFFFFFL) or (if (italic) 1L shl 32 else 0L)

    override suspend fun fontFor(codepoint: Int): HbFont? {
        val italic = style.italic
        val cacheKey = key(codepoint, italic)
        if (resolved.containsKey(cacheKey)) {
            val cached = resolved[cacheKey] ?: return null
            return sizelessFont(cached)
        }

        // First-call seeding of the EmojiCompat-bundled font (if any). Done
        // here rather than in `init` because `HbFace.from` is not safe to
        // run inside `init` if the bytes haven't been fully decoded yet.
        seedEmojiFontIfPending()

        val preferColor = match.preferColorEmoji && isLikelyEmoji(codepoint)
        // Codepoint script gates the pending walk so an Arabic lookup
        // doesn't trigger a 25 MB NotoColorEmoji load just to discover
        // that emoji font has no Arabic glyphs. `null` means "no clear
        // bias" (Latin / COMMON / ambiguous) - fall back to FIFO.
        val scriptHints = codepointToScriptHints(codepoint)

        // 1) EmojiCompat parity: when the app ships emoji2-bundled, Compose
        // Text draws emoji with that font (EmojiCompat substitution), so it
        // must win over whatever minikin would pick.
        if (preferColor) {
            bundledEmoji?.let { l ->
                val font = sizelessFont(l)
                if (font.glyphIdForCodepoint(codepoint) != 0) {
                    resolved[cacheKey] = l
                    return font
                }
            }
        }

        // 2) Ask the platform (API 31+): minikin resolves the exact font
        // file the native text stack - and therefore Compose Text - would
        // use for this codepoint. Deterministic and OEM/updatable-font
        // aware; falls through to the heuristic walk below on older APIs
        // or when the platform only has a notdef. For emoji the pick must
        // also be drawable by our vector-only paint pipeline (see
        // [platformPickLoaded]) - otherwise the color-preferring walk
        // below finds a COLR/SVG cover instead.
        platformPickLoaded(codepoint, requireDrawableColor = preferColor)?.let { l ->
            resolved[cacheKey] = l
            return sizelessFont(l)
        }

        // 3) Walk already-loaded faces, ranked by post-load attributes.
        val orderedLoaded = loaded.sortedWith(
            if (preferColor) rankLoadedPreferColor else rankLoadedPlain,
        )
        for (l in orderedLoaded) {
            val font = sizelessFont(l)
            if (font.glyphIdForCodepoint(codepoint) != 0) {
                if (!preferColor || l.hasColor) {
                    resolved[cacheKey] = l
                    return font
                }
                // Mono cover with color preference: hold it and keep looking.
                val firstCover = drainPendingForColorCover(codepoint, scriptHints, l, font)
                resolved[cacheKey] = firstCover.first
                return firstCover.second
            }
        }

        // 4) Lazy-load from the pending queue, picking script-matching
        // candidates first when the codepoint has a clear script bias.
        while (pending.isNotEmpty()) {
            val meta = pickNextPending(scriptHints) ?: break
            val l = tryLoad(meta) ?: continue
            val font = sizelessFont(l)
            if (font.glyphIdForCodepoint(codepoint) != 0) {
                if (!preferColor || l.hasColor) {
                    resolved[cacheKey] = l
                    return font
                }
                val firstCover = drainPendingForColorCover(codepoint, scriptHints, l, font)
                resolved[cacheKey] = firstCover.first
                return firstCover.second
            }
        }

        // 5) Exhausted - cache the miss so we don't walk the chain again.
        resolved[cacheKey] = null
        return null
    }

    /**
     * Resolve [codepoint] through the platform text stack and return the
     * matching face, lazily loading it on first use. Returns `null` below
     * API 31, when minikin itself only has a notdef for the codepoint, or
     * when the picked font can't be opened - callers fall through to the
     * heuristic ranked walk.
     *
     * [requireDrawableColor] rejects picks our paint pipeline can't
     * rasterize: minikin happily returns bitmap-emoji fonts (CBDT/CBLC -
     * Samsung's emoji font, `NotoColorEmojiLegacy`, pre-13 `NotoColorEmoji`)
     * which have real cmap coverage and advances but no outlines and no
     * COLR paint tree, so accepting them shapes to *invisible* glyphs.
     * `Loaded.hasColor` is exactly the "COLR/SVG vector color" check the
     * heuristic walk already trusts, so rejecting here hands the codepoint
     * to that walk, which finds a drawable color cover instead.
     */
    private suspend fun platformPickLoaded(
        codepoint: Int,
        requireDrawableColor: Boolean = false,
    ): Loaded? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val picked = PlatformFontPicker.pick(
            codepoint = codepoint,
            style = style,
            languages = match.languages,
            emojiPresentation = requireDrawableColor,
        ) ?: return null
        val file = picked.file ?: return null
        val ttcIndex = picked.ttcIndex
        loaded.firstOrNull { it.meta.file == file && it.meta.ttcIndex == ttcIndex }?.let { l ->
            if (requireDrawableColor && !l.hasColor) return null
            return if (sizelessFont(l).glyphIdForCodepoint(codepoint) != 0) l else null
        }
        val idx = pendingIndexOf(file, ttcIndex)
        val meta = if (idx >= 0) pending.removeAt(idx) else metaFromPlatformFont(picked, file, ttcIndex)
        val l = tryLoad(meta) ?: return null
        if (requireDrawableColor && !l.hasColor) return null
        return if (sizelessFont(l).glyphIdForCodepoint(codepoint) != 0) l else null
    }

    private fun pendingIndexOf(file: File, ttcIndex: Int): Int =
        pending.indexOfFirst { it.file == file && it.ttcIndex == ttcIndex }

    /**
     * Metadata for a platform-picked font that isn't in the
     * `SystemFonts.getAvailableFonts()` snapshot (e.g. an updatable font
     * under `/data/fonts`). Derived from the platform [android.graphics.fonts.Font]
     * directly, so no extra I/O.
     */
    private fun metaFromPlatformFont(
        picked: android.graphics.fonts.Font,
        file: File,
        ttcIndex: Int,
    ): PendingCandidate = PendingCandidate(
        file = file,
        weight = picked.style.weight,
        italic = picked.style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC,
        likelyHasColor = likelyHasColorFromName(file.name),
        languageHints = readLocaleList(picked),
        ttcIndex = ttcIndex,
    )

    /** Identity of one face on disk - `.ttc` collections hold several. */
    private fun faceKey(file: File, ttcIndex: Int): String = "${file.path}#$ttcIndex"

    /**
     * Optimised prewarm: predict which platform font files the per-codepoint
     * cover walk would touch and load those files **in parallel** on
     * `Dispatchers.IO` - file read + `HbFace.from` parse + color check
     * all concurrent - before running the (now mostly trivial) sequential
     * walk.
     *
     * Why this wins. Sequential prewarm pays the cost of each cold load
     * (file read + HarfBuzz parse, ~100–150 ms per system font) one
     * after the other. Parallel loads let the OS schedule disk I/O
     * across multiple queues and the JVM run multiple `HbFace.from`
     * parses concurrently - HarfBuzz allows constructing independent
     * faces from multiple threads (per its threading contract). The
     * downstream sequential walk then mostly hits already-loaded faces
     * via the in-memory `loaded` list and skips both the I/O and parse.
     *
     * What we don't change. The resolver's mutable state (`pending`,
     * `loaded`, `resolved`) is still touched single-threaded - parallel
     * work happens entirely outside the suspend context that mutates
     * resolver state, and the merge step runs back on the caller's
     * dispatcher after `coroutineScope` resumes. The steady-state
     * `fontFor` path is unchanged.
     *
     * Prediction policy. For each codepoint we take the first 2
     * pending entries whose script hints match (or top-2 emoji-likely
     * for emoji codepoints, or top-2 non-emoji for unscripted Latin).
     * For codepoints with no script-matched candidate (vendor fonts
     * with empty `localeList`, e.g. Samsung's Arabic / Hebrew), we
     * fall back to the top-N pre-ranked entries - same files the
     * cover walk would otherwise probe sequentially.
     */
    override suspend fun prewarm(codepoints: IntArray) {
        seedEmojiFontIfPending()
        val predicted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) predictMetasViaPlatform(codepoints)
            else predictMetasForPrewarm(codepoints)
        if (predicted.isEmpty()) {
            for (cp in codepoints) fontFor(cp)
            return
        }
        // Parallel load: each candidate runs its file read + `HbFace.from`
        // + color check on `Dispatchers.IO`, so disk I/O and HarfBuzz
        // parsing happen concurrently. HarfBuzz allows constructing
        // independent faces from multiple threads (per its threading
        // contract - see HarfbuzzNative comment), so this is safe as
        // long as each `tryLoadStandalone` builds its own `HbFace`.
        val loadedFaces: List<Loaded> = coroutineScope {
            predicted.map { meta ->
                async(Dispatchers.IO) { tryLoadStandalone(meta) }
            }.awaitAll()
        }.filterNotNull()
        // Merge into resolver state. Safe because we're back on the
        // single-threaded suspend context that the caller invoked
        // `prewarm` on, and `loaded` / `pending` haven't been touched
        // since we read `predicted`. Dedupe `pending` so the sequential
        // walk doesn't re-load anything we already have.
        val mergedKeys = HashSet<String>(loadedFaces.size)
        for (l in loadedFaces) {
            loaded.add(l)
            mergedKeys.add(faceKey(l.meta.file, l.meta.ttcIndex))
        }
        if (mergedKeys.isNotEmpty()) {
            // ArrayDeque has no `removeIf`; rebuild it. `pending` typically
            // has ~200 entries, so this is a microsecond-scale rebuild.
            pending = ArrayDeque(pending.filter { faceKey(it.file, it.ttcIndex) !in mergedKeys })
        }
        // Sequential cover walk to populate `resolved` cache. Most
        // codepoints now hit the parallel-loaded faces in `loaded`
        // first; only codepoints whose cover wasn't predicted fall
        // through to the (now smaller) `pending` queue.
        for (cp in codepoints) fontFor(cp)
    }

    /**
     * Read + parse a candidate without touching resolver state. Mirrors
     * [tryLoad] except it doesn't add to [loaded]; the caller merges the
     * returned [Loaded] under the single-threaded suspend context.
     */
    private suspend fun tryLoadStandalone(meta: PendingCandidate): Loaded? {
        // mmap-backed: HarfBuzz reads the font directly from the
        // file's kernel-shared pages. No `readBytes()`, no JVM heap
        // copy, no malloc + memcpy in JNI - the font header pages in
        // on the first hb_face_create call and glyph outlines page in
        // lazily during shape.
        val face = runCatching { HbFace.from { path(meta.file.absolutePath, meta.ttcIndex) } }
            .getOrNull() ?: return null
        val hasColor = runCatching { face.hasDrawableColor() }.getOrDefault(meta.likelyHasColor)
        return Loaded(face = face, meta = meta, hasColor = hasColor)
    }

    /**
     * Predict the set of platform font files the per-codepoint cover
     * walk is most likely to open. Read-only on resolver state - does
     * **not** drain `pending`, so the subsequent sequential walk still
     * sees the full pre-ranked queue.
     *
     * Mirrors the script-bias logic of [pickNextPending]: emoji
     * codepoints prefer color-likely candidates, scripted non-emoji
     * codepoints prefer matching language hints (and skip emoji-only
     * fonts), and unscripted codepoints (Latin / COMMON / INHERITED)
     * fall back to the first non-emoji-only entry.
     */
    private fun predictMetasForPrewarm(codepoints: IntArray): List<PendingCandidate> {
        if (pending.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<PendingCandidate>()
        fun addIfNew(meta: PendingCandidate) {
            if (seen.add(faceKey(meta.file, meta.ttcIndex))) out.add(meta)
        }
        for (cp in codepoints) {
            val hints = codepointToScriptHints(cp)
            val isEmojiCp = hints != null && EMOJI_SCRIPT_TAG in hints
            val scriptMatches = pending.asSequence()
                .filter { meta ->
                    when {
                        hints == null -> !isEmojiOnlyFont(meta)
                        isEmojiCp -> meta.likelyHasColor || EMOJI_SCRIPT_TAG in meta.languageHints
                        else -> hints.any { h -> h in meta.languageHints } && !isEmojiOnlyFont(meta)
                    }
                }
                .take(PREDICT_FILES_PER_CODEPOINT)
                .toList()
            if (scriptMatches.isNotEmpty()) {
                for (m in scriptMatches) addIfNew(m)
            } else {
                // Vendor fonts (e.g. Samsung's Arabic / Hebrew / Devanagari /
                // Thai) often ship with no localeList, so the script-hint
                // filter returns nothing - but the cover walk still has to
                // probe them. Fall back to the top-N pre-ranked entries
                // (skipping emoji-only for non-emoji codepoints) so we
                // load what the walk actually opens.
                pending.asSequence()
                    .filter { meta -> isEmojiCp || !isEmojiOnlyFont(meta) }
                    .take(PREDICT_FILES_PER_CODEPOINT)
                    .forEach(::addIfNew)
            }
        }
        return out
    }

    /**
     * Platform-pick-driven prediction (API 31+): resolve each codepoint
     * via [PlatformFontPicker] (a shape call, no font I/O) and collect
     * the distinct files minikin chose, so [prewarm] parallel-loads
     * exactly what [fontFor]'s platform step will ask for. Skips
     * codepoints already covered by [bundledEmoji] or an already-loaded
     * face. Read-only on [pending] - the merge step after the parallel
     * load removes whatever got loaded.
     */
    private suspend fun predictMetasViaPlatform(codepoints: IntArray): List<PendingCandidate> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<PendingCandidate>()
        for (cp in codepoints) {
            val emojiCp = match.preferColorEmoji && isLikelyEmoji(cp)
            if (emojiCp) {
                val bundled = bundledEmoji
                if (bundled != null && sizelessFont(bundled).glyphIdForCodepoint(cp) != 0) continue
            }
            val picked = PlatformFontPicker.pick(
                codepoint = cp,
                style = style,
                languages = match.languages,
                emojiPresentation = emojiCp,
            ) ?: continue
            val file = picked.file ?: continue
            val ttcIndex = picked.ttcIndex
            if (loaded.any { it.meta.file == file && it.meta.ttcIndex == ttcIndex }) continue
            if (!seen.add(faceKey(file, ttcIndex))) continue
            val idx = pendingIndexOf(file, ttcIndex)
            out.add(if (idx >= 0) pending[idx] else metaFromPlatformFont(picked, file, ttcIndex))
        }
        return out
    }

    /**
     * Pick the next [PendingCandidate] to try, **preserving pre-rank
     * order** but skipping the one font category we can categorically
     * rule out: emoji-only fonts (`zsye` script tag, no other tags)
     * for non-emoji codepoints. Returns `null` when [pending] is empty.
     *
     * Rationale: An NotoColorEmoji-style font is ~25 MB and *cannot*
     * cover Arabic / CJK / Latin codepoints - its glyph table is
     * pictograms by design. Loading it during a non-emoji cold lookup
     * is pure waste. CJK / Arabic / etc. fonts, by contrast, *might*
     * cover unrelated codepoints via fallback tables, so we don't gate
     * on those - pre-rank order already decided which to try first.
     *
     * An earlier version of this helper aggressively gated on every
     * tagged-mismatched font and regressed Arabic cold-load on real
     * devices, because the first-pre-ranked Arabic cover was often a
     * multi-tagged font (e.g., `["ja","zh","und"]`) that actually
     * covers Arabic via OS fallback. Keeping the gate narrow to
     * "obviously emoji-only" avoids that regression while still
     * skipping the dominant 25 MB load.
     *
     * O(n) per pick; pending has ~200 entries on stock Android and we
     * expect ≤3 picks per cold lookup, so total work stays under a
     * millisecond - much smaller than even one font load.
     */
    private fun pickNextPending(scriptHints: Set<String>?): PendingCandidate? {
        if (pending.isEmpty()) return null
        if (scriptHints == null || EMOJI_SCRIPT_TAG in scriptHints) {
            // Codepoint is itself emoji or unscripted (Latin/COMMON) -
            // no point gating on the emoji-only category.
            return pending.removeFirst()
        }
        val firstAcceptableIdx = pending.indexOfFirst { meta ->
            !isEmojiOnlyFont(meta)
        }
        return if (firstAcceptableIdx >= 0) {
            pending.removeAt(firstAcceptableIdx)
        } else {
            pending.removeFirst()
        }
    }

    /**
     * `true` when [meta]'s only language hint is the emoji script tag
     * (`zsye`). Used by [pickNextPending] to skip NotoColorEmoji-style
     * fonts during non-emoji cold lookups. A font tagged with `zsye`
     * AND another script (rare but possible) doesn't qualify - we
     * keep it in line.
     */
    private fun isEmojiOnlyFont(meta: PendingCandidate): Boolean =
        meta.languageHints.size == 1 && meta.languageHints.first() == EMOJI_SCRIPT_TAG

    /**
     * Continue draining [pending] looking for a *color-bearing* font that
     * also covers [codepoint]. If we run out, fall back to the monochrome
     * cover the caller already has. Honors [scriptHints] when picking the
     * next candidate so the color-preference walk doesn't re-introduce
     * the unrelated-font load that the script-aware pick avoids.
     */
    private suspend fun drainPendingForColorCover(
        codepoint: Int,
        scriptHints: Set<String>?,
        firstCoverLoaded: Loaded,
        firstCoverFont: HbFont,
    ): Pair<Loaded, HbFont> {
        while (pending.isNotEmpty()) {
            val next = pickNextPending(scriptHints) ?: break
            val l = tryLoad(next) ?: continue
            val font = sizelessFont(l)
            if (font.glyphIdForCodepoint(codepoint) != 0 && l.hasColor) return l to font
        }
        return firstCoverLoaded to firstCoverFont
    }

    private suspend fun sizelessFont(l: Loaded): HbFont {
        l.sizelessFont?.let { return it }
        val font = l.face.toFont()
        l.sizelessFont = font
        return font
    }

    private suspend fun tryLoad(meta: PendingCandidate): Loaded? {
        // mmap the font via HarfBuzz's hb_blob_create_from_file_or_fail
        // so the single-threaded harfbuzz-bg dispatcher isn't pinned
        // reading 10–20+ MB system fonts (NotoColorEmoji.ttf,
        // NotoSansCJK.ttc) into a JVM ByteArray. The kernel pages in
        // the font header on hb_face_create and the glyph data lazily
        // during shape; no eager full-file read, no JVM heap copy.
        val face = runCatching { HbFace.from { path(meta.file.absolutePath, meta.ttcIndex) } }
            .getOrNull() ?: return null
        val hasColor = runCatching { face.hasDrawableColor() }.getOrDefault(meta.likelyHasColor)
        val l = Loaded(face = face, meta = meta, hasColor = hasColor)
        loaded.add(l)
        return l
    }

    override fun close() {
        for (l in loaded) {
            l.sizelessFont?.let { runCatching { it.close() } }
            l.sizelessFont = null
            runCatching { l.face.close() }
        }
        loaded.clear()
        bundledEmoji = null
        pending = ArrayDeque()
        resolved.clear()
    }
}

/**
 * Pre-rank pending candidates by everything we can know without opening
 * a file: name-based color guess (NotoColorEmoji etc.), italic match,
 * language overlap, then weight distance. The very first candidate will
 * usually cover the codepoint.
 */
private fun rankPending(
    pending: List<AndroidSystemFontResolver.PendingCandidate>,
    style: FontStyleHint,
    languages: List<HbLanguage>,
    preferColorBias: Boolean,
): List<AndroidSystemFontResolver.PendingCandidate> {
    val requestedLanguages = languages.map { primaryTag(it.bcp47) }.toSet()
    return pending.sortedWith(
        Comparator { a, b ->
            if (preferColorBias) {
                val ac = if (a.likelyHasColor) 0 else 1
                val bc = if (b.likelyHasColor) 0 else 1
                if (ac != bc) return@Comparator ac - bc
            }
            if (requestedLanguages.isNotEmpty()) {
                val al = if (a.languageHints.any { it in requestedLanguages }) 0 else 1
                val bl = if (b.languageHints.any { it in requestedLanguages }) 0 else 1
                if (al != bl) return@Comparator al - bl
            }
            val ai = if (a.italic == style.italic) 0 else 1
            val bi = if (b.italic == style.italic) 0 else 1
            if (ai != bi) return@Comparator ai - bi
            val dw = abs(a.weight - style.weight) - abs(b.weight - style.weight)
            if (dw != 0) return@Comparator dw
            // Deterministic total order - see the loaded-rank comparator in
            // [AndroidSystemFontResolver] for why this matters (the input
            // Set's iteration order is random per process).
            val byPath = a.file.path.compareTo(b.file.path)
            if (byPath != 0) return@Comparator byPath
            a.ttcIndex - b.ttcIndex
        },
    )
}

/**
 * Wraps [TextRunShaper] (API 31+) to ask minikin - the engine behind the
 * platform text stack and Compose Text - which system font it would use
 * for a codepoint. Using the platform's own matcher gives exact fallback
 * parity with native text rendering and sidesteps every ordering problem
 * in `SystemFonts.getAvailableFonts()` (which iterates an IdentityHashMap
 * and is randomly ordered per process). It also handles OEM fallback
 * chains and Android 13+ updatable fonts for free.
 *
 * API 31+ only ([TextRunShaper] doesn't exist before S) - every caller
 * must gate on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` before
 * touching this object.
 */
private object PlatformFontPicker {

    /** VS16 - "render the preceding character with emoji presentation". */
    private const val EMOJI_VARIATION_SELECTOR = 0xFE0F

    /**
     * Shape [codepoint] with a paint configured from [style] and
     * [languages], and return the font minikin picked. Returns `null`
     * when the platform only has a notdef for the codepoint - callers
     * should fall back to heuristics rather than render tofu from the
     * picked font. Any platform hiccup is swallowed to `null` for the
     * same reason.
     *
     * [emojiPresentation] appends VS16 to the probe. Text-default emoji
     * like U+2764 (red heart) itemize to a monochrome symbols font when
     * probed alone, but real input carries `U+2764 U+FE0F` and renders
     * from the emoji font - probing the same sequence keeps the pick
     * aligned with what the platform actually displays.
     */
    fun pick(
        codepoint: Int,
        style: FontStyleHint,
        languages: List<HbLanguage>,
        emojiPresentation: Boolean = false,
    ): android.graphics.fonts.Font? = runCatching {
        val base = Character.toChars(codepoint)
        val chars = if (emojiPresentation) {
            base + EMOJI_VARIATION_SELECTOR.toChar()
        } else {
            base
        }
        val paint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, style.weight, style.italic)
            val locales = languages.mapNotNull { lang ->
                runCatching { Locale.forLanguageTag(lang.bcp47) }.getOrNull()
                    ?.takeIf { it.language.isNotEmpty() }
            }
            if (locales.isNotEmpty()) textLocales = LocaleList(*locales.toTypedArray())
        }
        val glyphs = TextRunShaper.shapeTextRun(
            chars, 0, chars.size, 0, chars.size, 0f, 0f, false, paint,
        )
        if (glyphs.glyphCount() <= 0) return@runCatching null
        if (glyphs.getGlyphId(0) == 0) return@runCatching null
        glyphs.getFont(0)
    }.getOrNull()
}

/**
 * Every color format the draw pipeline can rasterize on this platform:
 * COLR v1 paint trees, COLR v0 layers, OT-SVG, and (via the paint
 * pipeline's PAINT_IMAGE ops) CBDT/sbix PNG bitmaps - the format of most
 * OS emoji fonts. Used both for emoji ranking and as the drawability
 * gate on platform-picked emoji fonts.
 */
private fun HbFace.hasDrawableColor(): Boolean =
    hasColorPaint() || hasColorLayers() || hasColorSvg() || hasColorPng()

/** Filename-based color guess so we can rank emoji-likely fonts first without loading them. */
private fun likelyHasColorFromName(name: String): Boolean {
    val n = name.lowercase()
    return "color" in n || "emoji" in n || "flag" in n
}

/**
 * Read [android.graphics.fonts.Font.getLocaleList] and reduce it to a
 * set of BCP-47 primary subtags. Empty when the font ships no locale
 * metadata.
 *
 * The platform's canonical emoji font is tagged `und-Zsye` ("undefined
 * language, emoji script"). The default `Locale.getLanguage()` strips
 * the script subtag, so we'd lose the emoji marker and fall back to
 * filename heuristics. Surface `zsye` explicitly when the script
 * subtag is present so the resolver's color/language ranking lands on
 * the OS's official emoji font first - this is what makes HarfBuzz
 * Text agree with Compose Text out of the box on devices without
 * `androidx.emoji2:emoji2-bundled`.
 */
private fun readLocaleList(font: android.graphics.fonts.Font): Set<String> {
    val list = runCatching { font.localeList }.getOrNull() ?: return emptySet()
    val tags = HashSet<String>(list.size())
    for (i in 0 until list.size()) {
        val locale = list[i] ?: continue
        val lang = locale.language.lowercase()
        if (lang.isNotEmpty()) tags.add(lang)
        val script = runCatching { locale.script.lowercase() }.getOrDefault("")
        if (script.isNotEmpty()) tags.add(script)
    }
    return tags
}

internal actual fun createSystemFontResolver(match: SystemFallback.Match): SystemFontResolver? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

    // Walk SystemFonts ONCE to build lightweight metadata. No file reads,
    // no HbFace creation - just primitives we can sort cheaply.
    val rawCandidates = SystemFonts.getAvailableFonts().mapNotNull { font ->
        val file = font.file ?: return@mapNotNull null
        val style = font.style
        val nameHints = inferLanguageHintsFromPath(file.nameWithoutExtension.lowercase())
        val platformLangs = readLocaleList(font)
        val hints = if (platformLangs.isNotEmpty()) platformLangs else nameHints
        AndroidSystemFontResolver.PendingCandidate(
            file = file,
            weight = style.weight,
            italic = style.slant == android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC,
            likelyHasColor = likelyHasColorFromName(file.name),
            languageHints = hints,
            ttcIndex = font.ttcIndex,
        )
    }

    // Pre-rank with a "color preferred" bias true so emoji-likely fonts come
    // first by default - the per-codepoint resolver re-ranks already-loaded
    // faces every call, so this initial bias only affects what gets loaded
    // first when the cache is cold.
    val effectiveStyle = match.style ?: FontStyleHint()
    val ranked = rankPending(rawCandidates, effectiveStyle, match.languages, preferColorBias = match.preferColorEmoji)

    // If the consuming app pulls in `androidx.emoji2:emoji2-bundled`, its
    // `NotoColorEmojiCompat.ttf` asset is bundled into the APK and we can
    // load the bytes here. Seeding the resolver with this font means
    // HarfBuzz Text and Compose Text agree on which emoji glyphs to draw.
    // Falls back to `null` when emoji2-bundled isn't on the classpath.
    val emojiBytes = HarfBuzzAppContext.get()?.let { ctx ->
        EmojiCompatBridge.bundledEmojiFontBytes(ctx)
    }

    return AndroidSystemFontResolver(match, ranked, bootstrapEmojiBytes = emojiBytes)
}
