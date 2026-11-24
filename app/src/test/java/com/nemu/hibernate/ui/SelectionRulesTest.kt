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
    fun rootOnlyStrategyCanBePlannedWithoutRootButNotApplied() {
        val hide = row(
            preset = StrategyPreset.HIDE,
            strategy = FreezeStrategy.HIDE
        )

        assertFalse(SelectionRules.isSelectable(hide, rootAvailable = false))
        assertTrue(SelectionRules.isSelectableForPlan(hide))
        assertEquals(
            1,
            SelectionRules.selectedForPlan(listOf(hide), setOf(hide.packageName)).size
        )
        assertTrue(
            SelectionRules.selectedForApply(
                listOf(hide),
                setOf(hide.packageName),
                rootAvailable = false,
                canFreeze = false
            ).isEmpty()
        )
    }

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
        assertEquals(
            listOf("com.example.on"),
            SelectionRules.planTargets(catalog).map { it.packageName }
        )
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
    fun planningSelectionDoesNotRequireAFreezeBackend() {
        val catalog = listOf(row(preset = StrategyPreset.FREEZE))

        assertTrue(
            SelectionRules.isSelectable(
                catalog.single(),
                rootAvailable = false
            )
        )
        assertEquals(
            1,
            SelectionRules.selectedForPlan(
                catalog,
                setOf("com.example.app")
            ).size
        )
        assertTrue(
            SelectionRules.selectedForApply(
                catalog,
                setOf("com.example.app"),
                rootAvailable = false,
                canFreeze = false
            ).isEmpty()
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

    @Test
    fun unfreezeScopeLabelOmitsRestorableCountWhenAllRowsAreOurs() {
        assertEquals(
            "当前冻结 5",
            unfreezeScopeLabel(frozenCount = 5, selectableCount = 5)
        )
    }

    @Test
    fun unfreezeScopeLabelShowsRestorableCountWhenSomeRowsAreReadOnly() {
        assertEquals(
            "当前冻结 5 · 可解冻 3",
            unfreezeScopeLabel(frozenCount = 5, selectableCount = 3)
        )
    }

    @Test
    fun unfreezeScopeLabelHandlesEmptyScope() {
        assertEquals(
            "当前冻结 0",
            unfreezeScopeLabel(frozenCount = 0, selectableCount = 0)
        )
    }
}
