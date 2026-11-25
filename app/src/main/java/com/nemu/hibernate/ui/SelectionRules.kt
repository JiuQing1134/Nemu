package com.nemu.hibernate.ui

import com.nemu.hibernate.core.TargetCopy

/**
 * Scope label for the unfreeze page's “current” tab. The list also contains
 * diagnostic rows frozen/hidden by another tool, which Nemu cannot restore;
 * showing only the total would imply every listed row is actionable. The
 * second number appears only when some rows are read-only.
 */
internal fun unfreezeScopeLabel(
    frozenCount: Int,
    selectableCount: Int
): String = if (selectableCount == frozenCount) {
    "当前冻结 $frozenCount"
} else {
    "当前冻结 $frozenCount · 可解冻 $selectableCount"
}

/**
 * Session selection is deliberately independent from the persistent boot plan.
 * A target can be applied now without being enabled for boot, and vice versa.
 */
object SelectionRules {
    /**
     * Selecting a row only means “this target participates in a batch”. It
     * does not require a freeze backend: adding/removing a boot plan entry is
     * a preference write. Apply is gated separately by [selectedForApply].
     */
    fun isSelectable(
        target: CatalogTargetUiState,
        rootAvailable: Boolean
    ): Boolean = target.isInstalled && target.active &&
        TargetCopy.unavailableReason(
            target.target,
            target.preset,
            rootAvailable
        ) == null

    /**
     * Planning is a preference write and is executed later by the boot
     * module, which may have root even when the current session does not.
     * Root-only strategies therefore stay selectable here; platform-rejected
     * and component-empty strategies remain excluded.
     */
    fun isSelectableForPlan(target: CatalogTargetUiState): Boolean =
        target.isInstalled && target.active &&
            TargetCopy.unavailableReason(
                target.target,
                target.preset,
                rootAvailable = true
            ) == null

    /** Selected rows that can be written to the boot plan. */
    fun selectedForPlan(
        catalog: List<CatalogTargetUiState>,
        selected: Set<String>
    ): List<CatalogTargetUiState> = catalog.filter { target ->
        target.packageName in selected &&
            isSelectableForPlan(target)
    }

    fun selectedForApply(
        catalog: List<CatalogTargetUiState>,
        selected: Set<String>,
        rootAvailable: Boolean,
        canFreeze: Boolean = true
    ): List<CatalogTargetUiState> = catalog.filter { target ->
        target.packageName in selected &&
            canFreeze &&
            isSelectable(target, rootAvailable)
    }

    fun planTargets(
        catalog: List<CatalogTargetUiState>
    ): List<CatalogTargetUiState> = catalog.filter {
        it.isInstalled && it.enabled && it.active
    }

    fun planCount(catalog: List<CatalogTargetUiState>): Int =
        planTargets(catalog).size

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
