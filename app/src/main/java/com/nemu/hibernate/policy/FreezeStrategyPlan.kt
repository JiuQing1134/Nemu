package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetComponent

/**
 * Pure command planner for the multi-mode freeze catalog. It does not execute
 * anything and does not touch SharedPreferences, so every command surface can
 * be unit-tested and rendered in the Apply/Unfreeze confirmation dialogs.
 */
data class FreezeStrategyPlan(
    val target: Target,
    val strategy: FreezeStrategy,
    val components: List<TargetComponent>,
    val commands: List<String>,
    val restoreCommands: List<String>
) {
    fun describe(): String = buildString {
        append(target.label)
        append(" [")
        append(strategy.name)
        append("]")
        if (components.isNotEmpty()) {
            append(" components=")
            append(components.joinToString(",") { it.label })
        }
    }
}

object FreezeStrategyPlanner {
    fun apply(
        target: Target,
        strategy: FreezeStrategy,
        componentOverride: List<TargetComponent>? = null,
        options: StrategyOptions = StrategyOptions()
    ): FreezeStrategyPlan {
        require(FreezeStrategyPolicy.validate(target, strategy) == null) {
            FreezeStrategyPolicy.validate(target, strategy) ?: ""
        }
        val components = if (strategy == FreezeStrategy.COMPONENT) {
            componentOverride ?: target.components
        } else {
            emptyList()
        }
        val commands = when (strategy) {
            FreezeStrategy.SUSPEND -> buildList {
                add(CommandBuilder.suspend(target.packageName))
                if (options.forceStop) add(CommandBuilder.forceStop(target.packageName))
            }
            FreezeStrategy.COMPONENT -> buildList {
                components.forEach { add(CommandBuilder.disableComponent(it.className)) }
                if (options.forceStop) add(CommandBuilder.forceStop(target.packageName))
            }
            FreezeStrategy.DISABLE_USER -> listOf(
                CommandBuilder.disableUser(target.packageName)
            )
            FreezeStrategy.HIDE -> listOf(
                CommandBuilder.hide(target.packageName)
            )
            FreezeStrategy.OBSERVE -> emptyList()
        }
        val restore = when (strategy) {
            FreezeStrategy.SUSPEND -> listOf(
                CommandBuilder.unsuspend(target.packageName)
            )
            FreezeStrategy.COMPONENT ->
                components.map { CommandBuilder.defaultStateComponent(it.className) }
            FreezeStrategy.DISABLE_USER -> listOf(
                CommandBuilder.enable(target.packageName)
            )
            FreezeStrategy.HIDE -> listOf(
                CommandBuilder.unhide(target.packageName)
            )
            FreezeStrategy.OBSERVE -> emptyList()
        }
        return FreezeStrategyPlan(target, strategy, components, commands, restore)
    }

    /**
     * Restore commands for the stored strategy, without the capability
     * validation used by [apply]. A capability retired in a newer build must
     * still be undoable for state written by an older build.
     */
    fun restoreOnly(target: Target, strategy: FreezeStrategy): List<String> =
        when (strategy) {
            FreezeStrategy.SUSPEND -> listOf(
                CommandBuilder.unsuspend(target.packageName)
            )
            FreezeStrategy.COMPONENT -> target.components.map {
                CommandBuilder.defaultStateComponent(it.className)
            }
            FreezeStrategy.DISABLE_USER -> listOf(
                CommandBuilder.enable(target.packageName)
            )
            FreezeStrategy.HIDE -> listOf(
                CommandBuilder.unhide(target.packageName)
            )
            FreezeStrategy.OBSERVE -> emptyList()
        }

    /** Builds the plan for every target whose policy is enabled. */
    fun applyEnabled(
        targets: List<Target>,
        enabled: (String) -> Boolean,
        strategyOf: (Target) -> FreezeStrategy
    ): List<FreezeStrategyPlan> = targets.mapNotNull { target ->
        if (!enabled(target.packageName)) return@mapNotNull null
        val strategy = strategyOf(target)
        if (FreezeStrategyPolicy.validate(target, strategy) != null) return@mapNotNull null
        apply(target, strategy)
    }
}
