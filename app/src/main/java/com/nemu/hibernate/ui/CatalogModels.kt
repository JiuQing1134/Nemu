package com.nemu.hibernate.ui

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCategory

/**
 * One row on the Freeze/Unfreeze pages. Policy values come from TargetPolicy;
 * actual values come from PackageManager + the root process snapshot. The UI
 * never derives "applied" from the command result alone.
 */
data class CatalogTargetUiState(
    val target: Target,
    val enabled: Boolean = false,
    val strategy: FreezeStrategy = FreezeStrategy.SUSPEND,
    val applied: Boolean = false,
    val actual: TargetActualUiState = TargetActualUiState(),
    /** Any registered component disabled; null when not COMPONENT/read failed. */
    val componentsDisabled: Boolean? = null,
    /** A recorded original-enabled baseline proves Nemu touched disable-user. */
    val ownedDisable: Boolean = false,
    /** A recorded pre-hide baseline proves Nemu (not the user/OEM) hid it. */
    val ownedHidden: Boolean = false
) {
    val packageName: String get() = target.packageName
    val label: String get() = target.label
    val risk: RiskLevel get() = target.risk
    val category: TargetCategory get() = target.category
    val observeOnly: Boolean
        get() = target.supportedStrategies == setOf(FreezeStrategy.OBSERVE)

    val hasFrozenState: Boolean
        get() = applied ||
            actual.suspended == true ||
            (actual.hidden == true && ownedHidden) ||
            (strategy == FreezeStrategy.COMPONENT &&
                componentsDisabled == true) ||
            // An actually-disabled package stays visible only when Nemu
            // has ownership evidence (applied marker or recorded baseline).
            // A purely user-disabled package is not ours and cannot be enabled
            // by a restore, so it must not clutter the recovery page.
            (FreezeStrategy.DISABLE_USER in target.supportedStrategies &&
                actual.packageEnabled == false &&
                (applied || ownedDisable))

    fun actualText(): String = when {
        actual.hidden == true && ownedHidden -> "已隐藏 · 对系统不可见"
        actual.hidden == true -> "已被隐藏（不是本应用设置）"
        strategy == FreezeStrategy.HIDE -> when {
            applied -> "隐藏标记存在 · 状态待确认"
            else -> "未隐藏（仍可见）"
        }
        strategy == FreezeStrategy.COMPONENT -> buildString {
            append(
                when (componentsDisabled) {
                    true -> "组件已停用"
                    false -> "组件启用"
                    null -> "组件状态未知"
                }
            )
            if (actual.processRunning == true) append(" · 进程存活")
        }
        actual.suspended == null && actual.packageEnabled == null -> "未知"
        actual.packageEnabled == false && actual.suspended == true ->
            "已停用 · 已暂停"
        actual.packageEnabled == false -> "已停用"
        actual.suspended == true && actual.processRunning == true ->
            "已暂停 · 进程存活"
        actual.suspended == true -> "已暂停"
        actual.processRunning == true -> "运行中"
        else -> "未冻结"
    }
}
