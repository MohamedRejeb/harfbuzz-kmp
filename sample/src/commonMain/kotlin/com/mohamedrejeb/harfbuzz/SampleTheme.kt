package com.mohamedrejeb.harfbuzz

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Custom Material 3 theme for the kotlin-harfbuzz sample.
 *
 * The palette is deliberately typography-flavoured - a deep ink-violet
 * primary (the kind of indigo manuscript scribes used) paired with a warm
 * copper/amber secondary that picks up the colour-emoji and Aref Ruqaa Ink
 * showcase. Surface tones lean slightly warm in light mode and carry that
 * warmth through to the dark scheme.
 *
 * Wraps [MaterialTheme] with the project's typography (Noto Naskh Arabic +
 * Roboto stack - see [com.mohamedrejeb.harfbuzz.harfBuzzSampleTypography])
 * applied on top so every Material text style renders Arabic correctly
 * even on Wasm (where the platform text stack is monoscript by default).
 */
@Composable
internal fun HarfBuzzSampleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = harfBuzzSampleTypography(),
        content = content,
    )
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF4F378A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF1B0F47),

    secondary = Color(0xFFB05518),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC4),
    onSecondaryContainer = Color(0xFF381E00),

    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1DAFF),
    onTertiaryContainer = Color(0xFF251431),

    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF1E1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1B22),
    surfaceVariant = Color(0xFFE8E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF3F8),
    surfaceContainer = Color(0xFFF3ECF1),
    surfaceContainerHigh = Color(0xFFEDE6EB),
    surfaceContainerHighest = Color(0xFFE7E0E5),

    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4CF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF371E73),
    primaryContainer = Color(0xFF4F378A),
    onPrimaryContainer = Color(0xFFE8DDFF),

    secondary = Color(0xFFFFB68C),
    onSecondary = Color(0xFF572800),
    secondaryContainer = Color(0xFF7A3D00),
    onSecondaryContainer = Color(0xFFFFDCC4),

    tertiary = Color(0xFFD7BCE7),
    onTertiary = Color(0xFF3B2747),
    tertiaryContainer = Color(0xFF533D5F),
    onTertiaryContainer = Color(0xFFF1DAFF),

    background = Color(0xFF15121A),
    onBackground = Color(0xFFE8E0EC),
    surface = Color(0xFF1E1A24),
    onSurface = Color(0xFFE8E0EC),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4CF),
    surfaceContainerLowest = Color(0xFF100D14),
    surfaceContainerLow = Color(0xFF1E1A24),
    surfaceContainer = Color(0xFF221E28),
    surfaceContainerHigh = Color(0xFF2C2832),
    surfaceContainerHighest = Color(0xFF37323D),

    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)
