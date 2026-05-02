package com.mohamedrejeb.harfbuzz.core

import android.content.Context

/**
 * Soft hook into `androidx.emoji2` so HarfBuzz Text renders the same
 * emoji glyphs Compose Text does when the consuming app uses
 * EmojiCompat.
 *
 * The integration is **runtime-detected** so this library doesn't add a
 * hard dep on `androidx.emoji2:emoji2`:
 *
 *  - If the consuming app pulls in `androidx.emoji2:emoji2-bundled`, its
 *    AAR ships `assets/NotoColorEmojiCompat.ttf` and the app's APK
 *    inherits that asset at build time. We open it via [Context.getAssets]
 *    and load the bytes into an [HbFace] - exact byte parity with what
 *    EmojiCompat would render.
 *  - If only the downloadable variant (`androidx.emoji2:emoji2`) is
 *    present, the font is fetched into the platform font cache via
 *    `FontsContractCompat`; that cache isn't a stable public API so we
 *    don't try to read it. Falling through here is harmless - the
 *    Android resolver still picks a system emoji font, and the
 *    [readLocaleList] Zsye-aware ranking biases it toward the platform's
 *    canonical emoji font (`und-Zsye`-tagged).
 *  - If neither variant is present, this returns `null` and the resolver
 *    behaves as before.
 *
 * Why bytes-from-asset instead of reflection on the live EmojiCompat
 * `Typeface`: the Typeface doesn't expose the source bytes. Reflection on
 * `MetadataRepo` would only get us the flatbuffer-encoded metadata, not
 * the glyph outlines we need for HarfBuzz shaping. Reading the bundled
 * asset is the only stable path to the same byte stream.
 */
internal object EmojiCompatBridge {

    /** Asset path shipped by `androidx.emoji2:emoji2-bundled`. */
    private const val BUNDLED_ASSET_NAME = "NotoColorEmojiCompat.ttf"

    /**
     * Process-wide cache of the bundled emoji font bytes. The asset is
     * ~10 MB (NotoColorEmojiCompat.ttf), and every distinct
     * `SystemFallback.Match` config rebuilt during shaping used to
     * trigger a fresh `assets.open(...).readBytes()` against the same
     * APK asset on the harfbuzz-bg dispatcher - blocking other shape
     * jobs while it loaded. The asset never changes at runtime, so
     * memoise the result (success and "not present" alike) and return
     * the cached value to every caller.
     */
    @Volatile private var cachedBytes: ByteArray? = null
    @Volatile private var cacheLoaded: Boolean = false

    /**
     * Read [BUNDLED_ASSET_NAME] from the consuming app's assets if
     * `emoji2-bundled` is on the classpath. Returns the raw font bytes,
     * or `null` if the asset isn't present (no `emoji2-bundled` dep, or
     * stripped by an aggressive resource-shrinker). The result is
     * cached process-wide on first call so subsequent resolver builds
     * skip the asset I/O entirely.
     */
    fun bundledEmojiFontBytes(context: Context): ByteArray? {
        if (cacheLoaded) return cachedBytes
        synchronized(this) {
            if (cacheLoaded) return cachedBytes
            cachedBytes = runCatching {
                context.assets.open(BUNDLED_ASSET_NAME).use { it.readBytes() }
            }.getOrNull()
            cacheLoaded = true
        }
        return cachedBytes
    }

    /**
     * `true` when `androidx.emoji2:emoji2` is on the classpath. Used as
     * a soft signal - present even if the downloadable font hasn't
     * finished fetching, so we treat it as "the consumer uses EmojiCompat,
     * try to honour their preference where we can". Currently this only
     * gates the asset-read above; left here so future hooks (e.g.
     * `getEmojiMatch`-based codepoint coverage) have a stable detection
     * point.
     */
    fun emojiCompatOnClasspath(): Boolean = runCatching {
        Class.forName("androidx.emoji2.text.EmojiCompat")
        true
    }.getOrDefault(false)
}
