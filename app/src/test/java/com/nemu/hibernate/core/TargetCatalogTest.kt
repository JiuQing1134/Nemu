package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun everyDefaultStrategyIsValid() {
        TargetCatalog.all.forEach { target ->
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
    fun redWholePackageStrategiesRequireHighRiskWarning() {
        val whole = setOf(
            FreezeStrategy.SUSPEND,
            FreezeStrategy.DISABLE_USER,
            FreezeStrategy.HIDE
        )
        TargetCatalog.all.filter { it.risk == RiskLevel.RED }.forEach { target ->
            whole.forEach { strategy ->
                assertTrue(
                    "${target.packageName} $strategy 缺少高风险提醒",
                    FreezeStrategyPolicy.requiresHighRiskWarning(
                        target,
                        strategy
                    )
                )
            }
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
    fun denyListGateAllowsRegisteredAiSuspendException() {
        val ai = TargetCatalog.byPackage("com.hihonor.aipluginengine")
        assertNotNull(ai)
        assertNull(
            FreezeStrategyPolicy.validate(ai!!, FreezeStrategy.SUSPEND)
        )
    }

    @Test
    fun denyListGateAllowsNumberIdentityDisableException() {
        val numberIdentity = TargetCatalog.byPackage("com.hihonor.numberidentity")
        assertNotNull(numberIdentity)
        assertNull(
            FreezeStrategyPolicy.validate(
                numberIdentity!!,
                FreezeStrategy.DISABLE_USER
            )
        )
    }

    @Test
    fun securityServerSuspendIsAllowedWithWarning() {
        val securityServer = TargetCatalog.byPackage("com.hihonor.securityserver")
        assertNotNull(securityServer)
        assertNull(
            FreezeStrategyPolicy.validate(
                securityServer!!,
                FreezeStrategy.SUSPEND
            )
        )
        assertTrue(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                securityServer,
                FreezeStrategy.SUSPEND
            )
        )
    }

    @Test
    fun securityPluginBaseWholePackageIsAllowedWithWarning() {
        val pluginBase = TargetCatalog.byPackage("com.hihonor.securitypluginbase")
        assertNotNull(pluginBase)
        listOf(
            FreezeStrategy.SUSPEND,
            FreezeStrategy.DISABLE_USER,
            FreezeStrategy.HIDE
        ).forEach { strategy ->
            assertNull(
                "${pluginBase!!.packageName} $strategy 应可用",
                FreezeStrategyPolicy.validate(pluginBase, strategy)
            )
            assertTrue(
                "${pluginBase.packageName} $strategy 需要高风险提醒",
                FreezeStrategyPolicy.requiresHighRiskWarning(
                    pluginBase,
                    strategy
                )
            )
        }
    }

    @Test
    fun powerGenieWholePackageStrategiesAreAvailableButHighRisk() {
        val powergenie = TargetCatalog.byPackage("com.hihonor.powergenie")
        assertNotNull(powergenie)
        listOf(
            FreezeStrategy.SUSPEND,
            FreezeStrategy.DISABLE_USER,
            FreezeStrategy.HIDE
        ).forEach { strategy ->
            assertNull(
                TargetCopy.unavailableReason(powergenie!!, strategy)
            )
            assertNotNull(TargetCopy.riskWarning(powergenie, strategy))
        }
    }
}
