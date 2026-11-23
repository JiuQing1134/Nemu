package com.nemu.hibernate.ui

import androidx.compose.runtime.saveable.listSaver

/**
 * Bottom-tab navigation with a small in-app back stack.
 *
 * Direct taps on the bottom bar are top-level navigation: they clear the
 * stack, so system back exits. Programmatic jumps (detection/all-apps/settings
 * deep links) push the tab we came from, so back returns there instead of
 * closing Nemu.
 */
internal data class TabNavigation(
    val selected: Int,
    val backStack: List<Int> = emptyList()
) {
    fun selectDirect(tab: Int): TabNavigation = TabNavigation(tab)

    fun openProgrammatic(tab: Int): TabNavigation =
        if (tab == selected) this
        else TabNavigation(tab, backStack + selected)

    /** Null when there is nowhere in-app to go back to. */
    fun back(): TabNavigation? =
        backStack.lastOrNull()?.let {
            TabNavigation(it, backStack.dropLast(1))
        }

    companion object {
        fun initial(tab: Int): TabNavigation = TabNavigation(tab)

        val Saver = listSaver<TabNavigation, Int>(
            save = { listOf(it.selected) + it.backStack },
            restore = { TabNavigation(it.first(), it.drop(1)) }
        )
    }
}
