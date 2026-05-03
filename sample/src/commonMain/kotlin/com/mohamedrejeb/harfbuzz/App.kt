package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.Font
import com.mohamedrejeb.harfbuzz.compose.ArcSide
import com.mohamedrejeb.harfbuzz.compose.ArcText
import com.mohamedrejeb.harfbuzz.compose.FaceLoad
import com.mohamedrejeb.harfbuzz.compose.FontLoad
import com.mohamedrejeb.harfbuzz.compose.MeasuredText
import com.mohamedrejeb.harfbuzz.compose.MeasuredTextLoad
import com.mohamedrejeb.harfbuzz.compose.ShapedText
import com.mohamedrejeb.harfbuzz.compose.ShapedTextOverflow
import com.mohamedrejeb.harfbuzz.compose.drawShapedText
import com.mohamedrejeb.harfbuzz.compose.rememberHbFace
import com.mohamedrejeb.harfbuzz.compose.rememberHbFont
import com.mohamedrejeb.harfbuzz.compose.rememberMeasuredText
import com.mohamedrejeb.harfbuzz.core.paragraph.JustificationStrategy
import com.mohamedrejeb.harfbuzz.core.paragraph.ParagraphAlignment
import com.mohamedrejeb.harfbuzz.core.HbDirection
import com.mohamedrejeb.harfbuzz.core.HbFace
import com.mohamedrejeb.harfbuzz.core.HbFeature
import com.mohamedrejeb.harfbuzz.core.HbFont
import com.mohamedrejeb.harfbuzz.core.HbFontStack
import com.mohamedrejeb.harfbuzz.core.HbVariation
import com.mohamedrejeb.harfbuzz.core.HbVariationAxis
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.ceil
import harfbuzz_kmp.sample.generated.resources.NotoNaskhArabic_Bold
import harfbuzz_kmp.sample.generated.resources.NotoNaskhArabic_Medium
import harfbuzz_kmp.sample.generated.resources.NotoNaskhArabic_Regular
import harfbuzz_kmp.sample.generated.resources.Res
import harfbuzz_kmp.sample.generated.resources.Roboto_Bold
import harfbuzz_kmp.sample.generated.resources.Roboto_Medium
import harfbuzz_kmp.sample.generated.resources.Roboto_Regular
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Demo: kotlin-harfbuzz running against the bundled Roboto + NotoNaskhArabic
 * fonts (Compose Multiplatform Resources). Shows Latin shaping, Arabic
 * shaping, mixed-bidi paragraphs, OT feature toggles, and ArcText.
 */
@Composable
@Preview
fun App() {
    HarfBuzzSampleTheme {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompact = maxWidth < 600.dp
                FontDemos(isCompact = isCompact)
            }
        }
    }
}

/**
 * Tall hero section at the top of the page - the project's "front door".
 * Establishes the visual identity (typography-flavoured palette, mixed-script
 * preview) and tells the visitor in one glance what's inside.
 */
@Composable
private fun Hero(isCompact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isCompact) 20.dp else 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (isCompact) 16.dp else 28.dp,
                vertical = if (isCompact) 20.dp else 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "kotlin-harfbuzz",
                style = if (isCompact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Multi-script text shaping for Compose Multiplatform - Latin, Arabic, COLR/SVG color glyphs, " +
                        "fallback chains, system-font cascade, and variable axes, on JVM, Android, iOS, and Wasm.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                HeroTag("BiDi shaping")
                HeroTag("COLR v0/v1 + SVG-in-OT")
                HeroTag("Variable fonts")
                HeroTag("System font fallback")
                HeroTag("ArcText layout")
            }
        }
    }
}

@Composable
private fun HeroTag(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * A small heading printed between groups of demos so the page has visible
 * rhythm. Two-line: the section title in primary, a one-line subtitle in
 * the muted variant.
 */
@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Bundle a `FontFamily` covering both Latin (Roboto) and Arabic (Noto Naskh)
 * weights, then apply it to every Material text style. Without this, Compose
 * Multiplatform's web (Skiko Wasm) text engine has no Arabic glyphs at all -
 * the `OutlinedTextField` shows tofu boxes when you type Arabic. On JVM /
 * Android / iOS the OS fallback usually covers Arabic, but registering the
 * bundled font here makes rendering identical across every target.
 *
 * Note that this is *only* for Compose's native TextField/Text rendering.
 * The HarfBuzz pipeline (`ShapedText`, `ArcText`, etc.) loads bytes
 * separately via `Res.readBytes` and shapes them itself.
 */
@Composable
internal fun harfBuzzSampleTypography(): Typography {
    val family = FontFamily(
        Font(Res.font.NotoNaskhArabic_Regular, FontWeight.Normal),
        Font(Res.font.NotoNaskhArabic_Medium, FontWeight.Medium),
        Font(Res.font.NotoNaskhArabic_Bold, FontWeight.Bold),
    )
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

/**
 * Container that gives every demo the same look - a soft tonal surface with
 * a coloured accent stripe, generous header, and tight internal rhythm.
 *
 * The accent stripe and tonal background tie cards into the page's brand
 * palette without needing per-demo styling. Title sits in [titleMedium]
 * with semi-bold weight so it reads as a heading, not just a row label,
 * and the optional subtitle stays muted via `onSurfaceVariant`.
 */
@Composable
private fun DemoCard(
    title: String,
    subtitle: String? = null,
    /**
     * When `true`, paints a small spinner pinned to the card's top-right
     * corner without disturbing the content layout. Demos use this to
     * signal in-flight font / variation work without having to swap out
     * the rendered text for a full-height loading placeholder (which
     * caused jarring height changes whenever a slider drag re-shaped).
     */
    loading: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // Outer Box wraps the row content so the optional loading spinner
        // can be positioned at the card's top-right corner regardless of
        // the inner content's height - `Modifier.matchParentSize()` is not
        // needed because we only paint a small fixed-size indicator.
        Box(modifier = Modifier.fillMaxWidth()) {
            // Row uses IntrinsicSize.Min so the leading accent stripe stretches
            // to match the content column's natural height - without that the
            // Box has zero intrinsic height and the stripe collapses.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                // 4dp accent stripe along the leading edge - uses the
                // primary colour at low opacity so it tints rather than
                // shouts.
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                )
                Column(
                    // Slightly tighter than the standard 16dp Card inset so phone
                    // viewports (now 10dp outer + 12dp inner = 22dp lost per
                    // side) still leave room for the 360dp layouts inside.
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 14.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    content()
                }
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp, end = 14.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun FontDemos(isCompact: Boolean) {
    val arabicState by rememberHbFont(
        bytesProvider = { readFontBytes(FontPath.ARABIC_REGULAR) },
        key = FontPath.ARABIC_REGULAR,
    )
    val latinState by rememberHbFont(
        bytesProvider = { readFontBytes(FontPath.LATIN_REGULAR) },
        key = FontPath.LATIN_REGULAR,
    )
    val arabicBoldState by rememberHbFont(
        bytesProvider = { readFontBytes(FontPath.ARABIC_BOLD) },
        key = FontPath.ARABIC_BOLD,
    )
    // Aref Ruqaa Ink ships COLR v1 + SVG-in-OT - a great showcase for
    // the paint pipeline. Each demo passes its own sizePx down to the
    // render call, since fonts are sizeless and one font instance can
    // paint at any size.
    val arefRuqaaInkState by rememberHbFont(
        bytesProvider = { readFontBytes(FontPath.AREF_RUQAA_INK_REGULAR) },
        key = FontPath.AREF_RUQAA_INK_REGULAR,
    )
    val emojiState by rememberHbFont(
        bytesProvider = { readFontBytes(FontPath.EMOJI) },
        key = FontPath.EMOJI,
    )

    val maxContentWidth = 720.dp
    val itemModifier = Modifier.widthIn(max = maxContentWidth).fillMaxWidth()

    LazyColumn(
        modifier = Modifier.safeContentPadding().fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = if (isCompact) 10.dp else 24.dp,
            vertical = if (isCompact) 12.dp else 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 14.dp else 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item("hero") {
            Box(modifier = itemModifier) { Hero(isCompact = isCompact) }
        }

        when {
            arabicState is FontLoad.Loading ||
                    latinState is FontLoad.Loading ||
                    arabicBoldState is FontLoad.Loading ||
                    arefRuqaaInkState is FontLoad.Loading ||
                    emojiState is FontLoad.Loading -> {
                item("loading") {
                    Box(
                        modifier = itemModifier.height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }

            arabicState is FontLoad.Failed ->
                item("err-arabic") {
                    Box(itemModifier) { ErrorText("Arabic", (arabicState as FontLoad.Failed).cause) }
                }
            latinState is FontLoad.Failed ->
                item("err-latin") {
                    Box(itemModifier) { ErrorText("Latin", (latinState as FontLoad.Failed).cause) }
                }
            arabicBoldState is FontLoad.Failed ->
                item("err-arabic-bold") {
                    Box(itemModifier) { ErrorText("Arabic Bold", (arabicBoldState as FontLoad.Failed).cause) }
                }
            arefRuqaaInkState is FontLoad.Failed ->
                item("err-aref") {
                    Box(itemModifier) { ErrorText("Aref Ruqaa Ink", (arefRuqaaInkState as FontLoad.Failed).cause) }
                }
            emojiState is FontLoad.Failed ->
                item("err-emoji") {
                    Box(itemModifier) { ErrorText("Noto Color Emoji", (emojiState as FontLoad.Failed).cause) }
                }

            else -> {
                val arabic = (arabicState as FontLoad.Ready).font
                val latin = (latinState as FontLoad.Ready).font
                val arabicBold = (arabicBoldState as FontLoad.Ready).font
                val arefRuqaaInk = (arefRuqaaInkState as FontLoad.Ready).font
                val emoji = (emojiState as FontLoad.Ready).font

                demoSections(
                    itemModifier = itemModifier,
                    latin = DemoFont(latin, sizePx = LATIN_SIZE_PX),
                    arabic = DemoFont(arabic, sizePx = ARABIC_SIZE_PX),
                    arabicBold = DemoFont(arabicBold, sizePx = ARABIC_SIZE_PX),
                    arefRuqaaInk = DemoFont(arefRuqaaInk, sizePx = AREF_RUQAA_INK_SIZE_PX),
                    emoji = DemoFont(emoji, sizePx = EMOJI_SIZE_PX),
                )
            }
        }
    }
}

private const val LATIN_SIZE_PX = 24f
private const val ARABIC_SIZE_PX = 32f
private const val AREF_RUQAA_INK_SIZE_PX = 64f
private const val EMOJI_SIZE_PX = 56f

/**
 * Pairs a [HbFont] with the pixel size each demo intends to render at.
 * Fonts are sizeless, so the size lives on the call rather than baked
 * into the font. Bundling them keeps the demo signatures terse.
 */
internal data class DemoFont(val font: HbFont, val sizePx: Float)

/**
 * Adds the demo cards as `LazyColumn` items, grouped by thematic section.
 * Each section header + demo card lives in its own item so off-screen
 * cards don't pay the cold-composition cost on first paint.
 */
private fun LazyListScope.demoSections(
    itemModifier: Modifier,
    latin: DemoFont,
    arabic: DemoFont,
    arabicBold: DemoFont,
    arefRuqaaInk: DemoFont,
    emoji: DemoFont,
) {
    item("section-shaping") {
        Box(itemModifier) {
            SectionHeader(
                title = "Shaping",
                subtitle = "Run text through HarfBuzz and render the resulting glyphs.",
            )
        }
    }
    item("latin") { Box(itemModifier) { LatinShapingDemo(latin) } }
    item("arabic") { Box(itemModifier) { ArabicShapingDemo(arabic, arabicBold) } }
    item("features") { Box(itemModifier) { FeatureToggleDemo(arabic) } }

    item("section-color") {
        Box(itemModifier) {
            SectionHeader(
                title = "Color & rich glyphs",
                subtitle = "COLR v0/v1 + SVG-in-OpenType - fonts that paint themselves.",
            )
        }
    }
    item("color-glyphs") { Box(itemModifier) { ColorGlyphsDemo(arefRuqaaInk, emoji) } }

    item("section-variable") {
        Box(itemModifier) {
            SectionHeader(
                title = "Variable & fallback",
                subtitle = "Live axis values and per-cluster fallback chains across fonts.",
            )
        }
    }
    item("variable") { Box(itemModifier) { VariableFontsDemo() } }
    item("fallback") {
        Box(itemModifier) { FallbackFontsDemo(latin = latin, arabic = arabic, emoji = emoji) }
    }

    item("section-layout") {
        Box(itemModifier) {
            SectionHeader(
                title = "Layout",
                subtitle = "Bounds, runtime input, and text laid out along curves.",
            )
        }
    }
    item("bounds") { Box(itemModifier) { BoundsDemo(latin, arabic) } }
    item("line-layout") { Box(itemModifier) { LineLayoutDemo(latin, arabic) } }
    item("dynamic") { Box(itemModifier) { DynamicTextDemo(latin, arabic) } }
    item("arc") { Box(itemModifier) { ArcTextDemo(arabicBold) } }
}

@Composable
private fun LatinShapingDemo(latin: DemoFont) {
    DemoCard(title = "Latin shaping", subtitle = "Roboto Regular · 24 px") {
        ShapedText(
            text = "Hello, kotlin-harfbuzz! 1234",
            font = latin.font,
            sizePx = latin.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun ArabicShapingDemo(arabic: DemoFont, arabicBold: DemoFont) {
    DemoCard(title = "Arabic shaping", subtitle = "Noto Naskh Arabic · 32 px") {
        OutputLabel("Regular")
        ShapedText(
            text = "نَصٌّ عَرَبِيٌّ مُشَكَّلٌ لِلْاِخْتِبَارِ",
            font = arabic.font,
            sizePx = arabic.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(40.dp).fillMaxWidth(),
        )
        OutputLabel("Mixed Latin + Arabic + numerals")
        ShapedText(
            text = "Hello مرحبا 123 لاختبار",
            font = arabic.font,
            sizePx = arabic.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(40.dp).fillMaxWidth(),
        )
        OutputLabel("Bold")
        ShapedText(
            text = "كَلِمَاتٌ عَرَبِيَّةٌ مُشَكَّلَةٌ لِلْاِخْتِبَارِ",
            font = arabicBold.font,
            sizePx = arabicBold.sizePx,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(40.dp).fillMaxWidth(),
        )
    }
}

/**
 * Showcases the COLR / SVG render priority. Each row uses the same
 * font but flips between:
 *
 *  1. forceForegroundColor - every glyph paints with the caller's
 *     color, ignoring every color table. Useful for caret / selection
 *     overlays where a single foreground is required.
 *  2. Default - drawShapedText automatically picks SVG → COLR v1 →
 *     COLR v0 → mono, in that order. For Aref Ruqaa Ink + Noto Color
 *     Emoji this lands on SVG (or COLR v1 on Android).
 *
 * The Aref Ruqaa Ink line uses the same neutral Arabic sample our
 * Arabic demo uses, but rendered through the inked gradient artwork
 * the font designer baked into the SVG / paint trees.
 */
@Composable
private fun ColorGlyphsDemo(arefRuqaaInk: DemoFont, emoji: DemoFont) {
    var forceForeground by remember { mutableStateOf(false) }
    DemoCard(
        title = "Color glyphs",
        subtitle = "COLR v1 + SVG-in-OT · forceForegroundColor toggles the override path",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ControlLabel("Force foreground")
            FilterChip(
                selected = forceForeground,
                onClick = { forceForeground = !forceForeground },
                label = { Text(if (forceForeground) "ON" else "OFF") },
            )
        }
        OutputLabel("Aref Ruqaa Ink - gradient inks via SVG-in-OT")
        ShapedText(
            text = "نص عربي تجريبي للاختبار",
            font = arefRuqaaInk.font,
            sizePx = arefRuqaaInk.sizePx,
            color = MaterialTheme.colorScheme.primary,
            forceForegroundColor = forceForeground,
            modifier = Modifier.height(80.dp).fillMaxWidth(),
        )
        OutputLabel("Noto Color Emoji - multi-layer paint trees")
        ShapedText(
            text = "😀🌍🎉⭐❤️🦊🐱🍕",
            font = emoji.font,
            sizePx = emoji.sizePx,
            forceForegroundColor = forceForeground,
            modifier = Modifier.height(72.dp).fillMaxWidth(),
        )
    }
}

/**
 * Tier 1 fallback shaping showcase. The same Roboto font that handles
 * the Latin demos can't draw a single Arabic letter or emoji on its own,
 * so we hand `ShapedText` an [HbFontStack] with Noto Naskh Arabic and
 * Noto Color Emoji as fallbacks. Each cluster the primary font cannot
 * resolve gets re-shaped against the next font in the chain - the
 * result is one paragraph rendered with three fonts contributing in
 * the right places.
 *
 * Three rows demonstrate what the algorithm picks:
 *  1. Pure Latin → only Roboto contributes.
 *  2. Arabic-only sentence with Roboto primary → every cluster falls
 *     back to Noto Naskh.
 *  3. Latin + Arabic + emoji mixed → all three fonts contribute, each
 *     cluster routes through the chain until a font resolves it.
 */
@Composable
private fun FallbackFontsDemo(latin: DemoFont, arabic: DemoFont, emoji: DemoFont) {
    // Build the stack once and remember it so HbFontStack identity is
    // stable across recompositions - rememberMeasuredText keys on it,
    // so a fresh stack would re-shape every frame.
    val stack = remember(latin.font, arabic.font, emoji.font) {
        HbFontStack(primary = latin.font, fallbacks = listOf(arabic.font, emoji.font))
    }
    // The stack renders at one size per call: pick the primary's
    // intended size so Roboto-only lines look right, and let the
    // Arabic + emoji fallbacks paint at the same size for visual parity.
    val stackSizePx = latin.sizePx
    DemoCard(
        title = "Fallback fonts (Tier 1)",
        subtitle = "HbFontStack - Roboto primary, Noto Naskh + Color Emoji fallbacks",
    ) {
        OutputLabel("Latin only - primary covers it all")
        ShapedText(
            text = "All resolved by Roboto",
            fontStack = stack,
            sizePx = stackSizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(32.dp).fillMaxWidth(),
        )
        OutputLabel("Arabic - every cluster falls back to Noto Naskh")
        ShapedText(
            text = "نص عربي تجريبي للاختبار",
            fontStack = stack,
            sizePx = stackSizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(40.dp).fillMaxWidth(),
        )
        OutputLabel("Mixed - three fonts contribute to one paragraph")
        ShapedText(
            text = "Hello مرحبا 👋 العالم 🌍",
            fontStack = stack,
            sizePx = stackSizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(56.dp).fillMaxWidth(),
        )
    }
}

/**
 * Variable-fonts showcase backed by Trox-R.ttf, a custom variable font
 * with three normalized 0..1 axes:
 *   - `MORF` - gradually morphs the base letterforms.
 *   - `wdth` - widens / narrows the glyphs.
 *   - `BNCE` - adds a bouncy vertical offset per glyph.
 *
 * Sliding any axis triggers a fresh [HbFace.toFont] call with the
 * three pinned [HbVariation]s, so the rendered string updates live as
 * HarfBuzz re-shapes against the new font instance. The previous
 * font is closed via `DisposableEffect(font)` so we don't leak
 * native handles across slider movements.
 */
@Composable
private fun VariableFontsDemo() {
    // Library-managed face load: `rememberHbFace` runs the entire chain
    // (`harfBuzzInit()` + bytes read + `HbFace.fromBytes`) inside a single
    // `runShapingWork { ... }` bracket, so nothing pins Main during cold
    // start. Replaces the previous manual `LaunchedEffect` orchestration
    // which read font bytes via `Res.readBytes(...)` on Main between the
    // two off-Main hops (CMP 1.10.3's `readBytes` does NOT
    // `withContext(Dispatchers.IO)` internally).
    val faceLoad by rememberHbFace(
        bytesProvider = { readFontBytes(FontPath.TROX_VARIABLE) },
        key = FontPath.TROX_VARIABLE,
    )
    // Lifted from `VariableFontsControls` so the parent `DemoCard` can show
    // a single top-right spinner that covers BOTH the (one-shot) face load
    // and the (per-slider) font re-build. Without this, every slider drag
    // would either flash a full-height loading placeholder (height jump)
    // or display nothing (the previous bug).
    var fontLoading by remember { mutableStateOf(false) }

    val face = (faceLoad as? FaceLoad.Ready)?.face
    val faceFailure = (faceLoad as? FaceLoad.Failed)?.cause

    DemoCard(
        title = "Variable fonts",
        subtitle = "Trox-R · 3 axes (MORF, wdth, BNCE) · live re-shape on slider drag",
        loading = faceLoad is FaceLoad.Loading || fontLoading,
    ) {
        if (faceFailure != null) {
            ErrorText("Trox-R", faceFailure)
        } else {
            // Render the full controls regardless of face state. When `face`
            // is null we render placeholder sliders (disabled, default
            // 0..1 range) and an empty text slot of the eventual height.
            // This keeps the card height identical across loading transitions.
            VariableFontsControls(
                face = face,
                onLoadingChange = { fontLoading = it },
            )
        }
    }
}

@Composable
private fun VariableFontsControls(
    face: HbFace?,
    onLoadingChange: (Boolean) -> Unit,
) {
    // When `face` is null (initial face load still in flight) we still draw
    // the full slider stack so the card keeps a stable height - `axes` falls
    // back to an empty list and the sliders use a default 0..1 range with
    // `enabled = false`, matching Trox's normalized axis design.
    val axes = remember(face) { face?.variationAxes() ?: emptyList() }
    var morf by remember { mutableStateOf(0f) }
    var wdth by remember { mutableStateOf(0f) }
    var bnce by remember { mutableStateOf(0f) }

    val variations = remember(morf, wdth, bnce) {
        listOf(
            HbVariation.of("MORF", morf),
            HbVariation.of("wdth", wdth),
            HbVariation.of("BNCE", bnce),
        )
    }

    // A new HbFont per variation tuple. `face.toFont(...)` is suspend so
    // we drive it via `produceState`. We DO NOT close the prior font from
    // a `DisposableEffect(fontState)` lambda - Compose runs that lambda
    // *after* the new state has been committed, so it would close the
    // freshly-minted font we're about to render. Instead we keep the
    // most-recent `HbFontLoad.Ready` font in `displayedFont` and only swap
    // (and close the previous) when a *new* Ready arrives. That also gives
    // us "always show the last result" behaviour: while a new variation is
    // being shaped, the slot keeps painting the previous variation's text.
    val fontState by produceState<HbFontLoad>(
        initialValue = HbFontLoad.Loading,
        face, variations,
    ) {
        if (face == null) {
            value = HbFontLoad.Loading
            return@produceState
        }
        value = HbFontLoad.Loading
        var built: HbFont? = null
        try {
            built = face.toFont(variations)
            value = HbFontLoad.Ready(built)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            built?.close()
            throw ce
        } catch (cause: Throwable) {
            built?.close()
            value = HbFontLoad.Failed(cause)
        }
    }

    // Bubble the loading flag up to the parent so the `DemoCard` can paint
    // its top-right spinner. `LaunchedEffect(fontState)` re-fires on every
    // transition, which is fine - `onLoadingChange` only writes when the
    // flag actually changes (Compose dedupes equal `MutableState` writes).
    LaunchedEffect(fontState) {
        onLoadingChange(fontState is HbFontLoad.Loading)
    }

    // The "last successful" font - what the text slot actually paints. We
    // retain it across Loading transitions and only swap when a new Ready
    // lands. The previous handle is closed exactly once, at swap time, so
    // a rapid slider drag never closes an in-flight font and never leaks
    // a font that's been superseded.
    var displayedFont by remember(face) { mutableStateOf<HbFont?>(null) }
    LaunchedEffect(fontState) {
        val s = fontState
        if (s is HbFontLoad.Ready && s.font !== displayedFont) {
            val prev = displayedFont
            displayedFont = s.font
            prev?.close()
        }
    }
    DisposableEffect(face) {
        onDispose { displayedFont?.close() }
    }

    val failure = (fontState as? HbFontLoad.Failed)?.cause
    if (failure != null) {
        ErrorText("Variable Trox", failure)
        return
    }

    OutputLabel("Trox sample text")
    // Reserve the rendered text height even when no font is available yet,
    // so the card doesn't expand on first paint. 72.dp matches the
    // `Modifier.height(72.dp)` we apply once the font is ready.
    Box(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        displayedFont?.let { font ->
            ShapedText(
                text = "Trox harfbuzz",
                font = font,
                sizePx = 64f,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        }
    }

    OutputLabel("Axes")
    AxisSlider(
        label = "MORF",
        value = morf,
        axis = axes.firstOrNull { it.tag.toString() == "MORF" },
        onChange = { morf = it },
    )
    AxisSlider(
        label = "wdth",
        value = wdth,
        axis = axes.firstOrNull { it.tag.toString() == "wdth" },
        onChange = { wdth = it },
    )
    AxisSlider(
        label = "BNCE",
        value = bnce,
        axis = axes.firstOrNull { it.tag.toString() == "BNCE" },
        onChange = { bnce = it },
    )

    OutlinedButton(
        onClick = { morf = 0f; wdth = 0f; bnce = 0f },
        enabled = face != null,
        modifier = Modifier.padding(top = 4.dp),
    ) { Text("Reset axes") }
}

private sealed interface HbFontLoad {
    data object Loading : HbFontLoad
    data class Failed(val cause: Throwable) : HbFontLoad
    data class Ready(val font: HbFont) : HbFontLoad
}

@Composable
private fun AxisSlider(
    label: String,
    value: Float,
    axis: HbVariationAxis?,
    onChange: (Float) -> Unit,
) {
    // Render the slider row even when the axis is unknown (face still
    // loading, or a renamed axis). A disabled `0..1` range matches Trox's
    // normalised axes and keeps the card layout stable across face-load
    // transitions; a real axis swaps in the actual range as soon as the
    // face arrives.
    val range = axis?.let { it.minValue..it.maxValue } ?: 0f..1f
    val enabled = axis != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatTwoDecimals(value),
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun FeatureToggleDemo(arabic: DemoFont) {
    var ligaturesOn by remember { mutableStateOf(true) }
    var contextualOn by remember { mutableStateOf(true) }
    val features = listOf(
        HbFeature("liga", value = if (ligaturesOn) 1u else 0u),
        HbFeature("calt", value = if (contextualOn) 1u else 0u),
    )
    DemoCard(
        title = "OpenType feature toggles",
        subtitle = "Toggle ligatures (liga) and contextual alternates (calt)",
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = ligaturesOn,
                onClick = { ligaturesOn = !ligaturesOn },
                label = { Text("liga") },
                colors = FilterChipDefaults.filterChipColors(),
            )
            FilterChip(
                selected = contextualOn,
                onClick = { contextualOn = !contextualOn },
                label = { Text("calt") },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
        ShapedText(
            text = "لا كلام بلا فائدة في الحوار",
            font = arabic.font,
            sizePx = arabic.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            features = features,
            modifier = Modifier.height(40.dp).fillMaxWidth(),
        )
    }
}

/**
 * Visualises [MeasuredText.ink] (the actual painted-pixels box, magenta),
 * [MeasuredText.logical] (the typographic line box, cyan), and the baseline
 * (amber) on top of HarfBuzz-shaped text.
 */
@Composable
private fun BoundsDemo(latin: DemoFont, arabic: DemoFont) {
    DemoCard(
        title = "Calculated bounds",
        subtitle = "magenta = ink · cyan = logical · amber = baseline",
    ) {
        ShapedTextWithBounds(
            text = "Hello, kotlin-harfbuzz! gjpqy",
            font = latin.font,
            sizePx = latin.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )
        ShapedTextWithBounds(
            text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
            font = arabic.font,
            sizePx = arabic.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Showcases single-line `ShapedText` placement within a fixed-width
 * slot: alignment, kashida / word-spacing justify, and the three
 * overflow modes (Clip / Visible / Compress). The cyan stroke marks
 * the slot bounds so the alignment offset and overflow behaviour read
 * off visually.
 */
@Composable
private fun LineLayoutDemo(latin: DemoFont, arabic: DemoFont) {
    DemoCard(
        title = "Line alignment, justify, overflow",
        subtitle = "Cyan box marks the slot bounds for each line.",
    ) {
        OutputLabel("Latin: Start / Center / End in a 320 dp slot")
        SlotLine(latin, "Hello world", 320.dp, ParagraphAlignment.Start)
        SlotLine(latin, "Hello world", 320.dp, ParagraphAlignment.Center)
        SlotLine(latin, "Hello world", 320.dp, ParagraphAlignment.End)

        OutputLabel("Arabic: Right / Center / Left in a 320 dp slot")
        SlotLine(arabic, "مرحبا بالعالم", 320.dp, ParagraphAlignment.Right)
        SlotLine(arabic, "مرحبا بالعالم", 320.dp, ParagraphAlignment.Center)
        SlotLine(arabic, "مرحبا بالعالم", 320.dp, ParagraphAlignment.Left)

        OutputLabel("Justify: word-spacing (Latin) and Kashida (Arabic)")
        SlotLine(
            font = latin,
            text = "Hello world from kotlin",
            slot = 360.dp,
            alignment = ParagraphAlignment.Justify,
            justification = JustificationStrategy.WordSpacing,
        )
        SlotLine(
            font = arabic,
            text = "مرحبا بك في تجربة النص",
            slot = 360.dp,
            alignment = ParagraphAlignment.Justify,
            justification = JustificationStrategy.Mixed,
        )

        OutputLabel("Overflow modes (text is wider than the 220 dp slot)")
        SlotLine(
            font = latin,
            text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit",
            slot = 220.dp,
            overflow = ShapedTextOverflow.Clip,
        )
        SlotLine(
            font = latin,
            text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit",
            slot = 220.dp,
            overflow = ShapedTextOverflow.Visible,
        )
        SlotLine(
            font = latin,
            text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit",
            slot = 220.dp,
            overflow = ShapedTextOverflow.Compress,
        )
    }
}

@Composable
private fun SlotLine(
    font: DemoFont,
    text: String,
    slot: Dp,
    alignment: ParagraphAlignment = ParagraphAlignment.Start,
    justification: JustificationStrategy = JustificationStrategy.None,
    overflow: ShapedTextOverflow = ShapedTextOverflow.Clip,
) {
    val slotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .width(slot)
            .height(40.dp)
            .drawBehind {
                drawRect(color = slotColor, style = Stroke(width = 1f))
            },
    ) {
        ShapedText(
            text = text,
            font = font.font,
            sizePx = font.sizePx,
            color = MaterialTheme.colorScheme.onBackground,
            alignment = alignment,
            justification = justification,
            overflow = overflow,
        )
    }
}

/**
 * Live shaping playground: an `OutlinedTextField` plus controls for family,
 * weight, italic, size, direction, and which bounds rects to overlay. Every
 * change reshapes via `rememberMeasuredText` - re-loads the font only when
 * the family/weight/italic selection forces a different binary.
 */
@Composable
private fun DynamicTextDemo(latin: DemoFont, arabic: DemoFont) {
    var input by remember { mutableStateOf("Hello مرحبا 1234") }
    var family by remember { mutableStateOf(SampleFamily.Roboto) }
    var weight by remember { mutableStateOf(SampleWeight.Regular) }
    var italic by remember { mutableStateOf(false) }
    var sizePx by remember { mutableStateOf(28f) }
    var direction by remember { mutableStateOf(HbDirection.AUTO) }
    var textColor by remember { mutableStateOf(SampleColor.OnBackground) }
    var forceForegroundColor by remember { mutableStateOf(false) }
    var showInk by remember { mutableStateOf(true) }
    var showLogical by remember { mutableStateOf(true) }
    var showBaseline by remember { mutableStateOf(true) }

    val effectiveItalic = italic && family == SampleFamily.Roboto
    val availableWeights = family.weights
    val effectiveWeight = if (weight in availableWeights) weight else availableWeights.first()
    val fontPath = resolveFontPath(family, effectiveWeight, effectiveItalic)

    val dynamicFontState by rememberHbFont(
        bytesProvider = { readFontBytes(fontPath) },
        key = fontPath,
    )

    DemoCard(
        title = "Dynamic input",
        subtitle = "Type and watch HarfBuzz re-shape on every keystroke",
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Text") },
            modifier = Modifier.fillMaxWidth(),
        )

        // Family + Weight dropdowns. Dropdowns instead of FilterChip rows
        // so we don't pay a 2-pass FlowRow measure for ~10 chips on cold
        // start.
        LabeledDropdown(
            label = "Family",
            selected = family,
            options = SampleFamily.entries,
            optionLabel = { it.displayName },
            onSelect = { family = it },
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LabeledDropdown(
                label = "Weight",
                selected = effectiveWeight,
                options = availableWeights,
                optionLabel = { it.displayName },
                onSelect = { weight = it },
            )
            if (family == SampleFamily.Roboto) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Checkbox(checked = italic, onCheckedChange = { italic = it })
                    Text("Italic", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ControlLabel("Size: ${sizePx.toInt()} px")
        Slider(
            value = sizePx,
            onValueChange = { sizePx = it },
            valueRange = 12f..72f,
        )

        ControlLabel("Direction")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                HbDirection.AUTO to "Auto",
                HbDirection.LTR to "LTR",
                HbDirection.RTL to "RTL",
            ).forEach { (dir, label) ->
                FilterChip(
                    selected = direction == dir,
                    onClick = { direction = dir },
                    label = { Text(label) },
                )
            }
        }

        ControlLabel("Color")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SampleColor.entries.forEach { c ->
                ColorSwatch(
                    color = c.resolve(),
                    selected = textColor == c,
                    onClick = { textColor = c },
                )
            }
        }
        // Only meaningful for color fonts (Aref Ruqaa Ink). Greyed-out
        // for the others so users see when it has no effect.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = forceForegroundColor,
                onCheckedChange = { forceForegroundColor = it },
                enabled = family == SampleFamily.ArefRuqaaInk,
            )
            Text(
                "Override font colors",
                style = MaterialTheme.typography.bodySmall,
                color = if (family == SampleFamily.ArefRuqaaInk)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ControlLabel("Bounds overlay")
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BoundsToggle("Ink", showInk) { showInk = it }
            BoundsToggle("Logical", showLogical) { showLogical = it }
            BoundsToggle("Baseline", showBaseline) { showBaseline = it }
        }

        when (val s = dynamicFontState) {
            FontLoad.Loading -> Box(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.CenterStart,
            ) { CircularProgressIndicator() }

            is FontLoad.Failed -> ErrorText(fontPath, s.cause)
            is FontLoad.Ready -> ShapedTextWithBounds(
                text = input,
                font = s.font,
                sizePx = sizePx,
                color = textColor.resolve(),
                direction = direction,
                forceForegroundColor = forceForegroundColor,
                showInk = showInk,
                showLogical = showLogical,
                showBaseline = showBaseline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Suppress unused - kept in the signature for symmetry with sibling demos.
    @Suppress("UNUSED_VARIABLE")
    val unused = listOf(latin, arabic)
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlLabel(label)
        // FlowRow so chips wrap to a second line on narrow viewports
        // instead of overflowing - keeps the demos usable on phones.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> LabeledDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ControlLabel(label)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(optionLabel(selected))
                Spacer(Modifier.width(8.dp))
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoundsToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Small clickable color swatch with a thicker outline when selected. Drawn
 * via Canvas so we don't drag in another Material component for one circle.
 */
@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClick = onClick)
            .drawBehind {
                val r = size.minDimension / 2f
                drawCircle(color = color, radius = r - 2f)
                drawCircle(
                    color = outlineColor,
                    radius = r - 1f,
                    style = Stroke(width = if (selected) 2.5f else 1f),
                )
            },
    )
}

/** Preset palette for the dynamic-input demo. */
internal enum class SampleColor(val displayName: String) {
    OnBackground("Default"),
    Black("Black"),
    Primary("Primary"),
    Crimson("Crimson"),
    Teal("Teal"),
    Indigo("Indigo");

    @Composable
    fun resolve(): Color = when (this) {
        OnBackground -> MaterialTheme.colorScheme.onBackground
        Black -> Color.Black
        Primary -> MaterialTheme.colorScheme.primary
        Crimson -> Color(0xFFB00020)
        Teal -> Color(0xFF00897B)
        Indigo -> Color(0xFF3949AB)
    }
}

@Composable
private fun OutputLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal enum class SampleFamily(val displayName: String, val weights: List<SampleWeight>) {
    Roboto(
        "Roboto",
        listOf(
            SampleWeight.Light,
            SampleWeight.Regular,
            SampleWeight.Medium,
            SampleWeight.Bold,
            SampleWeight.Black,
        ),
    ),
    NotoNaskhArabic(
        "Noto Naskh",
        listOf(
            SampleWeight.Regular,
            SampleWeight.Medium,
            SampleWeight.SemiBold,
            SampleWeight.Bold,
        ),
    ),
    ArefRuqaa(
        "Aref Ruqaa",
        listOf(SampleWeight.Regular, SampleWeight.Bold),
    ),

    /**
     * Aref Ruqaa Ink is a COLR/CPAL "color font": glyphs ship with embedded
     * colors that take over from the caller-provided color when the renderer
     * supports COLR. Our HarfBuzz pipeline pulls a single monochrome outline
     * per glyph (no color layers), so we honour the requested color - the
     * native Compose Text widget would draw the embedded inks instead.
     */
    ArefRuqaaInk(
        "Aref Ruqaa Ink",
        listOf(SampleWeight.Regular, SampleWeight.Bold),
    ),
}

internal enum class SampleWeight(val displayName: String) {
    Light("Light"),
    Regular("Regular"),
    Medium("Medium"),
    SemiBold("SemiBold"),
    Bold("Bold"),
    Black("Black"),
}

private fun resolveFontPath(
    family: SampleFamily,
    weight: SampleWeight,
    italic: Boolean,
): String = when (family) {
    SampleFamily.Roboto -> {
        val w = when (weight) {
            SampleWeight.Light -> "Light"
            SampleWeight.Regular -> "Regular"
            SampleWeight.Medium -> "Medium"
            SampleWeight.Bold -> "Bold"
            SampleWeight.Black -> "Black"
            else -> "Regular"
        }
        val suffix = when {
            italic && weight == SampleWeight.Regular -> "Italic"
            italic -> "${w}Italic"
            else -> w
        }
        "font/Roboto-$suffix.ttf"
    }

    SampleFamily.NotoNaskhArabic -> {
        val w = when (weight) {
            SampleWeight.Regular -> "Regular"
            SampleWeight.Medium -> "Medium"
            SampleWeight.SemiBold -> "SemiBold"
            SampleWeight.Bold -> "Bold"
            else -> "Regular"
        }
        "font/NotoNaskhArabic-$w.ttf"
    }

    SampleFamily.ArefRuqaa -> {
        val w = if (weight == SampleWeight.Bold) "Bold" else "Regular"
        "font/ArefRuqaa-$w.ttf"
    }

    SampleFamily.ArefRuqaaInk -> {
        val w = if (weight == SampleWeight.Bold) "Bold" else "Regular"
        "font/ArefRuqaaInk-$w.ttf"
    }
}

/**
 * Local helper that reaches under `ShapedText` to render text *and* visualise
 * the bounds. Builds on the public `rememberMeasuredText` + `drawShapedText`
 * primitives - exactly what users would write to do their own custom drawing.
 */
@Composable
private fun ShapedTextWithBounds(
    text: String,
    font: HbFont,
    sizePx: Float,
    color: Color,
    modifier: Modifier = Modifier,
    direction: HbDirection = HbDirection.AUTO,
    forceForegroundColor: Boolean = false,
    showInk: Boolean = true,
    showLogical: Boolean = true,
    showBaseline: Boolean = true,
    inkColor: Color = Color(0xFFE91E63),       // magenta
    logicalColor: Color = Color(0xFF00BCD4),   // cyan
    baselineColor: Color = Color(0xFFFFB300),  // amber
) {
    val loadState by rememberMeasuredText(text, font, sizePx = sizePx, direction = direction)
    val measured: MeasuredText? = (loadState as? MeasuredTextLoad.Ready)?.measured

    // Size the layout so the logical line box (y = 0..lineHeight, baseline
    // at y = ascent) and the ink rect (which may extend further above or
    // below for marks/descenders) are both fully visible. Shift drawing down
    // by the highest paint above y=0.
    val pad = 4f
    val inkTopOnScreen = if (measured == null || measured.ink.isEmpty) 0f
    else measured.baseline + measured.ink.top
    val inkBottomOnScreen = if (measured == null || measured.ink.isEmpty)
        measured?.lineHeight ?: 0f
    else measured.baseline + measured.ink.bottom
    val lineH = measured?.lineHeight ?: 0f
    val needTop = minOf(0f, inkTopOnScreen) - pad
    val needBottom = maxOf(lineH, inkBottomOnScreen) + pad
    val shift = -needTop
    val totalH = (needBottom - needTop).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val widthCap = if (measured == null || measured.isEmpty) constraints.minWidth
                else minOf(constraints.maxWidth, ceil(measured.advance).toInt().coerceAtLeast(0))
                val height = ceil(totalH).toInt().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(maxOf(widthCap, constraints.minWidth), height) {
                    placeable.place(0, 0)
                }
            }
            .drawBehind {
                if (measured == null || measured.isEmpty) return@drawBehind
                drawShapedText(
                    measured,
                    topLeft = Offset(0f, shift),
                    color = color,
                    forceForegroundColor = forceForegroundColor,
                )

                if (showLogical) {
                    drawRect(
                        color = logicalColor,
                        topLeft = Offset(0f, shift),
                        size = Size(measured.advance.coerceAtLeast(1f), measured.lineHeight),
                        style = Stroke(width = 1f),
                    )
                }
                if (showInk && !measured.ink.isEmpty) {
                    drawRect(
                        color = inkColor,
                        topLeft = Offset(measured.ink.left, shift + measured.baseline + measured.ink.top),
                        size = Size(
                            width = (measured.ink.right - measured.ink.left).coerceAtLeast(1f),
                            height = (measured.ink.bottom - measured.ink.top).coerceAtLeast(1f),
                        ),
                        style = Stroke(width = 1f),
                    )
                }
                if (showBaseline) {
                    drawLine(
                        color = baselineColor,
                        start = Offset(0f, shift + measured.baseline),
                        end = Offset(measured.advance.coerceAtLeast(1f), shift + measured.baseline),
                        strokeWidth = 1f,
                    )
                }
            },
    )
}

@Composable
private fun ArcTextDemo(arabicBold: DemoFont) {
    var radiusDp by remember { mutableStateOf(110f) }
    DemoCard(title = "Arc text", subtitle = "Arabic shaped onto a circle") {
        ControlLabel("Radius: ${radiusDp.toInt()} dp")
        Slider(
            value = radiusDp,
            onValueChange = { radiusDp = it },
            valueRange = 60f..220f,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            ArcText(
                text = "نص عربي تجريبي للاختبار",
                font = arabicBold.font,
                sizePx = arabicBold.sizePx,
                radius = radiusDp.dp,
                side = ArcSide.Outside,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ErrorText(label: String, cause: Throwable) {
    val className = cause::class.simpleName ?: "Throwable"
    val msg = cause.message ?: "<no message>"
    Text(
        text = "Failed to load $label font:\n$className: $msg",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Format a [Float] to two decimal places - `"%.2f".format(...)` is JVM-only,
 * so we round manually for multiplatform compatibility.
 */
private fun formatTwoDecimals(value: Float): String {
    val hundredths = kotlin.math.round(value * 100f).toInt()
    val whole = hundredths / 100
    val frac = (hundredths % 100).let { if (it < 0) -it else it }
    val sign = if (hundredths < 0 && whole == 0) "-" else ""
    val fracStr = if (frac < 10) "0$frac" else frac.toString()
    return "$sign$whole.$fracStr"
}

internal object FontPath {
    const val ARABIC_REGULAR = "font/NotoNaskhArabic-Regular.ttf"
    const val ARABIC_MEDIUM = "font/NotoNaskhArabic-Medium.ttf"
    const val ARABIC_SEMIBOLD = "font/NotoNaskhArabic-SemiBold.ttf"
    const val ARABIC_BOLD = "font/NotoNaskhArabic-Bold.ttf"
    const val LATIN_REGULAR = "font/Roboto-Regular.ttf"
    const val LATIN_MEDIUM = "font/Roboto-Medium.ttf"
    const val LATIN_BOLD = "font/Roboto-Bold.ttf"
    const val AREF_RUQAA_REGULAR = "font/ArefRuqaa-Regular.ttf"
    const val AREF_RUQAA_BOLD = "font/ArefRuqaa-Bold.ttf"
    const val AREF_RUQAA_INK_REGULAR = "font/ArefRuqaaInk-Regular.ttf"
    const val AREF_RUQAA_INK_BOLD = "font/ArefRuqaaInk-Bold.ttf"
    const val EMOJI = "font/NotoColorEmoji-Regular.ttf"
    const val TROX_VARIABLE = "font/Trox-R.ttf"
}

/**
 * Reads bundled font bytes from Compose Multiplatform Resources. Uses
 * `Res.readBytes(path)` so we get raw bytes (not a `FontResource` tied to
 * Compose's built-in text pipeline).
 */
@OptIn(ExperimentalResourceApi::class)
internal suspend fun readFontBytes(path: String): ByteArray = Res.readBytes(path)
