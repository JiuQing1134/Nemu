package com.nemu.hibernate.core

/**
 * Warning metadata for the all-apps picker. Every installed package except
 * Nemu itself can be added; recovery-critical packages (launcher/SystemUI/
 * telephony/settings/input methods) only get an explicit "I understand the
 * risk" step and are stored as RED so their cards show the high-risk warning.
 */
object CustomTargetGuard {
    private val criticalPackages = setOf(
        "android",
        "com.nemu.hibernate",
        "com.nemu.dream",
        "com.nemu.windstop",
        "me.yuki.fina",
        "moe.shizuku.privileged.api",
        "org.lsposed.manager",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.hihonor.systemserver",
        "com.hihonor.systemmanager",
        "com.hihonor.powergenie",
        "com.hihonor.securityserver",
        "com.hihonor.awareness",
        "com.hihonor.iaware"
    )

    private val criticalPrefixes = listOf(
        "com.android.internal.systemui",
        "com.android.launcher",
        "com.hihonor.android.launcher",
        "com.hihonor.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    /** Nemu itself is the only package that must never become a target. */
    fun isSelf(packageName: String): Boolean =
        packageName == "com.nemu.dream" || packageName == "com.nemu.hibernate"

    /**
     * Removing the Target row would orphan any ownership record and could
     * leave a suspended/hidden/disabled package with no restore path. Check
     * both persisted ownership and the last actual-state snapshot.
     */
    fun shouldBlockRemoval(owned: Boolean, hasFrozenState: Boolean): Boolean =
        owned || hasFrozenState

    /** Recovery-critical names; they get a warning and RED risk, not a block. */
    fun isCriticalPackage(packageName: String): Boolean =
        packageName in criticalPackages ||
            criticalPrefixes.any { packageName.startsWith(it) } ||
            packageName.contains("inputmethod", ignoreCase = true)

    /** Human-readable warning for a critical package; null means no warning. */
    fun criticalReason(
        packageName: String,
        dynamicRoles: Map<String, String> = emptyMap()
    ): String? {
        if (isSelf(packageName)) return "Nemu 自身"
        dynamicRoles[packageName]?.let { return it }
        if (packageName in criticalPackages) return "系统关键应用"
        if (criticalPrefixes.any { packageName.startsWith(it) }) return "系统关键应用"
        if (packageName.contains("inputmethod", ignoreCase = true)) return "输入法"
        if (TargetCatalog.byPackage(packageName)?.risk == RiskLevel.RED) {
            return "高风险目标"
        }
        return null
    }
}
