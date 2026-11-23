package com.nemu.hibernate.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * FlClash colour engine, ported to Nemu.
 *
 * Upstream:
 *   https://github.com/chen08209/FlClash/blob/main/lib/providers/state/theme.dart
 *   https://github.com/chen08209/FlClash/blob/main/plugins/prism_ui/lib/color.dart
 *
 * FlClash builds every role with HCT / Material Color Utilities. Its default
 * palette is the eight iOS system colours below, and the default style is the
 * "vivid" content scheme: a saturated accent, paper-white cards with a faint
 * seed tint, and grey page / inset surfaces. The generated values live in
 * FlClashColorData.kt so Nemu does not need a colour-science dependency at
 * runtime. The generator is tools/flclash_theme_gen.py.
 *
 * FlClash is Apache-2.0; see https://github.com/chen08209/FlClash.
 */

/**
 * FlClash surfaces. These are deliberately not the same as Compose's
 * surfaceContainer* ladder: FlClash uses a page < card < block < inset
 * hierarchy, with the card brighter than the page in light mode and darker
 * than the page in dark mode.
 */
enum class FlClashSurface {
    PAGE,
    CARD,
    BLOCK,
    INSET
}

fun ColorScheme.flClashSurface(level: FlClashSurface): Color {
    val dark = surface.luminance() < 0.5f
    return when (level) {
        FlClashSurface.PAGE -> if (dark) surface else surfaceContainer
        FlClashSurface.CARD -> if (dark) surfaceContainer else surfaceBright
        FlClashSurface.BLOCK -> if (dark) surfaceContainerHigh else surfaceContainer
        FlClashSurface.INSET -> surfaceContainerHighest
    }
}

fun ColorScheme.flClashCard(): Color = flClashSurface(FlClashSurface.CARD)
fun ColorScheme.flClashBlock(): Color = flClashSurface(FlClashSurface.BLOCK)
fun ColorScheme.flClashInset(): Color = flClashSurface(FlClashSurface.INSET)

/** The floating navigation dock: card in light, block in dark. */
fun ColorScheme.flClashFloating(): Color =
    if (surface.luminance() < 0.5f) flClashBlock() else flClashCard()

private fun colorSchemeFromRoles(roles: LongArray): ColorScheme {
    require(roles.size == FLCLASH_ROLE_COUNT) {
        "FlClash colour table is ${roles.size} roles, expected $FLCLASH_ROLE_COUNT"
    }
    fun c(index: Int) = Color(roles[index].toInt())

    val scheme = lightColorScheme(
        primary = c(0),
        onPrimary = c(1),
        primaryContainer = c(2),
        onPrimaryContainer = c(3),
        secondary = c(4),
        onSecondary = c(5),
        secondaryContainer = c(6),
        onSecondaryContainer = c(7),
        tertiary = c(8),
        onTertiary = c(9),
        tertiaryContainer = c(10),
        onTertiaryContainer = c(11),
        error = c(12),
        onError = c(13),
        errorContainer = c(14),
        onErrorContainer = c(15),
        background = c(16),
        onBackground = c(17),
        surface = c(18),
        onSurface = c(19),
        surfaceVariant = c(20),
        onSurfaceVariant = c(21),
        outline = c(22),
        outlineVariant = c(23),
        inverseSurface = c(24),
        inverseOnSurface = c(25),
        inversePrimary = c(26),
        surfaceTint = c(27),
        scrim = c(28),
        surfaceDim = c(29),
        surfaceBright = c(30),
        surfaceContainerLowest = c(31),
        surfaceContainerLow = c(32),
        surfaceContainer = c(33),
        surfaceContainerHigh = c(34),
        surfaceContainerHighest = c(35)
    )
    // The roles below do not have a distinct FlClash source. Mirroring the
    // nearest generated role keeps Material components (which may read them)
    // inside the same palette instead of falling back to the stock purple.
    return scheme.copy(
        // Readability pass requested on-device: keep FlClash's structural
        // colours, but make body/secondary ink closer to true black.
        onSurface = Color(0xFF0A0A0A),
        onBackground = Color(0xFF0A0A0A),
        onSurfaceVariant = Color(0xFF2E2E2E),
        // HCT's error role drifts brown-red on some seeds. Error text and
        // destructive actions must stay the same vivid red as RiskChip.
        error = Color(0xFFFF383C),
        onError = Color.White,
        primaryFixed = c(2),
        primaryFixedDim = c(0),
        onPrimaryFixed = c(3),
        onPrimaryFixedVariant = c(0),
        secondaryFixed = c(6),
        secondaryFixedDim = c(4),
        onSecondaryFixed = c(7),
        onSecondaryFixedVariant = c(4),
        tertiaryFixed = c(10),
        tertiaryFixedDim = c(8),
        onTertiaryFixed = c(11),
        onTertiaryFixedVariant = c(8)
    )
}

private fun darkColorSchemeFromRoles(roles: LongArray): ColorScheme {
    val light = colorSchemeFromRoles(roles)
    // darkColorScheme shares the role order; build it from the same table.
    fun c(index: Int) = Color(roles[index].toInt())
    return darkColorScheme(
        primary = c(0),
        onPrimary = c(1),
        primaryContainer = c(2),
        onPrimaryContainer = c(3),
        secondary = c(4),
        onSecondary = c(5),
        secondaryContainer = c(6),
        onSecondaryContainer = c(7),
        tertiary = c(8),
        onTertiary = c(9),
        tertiaryContainer = c(10),
        onTertiaryContainer = c(11),
        error = c(12),
        onError = c(13),
        errorContainer = c(14),
        onErrorContainer = c(15),
        background = c(16),
        onBackground = c(17),
        surface = c(18),
        onSurface = c(19),
        surfaceVariant = c(20),
        onSurfaceVariant = c(21),
        outline = c(22),
        outlineVariant = c(23),
        inverseSurface = c(24),
        inverseOnSurface = c(25),
        inversePrimary = c(26),
        surfaceTint = c(27),
        scrim = c(28),
        surfaceDim = c(29),
        surfaceBright = c(30),
        surfaceContainerLowest = c(31),
        surfaceContainerLow = c(32),
        surfaceContainer = c(33),
        surfaceContainerHigh = c(34),
        surfaceContainerHighest = c(35)
    ).copy(
        onSurface = Color(0xFFF7F7F7),
        onBackground = Color(0xFFF7F7F7),
        onSurfaceVariant = Color(0xFFCECECE),
        error = Color(0xFFFF383C),
        onError = Color.White,
        primaryFixed = light.primaryFixed,
        primaryFixedDim = light.primaryFixedDim,
        onPrimaryFixed = light.onPrimaryFixed,
        onPrimaryFixedVariant = light.onPrimaryFixedVariant,
        secondaryFixed = light.secondaryFixed,
        secondaryFixedDim = light.secondaryFixedDim,
        onSecondaryFixed = light.onSecondaryFixed,
        onSecondaryFixedVariant = light.onSecondaryFixedVariant,
        tertiaryFixed = light.tertiaryFixed,
        tertiaryFixedDim = light.tertiaryFixedDim,
        onTertiaryFixed = light.onTertiaryFixed,
        onTertiaryFixedVariant = light.onTertiaryFixedVariant
    )
}

fun colorSchemeFor(
    palette: ThemePalette,
    style: ThemeStyle,
    dark: Boolean
): ColorScheme {
    val index = palette.ordinal * ThemeStyle.entries.size + style.ordinal
    return if (dark) {
        darkColorSchemeFromRoles(FLCLASH_DARK_ROLES[index])
    } else {
        colorSchemeFromRoles(FLCLASH_LIGHT_ROLES[index])
    }
}
