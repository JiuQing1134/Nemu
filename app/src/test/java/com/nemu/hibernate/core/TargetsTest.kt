package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetsTest {
    @Test
    fun updatePackage_is_first_and_disabled_by_default() {
        val update = Targets.all.first()
        assertEquals("com.hihonor.ouc", update.packageName)
        assertEquals(RiskLevel.YELLOW, update.risk)
        assertTrue(!update.defaultEnabled)
    }

    @Test
    fun every_target_has_a_unique_package() {
        val names = Targets.all.map { it.packageName }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun no_target_is_protected() {
        Targets.all.forEach { target ->
            assertTrue(
                "target must not be in deny list: ${target.packageName}",
                !DenyList.isProtected(target.packageName)
            )
        }
    }

    @Test
    fun byPackage_finds_update() {
        assertNotNull(Targets.byPackage("com.hihonor.ouc"))
    }

    @Test
    fun aiLeaves_are_green_or_yellow_and_selectable() {
        val ai = Targets.byPackage("com.honor.yoyoappsug")
        assertNotNull(ai)
        assertEquals(RiskLevel.GREEN, ai!!.risk)
        assertFalse(DenyList.isProtected(ai.packageName))
    }

    @Test
    fun genericTargets_doNotIncludeAntiFraudPackages() {
        val antiFraud = AntiFraudTargets.all.map { it.packageName }.toSet()
        Targets.all.forEach { target ->
            assertFalse(
                "anti-fraud package must stay out of Targets.all: ${target.packageName}",
                target.packageName in antiFraud
            )
        }
    }
}
