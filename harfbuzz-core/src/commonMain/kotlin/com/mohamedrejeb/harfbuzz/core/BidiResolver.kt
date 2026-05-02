package com.mohamedrejeb.harfbuzz.core

/**
 * UAX #9 Unicode Bidirectional Algorithm. JVM and Android delegate to
 * `java.text.Bidi` (ICU-backed) via [resolveBidiPlatform]; iOS and Wasm
 * fall through to the pure-Kotlin pipeline below.
 *
 * The pure-Kotlin pipeline is a pragmatic strong-types-first subset that
 * covers the cases real-world Arabic / Hebrew / Latin / Indic text hits
 * in practice (the 95% that downstream consumers care about), plus
 * correct handling of:
 *
 *   - Paragraph direction (P1-P3)
 *   - Strong directional types (L, R, AL)
 *   - Weak types W1-W7 (NSM, EN, ES, ET, AN, CS, BN)
 *   - Neutral types N1-N2 (B, S, WS, ON resolved to surrounding strong)
 *   - Implicit level computation I1-I2
 *   - Run segmentation by resolved level
 *
 * What the pure-Kotlin pipeline does NOT do (but the JVM/Android
 * platform delegation DOES):
 *   - Explicit embedding controls (LRE/RLE/PDF/LRO/RLO/LRI/RLI/FSI/PDI).
 *   - Mirrored bracket pairing (BD16/N0). Brackets render with their
 *     codepoint shape; visual mirroring stays the renderer's job either
 *     way, but the run-level partitioning around brackets differs.
 *
 * Apps that need bracket-aware bidi on iOS / Wasm should layer their
 * own pre-processing on top of [resolve] until those platforms grow a
 * `resolveBidiPlatform` actual.
 */
public class BidiResolver {

    /**
     * Resolve a paragraph of [text] into bidi runs, in **logical** order.
     * [baseDirection] selects the paragraph embedding level - `AUTO` looks
     * for the first strong character in [text].
     */
    public fun resolve(
        text: String,
        baseDirection: HbDirection = HbDirection.AUTO,
    ): List<BidiRun> {
        if (text.isEmpty()) return emptyList()

        // Platforms with a real UAX#9 implementation (JVM + Android via
        // `java.text.Bidi`) take the fast, correct path; iOS and Wasm
        // fall through to the pure-Kotlin pipeline below.
        resolveBidiPlatform(text, baseDirection)?.let { return it }

        // P1-P3: paragraph direction.
        val paragraphLevel = when (baseDirection) {
            HbDirection.RTL -> 1
            HbDirection.LTR -> 0
            else -> when (firstStrongDirection(text)) {
                BidiClass.R, BidiClass.AL -> 1
                BidiClass.L -> 0
                else -> 0
            }
        }

        val classes = classifyAll(text)
        applyWeakRules(classes, paragraphLevel)
        applyNeutralRules(classes, paragraphLevel)
        val levels = computeImplicitLevels(classes, paragraphLevel)
        return groupRuns(levels)
    }

    // ── Step 1: classification ──────────────────────────────────────────

    private fun classifyAll(text: String): IntArray {
        // Stored as ints for in-place rule application. Each int is the ordinal
        // of a [BidiClass]; we mutate as the algorithm progresses.
        val out = IntArray(text.length)
        for (i in text.indices) {
            val cp = text[i].code
            out[i] = bidiClassOrdinal(cp)
        }
        return out
    }

    private fun firstStrongDirection(text: String): BidiClass? {
        for (i in text.indices) {
            val cp = text[i].code
            val cls = bidiClassFor(cp)
            if (cls == BidiClass.L || cls == BidiClass.R || cls == BidiClass.AL) return cls
        }
        return null
    }

    // ── Step 2: weak rules W1-W7 (UAX #9 §3.3.3) ────────────────────────

    private fun applyWeakRules(classes: IntArray, paragraphLevel: Int) {
        val n = classes.size
        if (n == 0) return

        // W1: NSM takes class of preceding char (sos = paragraph dir).
        run {
            var prev: Int = if (paragraphLevel == 0) BidiClass.L.ordinal else BidiClass.R.ordinal
            for (i in 0 until n) {
                if (classes[i] == BidiClass.NSM.ordinal) {
                    classes[i] = prev
                } else {
                    prev = classes[i]
                }
            }
        }

        // W2: EN preceded by AL (ignoring others) → AN.
        for (i in 0 until n) {
            if (classes[i] == BidiClass.EN.ordinal) {
                var j = i - 1
                while (j >= 0 && classes[j] != BidiClass.L.ordinal &&
                    classes[j] != BidiClass.R.ordinal && classes[j] != BidiClass.AL.ordinal
                ) j--
                if (j >= 0 && classes[j] == BidiClass.AL.ordinal) {
                    classes[i] = BidiClass.AN.ordinal
                }
            }
        }

        // W3: AL → R.
        for (i in 0 until n) {
            if (classes[i] == BidiClass.AL.ordinal) classes[i] = BidiClass.R.ordinal
        }

        // W4: ES or CS between two ENs → EN; CS between two ANs → AN.
        for (i in 1 until n - 1) {
            val c = classes[i]
            if (c == BidiClass.ES.ordinal && classes[i - 1] == BidiClass.EN.ordinal && classes[i + 1] == BidiClass.EN.ordinal) {
                classes[i] = BidiClass.EN.ordinal
            } else if (c == BidiClass.CS.ordinal) {
                if (classes[i - 1] == BidiClass.EN.ordinal && classes[i + 1] == BidiClass.EN.ordinal) {
                    classes[i] = BidiClass.EN.ordinal
                } else if (classes[i - 1] == BidiClass.AN.ordinal && classes[i + 1] == BidiClass.AN.ordinal) {
                    classes[i] = BidiClass.AN.ordinal
                }
            }
        }

        // W5: ET adjacent to EN → EN.
        for (i in 0 until n) {
            if (classes[i] == BidiClass.ET.ordinal) {
                if (i > 0 && classes[i - 1] == BidiClass.EN.ordinal) {
                    classes[i] = BidiClass.EN.ordinal
                }
            }
        }
        for (i in n - 1 downTo 0) {
            if (classes[i] == BidiClass.ET.ordinal) {
                if (i < n - 1 && classes[i + 1] == BidiClass.EN.ordinal) {
                    classes[i] = BidiClass.EN.ordinal
                }
            }
        }

        // W6: remaining ES, ET, CS → ON.
        for (i in 0 until n) {
            val c = classes[i]
            if (c == BidiClass.ES.ordinal || c == BidiClass.ET.ordinal || c == BidiClass.CS.ordinal) {
                classes[i] = BidiClass.ON.ordinal
            }
        }

        // W7: EN preceded by L (ignoring N) → L.
        for (i in 0 until n) {
            if (classes[i] == BidiClass.EN.ordinal) {
                var j = i - 1
                while (j >= 0 && classes[j] != BidiClass.L.ordinal && classes[j] != BidiClass.R.ordinal) j--
                if (j >= 0 && classes[j] == BidiClass.L.ordinal) {
                    classes[i] = BidiClass.L.ordinal
                }
            }
        }
    }

    // ── Step 3: neutral resolution N1-N2 ────────────────────────────────

    private fun applyNeutralRules(classes: IntArray, paragraphLevel: Int) {
        val n = classes.size
        if (n == 0) return

        // sos / eos types based on paragraph level.
        val sos = if (paragraphLevel == 0) BidiClass.L.ordinal else BidiClass.R.ordinal
        val eos = sos

        var i = 0
        while (i < n) {
            if (isNeutral(classes[i])) {
                // Find run of neutrals
                val start = i
                while (i < n && isNeutral(classes[i])) i++
                val end = i // exclusive
                val before = if (start == 0) sos else strongOf(classes[start - 1])
                val after = if (end == n) eos else strongOf(classes[end])

                val resolved = if (before == after) before else if (paragraphLevel == 0) BidiClass.L.ordinal else BidiClass.R.ordinal
                for (k in start until end) classes[k] = resolved
            } else {
                i++
            }
        }
    }

    private fun isNeutral(c: Int): Boolean = c == BidiClass.B.ordinal ||
        c == BidiClass.S.ordinal || c == BidiClass.WS.ordinal || c == BidiClass.ON.ordinal

    private fun strongOf(c: Int): Int = when (c) {
        BidiClass.L.ordinal -> BidiClass.L.ordinal
        BidiClass.R.ordinal -> BidiClass.R.ordinal
        BidiClass.EN.ordinal, BidiClass.AN.ordinal -> BidiClass.R.ordinal // treat as R for neutral resolution
        else -> c
    }

    // ── Step 4: implicit levels I1-I2 ───────────────────────────────────

    private fun computeImplicitLevels(classes: IntArray, paragraphLevel: Int): IntArray {
        val out = IntArray(classes.size) { paragraphLevel }
        for (i in classes.indices) {
            val cls = classes[i]
            out[i] = when (paragraphLevel) {
                0 -> when (cls) {
                    BidiClass.L.ordinal -> 0
                    BidiClass.R.ordinal -> 1
                    BidiClass.AN.ordinal, BidiClass.EN.ordinal -> 2
                    else -> 0
                }
                else -> when (cls) {
                    BidiClass.R.ordinal -> 1
                    BidiClass.AN.ordinal, BidiClass.EN.ordinal -> 2
                    BidiClass.L.ordinal -> 2
                    else -> 1
                }
            }
        }
        return out
    }

    private fun groupRuns(levels: IntArray): List<BidiRun> {
        if (levels.isEmpty()) return emptyList()
        val runs = mutableListOf<BidiRun>()
        var start = 0
        var current = levels[0]
        for (i in 1 until levels.size) {
            if (levels[i] != current) {
                runs.add(BidiRun(start, i, current))
                start = i
                current = levels[i]
            }
        }
        runs.add(BidiRun(start, levels.size, current))
        return runs
    }

    // ── Bidi_Class lookup ────────────────────────────────────────────────

    /**
     * Maps a codepoint to its UAX#9 Bidi_Class. Covers the ranges that real
     * Arabic / Hebrew / Latin / Indic / CJK / common-punctuation text uses;
     * everything outside falls back to L (left-to-right) which matches the
     * Unicode default for assigned characters in the "L" category and is
     * conservative for unassigned codepoints.
     */
    public fun bidiClassFor(codepoint: Int): BidiClass = BidiClass.entries[bidiClassOrdinal(codepoint)]

    private fun bidiClassOrdinal(cp: Int): Int = when (cp) {
        // Block 0x0590..0x05FF: Hebrew (R + NSM)
        in 0x0591..0x05BD, in 0x05BF..0x05BF, in 0x05C1..0x05C2, in 0x05C4..0x05C5, 0x05C7 -> BidiClass.NSM.ordinal
        in 0x0590..0x05FF -> BidiClass.R.ordinal

        // Block 0x0600..0x06FF: Arabic (AL + AN + NSM)
        in 0x0610..0x061A, in 0x064B..0x065F, 0x0670, in 0x06D6..0x06DC,
        in 0x06DF..0x06E4, in 0x06E7..0x06E8, in 0x06EA..0x06ED -> BidiClass.NSM.ordinal
        in 0x0660..0x0669 -> BidiClass.AN.ordinal               // Arabic-Indic digits
        in 0x06F0..0x06F9 -> BidiClass.EN.ordinal               // Extended Arabic-Indic digits (Persian/Urdu)
        in 0x0600..0x0605, in 0x0608..0x0608, 0x060B,
        in 0x060D..0x060D -> BidiClass.AL.ordinal
        0x060C -> BidiClass.CS.ordinal                          // Arabic comma
        0x061F -> BidiClass.AL.ordinal                          // Arabic question mark
        0x061B -> BidiClass.AL.ordinal                          // Arabic semicolon
        in 0x0600..0x06FF -> BidiClass.AL.ordinal

        // Block 0x0700..0x074F: Syriac
        in 0x0700..0x074F -> BidiClass.AL.ordinal

        // 0x0750..0x077F: Arabic Supplement
        in 0x0750..0x077F -> BidiClass.AL.ordinal

        // ASCII: digits = EN, basic punctuation = ES/CS/ET/ON, letters = L
        in 0x0030..0x0039 -> BidiClass.EN.ordinal               // 0-9
        0x002B, 0x002D -> BidiClass.ES.ordinal                  // + -
        0x002C, 0x002E, 0x002F, 0x003A -> BidiClass.CS.ordinal  // , . / :
        0x0023, 0x0024, 0x0025, 0x00A2, 0x00A3, 0x00A4, 0x00A5,
        0x0025, 0x2030, 0x2031 -> BidiClass.ET.ordinal          // # $ % currency
        0x000A, 0x000D, 0x001C, 0x001D, 0x001E, 0x0085, 0x2029 -> BidiClass.B.ordinal  // paragraph separators
        0x0009, 0x000B, 0x001F -> BidiClass.S.ordinal           // segment separators
        0x000C, 0x0020, 0x00A0, 0x1680, in 0x2000..0x200A, 0x202F, 0x205F, 0x3000 -> BidiClass.WS.ordinal
        in 0x0041..0x005A, in 0x0061..0x007A -> BidiClass.L.ordinal       // ASCII letters
        in 0x0080..0x00BF -> BidiClass.ON.ordinal               // misc Latin-1
        in 0x00C0..0x024F -> BidiClass.L.ordinal                // Latin-1 supplement, Latin Extended-A/B

        // Greek, Cyrillic, Armenian, etc.
        in 0x0370..0x05BD -> BidiClass.L.ordinal

        // Devanagari (with combining marks → NSM where applicable)
        in 0x0900..0x0903 -> BidiClass.L.ordinal
        in 0x093A..0x093A, in 0x093C..0x093C, in 0x0941..0x0948,
        in 0x094D..0x094D, in 0x0951..0x0957, in 0x0962..0x0963 -> BidiClass.NSM.ordinal
        in 0x0900..0x097F -> BidiClass.L.ordinal

        // CJK
        in 0x3000..0x33FF -> BidiClass.L.ordinal
        in 0x4E00..0x9FFF -> BidiClass.L.ordinal
        in 0xAC00..0xD7AF -> BidiClass.L.ordinal

        // Punctuation (mostly ON in UAX #9)
        in 0x0021..0x002A, in 0x003B..0x0040, in 0x005B..0x0060, in 0x007B..0x007F,
        in 0x2000..0x206F -> BidiClass.ON.ordinal

        // Default
        else -> BidiClass.L.ordinal
    }
}

/**
 * UAX#9 Bidi_Class. Subset of the full Unicode property - covers the values
 * the resolver consumes after weak/neutral rules collapse the others.
 */
public enum class BidiClass {
    L,    // Left-to-right
    R,    // Right-to-left
    AL,   // Arabic letter
    EN,   // European number
    AN,   // Arabic number
    ES,   // European separator
    ET,   // European terminator
    CS,   // Common separator
    NSM,  // Non-spacing mark
    BN,   // Boundary neutral
    B,    // Paragraph separator
    S,    // Segment separator
    WS,   // White space
    ON,   // Other neutral
}

/**
 * One maximal same-direction codepoint range from [BidiResolver.resolve].
 * [level] follows UAX#9: even = LTR, odd = RTL. [start] is inclusive,
 * [end] exclusive - both in **codepoint** indices (UTF-16 char offsets).
 */
public data class BidiRun(
    public val start: Int,
    public val end: Int,
    public val level: Int,
) {
    public val isRtl: Boolean get() = level and 1 != 0
    public val length: Int get() = end - start
}
