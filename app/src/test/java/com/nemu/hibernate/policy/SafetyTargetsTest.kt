package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTargetsTest {
    @Test
    fun includesEveryCoreTarget() {
        Targets.all.forEach {
            assertTrue(
                it.packageName,
                it.packageName in SafetyTargets.suspendPackageNames
            )
        }
    }

    @Test
    fun includesCloudSuspendTargetsAndNumberIdentity() {
        CloudTargets.suspendPackageNames.forEach {
            assertTrue(it, it in SafetyTargets.suspendPackageNames)
        }
        assertTrue(
            AntiFraudTargets.NUMBER_IDENTITY in SafetyTargets.suspendPackageNames
        )
        AiTargets.all.forEach {
            assertTrue(
                it.packageName,
                it.packageName in SafetyTargets.suspendPackageNames
            )
        }
    }

    @Test
    fun includesCarrierDmBecauseTheCatalogAllowsSuspendForThem() {
        CloudTargets.disableUserAllowed.forEach {
            assertTrue(it, it in SafetyTargets.suspendPackageNames)
        }
        // Suspend-capable catalog targets are included so shutdown can always
        // clear them, even when their default strategy is OBSERVE.
        assertTrue(
            "com.hihonor.cipservice" in SafetyTargets.suspendPackageNames
        )
        assertTrue(
            "com.hihonor.android.hnaps" in SafetyTargets.suspendPackageNames
        )
        assertTrue(
            "com.hihonor.msdp" in SafetyTargets.suspendPackageNames
        )
        assertTrue(
            "com.hihonor.assetsync" in SafetyTargets.suspendPackageNames
        )
        assertTrue(
            "com.hihonor.assetsyncservice" in SafetyTargets.suspendPackageNames
        )
    }

    @Test
    fun packageListHasNoDuplicates() {
        assertEquals(
            SafetyTargets.suspendPackageNames.size,
            SafetyTargets.suspendPackageNames.distinct().size
        )
    }

    @Test
    fun includesUnifiedCatalogSuspendTargets() {
        listOf(
            "com.hihonor.ouc",
            "com.hihonor.systemappsupdater",
            "com.hihonor.magictranslate",
            "com.hihonor.magicavatar"
        ).forEach {
            assertTrue(it, it in SafetyTargets.suspendPackageNames)
        }
    }

    @Test
    fun excludesCatalogTargetsThatAreOnlyComponentOrObserve() {
        listOf(
            "com.hihonor.systemserver",
            "com.hihonor.powergenie",
            "com.hihonor.iaware",
            "com.hihonor.android.pushagent"
        ).forEach {
            assertFalse(it, it in SafetyTargets.suspendPackageNames)
        }
    }
}
