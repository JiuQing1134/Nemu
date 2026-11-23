package com.nemu.hibernate.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.nemu.hibernate.ui.theme.ThemePalette
import com.nemu.hibernate.ui.theme.ThemeStyle
import com.nemu.hibernate.ui.theme.colorSchemeFor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status chips and filter chips draw text with the palette's designed
 * on-primary ink. The user explicitly prefers FlClash's white ink on the blue
 * controls (a luminance-inverted black ink was harder to read), so this test
 * guards the designed pairs rather than forcing pure black/white: every
 * palette × style × light/dark pair must keep at least the 3:1 WCAG contrast
 * required for UI components.
 */
class StatusChipContrastTest {
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun designedInkStaysReadableOnEveryPalette() {
        var checked = 0
        for (palette in ThemePalette.entries) {
            for (style in ThemeStyle.entries) {
                for (dark in listOf(false, true)) {
                    val scheme = colorSchemeFor(palette, style, dark)
                    val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
                    assertTrue(
                        "chip contrast $ratio on ${palette.name}/" +
                            "${style.name}/dark=$dark",
                        ratio >= 3.0f
                    )
                    checked++
                }
            }
        }
        assertTrue(checked == 64)
    }
}
