package com.nemu.hibernate.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/*
 * Nemu colour engine.
 *
 * Upstream reference: FlClash (https://github.com/chen08209/FlClash),
 * lib/providers/state/theme.dart + plugins/prism_ui/lib/color.dart, Apache-2.0.
 * The HCT tone ladder, surface levels and default "vivid" style are ported
 * from that reference. Nemu ships the precomputed tables in NemuColorData.kt,
 * so no colour-science dependency is needed at runtime. Generator:
 * tools/theme_gen.py.
 */

/**
 * Nemu surface levels. These are deliberately not the same as Compose's
 * surfaceContainer* ladder: Nemu uses a page < card < block < inset
 * hierarchy, with the card brighter than the page in light mode and darker
 * than the page in dark mode.
 */
enum class NemuSurface {
    PAGE,
    CARD,
    BLOCK,
    INSET
}

fun ColorScheme.nemuSurface(level: NemuSurface): Color {
    val dark = surface.luminance() < 0.5f
    return when (level) {
        NemuSurface.PAGE -> if (dark) surface else surfaceContainer
        NemuSurface.CARD -> if (dark) surfaceContainer else surfaceBright
        NemuSurface.BLOCK -> if (dark) surfaceContainerHigh else surfaceContainer
        NemuSurface.INSET -> surfaceContainerHighest
    }
}

fun ColorScheme.nemuCard(): Color = nemuSurface(NemuSurface.CARD)
fun ColorScheme.nemuBlock(): Color = nemuSurface(NemuSurface.BLOCK)
fun ColorScheme.nemuInset(): Color = nemuSurface(NemuSurface.INSET)

/** The floating navigation dock: card in light, block in dark. */
fun ColorScheme.nemuFloating(): Color =
    if (surface.luminance() < 0.5f) nemuBlock() else nemuCard()

private fun colorSchemeFromRoles(roles: LongArray): ColorScheme {
    require(roles.size == NEMU_ROLE_COUNT) {
        "Nemu colour table is ${roles.size} roles, expected $NEMU_ROLE_COUNT"
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
    // The roles below do not have a distinct upstream source. Mirroring the
    // nearest generated role keeps Material components (which may read them)
    // inside the same palette instead of falling back to the stock purple.
    return scheme.copy(
        // Readability pass requested on-device: keep the structural
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
        darkColorSchemeFromRoles(NEMU_DARK_ROLES[index])
    } else {
        colorSchemeFromRoles(NEMU_LIGHT_ROLES[index])
    }
}
