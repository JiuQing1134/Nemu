package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedRestorePlanTest {
    @Test
    fun routesOnlySelectedKnownPackagesAndIgnoresUnknownNames() {
        val core = Targets.all.first()
        val ai = AiTargets.all.first()
        val cloud = CloudTargets.telemetry.first()
        val plan = SelectedRestorePlan.fromSelection(
            setOf(core.packageName, ai.packageName, cloud.packageName, "com.example.unknown")
        )

        assertEquals(listOf(core), plan.core)
        assertEquals(listOf(ai), plan.ai)
        assertEquals(listOf(cloud), plan.cloud)
        assertEquals(setOf(core.packageName, ai.packageName, cloud.packageName), plan.packageNames)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun emptyOrUnknownOnlySelectionProducesNoRestoreWork() {
        assertTrue(SelectedRestorePlan.fromSelection(emptySet()).isEmpty)
        assertTrue(
            SelectedRestorePlan.fromSelection(setOf("com.example.unknown")).isEmpty
        )
    }
}
