package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentSelectionDiffTest {
    @Test
    fun deselectedReturnsOwnedMinusSelection() {
        assertEquals(
            setOf("a", "c"),
            ComponentSelectionDiff.deselected(
                owned = listOf("a", "b", "c"),
                selected = setOf("b")
            )
        )
    }

    @Test
    fun staleOwnedComponentIsIncluded() {
        assertEquals(
            setOf("removed.Component"),
            ComponentSelectionDiff.deselected(
                owned = listOf("removed.Component"),
                selected = emptySet()
            )
        )
    }
}
