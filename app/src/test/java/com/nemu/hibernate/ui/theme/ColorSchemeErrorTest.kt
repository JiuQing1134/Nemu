package com.nemu.hibernate.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSchemeErrorTest {
    @Test
    fun errorTextUsesBrightRedInBothModes() {
        assertEquals(
            Color(0xFFFF383C),
            colorSchemeFor(
                ThemePalette.BLUE,
                ThemeStyle.VIVID,
                dark = false
            ).error
        )
        assertEquals(
            Color(0xFFFF383C),
            colorSchemeFor(
                ThemePalette.BLUE,
                ThemeStyle.VIVID,
                dark = true
            ).error
        )
        assertEquals(
            Color.White,
            colorSchemeFor(
                ThemePalette.BLUE,
                ThemeStyle.VIVID,
                dark = false
            ).onError
        )
    }
}
