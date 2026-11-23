package com.nemu.hibernate.data

/**
 * Component ownership has two durable records: the owned flag and the exact
 * original state. A crash between the two commits must still leave the
 * component recoverable, so either key counts as ownership.
 */
object ComponentPreferenceKeys {
    fun ownedNames(
        keys: Collection<String>,
        packageName: String
    ): List<String> {
        val ownedPrefix = "strategy_component_owned_${packageName}_"
        val originalPrefix = "strategy_component_original_${packageName}_"
        return keys.asSequence()
            .mapNotNull { key ->
                when {
                    key.startsWith(ownedPrefix) -> key.removePrefix(ownedPrefix)
                    key.startsWith(originalPrefix) -> key.removePrefix(originalPrefix)
                    else -> null
                }
            }
            .distinct()
            .toList()
    }
}
