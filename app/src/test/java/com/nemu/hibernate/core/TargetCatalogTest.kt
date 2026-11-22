package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCatalogTest {
    @Test
    fun packagesAreUnique() {
        val names = TargetCatalog.all.map { it.packageName }
        assertEquals(names.size, names.distinct().size)
    }

    /**
     * Fresh-install safety: even though the boot-freeze master switch defaults
     * on, no catalog target may default to "enabled". The worker can then only
     * freeze targets the user explicitly selected.
     */
    @Test
    fun noTargetIsEnabledByDefault() {
        TargetCatalog.all.forEach { target ->
            assertFalse(
                "${target.packageName} must not be enabled on a fresh install",
                target.defaultEnabled
            )
        }
    }

    @Test
    fun everyDefaultStrategyIsSupportedAndValid() {
        TargetCatalog.all.forEach { target ->
            assertTrue(
                "${target.packageName} default ${target.defaultStrategy}",
                target.defaultStrategy in target.supportedStrategies
            )
            assertTrue(
                "${target.packageName} default invalid",
                FreezeStrategyPolicy.validate(target, target.defaultStrategy) == null ||
                    target.defaultStrategy == FreezeStrategy.OBSERVE
            )
        }
    }

    @Test
    fun everyComponentBelongsToItsPackage() {
        TargetCatalog.all.forEach { target ->
            target.components.forEach { component ->
                assertTrue(
                    "${component.className} must start with ${target.packageName}/",
                    component.className.startsWith("${target.packageName}/")
                )
            }
        }
    }

    @Test
    fun componentModeIsRejectedWithoutRegisteredComponents() {
        TargetCatalog.all.filter { it.components.isEmpty() }.forEach { target ->
            assertNotNull(
                "${target.packageName} must not offer a usable COMPONENT mode " +
                    "without components",
                FreezeStrategyPolicy.validate(
                    target,
                    FreezeStrategy.COMPONENT
                )
            )
        }
    }

    @Test
    fun catalogCoversTheFivePackageDomains() {
        listOf(
            TargetCategory.UPDATE,
            TargetCategory.AI,
            TargetCategory.CLOUD,
            TargetCategory.SAFETY,
            TargetCategory.THERMAL
        ).forEach { category ->
            assertNotNull(
                "no targets for $category",
                TargetCatalog.byCategory(category).takeIf { it.isNotEmpty() }
            )
        }
    }

    @Test
    fun redTargetsNeverSupportWholePackageDisable() {
        TargetCatalog.all.filter { it.risk == RiskLevel.RED }.forEach { target ->
            assertTrue(
                "${target.packageName} red disable-user",
                FreezeStrategy.DISABLE_USER !in target.supportedStrategies
            )
            assertTrue(
                "${target.packageName} red hide",
                FreezeStrategy.HIDE !in target.supportedStrategies
            )
        }
    }

    @Test
    fun commonlyUsedTargetsExplainTheirConsequences() {
        val ouc = TargetCatalog.byPackage("com.hihonor.ouc")
        assertNotNull(ouc)
        assertTrue(ouc!!.consequenceFor(FreezeStrategy.SUSPEND)!!.contains("更新"))
        assertTrue(ouc.consequenceFor(FreezeStrategy.COMPONENT)!!.contains("更新"))
    }

    @Test
    fun powerGenieStaysObserveOnlyAndExplainsWhy() {
        val powergenie = TargetCatalog.byPackage("com.hihonor.powergenie")
        assertNotNull(powergenie)
        assertEquals(
            setOf(FreezeStrategy.OBSERVE),
            powergenie!!.supportedStrategies
        )
        assertTrue(
            powergenie.consequenceFor(FreezeStrategy.OBSERVE)!!.contains("不会")
        )
    }
}
