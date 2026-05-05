package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.ui.graphics.Path
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.PaintBufferParser
import com.mohamedrejeb.harfbuzz.core.RecordedPaintOp
import com.mohamedrejeb.harfbuzz.core.RecordingPaintSink
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lazy `Map<Int, Path>` over the raw SVG path strings emitted by
 * `MeasuredFontPass`.
 *
 * Skips `parseSvgPathToCompose` for every glyph the paragraph never
 * actually draws: the practical win for color-emoji-heavy or CJK
 * paragraphs whose viewport only paints a fraction of the shaped
 * glyphs. Glyphs that *do* draw still get parsed exactly once and
 * memoised in the process-wide [GlyphPathCache] keyed on
 * `(font, glyphId, sizePx, flipY)`, so two `MeasuredText` instances
 * at the same `(font, sizePx)` reuse parsed paths.
 *
 * Behaves like a real `Map<Int, Path>` for the consumers that exist
 * today (`get`, `containsKey`, `size`, `keys`, `isEmpty`). Throws on
 * `values` / `entries` / `containsValue` because nothing in the draw
 * pipeline calls them and supporting them would force eager parsing
 * of every glyph - defeating the optimisation. Tighten if a future
 * caller materialises.
 *
 * Threading: lookups happen on the UI thread per frame and are
 * serialised by Compose's draw scheduler. [GlyphPathCache] is not
 * externally synchronised; concurrent draws on the same
 * `MeasuredText` would be a Compose-runtime bug.
 */
internal class LazyGlyphPathMap(
    private val rawSvgs: Map<Int, String>,
    private val flipY: Boolean,
    private val scale: Float,
    private val font: HbFont,
    private val sizePx: Float,
) : Map<Int, Path> {

    override val size: Int get() = rawSvgs.size
    override fun isEmpty(): Boolean = rawSvgs.isEmpty()
    override fun containsKey(key: Int): Boolean = rawSvgs.containsKey(key)
    override val keys: Set<Int> get() = rawSvgs.keys

    override fun get(key: Int): Path? {
        val cacheKey = GlyphPathCache.Key(
            font = font, glyphId = key, sizePx = sizePx, flipY = flipY,
        )
        GlyphPathCache.get(cacheKey)?.let { return it }
        val svg = rawSvgs[key] ?: return null
        val path = parseSvgPathToCompose(svg, flipY = flipY, scale = scale)
        GlyphPathCache.put(cacheKey, path)
        return path
    }

    override fun containsValue(value: Path): Boolean =
        throw UnsupportedOperationException("LazyGlyphPathMap.containsValue would force eager parse")

    override val values: Collection<Path>
        get() = throw UnsupportedOperationException("LazyGlyphPathMap.values would force eager parse")

    override val entries: Set<Map.Entry<Int, Path>>
        get() = throw UnsupportedOperationException("LazyGlyphPathMap.entries would force eager parse")
}

/**
 * Lazy `Map<Int, List<RecordedPaintOp>>` over the COLR v1 paint-tree
 * wire bytes emitted by `MeasuredFontPass`. Same lazy-decode story as
 * [LazyGlyphPathMap]: a paragraph with 200 emoji glyphs but only 8
 * visible pays for 8 paint-tree decodes, not 200.
 *
 * Decode follows the existing convention from `parseFontPass`: any
 * exception except `CancellationException` is swallowed so the
 * recorder can keep whatever ops it captured before the failure;
 * partial paint trees still draw better than blank glyphs.
 */
internal class LazyPaintTreeMap(
    private val rawBytes: Map<Int, ByteArray>,
) : Map<Int, List<RecordedPaintOp>> {

    private val parsed = HashMap<Int, List<RecordedPaintOp>>()

    override val size: Int get() = rawBytes.size
    override fun isEmpty(): Boolean = rawBytes.isEmpty()
    override fun containsKey(key: Int): Boolean = rawBytes.containsKey(key)
    override val keys: Set<Int> get() = rawBytes.keys

    override fun get(key: Int): List<RecordedPaintOp>? {
        parsed[key]?.let { return it }
        val bytes = rawBytes[key] ?: return null
        val recorder = RecordingPaintSink()
        try {
            PaintBufferParser.dispatch(bytes, recorder)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Bad / partial paint-tree wire bytes for this glyph - keep
            // whatever the recorder captured so the renderer can paint
            // partial trees instead of blanking the whole glyph. Mirrors
            // the same fallthrough that `parseFontPass` used to do
            // eagerly during build.
        }
        val ops = recorder.ops
        parsed[key] = ops
        return ops
    }

    override fun containsValue(value: List<RecordedPaintOp>): Boolean =
        throw UnsupportedOperationException("LazyPaintTreeMap.containsValue would force eager parse")

    override val values: Collection<List<RecordedPaintOp>>
        get() = throw UnsupportedOperationException("LazyPaintTreeMap.values would force eager parse")

    override val entries: Set<Map.Entry<Int, List<RecordedPaintOp>>>
        get() = throw UnsupportedOperationException("LazyPaintTreeMap.entries would force eager parse")
}
