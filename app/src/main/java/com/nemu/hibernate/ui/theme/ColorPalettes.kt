package com.nemu.hibernate.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Per-palette seed colours. Every other role is generated from the seed hue so
 * each palette is monochromatic (green stays green, amber stays amber) instead
 * of mixing unrelated Material roles. Light surfaces are the seed hue mixed
 * with white; dark surfaces are true black (#000000) for OLED.
 */
private data class PaletteSeed(
    val hue: Float,
    val saturation: Float
)

private fun seedFor(palette: ThemePalette): PaletteSeed = when (palette) {
    ThemePalette.MINT -> PaletteSeed(hue = 154f, saturation = 0.52f)
    ThemePalette.ICE -> PaletteSeed(hue = 196f, saturation = 0.58f)
    ThemePalette.PEACH -> PaletteSeed(hue = 24f, saturation = 0.56f)
    ThemePalette.LAVENDER -> PaletteSeed(hue = 264f, saturation = 0.42f)
    ThemePalette.GRAPHITE -> PaletteSeed(hue = 205f, saturation = 0.10f)
    ThemePalette.SAKURA -> PaletteSeed(hue = 342f, saturation = 0.55f)
}

/** HSL -> sRGB. h in degrees, s/l in 0..1. */
private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}

/** Slightly rotated analogous hue for tertiary accents. */
private fun analogous(hue: Float, degrees: Float = 24f): Float = hue + degrees

private fun lightScheme(palette: ThemePalette): ColorScheme {
    val seed = seedFor(palette)
    val h = seed.hue
    val s = seed.saturation
    val primary = hsl(h, s, 0.33f)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = hsl(h, s * 0.55f, 0.90f),
        onPrimaryContainer = hsl(h, s * 0.90f, 0.16f),
        secondary = hsl(h, s * 0.42f, 0.40f),
        onSecondary = Color.White,
        secondaryContainer = hsl(h, s * 0.40f, 0.87f),
        onSecondaryContainer = hsl(h, s * 0.55f, 0.18f),
        tertiary = hsl(analogous(h), s * 0.62f, 0.38f),
        onTertiary = Color.White,
        tertiaryContainer = hsl(analogous(h), s * 0.40f, 0.90f),
        onTertiaryContainer = hsl(analogous(h), s * 0.60f, 0.16f),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFAFAFA),
        onBackground = Color(0xFF1A1A1A),
        surface = Color(0xFFFAFAFA),
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFF0F0F0),
        onSurfaceVariant = Color(0xFF5E5E5E),
        outline = Color(0xFF8A8A8A),
        outlineVariant = Color(0xFFE2E2E2),
        inverseSurface = Color(0xFF2B2B2B),
        inverseOnSurface = Color(0xFFF2F2F2),
        inversePrimary = hsl(h, s * 0.65f, 0.78f),
        surfaceTint = primary,
        scrim = Color.Black,
        surfaceDim = Color(0xFFE6E6E6),
        surfaceBright = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF2F2F2),
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFEDEDED)
    )
}

private fun darkScheme(palette: ThemePalette): ColorScheme {
    val seed = seedFor(palette)
    val h = seed.hue
    val s = seed.saturation
    val primary = hsl(h, s * 0.72f, 0.74f)
    return darkColorScheme(
        primary = primary,
        onPrimary = hsl(h, s * 0.85f, 0.12f),
        primaryContainer = hsl(h, s * 0.70f, 0.26f),
        onPrimaryContainer = hsl(h, s * 0.35f, 0.92f),
        secondary = hsl(h, s * 0.35f, 0.78f),
        onSecondary = hsl(h, s * 0.40f, 0.12f),
        secondaryContainer = Color(0xFF2A2A2A),
        onSecondaryContainer = Color(0xFFE8E8E8),
        tertiary = hsl(analogous(h), s * 0.55f, 0.78f),
        onTertiary = hsl(analogous(h), s * 0.55f, 0.14f),
        tertiaryContainer = hsl(analogous(h), s * 0.50f, 0.24f),
        onTertiaryContainer = hsl(analogous(h), s * 0.28f, 0.92f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color.Black,
        onBackground = Color(0xFFE8E8E8),
        surface = Color.Black,
        onSurface = Color(0xFFE8E8E8),
        surfaceVariant = Color(0xFF242424),
        onSurfaceVariant = Color(0xFFC6C6C6),
        outline = Color(0xFF8A8A8A),
        outlineVariant = Color(0xFF343434),
        inverseSurface = Color(0xFFE8E8E8),
        inverseOnSurface = Color(0xFF1A1A1A),
        inversePrimary = hsl(h, s * 0.80f, 0.36f),
        surfaceTint = primary,
        scrim = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF2A2A2A),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF111111),
        surfaceContainerHigh = Color(0xFF181818),
        surfaceContainerHighest = Color(0xFF202020)
    )
}

private val schemes: Map<ThemePalette, Pair<ColorScheme, ColorScheme>> =
    ThemePalette.entries.associateWith { palette ->
        lightScheme(palette) to darkScheme(palette)
    }

fun colorSchemeFor(palette: ThemePalette, dark: Boolean): ColorScheme {
    val pair = schemes.getValue(palette)
    return if (dark) pair.second else pair.first
}

/** Representative light-theme primary used by the settings swatch. */
fun swatchColorFor(palette: ThemePalette): Color {
    val seed = seedFor(palette)
    return hsl(seed.hue, seed.saturation, 0.33f)
}
