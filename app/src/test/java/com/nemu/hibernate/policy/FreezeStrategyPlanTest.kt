package com.nemu.hibernate.policy

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.core.TargetComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeStrategyPlanTest {
    private val component = TargetComponent(
        className = "com.example.app/com.example.app.RestartService",
        label = "RestartService"
    )
    private val green = Target(
        packageName = "com.example.app",
        label = "Example",
        risk = RiskLevel.GREEN,
        category = TargetCategory.AI
    )
    private val red = Target(
        packageName = "com.example.red",
        label = "Red",
        risk = RiskLevel.RED,
        category = TargetCategory.AI,
        components = listOf(component)
    )

    @Test
    fun suspendPlanEmitsSuspendAndForceStop() {
        val plan = FreezeStrategyPlanner.apply(green, FreezeStrategy.SUSPEND)

        assertEquals(2, plan.commands.size)
        assertTrue(plan.commands.any { it.contains("pm suspend") })
        assertTrue(plan.commands.any { it.contains("am force-stop") })
        assertEquals(1, plan.restoreCommands.size)
        assertTrue(plan.restoreCommands[0].contains("pm unsuspend"))
    }

    @Test
    fun suspendWithoutForceStopEmitsOnlySuspend() {
        val plan = FreezeStrategyPlanner.apply(
            green,
            FreezeStrategy.SUSPEND,
            options = StrategyOptions(forceStop = false)
        )

        assertEquals(1, plan.commands.size)
        assertTrue(plan.commands[0].contains("pm suspend"))
        assertTrue(plan.commands.none { it.contains("force-stop") })
    }

    @Test
    fun suspendWithExplicitForceStopEmitsBoth() {
        val plan = FreezeStrategyPlanner.apply(
            green,
            FreezeStrategy.SUSPEND,
            options = StrategyOptions(forceStop = true)
        )

        assertEquals(2, plan.commands.size)
        assertTrue(plan.commands.any { it.contains("pm suspend") })
        assertTrue(plan.commands.any { it.contains("force-stop") })
    }

    @Test
    fun componentPlanDisablesOnlyRegisteredComponentsAndRestoresDefaultState() {
        val plan = FreezeStrategyPlanner.apply(red, FreezeStrategy.COMPONENT)

        assertTrue(plan.commands.any { it.contains("pm disable") && it.contains("RestartService") })
        assertTrue(plan.commands.any { it.contains("am force-stop") })
        assertTrue(plan.restoreCommands.any {
            it.contains("pm default-state") && it.contains("RestartService")
        })
    }

    @Test
    fun redPackageCanBeDisabledWithWarning() {
        val error = com.nemu.hibernate.core.FreezeStrategyPolicy.validate(
            red,
            FreezeStrategy.DISABLE_USER
        )

        assertNull(error)
        assertTrue(
            com.nemu.hibernate.core.FreezeStrategyPolicy
                .requiresHighRiskWarning(red, FreezeStrategy.DISABLE_USER)
        )
    }

    @Test
    fun observePlanHasNoCommands() {
        val plan = FreezeStrategyPlanner.apply(red, FreezeStrategy.OBSERVE)

        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.restoreCommands.isEmpty())
    }

    @Test
    fun hidePlanUsesPmHideAndPmUnhide() {
        val plan = FreezeStrategyPlanner.apply(green, FreezeStrategy.HIDE)

        assertEquals(1, plan.commands.size)
        assertTrue(plan.commands[0].contains("pm hide"))
        assertTrue(plan.commands[0].contains(green.packageName))
        assertTrue(plan.restoreCommands[0].contains("pm unhide"))
    }

    @Test
    fun redPackageCanBeHiddenWithWarning() {
        val error = com.nemu.hibernate.core.FreezeStrategyPolicy.validate(
            red,
            FreezeStrategy.HIDE
        )

        assertNull(error)
        assertTrue(
            com.nemu.hibernate.core.FreezeStrategyPolicy
                .requiresHighRiskWarning(red, FreezeStrategy.HIDE)
        )
    }

    @Test
    fun enabledOnlyPlanSkipsDisabledTargets() {
        val target2 = green.copy(packageName = "com.example.two", label = "Two")
        val plans = FreezeStrategyPlanner.applyEnabled(
            listOf(green, target2),
            enabled = { it == green.packageName },
            strategyOf = { FreezeStrategy.SUSPEND }
        )

        assertEquals(1, plans.size)
        assertEquals(green.packageName, plans[0].target.packageName)
    }
}
