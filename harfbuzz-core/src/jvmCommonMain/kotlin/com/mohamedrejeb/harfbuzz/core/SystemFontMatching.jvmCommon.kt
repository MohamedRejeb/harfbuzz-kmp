package com.mohamedrejeb.harfbuzz.core

/**
 * Helpers shared between the JVM and Android system-font resolvers.
 * Both rank candidate fonts by the same criteria: color-emoji
 * preference, language overlap, italic match, weight closeness - so
 * the heuristics live here and the per-platform resolvers consume
 * them.
 *
 * iOS has its own resolver path (CoreText cascade list) and doesn't
 * need these.
 */

/**
 * Extract the BCP-47 primary subtag from a tag string. Examples:
 *  `"ar-SA"` → `"ar"`,
 *  `"zh-Hans-CN"` → `"zh"`,
 *  `"en"` → `"en"`,
 *  `""` → `""`.
 */
internal fun primaryTag(bcp47: String): String =
    bcp47.substringBefore('-').lowercase()

/**
 * Heuristic mapping from a font path's *file name* to BCP-47 primary
 * tags the font is biased toward. Best-effort and deliberately
 * permissive - a single CJK font can serve `zh`, `ja`, and `ko`
 * users so we tag it with all three. An empty result means "no
 * language preference"; the resolver falls back to coverage-only
 * ranking.
 *
 * Used by JVM (where it's our only language signal) and as a fallback
 * on Android when the platform's [android.graphics.fonts.Font.getLocaleList]
 * comes back empty.
 */
internal fun inferLanguageHintsFromPath(nameLower: String): Set<String> {
    val hints = mutableSetOf<String>()
    // Emoji fonts: tag with the standard `zsye` script subtag so the
    // resolver's pick logic can short-circuit a 25-188 MB load when a
    // non-emoji codepoint is being looked up. NotoColorEmoji /
    // seguiemj / Apple Color Emoji all match here.
    if ("emoji" in nameLower || "seguiemj" in nameLower) hints.add("zsye")
    if (listOf("arabic", "naskh", "geeza", "damascus", "mishafi", "bayan", "trad")
            .any { it in nameLower }) hints.add("ar")
    if ("hebrew" in nameLower || "david" in nameLower) hints.add("he")
    if (listOf("pingfang", "heiti", "yahei", "song", "jhenghei", "lisung", "msyh", "msjh", "cjk")
            .any { it in nameLower }) hints.add("zh")
    if (listOf("hiragino", "yu gothic", "yugoth", "ms gothic", "meiryo", "noto sans cjk", "cjk")
            .any { it in nameLower }) hints.add("ja")
    if (listOf("malgun", "applesdgothic", "nanum", "gulim", "batang").any { it in nameLower }) {
        hints.add("ko")
    }
    if ("cjk" in nameLower) hints.add("ko")
    if (listOf("devanagari", "kohinoor", "mangal", "nirmala").any { it in nameLower }) {
        hints.add("hi")
    }
    if ("thai" in nameLower || "thonburi" in nameLower) hints.add("th")
    return hints
}

/**
 * Heuristic test for "this codepoint is probably an emoji". We don't
 * pull in the full Unicode `Emoji` property data - instead we test
 * the well-known emoji blocks that account for ~99% of real-world
 * emoji input. Misidentifying a non-emoji here is harmless: it just
 * means [SystemFallback.Match.preferColorEmoji] biases the resolver
 * toward color fonts on a codepoint that would have resolved fine
 * against a monochrome one anyway.
 */
/**
 * Heuristic mapping from a codepoint to the BCP-47 primary tags a font
 * covering it would carry. Returns `null` for codepoints with no clear
 * script bias (Latin, COMMON punctuation, INHERITED marks) - those
 * codepoints can be covered by almost any font, so the resolver should
 * fall back to its default ranking instead of constraining the walk.
 *
 * Used by the system-font resolver to skip obviously-irrelevant
 * candidates during a cold cover walk: looking up an Arabic codepoint
 * shouldn't trigger a 25 MB NotoColorEmoji load just to discover the
 * emoji font has no Arabic glyphs. Falling back to "no signal" when
 * the script is ambiguous (HAN can be Chinese / Japanese / Korean) is
 * fine - the resolver will still try every CJK-tagged candidate; we
 * just want to avoid loading non-CJK fonts first.
 */
internal fun codepointToScriptHints(cp: Int): Set<String>? {
    if (isLikelyEmoji(cp)) return EMOJI_SCRIPT_TAGS
    return when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.ARABIC -> ARABIC_TAGS
        Character.UnicodeScript.HEBREW -> HEBREW_TAGS
        // HAN is shared by zh/ja/ko; we can't tell which the user
        // intends so we tag all three. The resolver picks the first
        // covering font in this set.
        Character.UnicodeScript.HAN -> CJK_TAGS
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> JA_TAGS
        Character.UnicodeScript.HANGUL -> KO_TAGS
        Character.UnicodeScript.DEVANAGARI -> HI_TAGS
        Character.UnicodeScript.THAI -> TH_TAGS
        else -> null
    }
}

// Tag-set constants - pre-allocated so the codepoint→hints lookup
// doesn't allocate on every cover-walk pick.
private val EMOJI_SCRIPT_TAGS: Set<String> = setOf("zsye")
private val ARABIC_TAGS: Set<String> = setOf("ar")
private val HEBREW_TAGS: Set<String> = setOf("he")
private val CJK_TAGS: Set<String> = setOf("zh", "ja", "ko")
private val JA_TAGS: Set<String> = setOf("ja")
private val KO_TAGS: Set<String> = setOf("ko")
private val HI_TAGS: Set<String> = setOf("hi")
private val TH_TAGS: Set<String> = setOf("th")

internal fun isLikelyEmoji(cp: Int): Boolean = when (cp) {
    in 0x1F300..0x1F5FF -> true   // Misc Symbols & Pictographs
    in 0x1F600..0x1F64F -> true   // Emoticons
    in 0x1F680..0x1F6FF -> true   // Transport and Map
    in 0x1F700..0x1F77F -> true   // Alchemical
    in 0x1F780..0x1F7FF -> true   // Geometric Shapes Extended
    in 0x1F900..0x1F9FF -> true   // Supplemental Symbols and Pictographs
    in 0x1FA70..0x1FAFF -> true   // Symbols and Pictographs Extended-A
    in 0x2600..0x26FF -> true     // Misc Symbols (incl. ☀ ☁ ☂)
    in 0x2700..0x27BF -> true     // Dingbats
    in 0x1F1E6..0x1F1FF -> true   // Regional Indicator (flags)
    0x231A, 0x231B, 0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3 -> true
    else -> false
}
