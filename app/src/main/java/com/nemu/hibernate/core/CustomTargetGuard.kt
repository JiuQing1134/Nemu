package com.nemu.hibernate.core

/**
 * Hard safety filter for the "freeze any app" picker. The user asked for
 * arbitrary apps, but hiding a launcher/SystemUI/telephony package can make the
 * phone unbootable or unrecoverable. These packages are never offered as
 * custom targets; catalog RED targets keep their existing component-only UI.
 */
object CustomTargetGuard {
    private val exactDenied = setOf(
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

    private val deniedPrefixes = listOf(
        "com.android.internal.systemui",
        "com.android.launcher",
        "com.hihonor.android.launcher",
        "com.hihonor.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    fun denyReason(packageName: String): String? {
        if (packageName in exactDenied) return "系统关键应用"
        if (deniedPrefixes.any { packageName.startsWith(it) }) return "系统关键应用"
        if (packageName.contains("inputmethod", ignoreCase = true)) return "输入法"
        if (TargetCatalog.byPackage(packageName)?.risk == RiskLevel.RED) {
            return "目录中的高风险目标"
        }
        return null
    }

    fun isDenied(packageName: String): Boolean = denyReason(packageName) != null
}
