package com.nemu.hibernate.ui

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCategory

/**
 * One row on the Freeze/Unfreeze pages. Policy values come from TargetPolicy;
 * actual values come from PackageManager + the root process snapshot. The UI
 * never derives "applied" from the command result alone.
 */
/**
 * "Ownership pending" means the applied marker has no matching live state.
 * AppOps/standby ownership is a live frozen state in its own right (OBSERVE
 * targets have no suspension/component marker), so it must not count as
 * pending cleanup.
 */
internal fun isOwnershipPending(
    applied: Boolean,
    actuallyFrozen: Boolean,
    ownsAppOps: Boolean,
    ownsStandby: Boolean
): Boolean = applied && !actuallyFrozen && !ownsAppOps && !ownsStandby

data class CatalogTargetUiState(
    val target: Target,
    val enabled: Boolean = false,
    val strategy: FreezeStrategy = FreezeStrategy.SUSPEND,
    /** UI preset derived from strategy + options; never persisted. */
    val preset: StrategyPreset = StrategyPreset.OBSERVE,
    /** True when the target participates in apply/boot planning. */
    val active: Boolean = strategy != FreezeStrategy.OBSERVE,
    val applied: Boolean = false,
    val actual: TargetActualUiState = TargetActualUiState(),
    /** Any registered component disabled; null when not COMPONENT/read failed. */
    val componentsDisabled: Boolean? = null,
    val selectedComponents: Set<String> = emptySet(),
    val ownedComponents: Set<String> = emptySet(),
    /** className -> PackageManager enabled state (0/1/2/3/4), null when unreadable. */
    val componentStates: Map<String, Int?> = emptyMap(),
    /** A recorded original-enabled baseline proves Nemu touched disable-user. */
    val ownedDisable: Boolean = false,
    /** A recorded pre-hide baseline proves Nemu (not the user/OEM) hid it. */
    val ownedHidden: Boolean = false,
    /** Nemu owns an AppOps background restriction for this package. */
    val appOpsOwned: Boolean = false,
    /** Nemu owns a standby-bucket restriction for this package. */
    val standbyOwned: Boolean = false,
    /** Applied marker without a matching live state (e.g. uninstalled). */
    val ownershipPending: Boolean = false,
    /** PackageInfo.firstInstallTime; 0 when unavailable. */
    val installedAt: Long = 0L
) {
    val packageName: String get() = target.packageName
    val label: String get() = target.label
    val risk: RiskLevel get() = target.risk
    val category: TargetCategory get() = target.category

    val hasFrozenState: Boolean
        get() = applied ||
            actual.suspended == true ||
            (actual.hidden == true && ownedHidden) ||
            (strategy == FreezeStrategy.COMPONENT &&
                componentsDisabled == true) ||
            ownedComponents.isNotEmpty() ||
            // An actually-disabled package stays visible only when Nemu
            // has ownership evidence (applied marker or recorded baseline).
            // A purely user-disabled package is not ours and cannot be enabled
            // by a restore, so it must not clutter the recovery page.
            (actual.packageEnabled == false && (applied || ownedDisable)) ||
            appOpsOwned ||
            standbyOwned ||
            ownershipPending

    /**
     * True when Nemu holds evidence that it owns some frozen state here.
     * A row that is only suspended by another tool is read-only: restoring it
     * would either fail or silently take over someone else's suspension.
     */
    val ownsAnyState: Boolean
        get() = applied || ownedDisable || ownedHidden ||
            ownedComponents.isNotEmpty() || appOpsOwned || standbyOwned ||
            ownershipPending

    /** User-facing SUSPEND word; follows the selected preset. */
    fun suspendWord(): String =
        if (preset == StrategyPreset.PAUSE) "暂停" else "冻结"

    fun actualText(): String = when {
        ownershipPending -> "所有权待清理"
        actual.hidden == true && ownedHidden -> "已隐藏"
        actual.hidden == true -> "他人隐藏"
        strategy == FreezeStrategy.HIDE ->
            if (applied) "隐藏待确认" else "未隐藏"
        strategy == FreezeStrategy.COMPONENT -> when {
            componentsDisabled == true -> "组件已停用"
            componentsDisabled == false -> "组件启用"
            else -> "状态未知"
        }
        actual.suspended == null && actual.packageEnabled == null -> "状态未知"
        actual.packageEnabled == false && actual.suspended == true ->
            "已停用·已${suspendWord()}"
        actual.packageEnabled == false -> "已停用"
        actual.suspended == true && !ownsAnyState -> "他人暂停"
        actual.suspended == true && actual.processRunning == true ->
            "已${suspendWord()}·进程存活"
        actual.suspended == true -> "已${suspendWord()}"
        appOpsOwned || standbyOwned -> "已限制"
        actual.processRunning == true -> "运行中"
        else -> "未${suspendWord()}"
    }
}
