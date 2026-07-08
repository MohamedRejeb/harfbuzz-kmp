package com.mohamedrejeb.harfbuzz.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.buildMeasuredText
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.SystemFallback
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * One-shot tool that renders the font-picker thumbnails for every font in
 * the designData API: the font's curated display name, drawn with the font
 * itself, black on a transparent canvas of fixed height. Color fonts
 * (COLR/SVG tables) keep their designed palette instead of black.
 *
 * Input is a TSV produced from the API dump (see `sample/thumbgen/`):
 * `postscript, lang, text, fallback, url, group, full_name`. The url column
 * accepts a local path (relative to the working dir) for fonts whose API
 * download is broken. Output is one `<postscript>.png` per row plus a
 * `report.tsv` describing what was rendered (and any fallbacks or failures).
 *
 * Rendering is pure vector at the final size: glyph outlines are shaped and
 * drawn by Skia's analytic path anti-aliasing directly at the output pixel
 * size, then the ink box is blitted 1:1 onto the final canvas. No raster
 * resampling touches the text (an earlier oversample-then-minify pipeline
 * produced visibly aliased edges — cubic filters undersample at 4x
 * minification).
 *
 * Run with:
 * `./gradlew :sample:generateFontThumbnails [-PthumbFilter=Ps1,Ps2]`
 */

/**
 * Extra output resolution on top of the reference layout: the layout is
 * designed in the old thumbnails' 64px-height space, then emitted at
 * OUTPUT_SCALE× so text stays crisp on high-density screens.
 */
private const val OUTPUT_SCALE = 3

/** Final canvas height; 64px reference height × [OUTPUT_SCALE]. */
private const val CANVAS_HEIGHT = 64 * OUTPUT_SCALE

/** Horizontal padding each side; the demo thumb uses 14px on 64px height. */
private const val HORIZONTAL_PADDING = 14 * OUTPUT_SCALE

/** Ink taller than this is re-shaped at a smaller size to fit the canvas. */
private const val MAX_INK_HEIGHT = 60 * OUTPUT_SCALE

/**
 * Font size in the 64px reference space. Calibrated so the demo font
 * (THARWAT EMARA RUQAA, "عمارة") reproduces the reference thumbnail's ink
 * height of 36px on the 64px canvas.
 */
private const val TARGET_FONT_PX = 56f

/** Font size actually rendered: reference size × output scale. */
private const val FONT_PX = TARGET_FONT_PX * OUTPUT_SCALE

private data class ThumbJob(
    val postscript: String,
    val lang: String,
    val text: String,
    val fallback: String,
    val url: String,
    val group: String,
    val fullName: String,
)

private data class ThumbResult(
    val job: ThumbJob,
    val status: String,
    val textUsed: String,
    val width: Int,
    val height: Int,
    val note: String = "",
)

fun main(args: Array<String>) {
    val jobsFile = File(args.getOrElse(0) { "thumbgen/thumb-names.tsv" })
    val outRoot = File(args.getOrElse(1) { "build/font-thumbs" })
    val filter = args.getOrNull(2)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()

    require(jobsFile.exists()) { "Jobs file not found: ${jobsFile.absolutePath}" }
    val allJobs = parseJobs(jobsFile)
    val jobs = if (filter == null) allJobs else allJobs.filter { it.postscript in filter }
    require(jobs.isNotEmpty()) { "No jobs matched (filter=$filter)" }

    val fontsDir = File(outRoot, "fonts").apply { mkdirs() }
    val outDir = File(outRoot, "out").apply { mkdirs() }

    val results = mutableListOf<ThumbResult>()
    jobs.forEachIndexed { index, job ->
        val result = runCatching { generateOne(job, fontsDir, outDir) }
            .getOrElse { e ->
                ThumbResult(job, "ERROR", "", 0, 0, "${e::class.simpleName}: ${e.message}")
            }
        results.add(result)
        println(
            "[${index + 1}/${jobs.size}] ${result.status.padEnd(9)} " +
                "${job.postscript}  \"${result.textUsed}\"  " +
                "${result.width}x${result.height}  ${result.note}",
        )
    }

    writeReport(File(outRoot, "report.tsv"), results)
    val counts = results.groupingBy { it.status }.eachCount()
    println("\nDone: $counts")
    println("Output: ${outDir.absolutePath}")
}

private fun parseJobs(file: File): List<ThumbJob> =
    file.readLines()
        .drop(1)
        .filter { it.isNotBlank() }
        .map { line ->
            val cols = line.split('\t')
            require(cols.size >= 7) { "Malformed TSV row: $line" }
            ThumbJob(cols[0], cols[1], cols[2], cols[3], cols[4], cols[5], cols[6])
        }
        .also { jobs ->
            val dup = jobs.groupingBy { it.postscript }.eachCount().filterValues { it > 1 }
            require(dup.isEmpty()) { "Duplicate postscript names would collide: ${dup.keys}" }
        }

private fun generateOne(job: ThumbJob, fontsDir: File, outDir: File): ThumbResult {
    val fontFile = resolveFont(job, fontsDir)
    val fontBytes = fontFile.readBytes()

    return runBlocking {
        val face = HbFace.from { bytes(fontBytes) }
        val font = face.toFont()
        try {
            val stack = HbFontStack(font, emptyList(), SystemFallback.None)

            suspend fun measure(text: String, lang: String, sizePx: Float): Pair<MeasuredText, Int> {
                val measured = buildMeasuredText(
                    text = text,
                    fontStack = stack,
                    sizePx = sizePx,
                    features = emptyList(),
                    direction = HbDirection.AUTO,
                    language = if (lang == "ar") HbLanguage.ARABIC else HbLanguage.ENGLISH,
                )
                return measured to countNotdefs(measured, text)
            }

            var textUsed = job.text
            var langUsed = job.lang
            var (measured, notdefs) = measure(job.text, job.lang, FONT_PX)
            var status = "OK"
            var note = ""

            if (notdefs > 0 && job.fallback.isNotBlank() && job.fallback != job.text) {
                val (fbMeasured, fbNotdefs) = measure(job.fallback, "en", FONT_PX)
                if (fbNotdefs < notdefs) {
                    textUsed = job.fallback
                    langUsed = "en"
                    measured = fbMeasured
                    status = "FALLBACK"
                    note = "primary \"${job.text}\" had $notdefs missing glyphs"
                    notdefs = fbNotdefs
                }
            }
            if (notdefs > 0) {
                status = "COVERAGE"
                note = "$notdefs missing glyphs in \"$textUsed\""
            }

            var rendered = renderWithMargin(measured)
            var ink = findInkBounds(rendered)
                ?: return@runBlocking ThumbResult(job, "ERROR", textUsed, 0, 0, "empty ink (nothing drawn)")

            // Over-tall ink (dramatic ascenders/flourishes): re-shape at a
            // smaller size instead of shrinking pixels, so the output stays a
            // pure vector rasterization. Ink height is ~linear in font size;
            // the 0.98 undershoot absorbs the nonlinearity in 1-2 passes.
            var attempts = 0
            while (ink.height > MAX_INK_HEIGHT && attempts < 3) {
                val fit = MAX_INK_HEIGHT.toFloat() / ink.height * 0.98f
                measured = measure(textUsed, langUsed, measured.sizePx * fit).first
                rendered = renderWithMargin(measured)
                ink = findInkBounds(rendered)
                    ?: return@runBlocking ThumbResult(job, "ERROR", textUsed, 0, 0, "empty ink after refit")
                attempts++
            }
            if (attempts > 0 && note.isEmpty()) note = "refit to ${measured.sizePx.toInt()}px to cap ink height"
            if (measured.hasColorGlyphs) note = listOf(note, "color font (designed palette kept)").filter { it.isNotEmpty() }.joinToString("; ")

            val (png, w, h) = composeFinal(rendered, ink)
            File(outDir, "${job.postscript}.png").writeBytes(png)

            val nameNote = unusualNameNote(job.postscript)
            ThumbResult(job, status, textUsed, w, h, listOf(note, nameNote).filter { it.isNotEmpty() }.joinToString("; "))
        } finally {
            font.close()
            face.close()
        }
    }
}

private fun resolveFont(job: ThumbJob, fontsDir: File): File {
    // Non-http entries are local file paths (relative to the working dir):
    // used for fonts whose API download is broken and a fixed copy is
    // checked in under thumbgen/fonts/.
    if (!job.url.startsWith("http")) {
        val local = File(job.url)
        require(local.exists()) { "Local font not found: ${local.absolutePath}" }
        return local
    }
    val extension = job.url.substringAfterLast('.', "ttf").take(5)
    val safeName = job.postscript.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
    val target = File(fontsDir, "$safeName.$extension")
    if (target.exists() && target.length() > 0) return target

    val connection = URI(job.url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 60_000
    try {
        require(connection.responseCode == 200) { "HTTP ${connection.responseCode} for ${job.url}" }
        val bytes = connection.inputStream.use { it.readBytes() }
        require(bytes.isNotEmpty()) { "Empty font download: ${job.url}" }
        target.writeBytes(bytes)
    } finally {
        connection.disconnect()
    }
    return target
}

/** Count glyphs shaped to `.notdef` (id 0), ignoring whitespace clusters. */
private fun countNotdefs(measured: MeasuredText, text: String): Int {
    var count = 0
    for (run in measured.paragraph.runs) {
        for (info in run.glyphs) {
            if (info.glyphId != 0) continue
            val ch = text.getOrNull(info.cluster)
            if (ch == null || !ch.isWhitespace()) count++
        }
    }
    return count
}

/**
 * Draw the measured line, black on transparent, at its shaped size (1:1 -
 * this bitmap's ink is blitted to the output without resampling). The margin
 * gives overhanging swashes room; if ink still touches an edge the margin
 * doubles and the draw retries.
 */
private fun renderWithMargin(measured: MeasuredText): ImageBitmap {
    var margin = measured.sizePx
    repeat(3) { attempt ->
        val width = ceil(max(measured.advance, measured.ink.width) + 2 * margin).toInt().coerceAtLeast(8)
        val height = ceil(measured.ascent + measured.descent + 2 * margin).toInt().coerceAtLeast(8)
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(width.toFloat(), height.toFloat()),
        ) {
            // Monochrome glyphs take the black foreground; color fonts
            // (COLR/SVG tables, e.g. Aref Ruqaa Ink, Nabla) keep their
            // designed palette — their look IS the font style.
            drawShapedText(
                measured = measured,
                topLeft = Offset(margin, margin),
                color = Color.Black,
            )
        }
        val ink = findInkBounds(bitmap)
        val touchesEdge = ink != null && (
            ink.left <= 1 || ink.top <= 1 ||
                ink.right >= width - 2 || ink.bottom >= height - 2
            )
        if (!touchesEdge || attempt == 2) return bitmap
        margin *= 2
    }
    error("unreachable")
}

private data class InkBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1
}

/** Tight bounding box of pixels with alpha > 0, or null if fully transparent. */
private fun findInkBounds(bitmap: ImageBitmap): InkBox? {
    val pixels = bitmap.toPixelMap()
    var left = bitmap.width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    val buffer = pixels.buffer
    for (y in 0 until bitmap.height) {
        val rowStart = pixels.bufferOffset + y * pixels.stride
        for (x in 0 until bitmap.width) {
            if (buffer[rowStart + x] ushr 24 == 0) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    return if (right < 0) null else InkBox(left, top, right, bottom)
}

/**
 * Blit the ink box 1:1 onto the final fixed-height transparent canvas: ink
 * vertically centered, fixed padding left and right, width following the
 * text. No scaling happens here (the refit loop in [generateOne] guarantees
 * the ink fits), so the vector rasterization reaches the PNG untouched.
 */
private fun composeFinal(rendered: ImageBitmap, ink: InkBox): Triple<ByteArray, Int, Int> {
    val canvasWidth = ink.width + 2 * HORIZONTAL_PADDING
    val surface = Surface.makeRasterN32Premul(canvasWidth, CANVAS_HEIGHT)
    val source = Image.makeFromBitmap(rendered.asSkiaBitmap())
    val destinationTop = ((CANVAS_HEIGHT - ink.height) / 2f).toInt().toFloat()
    surface.canvas.drawImageRect(
        source,
        org.jetbrains.skia.Rect.makeXYWH(
            ink.left.toFloat(),
            ink.top.toFloat(),
            ink.width.toFloat(),
            ink.height.toFloat(),
        ),
        org.jetbrains.skia.Rect.makeXYWH(
            HORIZONTAL_PADDING.toFloat(),
            destinationTop,
            ink.width.toFloat(),
            ink.height.toFloat(),
        ),
        SamplingMode.DEFAULT,
        null,
        true,
    )
    val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: error("PNG encode failed")
    return Triple(png, canvasWidth, CANVAS_HEIGHT)
}

private fun unusualNameNote(postscript: String): String {
    val unusual = postscript.filter { !it.isLetterOrDigit() && it !in "._-" }.toSet()
    return if (unusual.isEmpty()) "" else "filename has unusual chars: $unusual"
}

private fun writeReport(file: File, results: List<ThumbResult>) {
    file.parentFile.mkdirs()
    file.writeText(
        buildString {
            appendLine("postscript\tstatus\ttext_used\twidth\theight\tnote")
            results.forEach { r ->
                appendLine("${r.job.postscript}\t${r.status}\t${r.textUsed}\t${r.width}\t${r.height}\t${r.note}")
            }
        },
    )
}
