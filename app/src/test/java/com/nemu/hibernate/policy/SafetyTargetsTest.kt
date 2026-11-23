package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
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
    fun includesNumberIdentityAndEveryAiTarget() {
        assertTrue(
            "com.hihonor.numberidentity" in SafetyTargets.suspendPackageNames
        )
        AiTargets.all.forEach {
            assertTrue(
                it.packageName,
                it.packageName in SafetyTargets.suspendPackageNames
            )
        }
    }

    @Test
    fun includesCloudCategoryTargetsBecauseSuspendIsSelectable() {
        // Suspend-capable catalog targets are included so shutdown can always
        // clear them, even when their default strategy is OBSERVE.
        listOf(
            "com.hihonor.regservice",
            "com.hihonor.android.UEInfoCheck",
            "com.hihonor.android.AutoRegSms"
        ).forEach {
            assertTrue(it, it in SafetyTargets.suspendPackageNames)
        }
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
    fun includesEveryCatalogTargetForOwnedSafetyCleanup() {
        listOf(
            "com.hihonor.systemserver",
            "com.hihonor.powergenie",
            "com.hihonor.iaware",
            "com.hihonor.android.pushagent"
        ).forEach {
            assertTrue(it, it in SafetyTargets.suspendPackageNames)
        }
    }
}
