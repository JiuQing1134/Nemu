package com.nemu.hibernate.core

object Targets {
    val ouc: Target = Target(
        packageName = "com.hihonor.ouc",
        label = "软件更新",
        risk = RiskLevel.YELLOW,
        category = TargetCategory.UPDATE,
        components = listOf(
            // CotaService is deliberately NOT listed: HnStartupGuide/system
            // bind it during setup and disabling it can break the first-run
            // flow. Only broadcast/download receivers are safe to disable.
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.provider.DownloadReceiver",
                label = "DownloadReceiver",
                role = "下载/安装广播"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.newBiz.reveiver.CotaReceiver",
                label = "CotaReceiver",
                role = "COTA 广播"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.newUtils.download.provider.CotaDownloadReceiver",
                label = "CotaDownloadReceiver",
                role = "COTA 下载广播"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.biz.impl.reveiver.CheckNewVersionReceiver",
                label = "CheckNewVersionReceiver",
                role = "版本检查广播"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.biz.impl.reveiver.FirmwareAutoDownloadReceiver",
                label = "FirmwareAutoDownloadReceiver",
                role = "固件自动下载"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.biz.impl.reveiver.FirmwareInstallReceiver",
                label = "FirmwareInstallReceiver",
                role = "固件安装广播"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.biz.impl.reveiver.NightUpgradeReceiver",
                label = "NightUpgradeReceiver",
                role = "夜间自动升级"
            ),
            TargetComponent(
                className = "com.hihonor.ouc/com.hihonor.android.hnouc.biz.impl.reveiver.SelfUpdateReceiver",
                label = "SelfUpdateReceiver",
                role = "OUC 自更新"
            )
        ),
        description = "系统更新服务。冻结后手机不会自动检查、下载或安装新系统版本；" +
            "应用商店/手动检查也可能受影响。不会卸载任何东西，解冻即可恢复。",
        consequences = mapOf(
            FreezeStrategy.SUSPEND to
                "系统更新会被暂停：手机不会在后台检查/下载系统更新，" +
                    "设置里的“软件更新”可能提示检查失败。不会影响已安装的系统。",
            FreezeStrategy.COMPONENT to
                "只停用下载/安装/夜间升级等广播：自动更新会停止，" +
                    "但系统更新界面仍可打开；手动检查是否可用取决于剩余服务。",
            FreezeStrategy.OBSERVE to
                "不改动系统更新，只在检测页显示它的运行状态。"
        )
    )

    val all: List<Target> = listOf(
        ouc,
        Target(
            packageName = "com.honor.yoyoappsug",
            label = "App 建议",
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI
        ),
        Target(
            packageName = "com.honor.yoyocards",
            label = "YOYO 建议卡片集",
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI
        ),
        Target(
            packageName = "com.hihonor.suggestion",
            label = "智能提醒",
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI
        ),
        Target(
            packageName = "com.hihonor.sceneservice",
            label = "荣耀智慧空间基础服务",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.AI
        )
    )

    fun byPackage(packageName: String): Target? =
        all.firstOrNull { it.packageName == packageName }
}
