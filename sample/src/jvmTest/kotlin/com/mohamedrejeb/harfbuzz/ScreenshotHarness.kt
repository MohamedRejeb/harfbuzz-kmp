package com.mohamedrejeb.harfbuzz

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Screenshot-test harness for Compose Multiplatform Desktop.
 *
 * Usage:
 *
 * ```kotlin
 * @get:Rule val rule = createComposeRule()
 *
 * @Test fun arc_text_arabic_naskh_outside() {
 *     rule.captureGolden("arc_text_arabic_naskh_outside.png") {
 *         ArcText(text = "...", font = ...)
 *     }
 * }
 * ```
 *
 * On first run (or with `-Dkotlin.harfbuzz.regenerate.goldens=true`) the test
 * writes the captured image to `src/jvmTest/resources/goldens/<name>.png` so
 * it can be reviewed and committed. On subsequent runs, the captured image
 * is compared against the committed golden with a small per-pixel tolerance
 * - anti-aliasing differences across machines / OS versions are inevitable
 * and the threshold is generous enough to absorb them while still catching
 * structural regressions (wrong glyph order, missing rotation, etc.).
 */
internal const val DEFAULT_TOLERANCE: Double = 0.02   // 2 % allowed pixel difference

internal fun ComposeContentTestRule.captureGolden(
    goldenName: String,
    tolerance: Double = DEFAULT_TOLERANCE,
    content: @Composable () -> Unit,
) {
    setContent { content() }
    // Drain the async font load + measured-text build chain. The work runs on
    // a background dispatcher (`harfbuzz-bg` thread on JVM), so `waitForIdle`
    // alone is not enough - it only pumps Compose's snapshot queue and ignores
    // suspended `withContext(harfbuzzDispatcher)` work. We alternate a short
    // real-time sleep with `waitForIdle()` so the bg thread has time to
    // (a) finish shaping, (b) post the produceState update, and
    // (c) trigger the resulting recomposition. Color emoji is the worst case:
    // shaping -> per-glyph SVG slicing -> SVGDOM raster -> re-compose. The
    // entire orchestration now runs off-Main (see [runShapingWork]), so the
    // drain must cover the wall-clock cost of all glyphs serialised on the
    // single `harfbuzz-bg` thread - a paragraph with ~8 emoji at 64 px takes
    // ~300–500 ms cold (Skia SVGDOM raster dominates).
    repeat(50) {
        Thread.sleep(50)
        waitForIdle()
    }

    val captured: ImageBitmap = onRoot().captureToImage()
    val capturedBytes = captured.encodeAsPng()

    val regenerate = System.getProperty("kotlin.harfbuzz.regenerate.goldens") == "true"
    val goldenFile = File(goldensDirectory(), goldenName)

    if (regenerate || !goldenFile.exists()) {
        goldenFile.parentFile.mkdirs()
        goldenFile.writeBytes(capturedBytes)
        if (regenerate) {
            println("[golden] regenerated $goldenName at ${goldenFile.absolutePath}")
        } else {
            println("[golden] bootstrapped $goldenName at ${goldenFile.absolutePath}")
        }
        return
    }

    val goldenBytes = goldenFile.readBytes()
    val diff = comparePngs(goldenBytes, capturedBytes, tolerance)
    if (diff != null) {
        // Write the failed capture next to the golden for visual diffing.
        val failed = File(goldenFile.parentFile, "${goldenName}.actual.png")
        failed.writeBytes(capturedBytes)
        throw AssertionError(
            "Screenshot mismatch for '$goldenName' " +
                "($diff differing pixels above tolerance ${"%.4f".format(tolerance)}). " +
                "See ${failed.absolutePath} and re-run with " +
                "`-Dkotlin.harfbuzz.regenerate.goldens=true` if the new output is correct.",
        )
    }
}

private fun goldensDirectory(): File {
    // Tests run from the module root; the resources dir is alongside source.
    val candidates = listOf(
        File("sample/src/jvmTest/resources/goldens"),
        File("src/jvmTest/resources/goldens"),
    )
    return candidates.firstOrNull { it.parentFile?.exists() == true }
        ?: File("sample/src/jvmTest/resources/goldens")
}

private fun ImageBitmap.encodeAsPng(): ByteArray {
    // Compose Desktop's ImageBitmap is backed by a Skia bitmap. Skia can
    // encode PNG directly without round-tripping through AWT, which preserves
    // the exact pixel data we drew.
    val skiaImage: Image = Image.makeFromBitmap(toSkiaBitmap())
    return skiaImage.encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: error("Skia PNG encode returned null")
}

private fun ImageBitmap.toSkiaBitmap(): org.jetbrains.skia.Bitmap {
    // Compose Desktop's ImageBitmap is wrapped via SkiaImageBitmap; reach in
    // via the standard helper. Falls back to AWT round-trip if the cast
    // fails - slower but robust.
    return try {
        val cls = Class.forName("androidx.compose.ui.graphics.SkiaImageBitmap")
        val method = cls.getMethod("getBitmap")
        method.invoke(this) as org.jetbrains.skia.Bitmap
    } catch (_: Throwable) {
        val awt = toAwtImage()
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(awt, "png", it) }.toByteArray()
        org.jetbrains.skia.Bitmap.makeFromImage(Image.makeFromEncoded(bytes))
    }
}

/** Returns null if images match within tolerance; otherwise the offending pixel count. */
private fun comparePngs(goldenBytes: ByteArray, capturedBytes: ByteArray, tolerance: Double): Int? {
    if (goldenBytes.contentEquals(capturedBytes)) return null

    val golden = Image.makeFromEncoded(goldenBytes)
    val captured = Image.makeFromEncoded(capturedBytes)
    if (golden.width != captured.width || golden.height != captured.height) {
        return Int.MAX_VALUE
    }
    // peekPixels returns null when the decoded image isn't backed by a
    // CPU-readable raster (e.g. when Skia keeps it in a lazy / encoded
    // form). Fall back to AWT BufferedImage decoding which always
    // produces a raster we can read directly.
    val gPixels = readArgbBytes(goldenBytes) ?: return Int.MAX_VALUE
    val cPixels = readArgbBytes(capturedBytes) ?: return Int.MAX_VALUE
    if (gPixels.size != cPixels.size) return Int.MAX_VALUE

    var diffPixels = 0
    var i = 0
    while (i < gPixels.size) {
        val dR = abs(gPixels[i].toInt() - cPixels[i].toInt())
        val dG = abs(gPixels[i + 1].toInt() - cPixels[i + 1].toInt())
        val dB = abs(gPixels[i + 2].toInt() - cPixels[i + 2].toInt())
        // Ignore alpha differences (often vary due to compositing).
        if (dR > 8 || dG > 8 || dB > 8) diffPixels++
        i += 4
    }
    val totalPixels = (gPixels.size / 4)
    val ratio = diffPixels.toDouble() / totalPixels
    return if (ratio > tolerance) diffPixels else null
}

/**
 * Decode [bytes] as a PNG via AWT and pull the pixel data as flat
 * RGBA8888. Returns `null` if AWT can't decode the image at all - any
 * other failure (truncated image, exotic colour profile) bubbles up
 * intentionally so the harness fails loud rather than silently passing
 * a bad capture.
 */
private fun readArgbBytes(bytes: ByteArray): ByteArray? {
    val img = ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return null
    val w = img.width
    val h = img.height
    val rgb = IntArray(w * h)
    img.getRGB(0, 0, w, h, rgb, 0, w)
    val out = ByteArray(rgb.size * 4)
    for (i in rgb.indices) {
        val argb = rgb[i]
        // BufferedImage.getRGB returns ARGB ints regardless of the
        // image's stored colour model. Convert to RGBA8888 to match
        // what the comparison loop expects.
        out[i * 4] = ((argb shr 16) and 0xFF).toByte()
        out[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()
        out[i * 4 + 2] = (argb and 0xFF).toByte()
        out[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()
    }
    return out
}

/** Convenience to construct the test rule from harness imports. */
internal fun composeRule(): ComposeContentTestRule = createComposeRule()
