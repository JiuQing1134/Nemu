package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeStrategyPolicyTest {
    private fun target(
        packageName: String,
        risk: RiskLevel = RiskLevel.RED,
        components: List<TargetComponent> = emptyList()
    ) = Target(
        packageName = packageName,
        label = packageName,
        risk = risk,
        components = components
    )

    @Test
    fun denyListNoLongerBlocksWholePackageStrategies() {
        val target = target("com.hihonor.android.pushagent")

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.SUSPEND))
        assertTrue(FreezeStrategyPolicy.isAllowed(target, FreezeStrategy.SUSPEND))
    }

    @Test
    fun redTargetsCanUseWholePackageStrategiesWithWarning() {
        val target = target(
            "com.hihonor.aipluginengine",
        )

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.DISABLE_USER))
        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.HIDE))
        assertTrue(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                target,
                FreezeStrategy.HIDE
            )
        )
    }

    @Test
    fun aiSuspendStillRequiresNoExceptionGate() {
        val target = target(
            "com.hihonor.aipluginengine",
        )

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.SUSPEND))
        assertTrue(FreezeStrategyPolicy.isAllowed(target, FreezeStrategy.SUSPEND))
    }

    @Test
    fun numberIdentityDisableUserIsAllowed() {
        val target = target(
            "com.hihonor.numberidentity",
            risk = RiskLevel.YELLOW,
        )

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.DISABLE_USER))
    }

    @Test
    fun denyListAllowsHideForYellowTarget() {
        val target = target(
            "com.hihonor.securitypluginbase",
            risk = RiskLevel.YELLOW,
        )

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.HIDE))
        assertTrue(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                target,
                FreezeStrategy.HIDE
            )
        )
    }

    @Test
    fun denyListAllowsDisableUserForYellowTarget() {
        val target = target(
            "com.hihonor.securitypluginbase",
            risk = RiskLevel.YELLOW,
        )

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.DISABLE_USER))
    }

    @Test
    fun highRiskWarningOnlyForWholePackageStrategies() {
        val target = target(
            "com.hihonor.securitypluginbase",
            risk = RiskLevel.YELLOW,
        )

        assertFalse(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                target,
                FreezeStrategy.OBSERVE
            )
        )
        assertFalse(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                target,
                FreezeStrategy.COMPONENT
            )
        )
    }

    @Test
    fun denyListDoesNotBlockComponentStrategy() {
        val target = target(
            "com.hihonor.systemserver",
            components = listOf(
                TargetComponent(
                    className = "com.hihonor.systemserver/" +
                        "com.hihonor.systemserver.booster.framework.config." +
                        "CommonCloudParaUpdateReceiver",
                    label = "CommonCloudParaUpdateReceiver"
                )
            )
        )

        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.COMPONENT))
    }

    @Test
    fun isAllowedMatchesValidate() {
        val target = target("com.hihonor.android.pushagent")

        assertEquals(
            FreezeStrategyPolicy.validate(target, FreezeStrategy.SUSPEND) == null,
            FreezeStrategyPolicy.isAllowed(target, FreezeStrategy.SUSPEND)
        )
    }

    @Test
    fun hidingOrDisablingTheUpdateStackRequiresAWarning() {
        val ouc = target("com.hihonor.ouc", RiskLevel.YELLOW)
        assertTrue(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                ouc,
                FreezeStrategy.DISABLE_USER
            )
        )
        assertTrue(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                ouc,
                FreezeStrategy.HIDE
            )
        )
        assertFalse(
            FreezeStrategyPolicy.requiresHighRiskWarning(
                ouc,
                FreezeStrategy.SUSPEND
            )
        )
    }
}
