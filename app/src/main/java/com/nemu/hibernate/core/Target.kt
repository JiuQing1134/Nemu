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
    val defaultEnabled: Boolean = false,
    val suspend: Boolean = true,
    val forceStop: Boolean = true,
    val clearCache: Boolean = true,
    val category: TargetCategory = TargetCategory.AI,
    val defaultStrategy: FreezeStrategy = FreezeStrategy.SUSPEND,
    val supportedStrategies: Set<FreezeStrategy> =
        FreezeStrategyPolicy.supported(risk),
    val components: List<TargetComponent> = emptyList(),
    val description: String = "",
    /**
     * Concrete, user-facing consequences of each strategy, e.g.
     * "停用后无法检查/安装系统更新". Shown in the confirmation dialog before a
     * freeze command runs. Missing entries fall back to a generic explanation.
     */
    val consequences: Map<FreezeStrategy, String> = emptyMap()
) {
    fun consequenceFor(strategy: FreezeStrategy): String? =
        consequences[strategy]?.takeIf { it.isNotBlank() }
}
