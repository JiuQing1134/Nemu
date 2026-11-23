package com.nemu.hibernate.core

/**
 * Packages that need an explicit high-risk warning before whole-package
 * operations. They are no longer blocked; the warning is produced by
 * [com.nemu.hibernate.core.TargetCopy.riskWarning].
 */
object DenyList {
    val protectedPackages: Set<String> = setOf(
        "com.hihonor.systemserver",
        "com.hihonor.systemmanager",
        "com.hihonor.devicemanager",
        "com.hihonor.aipluginengine",
        "com.hihonor.assistant",
        "com.hihonor.magicvoice",
        "com.hihonor.brain",
        "com.hihonor.awareness",
        "com.hihonor.securityserver",
        "com.hihonor.securitypluginbase",
        "com.hihonor.numberidentity",
        "com.hihonor.powergenie",
        "com.hihonor.iaware",
        "com.hihonor.android.pushagent"
    )

    fun isProtected(packageName: String): Boolean =
        packageName in protectedPackages
}
