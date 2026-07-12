package com.mohamedrejeb.harfbuzz.core

/**
 * An ordered list of fonts used as a fallback chain for shaping. The
 * [primary] font is tried first; for any cluster the primary cannot
 * resolve (i.e., shapes to `.notdef`), the [fallbacks] are tried in
 * order, and finally the host platform's system fonts are consulted
 * if [system] is [SystemFallback.Match]. Only when every layer is
 * exhausted does the last attempted font's `.notdef` glyph win.
 *
 * The stack does **not** own [primary] or [fallbacks] - callers retain
 * ownership and close each `HbFont` themselves. Platform-resolved
 * system fonts are owned by a process-wide cache that outlives any
 * specific stack, so transient stack rebuilds (e.g. recomposition on
 * font-size change) don't trigger re-loading them. [close] is a
 * no-op kept for source compatibility with the previous contract.
 *
 * Use with [shapeParagraph] (extension defined alongside this class)
 * or via the Compose layer's `rememberMeasuredText(..., fontStack)`
 * overload to render strings that draw from more than one font.
 *
 * @property primary First font tried for every cluster.
 * @property fallbacks Tried in order whenever [primary] (or a previous
 *   fallback) yields a `.notdef` cluster.
 * @property system System-font integration. Defaults to
 *   [SystemFallback.Match] with `style = null` (inherit from [primary]),
 *   so missing glyphs (Arabic, CJK, color emoji…) get filled in from
 *   the host platform's installed fonts out of the box. Pass
 *   [SystemFallback.None] for tests or hermetic builds where you need
 *   determinism, or a custom [SystemFallback.Match] to override the
 *   style hint, language preference, or color-emoji bias.
 */
public class HbFontStack(
    public val primary: HbFont,
    public val fallbacks: List<HbFont> = emptyList(),
    public val system: SystemFallback = SystemFallback.Match(),
) : AutoCloseable {
    /** Primary plus fallbacks in resolution order. Never empty. */
    public val fonts: List<HbFont> = buildList {
        add(primary)
        addAll(fallbacks)
    }

    /**
     * Resolve the platform's system-font fallback resolver for this stack's
     * [system] config, drawing from a process-wide cache so size changes
     * (and other transient stack rebuilds) don't trigger re-loading the
     * host platform's fonts. See [sharedSystemResolverFor].
     *
     * The shared cache tolerates concurrent lookups from any thread
     * (copy-on-write, see [sharedSystemResolverFor]); per-stack shaping
     * is still documented as not safe to call concurrently for the same
     * stack, mirroring the rest of the API.
     */
    internal fun systemResolverOrNull(): SystemFontResolver? {
        val match = system as? SystemFallback.Match ?: return null
        // Inherit the primary font's style when the caller didn't pin one.
        // Resolved here so each platform resolver can rely on `match.style`
        // being non-null without poking at HbFont metadata itself.
        val effectiveStyle = match.style ?: primary.face.styleHint
        return sharedSystemResolverFor(match.copy(style = effectiveStyle))
    }

    /**
     * No-op for the shared resolver - system fonts are owned by the
     * process-wide cache and outlive any individual stack. Recompose-
     * driven stack churn (font size changes, etc.) used to trigger
     * re-loading every system font; pinning the resolver to a process-
     * wide cache avoids that.
     *
     * Kept as `AutoCloseable` for source compatibility - callers that
     * `use { ... }` a stack still compile.
     */
    override fun close() {
        // Intentional no-op. See KDoc.
    }

    /**
     * Pre-resolve [codepoints] through the system fallback resolver to
     * warm both the resolver cache and the OS page cache for the
     * platform fonts it touches. Returns once every codepoint has been
     * resolved (to a font or to "no cover").
     *
     * Use this on a low-priority background coroutine right after the
     * first frame paints, so the *next* paragraph that encounters
     * Arabic / CJK / emoji hits a warm cache instead of the cold-load
     * freeze. On a typical mid-range Android device the user-visible
     * first-render p95 for a mixed Latin+Arabic+Emoji+CJK paragraph
     * drops from tens of milliseconds (cold, mmap-backed) into the
     * single-digit-millisecond range (prewarmed).
     *
     * No-op when [system] is [SystemFallback.None] or the platform has
     * no system-font integration (Wasm). Idempotent: a second call
     * with overlapping codepoints just hits the cache.
     *
     * The cost is paid once per process per (style ×
     * preferColorEmoji × languages) key, since the resolver is
     * process-wide cached. The Android resolver loads predicted system
     * fonts in parallel on `Dispatchers.IO` (each candidate's mmap +
     * `HbFace.from` runs concurrently), then the sequential cover walk
     * completes against already-loaded faces.
     *
     * @param codepoints One representative codepoint per script you
     *   expect to render. Defaults to [DEFAULT_PREWARM_CODEPOINTS],
     *   which covers Arabic, Hebrew, CJK, Hiragana, Hangul, Devanagari,
     *   Thai, and emoji. Pass a smaller subset for apps that only
     *   render a known script subset (e.g. an Arabic-only app passes
     *   `intArrayOf(0x0644)`).
     */
    public suspend fun prewarmSystemFallback(
        codepoints: IntArray = DEFAULT_PREWARM_CODEPOINTS,
    ) {
        val resolver = systemResolverOrNull() ?: return
        resolver.prewarm(codepoints)
    }

    public companion object {
        /** Build a stack from a primary font and zero or more fallbacks. */
        public fun of(primary: HbFont, vararg fallbacks: HbFont): HbFontStack =
            HbFontStack(primary, fallbacks.toList())

        /**
         * One representative codepoint per common script - what
         * [prewarmSystemFallback] hits by default. Hand-picked to be
         * non-ambiguous and unlikely to be present in any explicit
         * fallback chain (so each codepoint actually does drive a
         * system-font load on first call):
         *
         *   U+0644  ARABIC LETTER LAM         ل
         *   U+05D0  HEBREW LETTER ALEF        א
         *   U+4E2D  CJK UNIFIED IDEOGRAPH    中
         *   U+3042  HIRAGANA LETTER A         あ
         *   U+AC00  HANGUL SYLLABLE GA       가
         *   U+0915  DEVANAGARI LETTER KA     क
         *   U+0E01  THAI CHARACTER KO KAI    ก
         *   U+1F600 GRINNING FACE             😀
         *
         * Apps that don't render some of these scripts should pass a
         * narrower array - each prewarmed codepoint costs one
         * (potentially heavy) system-font load.
         */
        public val DEFAULT_PREWARM_CODEPOINTS: IntArray = intArrayOf(
            0x0644,
            0x05D0,
            0x4E2D,
            0x3042,
            0xAC00,
            0x0915,
            0x0E01,
            0x1F600,
        )
    }
}

/**
 * How (or whether) a stack reaches into the host platform's installed
 * fonts when both [HbFontStack.primary] and [HbFontStack.fallbacks]
 * leave a cluster unresolved.
 */
public sealed interface SystemFallback {
    /** No system query - explicit fallbacks only. */
    public data object None : SystemFallback

    /**
     * Query the platform's installed fonts for any cluster the explicit
     * chain doesn't cover. Style-aware (best effort): the resolver picks
     * the closest weight/slant match it can find. The platform support
     * matrix as of v0.1:
     *
     * | Platform | Backed by                                   | Status        |
     * |----------|---------------------------------------------|---------------|
     * | JVM      | Curated list of common system font paths    | Implemented   |
     * | Android  | `android.graphics.fonts.SystemFonts` (29+)  | Implemented   |
     * | iOS      | (CoreText cascade list)                     | Stubbed       |
     * | Wasm     | (browser sandbox - no API)                  | Stubbed       |
     *
     * Where "Stubbed" means the resolver always returns `null` and
     * [HbFontStack] falls through to the explicit chain only. Wasm
     * users should rely on bundled fallback fonts in [HbFontStack.fallbacks].
     *
     * @property style Preferred weight + slant + width for the resolved
     *   font. The resolver picks the closest available match. When `null`
     *   (the default), the stack reads the primary font's style at resolve
     *   time, so a bold-italic Latin primary picks up bold-italic Arabic
     *   from the system without manual configuration.
     * @property preferColorEmoji When `true`, the resolver tries a
     *   color-emoji-capable font first whenever the missing codepoint
     *   is in an emoji block (`U+1F300..U+1FAFF`, etc.).
     * @property languages BCP-47 language tags to bias the match (e.g.
     *   `["ar"]` to prefer Arabic-family fonts on systems with multiple
     *   Arabic-coverage fonts). Empty list means "no preference".
     */
    public data class Match(
        public val style: FontStyleHint? = null,
        public val preferColorEmoji: Boolean = true,
        public val languages: List<HbLanguage> = emptyList(),
    ) : SystemFallback
}

/**
 * Style preferences passed to the platform font matcher. Mapped to each
 * platform's native font-style type:
 *
 *  - JVM: weight / italic become hints when ranking candidate fonts.
 *  - Android: maps to `android.graphics.fonts.FontStyle(weight, slant)`.
 *  - iOS: maps to a CoreText `kCTFontWeightTrait` + `kCTFontSlantTrait`.
 *
 * @property weight OpenType weight, 1..1000. Common values: 400 (regular),
 *   500 (medium), 700 (bold).
 * @property italic Whether to prefer italic / oblique variants.
 * @property width OpenType width, 1.0 = normal, <1 condensed, >1 expanded.
 */
public data class FontStyleHint(
    public val weight: Int = 400,
    public val italic: Boolean = false,
    public val width: Float = 1f,
)

/**
 * Cache key for the process-wide system resolver pool. Two stacks that
 * share the same effective Match config share a resolver - and therefore
 * its loaded HbFaces. This makes `remember(font) { HbFontStack(font) }`
 * survive font-size churn without re-loading platform fonts.
 *
 * The key uses [Match.style] *after* primary inheritance has been
 * applied, since that's the value the resolver actually keys ranking on.
 */
private data class SystemResolverCacheKey(
    val style: FontStyleHint,
    val preferColorEmoji: Boolean,
    val languages: List<HbLanguage>,
)

/**
 * Process-wide cache of system-font resolvers. Resolvers are never
 * closed: their loaded HbFaces persist for the process lifetime, and
 * the OS reclaims everything on app exit. Memory cost is bounded by
 * the number of distinct system fonts the app ever resolves (typically
 * a handful - Arabic, CJK, color emoji), which is a small fixed
 * overhead measured against the savings: every recompose that
 * rebuilds an HbFontStack used to throw away every loaded fallback.
 *
 * Copy-on-write behind a `@Volatile` immutable map: production shapes
 * from the harfbuzz-bg dispatcher AND, in some integrations, straight
 * from the main thread (seen in ANR traces entering
 * [sharedSystemResolverFor] via app-side measure calls), so lookups
 * race. Reads are lock-free; a miss re-checks after building so racing
 * builders converge on one instance per key. A concurrent insert of a
 * *different* key can drop this insert from the published map - the
 * next lookup just rebuilds it, which is cheap now that the expensive
 * platform font walk is snapshotted process-wide on Android.
 */
@kotlin.concurrent.Volatile
private var sharedResolvers: Map<SystemResolverCacheKey, SystemFontResolver> = emptyMap()

internal fun sharedSystemResolverFor(match: SystemFallback.Match): SystemFontResolver? {
    // [match.style] is non-null at this point - HbFontStack.systemResolverOrNull
    // resolves the primary-inheritance default before calling here.
    val style = match.style ?: FontStyleHint()
    val key = SystemResolverCacheKey(style, match.preferColorEmoji, match.languages)
    sharedResolvers[key]?.let { return it }
    val resolver = createSystemFontResolver(match) ?: return null
    // Re-check before publishing: a racing thread may have built and
    // published a resolver for this key while we were constructing ours.
    // Adopting theirs (and closing our still-empty duplicate - no faces
    // are loaded at construction) keeps one face cache per key.
    sharedResolvers[key]?.let { existing ->
        runCatching { resolver.close() }
        return existing
    }
    sharedResolvers = sharedResolvers + (key to resolver)
    return resolver
}

/**
 * Pre-populate the shared resolver cache under the key the stack would
 * compute for [match] (after primary inheritance). Test-only - the
 * production path goes through [sharedSystemResolverFor].
 */
internal fun seedSharedSystemResolverForTest(
    match: SystemFallback.Match,
    resolver: SystemFontResolver,
) {
    val style = match.style ?: FontStyleHint()
    sharedResolvers = sharedResolvers +
        (SystemResolverCacheKey(style, match.preferColorEmoji, match.languages) to resolver)
}

/** Drop every cached resolver so tests don't leak state into one another. */
internal fun clearSharedSystemResolverCacheForTest() {
    val dropped = sharedResolvers
    sharedResolvers = emptyMap()
    dropped.values.forEach { runCatching { it.close() } }
}
