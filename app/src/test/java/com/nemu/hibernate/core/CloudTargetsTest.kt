package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTargetsTest {
    @Test
    fun carrierDmTrio_is_disable_user_and_configured() {
        assertEquals(
            setOf(
                "com.hihonor.regservice",
                "com.hihonor.android.UEInfoCheck",
                "com.hihonor.android.AutoRegSms"
            ),
            CloudTargets.carrierDm.map { it.packageName }.toSet()
        )
        CloudTargets.carrierDm.forEach {
            assertEquals(CloudAction.DISABLE_USER, it.action)
        }
    }

    @Test
    fun telemetryTrio_is_suspend_and_configured() {
        assertEquals(
            setOf(
                "com.hihonor.hiview",
                "com.hihonor.hiviewpush",
                "com.hihonor.hiviewtunnel"
            ),
            CloudTargets.telemetry.map { it.packageName }.toSet()
        )
        CloudTargets.telemetry.forEach {
            assertEquals(CloudAction.SUSPEND, it.action)
        }
    }

    @Test
    fun cipservice_is_observe_only() {
        val cip = CloudTargets.byPackage("com.hihonor.cipservice")
        assertEquals(CloudAction.OBSERVE_ONLY, cip!!.action)
    }

    @Test
    fun pushagent_is_not_a_cloud_target_and_is_protected() {
        assertNull(CloudTargets.byPackage("com.hihonor.android.pushagent"))
        assertTrue(DenyList.isProtected("com.hihonor.android.pushagent"))
    }

    @Test
    fun no_cloud_target_is_protected() {
        CloudTargets.all.forEach {
            assertFalse(it.packageName, DenyList.isProtected(it.packageName))
        }
    }

    @Test
    fun cloud_targets_do_not_overlap_core_targets() {
        val core = Targets.all.map { it.packageName }.toSet()
        CloudTargets.all.forEach {
            assertFalse(it.packageName, it.packageName in core)
        }
    }

    @Test
    fun disable_user_allowlist_is_exactly_the_carrier_trio() {
        assertEquals(
            setOf(
                "com.hihonor.regservice",
                "com.hihonor.android.UEInfoCheck",
                "com.hihonor.android.AutoRegSms"
            ),
            CloudTargets.disableUserAllowed
        )
    }

    @Test
    fun suspend_packages_are_exactly_the_telemetry_trio() {
        assertEquals(
            setOf(
                "com.hihonor.hiview",
                "com.hihonor.hiviewpush",
                "com.hihonor.hiviewtunnel"
            ),
            CloudTargets.suspendPackageNames.toSet()
        )
    }

    @Test
    fun every_cloud_package_is_unique() {
        val names = CloudTargets.all.map { it.packageName }
        assertEquals(names.size, names.distinct().size)
    }
}
