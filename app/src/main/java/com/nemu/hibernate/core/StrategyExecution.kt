package com.nemu.hibernate.core

/**
 * What was found on disk for one target. Missing is distinct from Invalid:
 * legacy COMPONENT rows have no options and may be materialized once, while a
 * corrupt/unsupported value must never expand to "all components".
 */
sealed interface StoredOptions {
    data object Missing : StoredOptions
    data class Parsed(val options: StrategyOptions) : StoredOptions
    data class Invalid(val raw: String?) : StoredOptions
}

object StrategyOptionsResolver {
    fun resolve(
        target: Target,
        strategy: FreezeStrategy,
        stored: StoredOptions
    ): StrategyOptions? = when (stored) {
        is StoredOptions.Parsed -> stored.options
        StoredOptions.Missing -> if (strategy == FreezeStrategy.COMPONENT) {
            StrategyOptions(
                components = target.components.map { it.className }.toSet()
            )
        } else {
            StrategyOptions()
        }
        is StoredOptions.Invalid -> if (strategy == FreezeStrategy.COMPONENT) {
            null
        } else {
            StrategyOptions()
        }
    }
}

object StrategyExecution {
    /** True when the target participates in apply/boot planning. */
    fun isActive(strategy: FreezeStrategy, options: StrategyOptions): Boolean =
        strategy != FreezeStrategy.OBSERVE || options.appOps || options.standby

    /**
     * Components this apply may touch. COMPONENT with no configured selection
     * returns empty, and callers must fail closed (never write applied).
     */
    fun effectiveComponents(
        target: Target,
        strategy: FreezeStrategy,
        options: StrategyOptions
    ): List<TargetComponent> {
        if (strategy != FreezeStrategy.COMPONENT) return emptyList()
        val selected = options.components ?: return emptyList()
        return target.components.filter { it.className in selected }
    }
}
