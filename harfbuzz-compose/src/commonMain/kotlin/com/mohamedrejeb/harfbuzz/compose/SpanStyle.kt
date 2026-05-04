package com.mohamedrejeb.harfbuzz.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Per-character paint override carried by a [StyleRange] inside a
 * [StyledText]. Set only the attributes you want to override; leave
 * the others at their default-sentinel value ([Color.Unspecified] for
 * [color], `null` for [brush]) so spans can layer per-attribute over
 * the composable's outer defaults.
 *
 * Resolution rule when multiple spans overlap a glyph: spans are
 * processed in declaration order; each non-default attribute replaces
 * the prior value. The composable's outer `color` / `brush`
 * parameters supply the default that applies wherever no span sets
 * that attribute.
 */
@Immutable
public data class SpanStyle(
    public val color: Color = Color.Unspecified,
    public val brush: Brush? = null,
)
