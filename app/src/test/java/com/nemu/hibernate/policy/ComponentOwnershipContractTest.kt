package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A component removed by an app update leaves an owned baseline that is no
 * longer present in Target.components. Every restore path must merge owned
 * names back in, otherwise the stale baseline can never be resolved.
 */
class ComponentOwnershipContractTest {
    @Test
    fun residualAndUserRestoreMergeOwnedComponents() {
        val service = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/TargetFreezeService.kt"
        )

        assertTrue(service.contains("OwnedComponentCatalog.merge"))
        assertTrue(service.contains("policy.ownedComponentNames(target)"))
    }

    @Test
    fun shutdownScriptMergesOwnedComponents() {
        val script = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/ShutdownScript.kt"
        )

        assertTrue(script.contains("OwnedComponentCatalog.merge"))
        assertTrue(script.contains("policy.ownedComponentNames(target)"))
    }
}
