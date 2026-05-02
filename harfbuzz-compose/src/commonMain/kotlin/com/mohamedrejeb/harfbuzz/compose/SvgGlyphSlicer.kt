package com.mohamedrejeb.harfbuzz.compose

/**
 * Extracts a single glyph's `<g id="glyphN">` subtree from an OT-SVG
 * document and packages it with the document's `<defs>` into a fresh
 * minimal SVG that renders ONLY that glyph.
 *
 * OT-SVG's `SVGDocumentRecord` may cover a glyph range - Noto Color
 * Emoji ships *one* 14MB SVG document containing thousands of
 * `<g id="glyphN">` siblings, each representing a different emoji.
 * If we hand the raw bytes to Skia's SVGDOM and call render(), every
 * `<g>` paints on top of every other and we get a soup. Slicing to a
 * single `<g>` per glyph fixes that and also keeps the per-glyph
 * SVGDOM tiny so re-parsing it for caching stays cheap.
 *
 * The implementation works directly on bytes: OT-SVG documents are
 * machine-generated with predictable element ordering, the markers we
 * care about (`<g id="glyph...`, `</g>`, `<defs>`, `</defs>`) are pure
 * ASCII, and on Kotlin/Wasm `String` operations on multi-MB inputs
 * cost orders of magnitude more than the equivalent `ByteArray`
 * iteration (8 × 1.5 MB Aref Ruqaa Ink documents took >9 s when
 * processed via `decodeToString` + `StringBuilder`).
 *
 * Returns null when the slice fails (malformed input, target glyph
 * not present, no usable wrapper). Callers should fall back to
 * COLR v1 / monochrome in that case.
 */
internal object SvgGlyphSlicer {

    /**
     * Build a minimal SVG document containing just `<g id="glyph$gid">`
     * and the original document's `<defs>`. Returns the new bytes, or
     * null if the input doesn't look like an OT-SVG document or the
     * target glyph isn't found.
     */
    fun sliceGlyph(svgBytes: ByteArray, glyphId: Int): ByteArray? {
        // Skip slicing for single-glyph documents (Aref Ruqaa Ink etc.)
        // - Skia draws one glyph either way and we save the slice work.
        if (countGlyphGroupsBytes(svgBytes) <= 1) return null

        val openTagPrefix = (PATTERN_GLYPH_OPEN_PREFIX + glyphId.toString()).encodeToByteArray()
        val openIdx = indexOf(svgBytes, openTagPrefix, 0)
        if (openIdx < 0) return null

        // The next byte after the prefix must be `"` or `' '` so a
        // request for glyph 1 doesn't accidentally match `glyph10`.
        val afterPrefix = openIdx + openTagPrefix.size
        if (afterPrefix >= svgBytes.size) return null
        val terminator = svgBytes[afterPrefix].toInt() and 0xFF
        if (terminator != '"'.code && terminator != ' '.code) return null

        val openTagEnd = indexOfByte(svgBytes, '>'.code.toByte(), openIdx)
        if (openTagEnd < 0) return null

        val closeIdx = findMatchingClose(svgBytes, openTagEnd + 1)
        if (closeIdx < 0) return null

        val groupStart = openIdx
        val groupEnd = closeIdx + PATTERN_END_G.size

        val (defsStart, defsEnd) = extractDefsRange(svgBytes)

        val defsLen = if (defsStart >= 0) defsEnd - defsStart else 0
        val groupLen = groupEnd - groupStart
        val outSize = SVG_HEAD_BYTES.size + defsLen + groupLen + SVG_TAIL_BYTES.size
        val out = ByteArray(outSize)
        var pos = 0
        SVG_HEAD_BYTES.copyInto(out, pos); pos += SVG_HEAD_BYTES.size
        if (defsStart >= 0) {
            svgBytes.copyInto(out, pos, defsStart, defsEnd); pos += defsLen
        }
        svgBytes.copyInto(out, pos, groupStart, groupEnd); pos += groupLen
        SVG_TAIL_BYTES.copyInto(out, pos)
        return out
    }

    /**
     * Byte-level scan for `<g id="glyphN">` patterns. Returns 0, 1, or
     * 2 - that's all callers need (>1 means "multi-glyph, slice it";
     * ≤1 means "single-glyph, skip"). Early-exits on the second hit.
     */
    private fun countGlyphGroupsBytes(bytes: ByteArray): Int {
        val pattern = PATTERN_GLYPH_OPEN_PREFIX_BYTES
        val n = bytes.size
        val patLen = pattern.size
        if (n < patLen) return 0
        var count = 0
        var i = 0
        val limit = n - patLen
        while (i <= limit) {
            var matched = true
            for (j in 0 until patLen) {
                if (bytes[i + j] != pattern[j]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                count++
                if (count > 1) return count
                i += patLen
            } else {
                i++
            }
        }
        return count
    }

    /**
     * Byte-array equivalent of `String.indexOf(needle, fromIndex)`.
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, fromIndex: Int): Int {
        val hLen = haystack.size
        val nLen = needle.size
        if (nLen == 0) return fromIndex.coerceAtLeast(0)
        if (nLen > hLen) return -1
        val limit = hLen - nLen
        var i = fromIndex.coerceAtLeast(0)
        while (i <= limit) {
            var matched = true
            for (j in 0 until nLen) {
                if (haystack[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }

    private fun indexOfByte(haystack: ByteArray, needle: Byte, fromIndex: Int): Int {
        var i = fromIndex
        val n = haystack.size
        while (i < n) {
            if (haystack[i] == needle) return i
            i++
        }
        return -1
    }

    /**
     * Walks `<g>` / `</g>` / self-closing `<g .../>` tags from
     * [startIdx] forward, returning the byte index of the `</g>` that
     * closes the depth-1 outer group.
     */
    private fun findMatchingClose(bytes: ByteArray, startIdx: Int): Int {
        val openMarker = PATTERN_TAG_G_OPEN
        val closeMarker = PATTERN_END_G
        var depth = 1
        var i = startIdx
        val n = bytes.size
        while (i < n) {
            val nextOpen = indexOf(bytes, openMarker, i)
            val nextClose = indexOf(bytes, closeMarker, i)
            if (nextClose < 0) return -1
            if (nextOpen in 0..<nextClose) {
                val tagEnd = indexOfByte(bytes, '>'.code.toByte(), nextOpen)
                if (tagEnd < 0) return -1
                val isSelfClosing = bytes[tagEnd - 1].toInt() == '/'.code
                if (!isSelfClosing) depth++
                i = tagEnd + 1
            } else {
                depth--
                if (depth == 0) return nextClose
                i = nextClose + closeMarker.size
            }
        }
        return -1
    }

    /**
     * Locates the `<defs>...</defs>` byte range (inclusive of both
     * tags). Returns `(-1, -1)` when the document has no `<defs>`.
     */
    private fun extractDefsRange(bytes: ByteArray): Pair<Int, Int> {
        val open = indexOf(bytes, PATTERN_DEFS_OPEN, 0)
        if (open < 0) return -1 to -1
        val openEnd = indexOfByte(bytes, '>'.code.toByte(), open)
        if (openEnd < 0) return -1 to -1
        if (bytes[openEnd - 1].toInt() == '/'.code) return -1 to -1
        val close = indexOf(bytes, PATTERN_DEFS_CLOSE, openEnd + 1)
        if (close < 0) return -1 to -1
        return open to close + PATTERN_DEFS_CLOSE.size
    }

    private const val PATTERN_GLYPH_OPEN_PREFIX = "<g id=\"glyph"

    private val PATTERN_GLYPH_OPEN_PREFIX_BYTES: ByteArray =
        PATTERN_GLYPH_OPEN_PREFIX.encodeToByteArray()
    private val PATTERN_TAG_G_OPEN: ByteArray = "<g".encodeToByteArray()
    private val PATTERN_END_G: ByteArray = "</g>".encodeToByteArray()
    private val PATTERN_DEFS_OPEN: ByteArray = "<defs".encodeToByteArray()
    private val PATTERN_DEFS_CLOSE: ByteArray = "</defs>".encodeToByteArray()

    private val SVG_HEAD_BYTES: ByteArray = (
        "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\">"
        ).encodeToByteArray()
    private val SVG_TAIL_BYTES: ByteArray = "</svg>".encodeToByteArray()
}
