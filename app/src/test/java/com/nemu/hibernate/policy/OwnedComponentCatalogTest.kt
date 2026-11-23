package com.nemu.hibernate.policy

import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.TargetComponent
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedComponentCatalogTest {
    private val registered = TargetComponent(
        className = "com.example.app/com.example.app.LiveReceiver",
        label = "LiveReceiver"
    )
    private val stale = "com.example.app/com.example.app.OldReceiver"

    @Test
    fun staleOwnedComponentIsStillRestorable() {
        val merged = OwnedComponentCatalog.merge(
            registered = listOf(registered),
            ownedNames = listOf(stale)
        )

        assertEquals(
            listOf(registered.className, stale),
            merged.map { it.className }
        )
        assertEquals("OldReceiver", merged.last().label)
    }

    @Test
    fun registeredLabelIsPreservedAndDuplicatesAreRemoved() {
        val merged = OwnedComponentCatalog.merge(
            registered = listOf(registered),
            ownedNames = listOf(registered.className, registered.className)
        )

        assertEquals(1, merged.size)
        assertEquals(registered, merged.single())
        assertEquals(RiskLevel.YELLOW, merged.single().risk)
    }
}
