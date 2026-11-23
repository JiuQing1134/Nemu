package com.nemu.hibernate.core

/**
 * Extra AI targets that are not part of [Targets.all]. Every entry is opt-in
 * per target; there is no global "AI deep freeze" switch. Most are also in
 * [DenyList], so choosing a whole-package strategy shows a high-risk warning
 * but is not blocked.
 *
 * Catalog defaults are conservative (SUSPEND + force-stop); other strategies
 * remain selectable with the same warning.
 */
object AiTargets {
    val all: List<Target> = listOf(
        Target(
            packageName = "com.hihonor.aipluginengine",
            label = "荣耀 AI · 基础服务",
            risk = RiskLevel.RED
        ),
        Target(
            packageName = "com.hihonor.assistant",
            label = "YOYO 建议基础服务",
            risk = RiskLevel.RED
        ),
        Target(
            packageName = "com.hihonor.magicvoice",
            label = "YOYO 智能体/智慧语音",
            risk = RiskLevel.RED
        ),
        Target(
            packageName = "com.hihonor.voiceengine",
            label = "语音服务",
            risk = RiskLevel.YELLOW
        ),
        Target(
            packageName = "com.hihonor.brain",
            label = "智慧决策",
            risk = RiskLevel.RED
        ),
        Target(
            packageName = "com.hihonor.mageruntime",
            label = "MageEngineRuntime",
            risk = RiskLevel.YELLOW
        )
    )
}
