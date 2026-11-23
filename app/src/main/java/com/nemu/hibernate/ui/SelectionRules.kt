package com.nemu.hibernate.ui

import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.TargetCopy

/**
 * Session selection is deliberately independent from the persistent boot plan.
 * A target can be applied now without being enabled for boot, and vice versa.
 */
object SelectionRules {
    fun isSelectable(
        target: CatalogTargetUiState,
        rootAvailable: Boolean,
        canFreeze: Boolean = true
    ): Boolean = target.active &&
        (canFreeze || target.preset == StrategyPreset.OBSERVE) &&
        TargetCopy.unavailableReason(
            target.target,
            target.preset,
            rootAvailable
        ) == null

    fun selectedForApply(
        catalog: List<CatalogTargetUiState>,
        selected: Set<String>,
        rootAvailable: Boolean,
        canFreeze: Boolean = true
    ): List<CatalogTargetUiState> = catalog.filter { target ->
        target.packageName in selected &&
            target.active &&
            (canFreeze || target.preset == StrategyPreset.OBSERVE) &&
            TargetCopy.unavailableReason(
                target.target,
                target.preset,
                rootAvailable
            ) == null
    }

    fun planCount(catalog: List<CatalogTargetUiState>): Int =
        catalog.count { it.enabled && it.active }

    /**
     * Batch selection is scoped to what the user can currently see. A target
     * hidden by a category chip or search must not remain silently selected
     * and be applied later with the visible batch.
     */
    fun prune(
        selected: Set<String>,
        visible: Collection<String>
    ): Set<String> = selected intersect visible.toSet()
}
