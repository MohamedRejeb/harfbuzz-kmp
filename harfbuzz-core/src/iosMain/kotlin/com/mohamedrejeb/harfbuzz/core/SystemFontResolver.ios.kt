package com.mohamedrejeb.harfbuzz.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRange
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCharacters
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFURLCopyFileSystemPath
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreText.CTFontCopyAttribute
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateForString
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFontRef
import platform.CoreText.kCTFontItalicTrait
import platform.CoreText.kCTFontUIFontSystem
import platform.CoreText.kCTFontURLAttribute
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * iOS / macCatalyst system-font resolver backed by CoreText. Uses
 * [CTFontCreateForString] to find a font that covers each requested
 * codepoint - the same path UIKit follows when laying out a UILabel
 * that mixes scripts, so we get the OS's curated cascade list for free.
 * The resolved font's underlying file URL is read via
 * [CTFontCopyAttribute] with [kCTFontURLAttribute], the bytes are
 * loaded into HarfBuzz, and we cache by file path so the same font
 * isn't loaded twice across queries.
 *
 * Style hint: italic is applied to the *base* font (via
 * [CTFontCreateCopyWithSymbolicTraits]) before asking CoreText to
 * find a covering font, so on systems with italic Arabic we get the
 * italic Arabic. Weight matching is best-effort - CoreText doesn't
 * accept a per-codepoint weight hint, so we rely on the cascade
 * inheriting the base font's weight.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosSystemFontResolver(
    private val match: SystemFallback.Match,
    private val basePointSize: Float = 16f,
) : SystemFontResolver {

    // [HbFontStack.systemResolverOrNull] normally resolves the null default
    // to the primary's style before constructing the resolver. When the
    // resolver is constructed directly (tests, custom integrations), fall
    // back to the registered defaults - same shape `Match()` had before
    // primary-inheritance was introduced.
    private val style: FontStyleHint = match.style ?: FontStyleHint()

    private class Loaded(val face: HbFace) {
        /**
         * Single sizeless [HbFont] minted on first cover-walk and reused
         * for every subsequent resolve against this face. HbFont is now
         * sizeless so the same instance shapes at any caller-supplied
         * sizePx.
         */
        var sizelessFont: HbFont? = null
    }

    private val resolved: HashMap<Int, Loaded?> = HashMap()
    private val byPath: HashMap<String, Loaded> = HashMap()

    /**
     * The system base font with our requested style applied. CoreText's
     * cascade list is rooted at this font, so any italic preference we
     * carry through it propagates to the resolved fallbacks.
     */
    private val baseFont: CTFontRef? by lazy { createBaseFont() }

    private fun createBaseFont(): CTFontRef? {
        val raw = CTFontCreateUIFontForLanguage(
            uiType = kCTFontUIFontSystem,
            size = basePointSize.toDouble(),
            language = null,
        ) ?: return null
        if (!style.italic) return raw
        val italic = CTFontCreateCopyWithSymbolicTraits(
            font = raw,
            size = basePointSize.toDouble(),
            matrix = null,
            symTraitValue = kCTFontItalicTrait.convert(),
            symTraitMask = kCTFontItalicTrait.convert(),
        )
        return if (italic != null) {
            CFRelease(raw)
            italic
        } else {
            // Italic variant unavailable - fall back to the upright base.
            raw
        }
    }

    override suspend fun fontFor(codepoint: Int): HbFont? {
        if (resolved.containsKey(codepoint)) {
            val cached = resolved[codepoint] ?: return null
            return sizelessFont(cached)
        }
        val base = baseFont ?: run {
            resolved[codepoint] = null
            return null
        }

        val utf16 = codepointToString(codepoint)
        val cfStr = createCFString(utf16) ?: run {
            resolved[codepoint] = null
            return null
        }

        try {
            val range = cValue<CFRange> {
                location = 0
                length = utf16.length.convert()
            }
            val matched: CTFontRef = CTFontCreateForString(base, cfStr, range) ?: run {
                resolved[codepoint] = null
                return null
            }
            try {
                val pathStr = copyFileSystemPath(matched)
                if (pathStr == null) {
                    resolved[codepoint] = null
                    return null
                }
                val loaded = byPath[pathStr] ?: loadByPath(pathStr)?.also { byPath[pathStr] = it }
                if (loaded == null) {
                    resolved[codepoint] = null
                    return null
                }
                val font = sizelessFont(loaded)

                // Sanity check - CoreText sometimes returns the base when
                // nothing covers, in which case our HbFont still fails to
                // resolve. Cache null in that case.
                if (font.glyphIdForCodepoint(codepoint) == 0) {
                    resolved[codepoint] = null
                    return null
                }
                resolved[codepoint] = loaded
                return font
            } finally {
                CFRelease(matched)
            }
        } finally {
            CFRelease(cfStr)
        }
    }

    private suspend fun sizelessFont(l: Loaded): HbFont {
        l.sizelessFont?.let { return it }
        val font = l.face.toFont()
        l.sizelessFont = font
        return font
    }

    /**
     * Read [CTFontCopyAttribute]'s URL attribute and convert it into a
     * POSIX file system path (`/System/Library/Fonts/...`). Returns
     * `null` if CoreText didn't provide a URL - that happens for
     * memory-only fonts the OS doesn't expose to user code.
     */
    private fun copyFileSystemPath(font: CTFontRef): String? {
        val attrRef: CFTypeRef = CTFontCopyAttribute(font, kCTFontURLAttribute) ?: return null
        try {
            // Toll-free: CFTypeRef-typed-as-URL → CFURLRef. Both are
            // `CPointer<__CFURL>` at runtime; the type system needs the
            // hint via `reinterpret()` since CFTypeRef is opaque.
            val urlRef: CFURLRef = attrRef.reinterpret()
            val cfPath: CFStringRef = CFURLCopyFileSystemPath(urlRef, kCFURLPOSIXPathStyle)
                ?: return null
            try {
                return cfStringToKString(cfPath)
            } finally {
                CFRelease(cfPath)
            }
        } finally {
            CFRelease(attrRef)
        }
    }

    private fun loadByPath(path: String): Loaded? {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
        val length = data.length.toInt()
        if (length == 0) return null
        val srcPtr = data.bytes ?: return null
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), srcPtr, length.convert())
        }
        val face = runCatching { HbFace.from { bytes(bytes) } }.getOrNull() ?: return null
        return Loaded(face)
    }

    override fun close() {
        for (l in byPath.values) {
            l.sizelessFont?.let { runCatching { it.close() } }
            l.sizelessFont = null
            runCatching { l.face.close() }
        }
        byPath.clear()
        resolved.clear()
        baseFont?.let { CFRelease(it) }
    }
}

/**
 * Build a [CFStringRef] from a Kotlin UTF-16 string by allocating a
 * temporary `UniChar[]` buffer. The CFString takes ownership of an
 * internal copy, so the buffer is released when the `memScoped` block
 * exits. Caller must `CFRelease` the returned string when done.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createCFString(s: String): CFStringRef? = memScoped {
    val len = s.length
    val buf = allocArray<UShortVar>(len)
    for (i in 0 until len) {
        buf[i] = s[i].code.toUShort()
    }
    CFStringCreateWithCharacters(alloc = null, chars = buf, numChars = len.convert())
}

/**
 * Convert a [CFStringRef] to a Kotlin [String] via UTF-8. Returns
 * `null` if the conversion fails (extremely unusual - only happens
 * for malformed input the encoder can't represent).
 */
@OptIn(ExperimentalForeignApi::class)
private fun cfStringToKString(cfStr: CFStringRef): String? {
    val length = CFStringGetLength(cfStr).toInt()
    if (length == 0) return ""
    val maxBytes = CFStringGetMaximumSizeForEncoding(length.convert(), kCFStringEncodingUTF8).toInt() + 1
    if (maxBytes <= 0) return null
    val buf = ByteArray(maxBytes)
    return buf.usePinned { pinned ->
        val ok = CFStringGetCString(
            theString = cfStr,
            buffer = pinned.addressOf(0),
            bufferSize = buf.size.convert(),
            encoding = kCFStringEncodingUTF8,
        )
        if (ok) {
            // Find the trailing NUL and decode up to it.
            var end = 0
            while (end < buf.size && buf[end] != 0.toByte()) end++
            buf.decodeToString(0, end)
        } else null
    }
}

/**
 * Build a Kotlin [String] (UTF-16) from a single codepoint - handles
 * supplementary planes by emitting a surrogate pair. Used as the input
 * to [CTFontCreateForString], which works on UTF-16.
 */
private fun codepointToString(cp: Int): String {
    if (cp <= 0xFFFF) return cp.toChar().toString()
    val offset = cp - 0x10000
    val high = ((offset shr 10) + 0xD800).toChar()
    val low = ((offset and 0x3FF) + 0xDC00).toChar()
    return "$high$low"
}

internal actual fun createSystemFontResolver(match: SystemFallback.Match): SystemFontResolver? =
    IosSystemFontResolver(match)
