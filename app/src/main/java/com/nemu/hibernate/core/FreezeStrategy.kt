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
 * - DISABLE_USER: `pm disable-user --user 0 pkg`; available for every target,
 *   with a high-risk warning for Red/DenyList packages.
 * - HIDE: `pm hide --user 0 pkg`; the package becomes invisible to the user
 *   and to PackageManager lookups, like an uninstalled app, but data and the
 *   APK stay on disk. Restore with `pm unhide`. This is the "伪装卸载"
 *   strategy; available for every target with the same warning.
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
 * Whole-package strategies are available for every installed package. The
 * registry only describes component/restriction metadata; Red/DenyList
 * packages still get a high-risk warning before applying.
 */
object FreezeStrategyPolicy {
    private val wholePackageStrategies = setOf(
        FreezeStrategy.SUSPEND,
        FreezeStrategy.DISABLE_USER,
        FreezeStrategy.HIDE
    )

    fun isAllowed(target: Target, strategy: FreezeStrategy): Boolean =
        validate(target, strategy) == null

    fun validate(target: Target, strategy: FreezeStrategy): String? = when {
        // Whole-package strategies are available for every installed package.
        // The platform may still reject a specific package at execution time;
        // that is surfaced as a command failure, not a pre-emptive block.
        strategy == FreezeStrategy.COMPONENT && target.components.isEmpty() ->
            "${target.packageName} 没有登记可停用的组件"
        else -> null
    }

    /**
     * True when the target needs an explicit high-risk warning, not a block.
     * Hiding/disabling the update stack is especially dangerous: the user can
     * lose the only built-in way to install firmware or security updates.
     */
    fun requiresHighRiskWarning(target: Target, strategy: FreezeStrategy): Boolean {
        val updateCritical =
            target.packageName == "com.hihonor.ouc" ||
                target.packageName.contains("systemappsupdater") ||
                target.packageName.contains("upgradeguide") ||
                target.packageName.contains("softwareupdate")
        if (updateCritical &&
            (strategy == FreezeStrategy.DISABLE_USER ||
                strategy == FreezeStrategy.HIDE)
        ) {
            return true
        }
        return strategy in wholePackageStrategies &&
            (DenyList.isProtected(target.packageName) ||
                target.risk == RiskLevel.RED)
    }
}
