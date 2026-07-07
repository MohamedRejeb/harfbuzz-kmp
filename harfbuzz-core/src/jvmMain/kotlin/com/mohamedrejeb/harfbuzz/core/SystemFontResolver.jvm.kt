package com.mohamedrejeb.harfbuzz.core

import java.io.File

/**
 * Pragmatic JVM system-font resolver. Walks a curated list of common
 * system font paths per OS - these cover ~90% of real-world fallback
 * needs (Arabic on macOS = Geeza Pro, color emoji = Apple Color Emoji,
 * etc.) without the multi-second cost of enumerating every installed
 * font's cmap on first use.
 *
 * Strategy on each [fontFor] call:
 *  1. Cache hit on `(codepoint, italic)` → return immediately.
 *  2. Walk already-loaded faces, return the first that covers the
 *     codepoint.
 *  3. Lazily load the next un-tried curated path until one covers,
 *     caching every loaded face.
 *  4. Cache `null` for codepoints no curated font covers.
 *
 * Lifecycle: every loaded [HbFace] / [HbFont] is owned by this resolver.
 * Closing the resolver (via [HbFontStack.close]) closes every cached
 * font so callers don't leak native HarfBuzz handles.
 */
internal class JvmSystemFontResolver(
    private val match: SystemFallback.Match,
    candidatePaths: List<File> = JvmSystemFontPaths.curated(),
) : SystemFontResolver {

    // [HbFontStack.systemResolverOrNull] normally resolves the null default
    // to the primary's style before constructing the resolver. When the
    // resolver is constructed directly (tests, custom integrations), fall
    // back to the registered defaults - same shape `Match()` had before
    // primary-inheritance was introduced.
    private val style: FontStyleHint = match.style ?: FontStyleHint()

    private class Loaded(
        val face: HbFace,
        /** True when the path-name suggests italic / oblique. */
        val italic: Boolean,
        /** True when the face ships any color glyph table (COLR, CPAL, SVG). */
        val hasColor: Boolean,
        /**
         * BCP-47 primary tags inferred from the path name (e.g. `"ar"`
         * for `GeezaPro.ttc`, `"zh"` for `PingFang.ttc`). Used to bias
         * ranking when [SystemFallback.Match.languages] is non-empty.
         */
        val languageHints: Set<String>,
    ) {
        /**
         * Single sizeless [HbFont] minted on first cover-walk and reused for
         * every subsequent resolve against this face. HbFont is now sizeless
         * so the same instance shapes at any caller-supplied sizePx; no
         * per-size cache needed.
         */
        var sizelessFont: HbFont? = null
    }

    /** Curated paths we have not yet attempted to load. */
    private val pending: ArrayDeque<File> = ArrayDeque(candidatePaths)
    /** Faces successfully loaded so far, in load order. */
    private val loaded: MutableList<Loaded> = ArrayList(candidatePaths.size)
    /** `(codepoint, italic) → loaded?`, with `null` meaning "we already
     * checked every curated font, none covers it". */
    private val resolved: HashMap<Long, Loaded?> = HashMap()

    /**
     * BCP-47 primary subtags requested by [match], pre-computed once
     * at construction so the per-codepoint comparator doesn't rebuild
     * the set on every cache miss.
     */
    private val requestedLanguages: Set<String> =
        match.languages.map { primaryTag(it.bcp47) }.toSet()

    /** Pre-built comparator with color-emoji bias on. */
    private val rankPreferColor: Comparator<Loaded> = buildRankComparator(preferColor = true)

    /** Pre-built comparator without color-emoji bias. */
    private val rankPlain: Comparator<Loaded> = buildRankComparator(preferColor = false)

    private fun key(codepoint: Int, italic: Boolean): Long =
        (codepoint.toLong() and 0xFFFFFFFFL) or (if (italic) 1L shl 32 else 0L)

    override suspend fun fontFor(codepoint: Int): HbFont? {
        val italic = style.italic
        val cacheKey = key(codepoint, italic)
        if (resolved.containsKey(cacheKey)) {
            val cached = resolved[cacheKey] ?: return null
            return sizelessFont(cached)
        }

        // Color-emoji bias: when the codepoint is in a known emoji range
        // and the user asked for color emoji, prefer color-bearing fonts
        // ahead of monochrome ones. Without this, on systems where both
        // a color emoji font and a (monochrome) symbols font cover the
        // same codepoint, the symbols font often wins by load order and
        // emoji come back as outline shapes.
        val preferColor = match.preferColorEmoji && isLikelyEmoji(codepoint)
        // Codepoint script gates the pending walk so an Arabic lookup
        // doesn't load Apple Color Emoji.ttc (188 MB on macOS) just to
        // discover it has no Arabic glyphs. `null` means "no clear bias"
        // - fall back to the curated path order.
        val scriptHints = codepointToScriptHints(codepoint)

        val orderedLoaded = loaded.sortedWith(if (preferColor) rankPreferColor else rankPlain)
        for (l in orderedLoaded) {
            val font = sizelessFont(l)
            if (font.glyphIdForCodepoint(codepoint) != 0) {
                resolved[cacheKey] = l
                return font
            }
        }

        // Lazy-load remaining curated paths, picking script-matching
        // candidates first based on filename heuristics. We can't see
        // color/italic flags until after load, but the path name is a
        // strong enough signal to skip the obviously-wrong fonts.
        while (pending.isNotEmpty()) {
            val path = pickNextPendingPath(scriptHints) ?: break
            val l = tryLoad(path) ?: continue
            val font = sizelessFont(l)
            // If we already have a color-capable cover for this codepoint
            // and the freshly-loaded font is monochrome, prefer the color
            // one. Otherwise accept the first cover.
            if (font.glyphIdForCodepoint(codepoint) != 0) {
                if (!preferColor || l.hasColor) {
                    resolved[cacheKey] = l
                    return font
                }
                // Monochrome cover but color preferred - keep loading
                // until we find a color one or exhaust the chain. We
                // remember the first cover so we can fall back to it.
                val firstCoverLoaded: Loaded = l
                val firstCoverFont: HbFont = font
                while (pending.isNotEmpty()) {
                    val next = pickNextPendingPath(scriptHints) ?: break
                    val nl = tryLoad(next) ?: continue
                    val nf = sizelessFont(nl)
                    if (nf.glyphIdForCodepoint(codepoint) != 0 && nl.hasColor) {
                        resolved[cacheKey] = nl
                        return nf
                    }
                }
                resolved[cacheKey] = firstCoverLoaded
                return firstCoverFont
            }
        }

        // Exhausted: no curated font covers it.
        resolved[cacheKey] = null
        return null
    }

    /**
     * Pick the next curated path to try, **preserving curated order**
     * but skipping emoji-only fonts when the codepoint isn't itself
     * emoji. Returns `null` when [pending] is empty. Mirrors the
     * same-named helper in the Android resolver.
     *
     * Apple Color Emoji.ttc on macOS is 188 MB and *cannot* cover
     * Arabic / CJK / Latin codepoints - its glyph table is pictograms
     * by design. Loading it during a non-emoji cold lookup is pure
     * waste. Other tagged fonts may still cover unrelated codepoints
     * via fallback tables, so we don't gate on those - curated order
     * already decided which to try first.
     *
     * Pending is short on JVM (≤14 curated paths) so the per-call
     * overhead is sub-microsecond.
     */
    private fun pickNextPendingPath(scriptHints: Set<String>?): File? {
        if (pending.isEmpty()) return null
        if (scriptHints == null || EMOJI_SCRIPT_TAG in scriptHints) {
            return pending.removeFirst()
        }
        val firstAcceptableIdx = pending.indexOfFirst { path ->
            val hints = inferLanguageHintsFromPath(path.nameWithoutExtension.lowercase())
            !(hints.size == 1 && hints.first() == EMOJI_SCRIPT_TAG)
        }
        return if (firstAcceptableIdx >= 0) {
            pending.removeAt(firstAcceptableIdx)
        } else {
            pending.removeFirst()
        }
    }

    private companion object {
        /** Mirror of [EMOJI_SCRIPT_TAG] in the Android resolver. */
        const val EMOJI_SCRIPT_TAG = "zsye"
    }

    private fun buildRankComparator(preferColor: Boolean): Comparator<Loaded> {
        val italic = style.italic
        val langs = requestedLanguages
        return Comparator { a, b ->
            // Color-bearing fonts win when the caller asked for them on
            // an emoji codepoint.
            if (preferColor) {
                val ac = if (a.hasColor) 0 else 1
                val bc = if (b.hasColor) 0 else 1
                if (ac != bc) return@Comparator ac - bc
            }
            // Language hint match: a font tagged with one of the requested
            // BCP-47 primary tags ranks above one without a match. Falls
            // back to a no-op when the caller didn't supply any languages.
            if (langs.isNotEmpty()) {
                val al = if (a.languageHints.any { it in langs }) 0 else 1
                val bl = if (b.languageHints.any { it in langs }) 0 else 1
                if (al != bl) return@Comparator al - bl
            }
            // Italic-ness last: same italic flag as the request wins.
            val ai = if (a.italic == italic) 0 else 1
            val bi = if (b.italic == italic) 0 else 1
            ai - bi
        }
    }

    /**
     * Get-or-mint the sizeless [HbFont] for [l]. With sizeless fonts a
     * single instance is reused for every resolve against this face;
     * callers pass sizePx per shape / glyph call.
     */
    private suspend fun sizelessFont(l: Loaded): HbFont {
        l.sizelessFont?.let { return it }
        val font = l.face.toFont()
        l.sizelessFont = font
        return font
    }

    private suspend fun tryLoad(path: File): Loaded? {
        // mmap the font via HarfBuzz's hb_blob_create_from_file_or_fail
        // (HbFaceSource.path) so the single-threaded harfbuzz-bg
        // dispatcher isn't pinned reading 10–187 MB font files (Apple
        // Color Emoji.ttc is the worst case on macOS) into a JVM
        // ByteArray. The kernel pages in the font header on
        // hb_face_create and the glyph data lazily during shape;
        // no eager full-file read, no JVM heap copy.
        val face = runCatching { HbFace.from { path(path.absolutePath) } }
            .getOrNull() ?: return null
        val nameLower = path.nameWithoutExtension.lowercase()
        val italic = "italic" in nameLower || "oblique" in nameLower
        // PNG counts: CBDT/sbix bitmap glyphs (Apple Color Emoji on macOS)
        // render via the paint pipeline's PAINT_IMAGE ops on JVM.
        val hasColor = runCatching {
            face.hasColorPaint() || face.hasColorLayers() || face.hasColorSvg() || face.hasColorPng()
        }.getOrDefault(false)
        val l = Loaded(
            face = face,
            italic = italic,
            hasColor = hasColor,
            languageHints = inferLanguageHintsFromPath(nameLower),
        )
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
        resolved.clear()
        pending.clear()
    }
}

/**
 * Curated list of common system font paths per OS. Not exhaustive -
 * deliberately picks the small handful of fonts that actually cover
 * the scripts most apps need (Arabic, CJK, emoji) without scanning
 * the entire font directory tree.
 */
internal object JvmSystemFontPaths {
    fun curated(): List<File> = curatedPaths().map(::File).filter { it.exists() }

    private fun curatedPaths(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os || "darwin" in os -> macPaths
            "windows" in os || "win" == os -> windowsPaths
            "linux" in os -> linuxPaths
            else -> emptyList()
        }
    }

    // Apple ships these with macOS by default - order matters: emoji and
    // language-coverage fonts come first so the resolver finds them
    // before scanning the more general families.
    private val macPaths = listOf(
        // ── Color emoji ──────────────────────────────────────────────
        "/System/Library/Fonts/Apple Color Emoji.ttc",
        // ── Arabic ───────────────────────────────────────────────────
        "/System/Library/Fonts/Supplemental/GeezaPro.ttc",
        "/System/Library/Fonts/Supplemental/Damascus.ttc",
        "/System/Library/Fonts/Supplemental/Al Bayan.ttc",
        "/System/Library/Fonts/Supplemental/Mishafi.ttc",
        // ── Hebrew ───────────────────────────────────────────────────
        "/System/Library/Fonts/Supplemental/Arial Hebrew.ttc",
        // ── CJK ──────────────────────────────────────────────────────
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
        "/System/Library/Fonts/STHeiti Medium.ttc",
        "/Library/Fonts/Apple LiSung Light.ttc",
        // ── Korean ───────────────────────────────────────────────────
        "/System/Library/Fonts/AppleSDGothicNeo.ttc",
        // ── Devanagari (Hindi etc.) ──────────────────────────────────
        "/System/Library/Fonts/Supplemental/Kohinoor.ttc",
        "/System/Library/Fonts/Supplemental/DevanagariMT.ttc",
        // ── Thai ─────────────────────────────────────────────────────
        "/System/Library/Fonts/Supplemental/Thonburi.ttc",
        // ── Wide Unicode coverage ────────────────────────────────────
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/System/Library/Fonts/Geneva.ttf",
    )

    // Linux: Noto family is the most reliable coverage. Paths follow
    // Debian/Ubuntu's `fonts-noto-*` packages; Fedora's
    // `google-noto-*-fonts` lay out under similar trees.
    private val linuxPaths = listOf(
        // ── Color emoji ──────────────────────────────────────────────
        "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
        "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf",
        // ── Arabic ───────────────────────────────────────────────────
        "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Regular.ttf",
        "/usr/share/fonts/opentype/noto/NotoNaskhArabic-Regular.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansArabic-Regular.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansArabic-Regular.ttf",
        // ── Hebrew ───────────────────────────────────────────────────
        "/usr/share/fonts/truetype/noto/NotoSansHebrew-Regular.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansHebrew-Regular.ttf",
        // ── CJK ──────────────────────────────────────────────────────
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",
        // ── Devanagari ───────────────────────────────────────────────
        "/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf",
        // ── Thai ─────────────────────────────────────────────────────
        "/usr/share/fonts/truetype/noto/NotoSansThai-Regular.ttf",
        // ── Wide Unicode (DejaVu is on most distros by default) ──────
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    )

    // Windows: Segoe ships with the OS for color emoji + Latin/Arabic;
    // Microsoft's CJK + Devanagari/Thai families are bundled with most
    // editions but may be absent on minimal installs.
    private val windowsPaths = listOf(
        // ── Color emoji ──────────────────────────────────────────────
        """C:\Windows\Fonts\seguiemj.ttf""",       // Segoe UI Emoji
        // ── Arabic ───────────────────────────────────────────────────
        """C:\Windows\Fonts\trado.ttf""",          // Traditional Arabic
        """C:\Windows\Fonts\tradbdo.ttf""",        // Traditional Arabic Bold
        """C:\Windows\Fonts\simpo.ttf""",          // Simplified Arabic
        // ── Hebrew ───────────────────────────────────────────────────
        """C:\Windows\Fonts\david.ttf""",          // David (Hebrew)
        // ── CJK ──────────────────────────────────────────────────────
        """C:\Windows\Fonts\msyh.ttc""",           // Microsoft YaHei (Simplified)
        """C:\Windows\Fonts\msjh.ttc""",           // Microsoft JhengHei (Traditional)
        """C:\Windows\Fonts\malgun.ttf""",         // Malgun Gothic (Korean)
        """C:\Windows\Fonts\YuGothR.ttc""",        // Yu Gothic (Japanese)
        // ── Devanagari ───────────────────────────────────────────────
        """C:\Windows\Fonts\mangal.ttf""",         // Mangal
        """C:\Windows\Fonts\Nirmala.ttf""",        // Nirmala UI (multi-script Indic)
        // ── Wide Unicode ─────────────────────────────────────────────
        """C:\Windows\Fonts\arial.ttf""",          // Arial
        """C:\Windows\Fonts\arialuni.ttf""",       // Arial Unicode (legacy)
    )
}

/**
 * Extra system-font paths to merge into the curated list. Users with
 * fonts installed in non-standard locations (Homebrew, NixOS, Flatpak,
 * `~/.local/share/fonts`, …) can register paths here before constructing
 * an [HbFontStack] with [SystemFallback.Match]; the resolver will check
 * these in order *before* the curated default list.
 *
 * Example:
 *
 * ```
 * JvmExtraSystemFontPaths.add(File("/Users/me/Library/Fonts/MyArabic.ttf"))
 * val stack = HbFontStack(roboto, system = SystemFallback.Match())
 * ```
 */
public object JvmExtraSystemFontPaths {
    private val extras = mutableListOf<File>()

    /** Add [path] (or all of [paths]) to the curated list. */
    public fun add(vararg paths: File) {
        synchronized(extras) {
            for (p in paths) if (p !in extras) extras.add(p)
        }
    }

    /** Remove [path] from the curated list. */
    public fun remove(path: File) {
        synchronized(extras) { extras.remove(path) }
    }

    /** Clear every user-registered extra path. */
    public fun clear() {
        synchronized(extras) { extras.clear() }
    }

    internal fun snapshot(): List<File> = synchronized(extras) { extras.toList() }
}

internal actual fun createSystemFontResolver(match: SystemFallback.Match): SystemFontResolver? {
    val curated = JvmSystemFontPaths.curated()
    val extras = JvmExtraSystemFontPaths.snapshot().filter { it.exists() }
    val all = (extras + curated).distinct()
    return JvmSystemFontResolver(match, candidatePaths = all)
}
