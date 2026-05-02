package com.mohamedrejeb.harfbuzz.core

import kotlin.jvm.JvmInline

/**
 * ISO 15924 script tag wrapped as an [HbTag]. Construct via [HbScript.of] for
 * an arbitrary tag, or use the named constants below for common scripts.
 */
@JvmInline
public value class HbScript(public val tag: HbTag) {
    public companion object {
        public fun of(s: String): HbScript = HbScript(HbTag.of(s))

        /**
         * Sentinel that maps to `HB_SCRIPT_INVALID` so HarfBuzz's
         * `hb_buffer_guess_segment_properties` will auto-detect the script from
         * the buffer's codepoints. Use this when you don't know the script
         * ahead of time - the Arabic shaper, Indic shaper, etc. only engage
         * when the resolved script is correct, and `UNKNOWN` ("Zzzz") is a
         * *valid* tag that suppresses auto-detection. Default for [HbBuffer].
         */
        public val AUTO: HbScript = HbScript(HbTag(0u))
        public val UNKNOWN: HbScript = HbScript(HbTag.of("Zzzz"))
        public val COMMON: HbScript = HbScript(HbTag.of("Zyyy"))
        public val INHERITED: HbScript = HbScript(HbTag.of("Zinh"))

        public val LATIN: HbScript = HbScript(HbTag.of("Latn"))
        public val ARABIC: HbScript = HbScript(HbTag.of("Arab"))
        public val HEBREW: HbScript = HbScript(HbTag.of("Hebr"))
        public val DEVANAGARI: HbScript = HbScript(HbTag.of("Deva"))
        public val BENGALI: HbScript = HbScript(HbTag.of("Beng"))
        public val THAI: HbScript = HbScript(HbTag.of("Thai"))
        public val HAN: HbScript = HbScript(HbTag.of("Hani"))
        public val HIRAGANA: HbScript = HbScript(HbTag.of("Hira"))
        public val KATAKANA: HbScript = HbScript(HbTag.of("Kana"))
        public val HANGUL: HbScript = HbScript(HbTag.of("Hang"))
        public val GREEK: HbScript = HbScript(HbTag.of("Grek"))
        public val CYRILLIC: HbScript = HbScript(HbTag.of("Cyrl"))
        public val ARMENIAN: HbScript = HbScript(HbTag.of("Armn"))
        public val GEORGIAN: HbScript = HbScript(HbTag.of("Geor"))
        public val ETHIOPIC: HbScript = HbScript(HbTag.of("Ethi"))
    }
}
