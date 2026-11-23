package com.nemu.hibernate.core

/**
 * Single source for the terse user-facing copy. The UI must not render
 * Target.description / consequences directly: those are internal notes and
 * were the source of the duplicated paragraphs.
 */
object TargetCopy {
    private val functionLines: Map<String, String> = mapOf(
        "com.hihonor.ouc" to "系统更新检查、下载与安装",
        "com.hihonor.systemappsupdater" to "更新系统自带应用",
        "com.hihonor.android.upgradeguide" to "系统升级说明界面",
        "com.honor.yoyoappsug" to "桌面应用使用建议",
        "com.honor.yoyocards" to "YOYO 建议卡片",
        "com.hihonor.suggestion" to "场景提醒与建议通知",
        "com.hihonor.sceneservice" to "智慧空间场景联动",
        "com.hihonor.aipluginengine" to "端侧 AI 模型与能力",
        "com.hihonor.assistant" to "YOYO 卡片与建议服务",
        "com.hihonor.magicvoice" to "语音唤醒与交互",
        "com.hihonor.voiceengine" to "语音合成与唤醒联动",
        "com.hihonor.brain" to "端侧决策与云配置",
        "com.hihonor.mageruntime" to "AI 引擎运行时",
        "com.hihonor.visionengine" to "相机图库 AI 识别",
        "com.hihonor.magictranslate" to "翻译文本与对话",
        "com.hihonor.magicavatar" to "生成头像与形象",
        "com.hihonor.lens" to "相机识屏识物",
        "com.hihonor.magichome" to "AI 功能入口",
        "com.hihonor.hiboard" to "桌面负一屏卡片",
        "com.hihonor.regservice" to "运营商配置与自注册",
        "com.hihonor.android.UEInfoCheck" to "电信信息检查上报",
        "com.hihonor.android.AutoRegSms" to "运营商自注册短信",
        "com.hihonor.hiview" to "系统诊断与遥测",
        "com.hihonor.hiviewpush" to "遥测推送通道",
        "com.hihonor.hiviewtunnel" to "日志上传通道",
        "com.hihonor.android.chr" to "崩溃与诊断上报",
        "com.hihonor.detectrepair" to "本地检测与远程诊断",
        "com.hihonor.assetsync" to "同步厂商云控参数",
        "com.hihonor.assetsyncservice" to "COTA 参数同步",
        "com.hihonor.cipservice" to "疑似诊断上报",
        "com.hihonor.systemserver" to "系统插件与云参数",
        "com.hihonor.android.pushagent" to "系统通知推送通道",
        "com.hihonor.systemmanager" to "系统管家与反诈",
        "com.hihonor.numberidentity" to "来电识别与诈骗标记",
        "com.hihonor.securityserver" to "支付与风险校验",
        "com.hihonor.awareness" to "活体与情景判断",
        "com.hihonor.securitypluginbase" to "安全插件公共支撑",
        "com.hihonor.android.hnaps" to "刷新率画面自适应",
        "com.hihonor.powergenie" to "调频温控与充电",
        "com.hihonor.iaware" to "CPU/GPU 调度与云参数",
        "com.hihonor.msdp" to "融合传感器数据"
    )

    private val impactOverrides: Map<Pair<String, FreezeStrategy>, String> = mapOf(
        ("com.hihonor.ouc" to FreezeStrategy.SUSPEND) to
            "系统更新停止；已安装版本不受影响",
        ("com.hihonor.ouc" to FreezeStrategy.COMPONENT) to
            "自动更新广播停用；更新界面仍可打开",
        ("com.hihonor.hiview" to FreezeStrategy.COMPONENT) to
            "遥测组件停用；主进程可能仍存活",
        ("com.hihonor.numberidentity" to FreezeStrategy.DISABLE_USER) to
            "来电识别消失；通话不受影响",
        ("com.hihonor.systemserver" to FreezeStrategy.COMPONENT) to
            "云参数更新停用；系统服务继续运行",
        ("com.hihonor.systemmanager" to FreezeStrategy.COMPONENT) to
            "反诈标记减少；病毒与电池继续",
        ("com.hihonor.iaware" to FreezeStrategy.COMPONENT) to
            "云调度参数停更；现有调度继续",
        ("com.hihonor.detectrepair" to FreezeStrategy.COMPONENT) to
            "远程诊断云配置停更；本地检测可用"
    )

    private val platformRejected: Map<String, Set<FreezeStrategy>> = mapOf(
        "com.hihonor.hiview" to setOf(FreezeStrategy.SUSPEND),
        "com.hihonor.hiviewpush" to setOf(FreezeStrategy.SUSPEND),
        "com.hihonor.hiviewtunnel" to setOf(FreezeStrategy.SUSPEND),
        "com.hihonor.powergenie" to setOf(FreezeStrategy.COMPONENT)
    )

    fun functionLine(packageName: String): String =
        functionLines[packageName] ?: "用户添加的应用"

    /** Main strategy choices shown on a target card (restrictions excluded). */
    val mainPresets: List<StrategyPreset> = listOf(
        StrategyPreset.OBSERVE,
        StrategyPreset.PAUSE,
        StrategyPreset.FREEZE,
        StrategyPreset.COMPONENT,
        StrategyPreset.DISABLE_USER,
        StrategyPreset.HIDE
    )

    private val restrictionPresetTargets = setOf(
        "com.hihonor.securityserver",
        "com.hihonor.awareness"
    )

    /** Presets shown on a card; restrictions only for their registered targets. */
    fun presetsFor(target: Target): List<StrategyPreset> = buildList {
        addAll(mainPresets)
        if (target.packageName in restrictionPresetTargets) {
            add(StrategyPreset.RESTRICT_BACKGROUND)
            add(StrategyPreset.RESTRICT_STANDBY)
        }
    }

    fun baseStrategy(preset: StrategyPreset): FreezeStrategy = when (preset) {
        StrategyPreset.OBSERVE,
        StrategyPreset.RESTRICT_BACKGROUND,
        StrategyPreset.RESTRICT_STANDBY -> FreezeStrategy.OBSERVE
        StrategyPreset.PAUSE,
        StrategyPreset.FREEZE -> FreezeStrategy.SUSPEND
        StrategyPreset.COMPONENT -> FreezeStrategy.COMPONENT
        StrategyPreset.DISABLE_USER -> FreezeStrategy.DISABLE_USER
        StrategyPreset.HIDE -> FreezeStrategy.HIDE
    }

    fun label(strategy: FreezeStrategy): String = when (strategy) {
        FreezeStrategy.OBSERVE -> "观察"
        FreezeStrategy.SUSPEND -> "暂停"
        FreezeStrategy.COMPONENT -> "组件"
        FreezeStrategy.DISABLE_USER -> "停用"
        FreezeStrategy.HIDE -> "隐藏"
    }

    fun label(preset: StrategyPreset): String = when (preset) {
        StrategyPreset.OBSERVE -> "观察"
        StrategyPreset.PAUSE -> "暂停"
        StrategyPreset.FREEZE -> "冻结"
        StrategyPreset.COMPONENT -> "组件"
        StrategyPreset.DISABLE_USER -> "停用"
        StrategyPreset.HIDE -> "隐藏"
        StrategyPreset.RESTRICT_BACKGROUND -> "限制后台"
        StrategyPreset.RESTRICT_STANDBY -> "限制待机"
    }

    fun impact(strategy: FreezeStrategy): String = when (strategy) {
        FreezeStrategy.OBSERVE -> "不改动，只显示状态"
        FreezeStrategy.SUSPEND -> "暂停并结束当前进程；系统可能重新拉起服务"
        FreezeStrategy.COMPONENT -> "停用登记的后台组件；应用本身仍可打开"
        FreezeStrategy.DISABLE_USER -> "整包禁用，入口消失；数据保留"
        FreezeStrategy.HIDE -> "伪装成未安装；APK 和数据保留"
    }

    fun impactFor(target: Target, strategy: FreezeStrategy): String =
        impactOverrides[target.packageName to strategy] ?: impact(strategy)

    fun impact(preset: StrategyPreset): String = when (preset) {
        StrategyPreset.OBSERVE -> impact(FreezeStrategy.OBSERVE)
        StrategyPreset.PAUSE -> "暂停但不结束进程；后台服务仍可能被唤起"
        StrategyPreset.FREEZE -> impact(FreezeStrategy.SUSPEND)
        StrategyPreset.COMPONENT -> impact(FreezeStrategy.COMPONENT)
        StrategyPreset.DISABLE_USER -> impact(FreezeStrategy.DISABLE_USER)
        StrategyPreset.HIDE -> impact(FreezeStrategy.HIDE)
        StrategyPreset.RESTRICT_BACKGROUND ->
            "可以打开；后台运行被系统拒绝"
        StrategyPreset.RESTRICT_STANDBY ->
            "后台任务和网络被严格限制"
    }

    fun impactFor(target: Target, preset: StrategyPreset): String =
        if (preset == StrategyPreset.PAUSE) {
            impact(StrategyPreset.PAUSE)
        } else {
            impactFor(target, baseStrategy(preset))
        }

    fun restoreLine(strategy: FreezeStrategy): String = when (strategy) {
        FreezeStrategy.OBSERVE -> "无需恢复"
        FreezeStrategy.SUSPEND -> "取消暂停；若曾被强停，首次打开后恢复后台"
        FreezeStrategy.COMPONENT -> "按原状态恢复组件；若曾被强停，首次打开后恢复后台"
        FreezeStrategy.DISABLE_USER -> "重新启用；入口与数据回来"
        FreezeStrategy.HIDE -> "解除隐藏；入口与数据回来"
    }

    fun restoreLine(preset: StrategyPreset): String = when (preset) {
        StrategyPreset.RESTRICT_BACKGROUND -> "恢复原始后台模式"
        StrategyPreset.RESTRICT_STANDBY -> "恢复原始待机桶"
        else -> restoreLine(baseStrategy(preset))
    }

    /**
     * Confirmation dialog body. At most three lines, each with a stable
     * prefix so the UI can style the impact line without parsing prose.
     */
    fun confirmLines(
        target: Target,
        preset: StrategyPreset,
        enableOnly: Boolean,
        dryRun: Boolean,
        bootPlanEnabled: Boolean = true
    ): List<String> = buildList {
        if (enableOnly) {
            add(
                if (bootPlanEnabled) {
                    "计划：下次开机会执行；勾选不立即修改手机"
                } else {
                    "总开关：开机自动冻结已关闭，勾选只保存计划"
                }
            )
            val warning = riskWarning(target, preset)
            add(
                "影响：" + if (warning != null) {
                    "$warning；" + impactFor(target, preset)
                } else {
                    impactFor(target, preset)
                }
            )
            if (dryRun) add("执行：预览，不会修改手机")
        } else {
            val warning = riskWarning(target, preset)
            add(
                "影响：" + if (warning != null) {
                    "$warning；" + impactFor(target, preset)
                } else {
                    impactFor(target, preset)
                }
            )
            add("恢复：" + restoreLine(preset))
            if (dryRun) add("执行：预览，不会修改手机")
        }
    }

    /**
     * High-risk reminder shown on the card and in the confirm dialog. It never
     * blocks the action; only platform/root/component limits still do.
     */
    fun riskWarning(
        target: Target,
        strategy: FreezeStrategy
    ): String? = if (FreezeStrategyPolicy.requiresHighRiskWarning(target, strategy)) {
        "高风险，可能影响系统/安全功能"
    } else {
        null
    }

    fun riskWarning(target: Target, preset: StrategyPreset): String? =
        riskWarning(target, baseStrategy(preset))

    /**
     * Null means the strategy is selectable now. A non-null reason must be
     * short enough for one line and must never contain English enum names.
     */
    fun unavailableReason(
        target: Target,
        strategy: FreezeStrategy,
        rootAvailable: Boolean = true
    ): String? {
        if (strategy == FreezeStrategy.OBSERVE) return null
        if (platformRejected[target.packageName]?.contains(strategy) == true) {
            return "平台拒绝"
        }
        if (strategy == FreezeStrategy.COMPONENT && target.components.isEmpty()) {
            // There is no component discovery in this version, so "none" is
            // not proven. Never claim the package has no components at all.
            return "未发现组件"
        }
        if (!rootAvailable &&
            (strategy == FreezeStrategy.COMPONENT ||
                strategy == FreezeStrategy.DISABLE_USER ||
                strategy == FreezeStrategy.HIDE)
        ) {
            return "需要 Root"
        }
        return null
    }

    fun unavailableReason(
        target: Target,
        preset: StrategyPreset,
        rootAvailable: Boolean = true
    ): String? {
        // Restrictions need the root marker backend for ownership/rollback;
        // the base strategy is OBSERVE, so the generic strategy overload alone
        // would incorrectly report them as available without root.
        if (!rootAvailable &&
            (preset == StrategyPreset.RESTRICT_BACKGROUND ||
                preset == StrategyPreset.RESTRICT_STANDBY)
        ) {
            return "需要 Root"
        }
        return unavailableReason(
            target,
            baseStrategy(preset),
            rootAvailable
        )
    }
}
