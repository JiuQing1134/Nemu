package com.nemu.hibernate.core

/**
 * How a target is frozen. This is deliberately separate from the read-only
 * detection classification [com.nemu.hibernate.detection.FreezeMode].
 *
 * - SUSPEND: `pm suspend` + optional `am force-stop`; weakest, components can
 *   still be bound by the system.
 * - COMPONENT: selectively `pm disable --user 0 pkg/component`; intended for
 *   red packages whose framework bindings restart them. Restore with
 *   `pm default-state`.
 * - DISABLE_USER: `pm disable-user --user 0 pkg`; only for green/yellow leaf
 *   packages and explicitly confirmed carrier DM/number-identity targets.
 * - HIDE: `pm hide --user 0 pkg`; the package becomes invisible to the user
 *   and to PackageManager lookups, like an uninstalled app, but data and the
 *   APK stay on disk. Restore with `pm unhide`. This is the "伪装卸载"
 *   strategy. Only offered on explicit yellow/green leaf targets.
 * - OBSERVE: no command; read-only display (thermal/HAL/unknown packages).
 */
enum class FreezeStrategy {
    SUSPEND,
    COMPONENT,
    DISABLE_USER,
    HIDE,
    OBSERVE
}

enum class TargetCategory {
    UPDATE,
    AI,
    CLOUD,
    SAFETY,
    THERMAL,
    AUTOMATION
}

data class TargetComponent(
    val className: String,
    val label: String,
    val role: String = "",
    val risk: RiskLevel = RiskLevel.YELLOW
)

/**
 * Strategy policy: the UI can only offer modes that are safe for this target.
 * Red packages must never be whole-package disabled.
 */
object FreezeStrategyPolicy {
    val redSupported: Set<FreezeStrategy> = setOf(
        FreezeStrategy.SUSPEND,
        FreezeStrategy.COMPONENT,
        FreezeStrategy.OBSERVE
    )
    val yellowSupported: Set<FreezeStrategy> = setOf(
        FreezeStrategy.SUSPEND,
        FreezeStrategy.COMPONENT,
        FreezeStrategy.DISABLE_USER,
        FreezeStrategy.HIDE,
        FreezeStrategy.OBSERVE
    )
    val greenSupported: Set<FreezeStrategy> = setOf(
        FreezeStrategy.SUSPEND,
        FreezeStrategy.COMPONENT,
        FreezeStrategy.DISABLE_USER,
        FreezeStrategy.HIDE,
        FreezeStrategy.OBSERVE
    )

    fun supported(risk: RiskLevel): Set<FreezeStrategy> = when (risk) {
        RiskLevel.RED -> redSupported
        RiskLevel.YELLOW -> yellowSupported
        RiskLevel.GREEN -> greenSupported
    }

    fun isAllowed(target: Target, strategy: FreezeStrategy): Boolean =
        strategy in target.supportedStrategies &&
            strategy in supported(target.risk)

    fun validate(target: Target, strategy: FreezeStrategy): String? = when {
        !isAllowed(target, strategy) ->
            "${target.packageName} 不支持 ${strategy.name}（风险 ${target.risk.name}）"
        strategy == FreezeStrategy.COMPONENT && target.components.isEmpty() ->
            "${target.packageName} 没有登记可停用的组件"
        strategy == FreezeStrategy.DISABLE_USER && target.risk == RiskLevel.RED ->
            "红色包禁止整包 disable-user"
        strategy == FreezeStrategy.HIDE && target.risk == RiskLevel.RED ->
            "红色包禁止整包隐藏（伪装卸载）"
        strategy == FreezeStrategy.HIDE && FreezeStrategy.HIDE !in target.supportedStrategies ->
            "${target.packageName} 不在可隐藏清单中"
        else -> null
    }
}
