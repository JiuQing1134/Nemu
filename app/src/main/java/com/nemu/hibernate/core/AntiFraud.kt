package com.nemu.hibernate.core

enum class NumberIdentityMode {
    OFF,
    SUSPEND,
    DISABLE_USER
}

data class AntiFraudConfig(
    val numberIdentityMode: NumberIdentityMode = NumberIdentityMode.OFF,
    val restrictSecurityServer: Boolean = false,
    val restrictAwareness: Boolean = false
) {
    val isActive: Boolean
        get() = numberIdentityMode != NumberIdentityMode.OFF ||
            restrictSecurityServer ||
            restrictAwareness
}

enum class AntiFraudAction(val label: String) {
    SUSPEND("暂停"),
    DISABLE_USER("停用"),
    UNSUSPEND("取消暂停"),
    ENABLE("启用"),
    APP_OPS_IGNORE("限制后台"),
    APP_OPS_ALLOW("恢复后台"),
    STANDBY_RESTRICTED("限制待机"),
    STANDBY_ACTIVE("恢复待机")
}

data class AntiFraudCommand(
    val packageName: String,
    val action: AntiFraudAction,
    val shell: String
)

data class AntiFraudResult(
    val packageName: String,
    val action: AntiFraudAction,
    val shell: String,
    val ok: Boolean,
    val dryRun: Boolean,
    val output: String
)

data class AntiFraudTarget(
    val packageName: String,
    val label: String,
    val risk: RiskLevel,
    val applyActions: Set<AntiFraudAction>,
    val note: String
)

object AntiFraudTargets {
    const val NUMBER_IDENTITY = "com.hihonor.numberidentity"
    const val SECURITY_SERVER = "com.hihonor.securityserver"
    const val AWARENESS = "com.hihonor.awareness"
    const val SECURITY_PLUGIN_BASE = "com.hihonor.securitypluginbase"

    val all: List<AntiFraudTarget> = listOf(
        AntiFraudTarget(
            packageName = NUMBER_IDENTITY,
            label = "号码识别",
            risk = RiskLevel.YELLOW,
            applyActions = setOf(
                AntiFraudAction.SUSPEND,
                AntiFraudAction.DISABLE_USER
            ),
            note = "暂停或停用后失去来电识别/骚扰与诈骗标记；可恢复。"
        ),
        AntiFraudTarget(
            packageName = SECURITY_SERVER,
            label = "安全公共服务",
            risk = RiskLevel.RED,
            applyActions = setOf(
                AntiFraudAction.APP_OPS_IGNORE,
                AntiFraudAction.STANDBY_RESTRICTED
            ),
            note = "反诈核心。仅限制后台/待机，禁止整包暂停或停用。"
        ),
        AntiFraudTarget(
            packageName = AWARENESS,
            label = "情景感知",
            risk = RiskLevel.RED,
            applyActions = setOf(
                AntiFraudAction.APP_OPS_IGNORE,
                AntiFraudAction.STANDBY_RESTRICTED
            ),
            note = "反诈与人脸活体/深伪检测载体。仅限制后台/待机，禁止整包暂停或停用。"
        ),
        AntiFraudTarget(
            packageName = SECURITY_PLUGIN_BASE,
            label = "安全插件底座",
            risk = RiskLevel.YELLOW,
            applyActions = emptySet(),
            note = "仅观察，不提供任何一键操作。"
        )
    )

    fun byPackage(packageName: String): AntiFraudTarget? =
        all.firstOrNull { it.packageName == packageName }
}
