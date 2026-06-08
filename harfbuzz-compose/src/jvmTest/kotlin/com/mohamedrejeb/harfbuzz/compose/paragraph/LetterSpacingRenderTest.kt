package com.mohamedrejeb.harfbuzz.compose.paragraph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.mohamedrejeb.harfbuzz.compose.TestFonts
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbLanguage
import com.mohamedrejeb.harfbuzz.core.harfBuzzInit
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/**
 * Deterministic, Robolectric-free render of multi-line + aligned +
 * letter-spaced paragraphs to PNG (plain JVM skiko). Each render outlines
 * the alignment box so per-line positioning is visible. Output: /tmp/andalusi-ls.
 */
class LetterSpacingRenderTest {

    private val outDir = File(System.getProperty("java.io.tmpdir"), "harfbuzz-letter-spacing").apply { mkdirs() }
    private val handles = mutableListOf<AutoCloseable>()

    @Test
    fun render_all() = runBlocking {
        try {
            render("latin-center-ls", LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Center, 28f, 6f)
            render("latin-right-ls", LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Right, 28f, 6f)
            render("latin-center-nols", LATIN, TestFonts.robotoRegular(), ParagraphAlignment.Center, 28f, 0f)
            render("arabic-center-ls", ARABIC, TestFonts.notoNaskhArabicMedium(), ParagraphAlignment.Center, 34f, 8f, HbDirection.RTL)
            render("arabic-right-ls", ARABIC, TestFonts.notoNaskhArabicMedium(), ParagraphAlignment.Right, 34f, 8f, HbDirection.RTL)
            render("arabic-center-nols", ARABIC, TestFonts.notoNaskhArabicMedium(), ParagraphAlignment.Center, 34f, 0f, HbDirection.RTL)
            println("[LetterSpacingRenderTest] wrote PNGs to ${outDir.absolutePath}")
        } finally {
            handles.reversed().forEach { it.close() }
            handles.clear()
        }
    }

    private suspend fun render(
        name: String,
        text: String,
        font: ByteArray,
        alignment: ParagraphAlignment,
        sizePx: Float,
        letterSpacing: Float,
        direction: HbDirection = HbDirection.AUTO,
    ) {
        val boxW = 380f
        val margin = 30f
        val canvasW = (boxW + margin * 2).toInt()
        val canvasH = 260

        harfBuzzInit()
        val face = HbFace.fromBytes(font)
        val hbFont = face.toFont()
        handles.add(hbFont)
        handles.add(face)

        val paragraph = buildMeasuredParagraph(
            text = text,
            fontStack = HbFontStack(hbFont),
            sizePx = sizePx,
            maxWidth = boxW,
            alignment = alignment,
            direction = direction,
            features = emptyList(),
            language = HbLanguage.AUTO,
            lineSpacing = sizePx * 0.4f,
            justification = JustificationStrategy.None,
            letterSpacing = letterSpacing,
        )

        val surface = Surface.makeRasterN32Premul(canvasW, canvasH)
        val composeCanvas = surface.canvas.asComposeCanvas()
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = composeCanvas,
            size = Size(canvasW.toFloat(), canvasH.toFloat()),
        ) {
            drawRect(Color.White, size = Size(canvasW.toFloat(), canvasH.toFloat()))
            // Outline the alignment box [margin, margin+boxW] so per-line
            // centering / right-edge alignment is visible against it.
            drawRect(
                color = Color(0xFFE0E0E0),
                topLeft = Offset(margin, margin),
                size = Size(boxW, canvasH - margin * 2),
                style = Stroke(width = 1f),
            )
            drawShapedParagraph(
                paragraph = paragraph,
                topLeft = Offset(margin, margin),
                color = Color.Black,
            )
        }
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: error("PNG encode failed for $name")
        val file = File(outDir, "$name.png")
        file.writeBytes(png)
        assertTrue(png.size > 500, "render produced an empty/trivial PNG for $name (${png.size} bytes)")
    }

    private companion object {
        const val LATIN = "The quick brown fox jumps over the lazy dog and runs away"
        const val ARABIC = "السلام عليكم ورحمة الله وبركاته اهلا وسهلا بكم في تطبيقنا"
    }
}
