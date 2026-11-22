package com.nemu.hibernate.core

/**
 * Static cloud/telemetry targets. This list is deliberately separate from
 * [Targets] so the core freeze flow never suspends a disable-user package and
 * the cloud flow never touches the update/AI targets.
 */
object CloudTargets {
    val carrierDm: List<CloudTarget> = listOf(
        CloudTarget(
            packageName = "com.hihonor.regservice",
            label = "系统消息（实际：中国移动终端管理 DM）",
            action = CloudAction.DISABLE_USER,
            description = "CMCC DM：自注册、心跳、FOTA、应用清单上报。停用为可逆操作。"
        ),
        CloudTarget(
            packageName = "com.hihonor.android.UEInfoCheck",
            label = "UEInfoCheck（电信数据上报）",
            action = CloudAction.DISABLE_USER,
            description = "电信 UE 信息检查/上报组件。停用为可逆操作。"
        ),
        CloudTarget(
            packageName = "com.hihonor.android.AutoRegSms",
            label = "AutoRegSms（电信自注册短信）",
            action = CloudAction.DISABLE_USER,
            description = "电信自注册短信组件。停用后可能收不到运营商自注册短信。"
        )
    )

    val telemetry: List<CloudTarget> = listOf(
        CloudTarget(
            packageName = "com.hihonor.hiview",
            label = "hiview（遥测采集）",
            action = CloudAction.SUSPEND,
            description = "系统诊断/遥测采集；suspend 不阻止后台，配合 force-stop。"
        ),
        CloudTarget(
            packageName = "com.hihonor.hiviewpush",
            label = "hiviewpush（遥测推送）",
            action = CloudAction.SUSPEND,
            description = "遥测推送通道；suspend 不阻止后台，配合 force-stop。"
        ),
        CloudTarget(
            packageName = "com.hihonor.hiviewtunnel",
            label = "HiViewTunnel（遥测上传通道）",
            action = CloudAction.SUSPEND,
            description = "遥测上传通道；suspend 不阻止后台，配合 force-stop。"
        )
    )

    val observeOnly: List<CloudTarget> = listOf(
        CloudTarget(
            packageName = "com.hihonor.cipservice",
            label = "HnCIPService（仅观察）",
            action = CloudAction.OBSERVE_ONLY,
            description = "疑似 CHR 上报；本版本只展示状态，不提供操作。"
        )
    )

    val all: List<CloudTarget> = carrierDm + telemetry + observeOnly

    /** The only packages for which `pm disable-user` is allowed. */
    val disableUserAllowed: Set<String> = carrierDm.map { it.packageName }.toSet()

    /** Packages Nemu may leave suspended and must unsuspend before shutdown/boot. */
    val suspendPackageNames: List<String> = telemetry
        .filter { it.action == CloudAction.SUSPEND }
        .map { it.packageName }

    fun byPackage(packageName: String): CloudTarget? =
        all.firstOrNull { it.packageName == packageName }
}
