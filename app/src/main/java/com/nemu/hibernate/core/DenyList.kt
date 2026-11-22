package com.nemu.hibernate.core

/**
 * Packages that must never be whole-package frozen by the generic
 * [com.nemu.hibernate.policy.FreezeController]. The anti-fraud module has its
 * own narrower action matrix ([AntiFraudPolicy]) and does not use this list:
 * for securityserver/awareness it only emits AppOps/standby commands, and for
 * numberidentity it permits the explicitly confirmed suspend/disable path.
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
