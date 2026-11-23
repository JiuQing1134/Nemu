package com.nemu.hibernate.ui

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResourceTest {
    @Test
    fun lightWindowMatchesTheFlClashPageColour() {
        val themes = TestSources.read("src/main/res/values/themes.xml")

        assertTrue(themes.contains("#EFEDF0"))
        assertTrue(!themes.contains("#F2F7FA"))
    }
}
