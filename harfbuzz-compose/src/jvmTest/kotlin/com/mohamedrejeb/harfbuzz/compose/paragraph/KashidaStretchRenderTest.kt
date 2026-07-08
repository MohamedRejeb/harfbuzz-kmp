package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.compose.drawArcText
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.KashidaJustifier
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/**
 * Robolectric-free repro for the continuous-Kashida tashkeel gap + arc shape,
 * using the real font the user reported (TheYearofHandicrafts). Skips cleanly
 * if the font isn't present. Output: system tmp / harfbuzz-kashida.
 */
class KashidaStretchRenderTest {

    private val outDir = File(System.getProperty("java.io.tmpdir"), "harfbuzz-kashida").apply { mkdirs() }
    private val fontPath =
        "/Users/mohamedbenrejeb/IdeaProjects/AndalusiAndroid/.plans/fonts/TheYearofHandicrafts-Regular.ttf"

    @Test
    fun reproduce_poppins() = runBlocking {
        val path = "/Users/mohamedbenrejeb/IdeaProjects/AndalusiAndroid/.plans/fonts/Poppins-Regular.ttf"
        val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: run {
            println("SKIP: $path"); return@runBlocking
        }
        harfBuzzInit()
        val poppinsFace = HbFace.fromBytes(bytes)
        val poppins = poppinsFace.toFont()
        val notoFace = HbFace.fromBytes(TestFonts.notoNaskhArabicMedium())
        val noto = notoFace.toFont()
        try {
            // Latin primary + Arabic fallback — the Poppins case.
            val stack = HbFontStack.of(poppins, noto)
            val caps = stack.fonts.map { it.glyphIdForCodepoint(TATWEEL.code) }
            println("POPPINS per-font tatweel gids = $caps (primary should be 0, fallback > 0)")

            val text = "السلام عليكم"
            val natural = measure(stack, text)
            val kashidaWidth = measure(stack, TATWEEL.toString()).advance
            val target = natural.advance + 150f
            val jt = KashidaJustifier.justifyArabicLine(text, natural.advance, target, kashidaWidth).justifiedText
            val justified = measure(stack, jt)

            // Validate the .notdef signal the app's capability check relies on.
            fun notdefKashida(m: MeasuredText, text: String) = m.paragraph.runs.any { r ->
                r.glyphs.indices.any { r.glyphs[it].glyphId == 0 && text.getOrNull(r.glyphs[it].cluster) == TATWEEL }
            }
            println("POPPINS+fallback kashida notdef = ${notdefKashida(justified, jt)} (expect false → justify applies)")
            val only = HbFontStack.of(poppins)
            val jtOnly = KashidaJustifier.justifyArabicLine(
                text, measure(only, text).advance, target, measure(only, TATWEEL.toString()).advance,
            ).justifiedText
            println("POPPINS-only kashida notdef = ${notdefKashida(measure(only, jtOnly), jtOnly)} (expect true → falls back)")

            renderRows("poppins-justify", listOf(natural, justified, justified.stretchKashidaToWidth(target, jt)))
        } finally {
            poppins.close(); poppinsFace.close(); noto.close(); notoFace.close()
        }
    }

    @Test
    fun reproduce_phkhalid() = runBlocking {
        val path = "/Users/mohamedbenrejeb/IdeaProjects/AndalusiAndroid/.plans/fonts/PHKhalid-Regular.ttf"
        val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: run {
            println("SKIP: $path"); return@runBlocking
        }
        harfBuzzInit()
        val face = HbFace.fromBytes(bytes)
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val gid = font.glyphIdForCodepoint(TATWEEL.code)
            val adv = font.glyphAdvance(gid, SIZE_PX)
            val ext = font.glyphExtents(gid, SIZE_PX)
            val rb = adv - (ext?.xBearing ?: 0f) - (ext?.width ?: 0f)
            println("PHK tatweel gid=$gid advance=$adv xBearing=${ext?.xBearing} inkWidth=${ext?.width} rightBearing=$rb")
            measure(stack, "بـب").paragraph.runs.forEach { r ->
                r.glyphs.indices.forEach { i ->
                    if ("بـب".getOrNull(r.glyphs[i].cluster) == TATWEEL) {
                        println("PHKPROBE nominal=$gid contextual=${r.glyphs[i].glyphId} substitutes=${r.glyphs[i].glyphId != gid}")
                    }
                }
            }

            val text = "كيفك"
            val natural = measure(stack, text)
            val kashidaWidth = measure(stack, TATWEEL.toString()).advance
            val targets = listOf(natural.advance, natural.advance + 70f, natural.advance + 140f, natural.advance + 210f)
            val rows = targets.map { t ->
                val jt = KashidaJustifier.justifyArabicLine(text, natural.advance, t, kashidaWidth).justifiedText
                measure(stack, jt).stretchKashidaToWidth(t, jt)
            }
            renderRows("phk-kya-stretch", rows)

            // Same line on a curve — does the ink-aware stretch hold through the warp?
            val jtArc = KashidaJustifier.justifyArabicLine(text, natural.advance, natural.advance + 80f, kashidaWidth).justifiedText
            val arcBase = measure(stack, jtArc)
            arcRow("phk-kya-arc", listOf(arcBase.advance, arcBase.advance + 90f, arcBase.advance + 180f)) { t ->
                arcBase.stretchKashidaToWidth(t, jtArc)
            }
            // Does a SMALL per-kashida stretch warp cleanly? (decides the fix)
            arcRow("phk-small-arc", listOf(arcBase.advance + 4f, arcBase.advance + 10f, arcBase.advance + 24f)) { t ->
                arcBase.stretchKashidaToWidth(t, jtArc)
            }
            // Non-ligating font: factor should stay near 1 (→ continuous, no fallback).
            run {
                for (mult in listOf(1.3f, 1.8f, 2.5f)) {
                    val target = natural.advance * mult
                    val jt = KashidaJustifier.justifyArabicLine(text, natural.advance, target, kashidaWidth).justifiedText
                    val padded = measure(stack, jt)
                    var kAdv = 0f
                    padded.paragraph.runs.forEach { r ->
                        r.glyphs.indices.forEach { i ->
                            if (r.positions[i].xAdvance > 0f && jt.getOrNull(r.glyphs[i].cluster) == TATWEEL) kAdv += r.positions[i].xAdvance
                        }
                    }
                    val factor = if (kAdv > 0f) (target - (padded.advance - kAdv)) / kAdv else 1f
                    println("PHKFACTOR mult=$mult target=${target.toInt()} paddedAdv=${padded.advance.toInt()} factor=$factor")
                }
            }

            // Render the exact path the warp consumes (baselineGlyphPath), FLAT.
            // Connected here ⇒ warp bug; gappy here ⇒ buildBaselineGlyphPath bug.
            val sM = arcBase.stretchKashidaToWidth(arcBase.advance + 180f, jtArc)
            sM.paragraph.runs.forEach { r ->
                r.glyphs.indices.forEach { i ->
                    if (jtArc.getOrNull(r.glyphs[i].cluster) == TATWEEL) {
                        println("PHKARC tatweel gid=${r.glyphs[i].glyphId} xAdv=${r.positions[i].xAdvance} xScale=${r.positions[i].xScale}")
                    }
                }
            }
            renderPng("phk-baseline-flat", (sM.advance + 80f).toInt(), 220) {
                translate(left = 40f, top = 150f) { drawPath(sM.baselineGlyphPath, color = Color.Black) }
            }
        } finally {
            font.close()
            face.close()
        }
    }

    @Test
    fun reproduce_handicrafts() = runBlocking {
        val bytes = File(fontPath).takeIf { it.exists() }?.readBytes() ?: run {
            println("SKIP: font not found at $fontPath")
            return@runBlocking
        }
        harfBuzzInit()
        val face = HbFace.fromBytes(bytes)
        val font = face.toFont()
        try {
            val stack = HbFontStack(font)
            val gid = font.glyphIdForCodepoint(TATWEEL.code)
            val kashidaWidth = measure(stack, TATWEEL.toString()).advance
            val ext = font.glyphExtents(gid, SIZE_PX)
            val adv = font.glyphAdvance(gid, SIZE_PX)
            println("REPRO tatweelGid=$gid kashidaWidth=$kashidaWidth advance=$adv xBearing=${ext?.xBearing} inkWidth=${ext?.width} rightBearing=${adv - (ext?.xBearing ?: 0f) - (ext?.width ?: 0f)}")
            // Substitution probe: does an in-context tatweel shape to a NON-nominal gid?
            measure(stack, "بـب").paragraph.runs.forEach { r ->
                r.glyphs.indices.forEach { i ->
                    if ("بـب".getOrNull(r.glyphs[i].cluster) == TATWEEL) {
                        println("HCPROBE nominal=$gid contextual=${r.glyphs[i].glyphId} substitutes=${r.glyphs[i].glyphId != gid}")
                    }
                }
            }
            // EXACT app flow: integer kashida fill to the arc length, then the small
            // (~1.2×) residual stretch. This is what actually renders on device.
            run {
                val natAdv = measure(stack, PLAIN).advance
                val targets = listOf(natAdv * 1.3f, natAdv * 1.8f, natAdv * 2.5f)
                val finals = targets.associateWith { target ->
                    val jt = KashidaJustifier.justifyArabicLine(PLAIN, natAdv, target, kashidaWidth).justifiedText
                    measure(stack, jt).stretchKashidaToWidth(target, jt)
                }
                arcRow("hc-app-arc", targets) { target -> finals.getValue(target) }
                // PURE integer fill, NO stretch — does the font's own elongation warp connected?
                val intFinals = targets.associateWith { target ->
                    val jt = KashidaJustifier.justifyArabicLine(PLAIN, natAdv, target, kashidaWidth).justifiedText
                    measure(stack, jt)
                }
                arcRow("hc-int-arc", targets) { target -> intFinals.getValue(target) }
                // How far short does the integer fill land (→ why the circle won't close)?
                // And would using the IN-CONTEXT tatweel width close it?
                val ctxW = measure(stack, "بـب").advance - measure(stack, "بب").advance
                println("HCCTX standalone=$kashidaWidth contextual=$ctxW")
                targets.forEach { target ->
                    val stdFill = intFinals.getValue(target).advance
                    val ctxJt = KashidaJustifier.justifyArabicLine(PLAIN, natAdv, target, ctxW).justifiedText
                    val ctxFill = measure(stack, ctxJt).advance
                    println("HCCLOSE target=${target.toInt()} stdFill=${stdFill.toInt()}(${(stdFill / target * 100).toInt()}%) ctxFill=${ctxFill.toInt()}(${(ctxFill / target * 100).toInt()}%)")
                }
                // Mirror the engine's measure-and-correct loop → does the fill reach the target?
                val mcFinals = targets.associateWith { target ->
                    // Insert exactly `count` tatweels via a computed target (lib has no
                    // count API; the app's justifyWithKashidaCount does the same).
                    fun padTo(count: Int) = KashidaJustifier.justifyArabicLine(
                        PLAIN, natAdv, natAdv + count * kashidaWidth + 1f, kashidaWidth,
                    ).justifiedText
                    var count = ((target - natAdv) / kashidaWidth).toInt().coerceAtLeast(1)
                    var iters = 0
                    while (iters < 4) {
                        val a = measure(stack, padTo(count)).advance
                        if (a >= target) break
                        val per = (a - natAdv) / count
                        if (per <= 0f) break
                        val add = ((target - a) / per).toInt() // floor: never overshoot
                        if (add <= 0) break
                        count += add
                        iters++
                    }
                    val m = measure(stack, padTo(count))
                    println("HCMC target=${target.toInt()} count=$count advance=${m.advance.toInt()} fill%=${(m.advance / target * 100).toInt()} iters=$iters")
                    m
                }
                arcRow("hc-mc-arc", targets) { target -> mcFinals.getValue(target) }

                // SHORT text (few joins): measure-and-correct packs many tatweels per
                // join → long ligated forms. Does that gap the warp (the regression)?
                val short = "ببب"
                val natShort = measure(stack, short).advance
                val shortTarget = natShort + 260f
                fun padShort(c: Int) =
                    KashidaJustifier.justifyArabicLine(short, natShort, natShort + c * kashidaWidth + 1f, kashidaWidth).justifiedText
                var sc = ((shortTarget - natShort) / kashidaWidth).toInt().coerceAtLeast(1)
                var si = 0
                while (si < 4) {
                    val a = measure(stack, padShort(sc)).advance
                    if (a >= shortTarget) break
                    val per = (a - natShort) / sc
                    if (per <= 0f) break
                    sc += kotlin.math.ceil((shortTarget - a) / per).toInt().coerceAtLeast(1)
                    si++
                }
                val shortM = measure(stack, padShort(sc))
                println("HCSHORT count=$sc advance=${shortM.advance.toInt()} target=${shortTarget.toInt()}")
                arcRow("hc-short-mc-arc", listOf(shortM.advance)) { shortM }
            }

            // Simulate the app's arc fill (integer kashida to target) then the
            // stretch FACTOR it would need — the per-font fallback signal. Ligating
            // fonts should balloon past the 1.6 threshold (→ integer fallback).
            run {
                val natAdv = measure(stack, PLAIN).advance
                for (mult in listOf(1.3f, 1.8f, 2.5f)) {
                    val target = natAdv * mult
                    val jt = KashidaJustifier.justifyArabicLine(PLAIN, natAdv, target, kashidaWidth).justifiedText
                    val padded = measure(stack, jt)
                    var kAdv = 0f
                    padded.paragraph.runs.forEach { r ->
                        r.glyphs.indices.forEach { i ->
                            if (r.positions[i].xAdvance > 0f && jt.getOrNull(r.glyphs[i].cluster) == TATWEEL) kAdv += r.positions[i].xAdvance
                        }
                    }
                    val factor = if (kAdv > 0f) (target - (padded.advance - kAdv)) / kAdv else 1f
                    println("HCFACTOR mult=$mult target=${target.toInt()} paddedAdv=${padded.advance.toInt()} factor=$factor")
                }
            }

            // ── tashkeel gap: dump run + render flat stretch ────────────────
            val natural = measure(stack, TASHKEEL)
            val target = natural.advance + 140f
            val justifiedText = KashidaJustifier
                .justifyArabicLine(TASHKEEL, natural.advance, target, kashidaWidth)
                .justifiedText
            println("REPRO justified='${justifiedText}' (len ${justifiedText.length} vs ${TASHKEEL.length})")
            val justified = measure(stack, justifiedText)
            dumpRun("natural", justified)
            val stretched = justified.stretchKashidaToWidth(target, justifiedText)
            dumpRun("stretched", stretched)

            renderRows("tashkeel-flat", listOf(natural, justified, stretched))

            // ── tashkeel on arc ─────────────────────────────────────────────
            arcRow("tashkeel-arc", listOf(justified.advance, target, target + 120f)) { t ->
                justified.stretchKashidaToWidth(t, justifiedText)
            }

            // ── plain high-sweep arc (no tashkeel): is the warp circular? ───
            val plain = measure(stack, PLAIN)
            renderArcSweeps("plain-arc-sweeps", plain)

            // ── app-pattern: kashida AFTER each mark (mimics the app's
            //    tashkeel-aware insertion). Decides whether marks track. ──────
            val appPattern = ("" + BAA + FATHA + TATWEEL).repeat(4) + BAA + FATHA
            val appM = measure(stack, appPattern)
            dumpRun("appPattern-natural", appM)
            arcRow("arc-appattern", listOf(appM.advance, appM.advance + 110f, appM.advance + 230f)) { t ->
                appM.stretchKashidaToWidth(t, appPattern)
            }
            renderRows(
                "appattern-flat",
                listOf(appM, appM.stretchKashidaToWidth(appM.advance + 230f, appPattern)),
            )
        } finally {
            font.close()
            face.close()
        }
    }

    private fun dumpRun(tag: String, m: MeasuredText) {
        var pen = 0f
        m.paragraph.runs.forEach { run ->
            run.glyphs.forEachIndexed { i, g ->
                val p = run.positions[i]
                println(
                    "DUMP[$tag] i=$i gid=${g.glyphId} cl=${g.cluster} " +
                        "xAdv=${p.xAdvance} xOff=${p.xOffset} xScale=${p.xScale} " +
                        "penX=$pen drawX=${pen + p.xOffset}",
                )
                pen += p.xAdvance
            }
        }
        println("DUMP[$tag] total=${m.advance}")
    }

    private fun renderRows(name: String, rows: List<MeasuredText>) {
        val margin = 40f
        val rowH = SIZE_PX * 2.0f
        val w = (margin * 2 + rows.maxOf { it.advance }).toInt()
        val h = (margin * 2 + rowH * rows.size).toInt()
        renderPng(name, w, h) {
            rows.forEachIndexed { i, m ->
                val top = margin + rowH * i
                drawRect(Color(0xFFEDEDED), Offset(margin, top), Size(m.advance, rowH * 0.9f), style = Stroke(1f))
                drawShapedText(m, topLeft = Offset(margin, top + rowH * 0.45f), color = Color.Black)
            }
        }
    }

    private fun arcRow(name: String, targets: List<Float>, build: (Float) -> MeasuredText) {
        val radius = 150f
        val colW = 420
        renderPng(name, colW * targets.size, 360) {
            targets.forEachIndexed { i, t ->
                val m = build(t)
                val sweepDeg = ((m.advance / radius) * (180f / PI.toFloat())).coerceAtMost(330f)
                drawArcText(
                    measured = m,
                    center = Offset(colW * i + colW / 2f, 250f),
                    radiusPx = radius,
                    startAngleDeg = -90f - sweepDeg / 2f,
                    sweepAngleDeg = sweepDeg,
                    color = Color.Black,
                )
            }
        }
    }

    private fun renderArcSweeps(name: String, m: MeasuredText) {
        // Fixed text, decreasing radius => increasing sweep toward a full circle.
        val sweeps = listOf(90f, 180f, 270f, 330f)
        val cell = 360
        renderPng(name, cell * sweeps.size, cell) {
            sweeps.forEachIndexed { i, sweepDeg ->
                val radius = (m.advance / (sweepDeg * PI.toFloat() / 180f))
                drawArcText(
                    measured = m,
                    center = Offset(cell * i + cell / 2f, cell / 2f),
                    radiusPx = radius,
                    startAngleDeg = -90f - sweepDeg / 2f,
                    sweepAngleDeg = sweepDeg,
                    color = Color.Black,
                )
            }
        }
    }

    private suspend fun measure(stack: HbFontStack, text: String): MeasuredText =
        buildMeasuredParagraph(
            text = text,
            fontStack = stack,
            sizePx = SIZE_PX,
            maxWidth = Float.MAX_VALUE,
            alignment = ParagraphAlignment.Start,
            direction = HbDirection.RTL,
            features = emptyList(),
            language = HbLanguage.AUTO,
            lineSpacing = 0f,
            justification = JustificationStrategy.None,
            letterSpacing = 0f,
        ).lines.first().measured

    private fun renderPng(name: String, w: Int, h: Int, block: DrawScope.() -> Unit) {
        val surface = Surface.makeRasterN32Premul(w, h)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = surface.canvas.asComposeCanvas(),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(Color.White, size = Size(w.toFloat(), h.toFloat()))
            block()
        }
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG encode failed for $name")
        File(outDir, "$name.png").writeBytes(png)
    }

    private companion object {
        const val TATWEEL = 'ـ'
        const val FATHA = 'َ'
        const val BAA = 'ب'
        const val SIZE_PX = 64f
        const val PLAIN = "السلام عليكم"
        const val TASHKEEL = "السَّلَامُ عَلَيْكُمْ"
    }
}
