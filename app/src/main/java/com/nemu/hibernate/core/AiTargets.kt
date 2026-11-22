package com.nemu.hibernate.core

/**
 * AI deep-freeze targets (design docs/07 §6.1). Every entry is opt-in: the
 * user must enable "AI 深度冻结" and then each individual package.
 *
 * These packages intentionally stay out of [Targets.all] and out of the
 * generic [com.nemu.hibernate.policy.FreezeController] path. Most of them are
 * also in [DenyList], so the generic controller refuses them; only
 * [com.nemu.hibernate.policy.AiFreezeController] may act on this whitelist.
 *
 * Defaults are deliberately conservative: disabled, suspend + force-stop
 * only, never clear cache, never disable-user/uninstall.
 */
object AiTargets {
    val all: List<Target> = listOf(
        Target(
            packageName = "com.hihonor.aipluginengine",
            label = "荣耀 AI · 基础服务",
            risk = RiskLevel.RED,
            defaultEnabled = false,
            suspend = true,
            forceStop = true,
            clearCache = false
        ),
        Target(
            packageName = "com.hihonor.assistant",
            label = "YOYO 建议基础服务",
            risk = RiskLevel.RED,
            defaultEnabled = false,
            suspend = true,
            forceStop = true,
            clearCache = false
        ),
        Target(
            packageName = "com.hihonor.magicvoice",
            label = "YOYO 智能体/智慧语音",
            risk = RiskLevel.RED,
            defaultEnabled = false,
            suspend = true,
            forceStop = true,
            clearCache = false
        ),
        Target(
            packageName = "com.hihonor.voiceengine",
            label = "语音服务",
            risk = RiskLevel.YELLOW,
            defaultEnabled = false,
            suspend = true,
            forceStop = true,
            clearCache = false
        ),
        Target(
            packageName = "com.hihonor.brain",
            label = "智慧决策",
            risk = RiskLevel.RED,
            defaultEnabled = false,
            suspend = true,
            forceStop = true,
            clearCache = false
        ),
        Target(
            packageName = "com.hihonor.mageruntime",
            label = "MageEngineRuntime",
            risk = RiskLevel.YELLOW,
            defaultEnabled = false,
            suspend = true,
            forceStop = true,
            clearCache = false
        )
    )

    fun byPackage(packageName: String): Target? =
        all.firstOrNull { it.packageName == packageName }

    fun isAllowed(packageName: String): Boolean =
        all.any { it.packageName == packageName }
}
