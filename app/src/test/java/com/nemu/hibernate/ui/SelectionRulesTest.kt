package com.nemu.hibernate.ui

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRulesTest {
    private fun row(
        packageName: String = "com.example.app",
        enabled: Boolean = false,
        preset: StrategyPreset = StrategyPreset.FREEZE,
        strategy: FreezeStrategy = FreezeStrategy.SUSPEND,
        active: Boolean = true,
        target: Target = Target(
            packageName = packageName,
            label = packageName,
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI
        )
    ) = CatalogTargetUiState(
        target = target,
        enabled = enabled,
        strategy = strategy,
        preset = preset,
        active = active
    )

    @Test
    fun selectionAppliesEvenWhenNotInBootPlan() {
        val catalog = listOf(row(enabled = false))

        val selected = SelectionRules.selectedForApply(
            catalog,
            setOf("com.example.app"),
            rootAvailable = true
        )

        assertEquals(1, selected.size)
        assertEquals(0, SelectionRules.planCount(catalog))
    }

    @Test
    fun planCountOnlyCountsEnabledActiveTargets() {
        val catalog = listOf(
            row(packageName = "com.example.on", enabled = true),
            row(
                packageName = "com.example.observe",
                enabled = true,
                preset = StrategyPreset.OBSERVE,
                strategy = FreezeStrategy.OBSERVE,
                active = false
            )
        )

        assertEquals(1, SelectionRules.planCount(catalog))
    }

    @Test
    fun observeAndUnavailableTargetsAreNotSelectable() {
        assertFalse(
            SelectionRules.isSelectable(
                row(active = false),
                rootAvailable = true
            )
        )

        val pushagent = TargetCatalog.byPackage(
            "com.hihonor.android.pushagent"
        )!!
        assertFalse(
            SelectionRules.isSelectable(
                row(
                    packageName = pushagent.packageName,
                    preset = StrategyPreset.COMPONENT,
                    target = pushagent
                ),
                rootAvailable = true
            )
        )
        assertTrue(
            SelectionRules.isSelectable(
                row(),
                rootAvailable = true
            )
        )
    }

    @Test
    fun pruneDropsSelectionsHiddenByTheCurrentFilterOrSearch() {
        assertEquals(
            setOf("com.example.visible"),
            SelectionRules.prune(
                selected = setOf("com.example.visible", "com.example.hidden"),
                visible = listOf("com.example.visible")
            )
        )
    }

    @Test
    fun unavailableSelectionIsExcluded() {
        val pushagent = TargetCatalog.byPackage(
            "com.hihonor.android.pushagent"
        )!!
        val catalog = listOf(
            row(
                packageName = pushagent.packageName,
                preset = StrategyPreset.COMPONENT,
                target = pushagent
            )
        )

        assertEquals(
            0,
            SelectionRules.selectedForApply(
                catalog,
                setOf(pushagent.packageName),
                rootAvailable = true
            ).size
        )
    }
}
