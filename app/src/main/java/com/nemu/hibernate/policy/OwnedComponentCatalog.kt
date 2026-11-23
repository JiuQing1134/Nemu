package com.nemu.hibernate.policy

import com.nemu.hibernate.core.TargetComponent

/**
 * Merges the currently registered components with every class name Nemu still
 * owns. An app update can remove or rename a component while its exact-state
 * baseline remains in SharedPreferences; without this merge that baseline is
 * unreachable and every residual cleanup keeps failing on it.
 */
object OwnedComponentCatalog {
    fun merge(
        registered: List<TargetComponent>,
        ownedNames: List<String>
    ): List<TargetComponent> {
        val result = LinkedHashMap<String, TargetComponent>()
        registered.forEach { result.putIfAbsent(it.className, it) }
        ownedNames.forEach { className ->
            result.putIfAbsent(
                className,
                TargetComponent(
                    className = className,
                    label = displayLabel(className)
                )
            )
        }
        return result.values.toList()
    }

    private fun displayLabel(className: String): String =
        className.substringAfterLast('/').substringAfterLast('.')
}
