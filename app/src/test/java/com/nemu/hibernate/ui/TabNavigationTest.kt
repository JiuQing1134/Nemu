package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabNavigationTest {
    @Test
    fun directBottomBarTapClearsTheBackStack() {
        val nav = TabNavigation.initial(0)
            .openProgrammatic(1)
            .selectDirect(2)

        assertEquals(TabNavigation(2), nav)
        assertNull(nav.back())
    }

    @Test
    fun programmaticJumpPushesThePreviousTab() {
        val nav = TabNavigation.initial(3).openProgrammatic(1)

        assertEquals(1, nav.selected)
        assertEquals(listOf(3), nav.backStack)
        assertEquals(TabNavigation(3), nav.back())
    }

    @Test
    fun jumpingToTheCurrentTabDoesNothing() {
        val nav = TabNavigation.initial(1).openProgrammatic(1)

        assertEquals(TabNavigation(1), nav)
        assertNull(nav.back())
    }

    @Test
    fun repeatedDeepLinksPopInReverseOrder() {
        val nav = TabNavigation.initial(3)
            .openProgrammatic(2)
            .openProgrammatic(1)
            .openProgrammatic(4)

        assertEquals(listOf(3, 2, 1), nav.backStack)
        val back1 = nav.back()!!
        assertEquals(1, back1.selected)
        assertEquals(listOf(3, 2), back1.backStack)
        val back2 = back1.back()!!
        assertEquals(2, back2.selected)
        assertEquals(listOf(3), back2.backStack)
        val back3 = back2.back()!!
        assertEquals(3, back3.selected)
        assertNull(back3.back())
    }
}
