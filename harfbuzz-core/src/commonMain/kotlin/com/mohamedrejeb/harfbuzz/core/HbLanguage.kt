package com.mohamedrejeb.harfbuzz.core

import kotlin.jvm.JvmInline

/**
 * BCP 47 language tag (e.g. `"en"`, `"ar-SA"`, `"zh-Hans-CN"`). [AUTO] lets
 * HarfBuzz pick a default; specific languages enable language-specific
 * OpenType features and shaping rules.
 */
@JvmInline
public value class HbLanguage(public val bcp47: String) {
    public companion object {
        public val AUTO: HbLanguage = HbLanguage("")
        public val ENGLISH: HbLanguage = HbLanguage("en")
        public val ARABIC: HbLanguage = HbLanguage("ar")
        public val PERSIAN: HbLanguage = HbLanguage("fa")
        public val URDU: HbLanguage = HbLanguage("ur")
        public val HEBREW: HbLanguage = HbLanguage("he")
    }
}
