package com.nemu.hibernate.core

enum class RiskLevel {
    GREEN,
    YELLOW,
    RED
}

data class Target(
    val packageName: String,
    val label: String,
    val risk: RiskLevel,
    val category: TargetCategory = TargetCategory.AI,
    val defaultStrategy: FreezeStrategy = FreezeStrategy.SUSPEND,
    val components: List<TargetComponent> = emptyList(),
    val description: String = "",
    /**
     * Per-strategy consequence overrides. Kept for catalog documentation and
     * policy tests; the confirmation dialog uses [com.nemu.hibernate.core.TargetCopy]
     * (generic impact + selected overrides) instead of rendering this map.
     */
    val consequences: Map<FreezeStrategy, String> = emptyMap()
) {
    fun consequenceFor(strategy: FreezeStrategy): String? =
        consequences[strategy]?.takeIf { it.isNotBlank() }
}
