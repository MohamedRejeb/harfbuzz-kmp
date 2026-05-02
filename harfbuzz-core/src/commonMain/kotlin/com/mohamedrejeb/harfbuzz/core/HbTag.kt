package com.mohamedrejeb.harfbuzz.core

import kotlin.jvm.JvmInline

/**
 * A four-character HarfBuzz tag (`OT_TAG`) used to identify scripts, languages,
 * OpenType features, and font tables. Stored as the same packed `UInt`
 * representation HarfBuzz uses internally so it can cross the JNI / cinterop
 * boundary as a primitive.
 */
@JvmInline
public value class HbTag(public val raw: UInt) {
    public companion object {
        /**
         * Build a tag from a four-character ASCII string (e.g. `"liga"`, `"Arab"`).
         * Strings shorter than four characters are right-padded with spaces, longer
         * strings are truncated.
         */
        public fun of(s: String): HbTag {
            val a = s.codePointOrSpace(0)
            val b = s.codePointOrSpace(1)
            val c = s.codePointOrSpace(2)
            val d = s.codePointOrSpace(3)
            return HbTag((a shl 24) or (b shl 16) or (c shl 8) or d)
        }

        private fun String.codePointOrSpace(i: Int): UInt =
            if (i < length) this[i].code.toUInt() and 0xFFu else 0x20u
    }

    override fun toString(): String = buildString(4) {
        append(((raw shr 24) and 0xFFu).toInt().toChar())
        append(((raw shr 16) and 0xFFu).toInt().toChar())
        append(((raw shr 8) and 0xFFu).toInt().toChar())
        append((raw and 0xFFu).toInt().toChar())
    }
}
