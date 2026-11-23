package com.nemu.hibernate.core

/**
 * Unified, feature-oriented target catalog for the Nemu UI.
 *
 * It includes the core [Targets] and [AiTargets] entries plus cloud, safety and
 * thermal targets, rendered as one catalog with per-target strategy metadata.
 *
 * Package additions are conservative: entries that have not been verified on
 * this device default to OBSERVE. Red/DenyList packages remain selectable for
 * every whole-package strategy but carry an explicit high-risk warning; the
 * platform may still refuse a command, which surfaces as a command failure.
 */
object TargetCatalog {
    private fun component(pkg: String, cls: String, label: String, role: String) =
        TargetComponent(
            className = "$pkg/$cls",
            label = label,
            role = role
        )

    val update: List<Target> = listOf(
        Targets.ouc,
        Target(
            packageName = "com.hihonor.systemappsupdater",
            label = "系统应用更新器",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.UPDATE,
            defaultStrategy = FreezeStrategy.SUSPEND,
            components = listOf(
                component(
                    "com.hihonor.systemappsupdater",
                    "com.hihonor.updater.third.remote.UpdaterService",
                    "UpdaterService",
                    "系统应用远程更新服务"
                )
            ),
            description = "通过应用市场更新系统应用；停用只影响系统应用更新。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "系统自带应用（相机、图库等）不会在后台自动更新；已安装版本继续可用。",
                FreezeStrategy.COMPONENT to
                    "只停用远程更新服务：自动更新停止，应用市场里手动更新可能仍可用。",
                FreezeStrategy.DISABLE_USER to
                    "更新器被停用：系统应用不再自动更新，设置中显示为“已停用”，可随时恢复。",
                FreezeStrategy.HIDE to
                    "更新器对系统隐藏，看起来像已卸载；自动更新停止，数据仍在，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动更新器，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.android.upgradeguide",
            label = "升级引导界面",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.UPDATE,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "只有升级说明界面（UpgradeWizardActivity），不是后台更新服务。" +
                "不用时可以直接停用或隐藏，不会影响系统更新检查。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "升级说明界面无法打开；系统更新检查不受影响。",
                FreezeStrategy.DISABLE_USER to
                    "升级引导被停用，设置中显示为已停用；不会卸载，可恢复。",
                FreezeStrategy.HIDE to
                    "升级引导从系统隐藏，像已卸载；不影响系统更新，可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        )
    )

    private val aiComponents: Map<String, List<TargetComponent>> = mapOf(
        "com.hihonor.aipluginengine" to listOf(
            component("com.hihonor.aipluginengine", "com.hihonor.aipluginengine.AIModel.AIModelService", "AIModelService", "端侧 AI 模型服务"),
            component("com.hihonor.aipluginengine", "com.hihonor.aipluginengine.llm.service.LLMAbilityProvider", "LLMAbilityProvider", "大模型能力 Provider"),
            component("com.hihonor.aipluginengine", "com.hihonor.aipluginengine.semanticencoding.update.ResourceUpdateProvider", "ResourceUpdateProvider", "模型/资源更新"),
            component("com.hihonor.aipluginengine", "com.hihonor.aipluginengine.llm.service.CloudCfgReceiver", "CloudCfgReceiver", "AI 云配置更新")
        ),
        "com.hihonor.assistant" to listOf(
            component("com.hihonor.assistant", "com.hihonor.assistant.mcp.service.YOYOAssistantMcpService", "YOYOAssistantMcpService", "YOYO MCP 工具服务"),
            component("com.hihonor.assistant", "com.hihonor.assistant.cardsortmgr.contentprovider.DisplayCardProvider", "DisplayCardProvider", "桌面卡片 Provider"),
            component("com.hihonor.assistant", "com.hihonor.assistant.recommend.provider.Recommend22WidgetProvider", "Recommend22WidgetProvider", "推荐卡片/Widget"),
            component("com.hihonor.assistant", "com.hihonor.assistant.service.BrainNotifyService", "BrainNotifyService", "Brain 通知服务"),
            component("com.hihonor.assistant", "com.hihonor.assistant.third.ThirdDisplayCardProvider", "ThirdDisplayCardProvider", "第三方卡片 Provider"),
            component("com.hihonor.assistant", "com.hihonor.assistant.receiver.OceanUpdateReceiver", "OceanUpdateReceiver", "YOYO 资源/配置更新广播")
        ),
        "com.hihonor.magicvoice" to listOf(
            component("com.hihonor.magicvoice", "com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService", "MagicVoiceInteractionService", "系统语音交互主服务（实测组件停用可阻断 system bind）"),
            component("com.hihonor.magicvoice", "com.hihonor.wakeup.service.WakeupService", "WakeupService", "语音唤醒服务"),
            component("com.hihonor.magicvoice", "com.hihonor.magicvoice.voiceui.service.HonorPushMsgService", "HonorPushMsgService", "语音助手推送服务")
        ),
        "com.hihonor.voiceengine" to listOf(
            // TtsService is intentionally excluded: third-party apps bind the
            // system TTS engine, and disabling it breaks accessibility/TTS.
            component("com.hihonor.voiceengine", "com.hihonor.voiceengine.awarenessfence.BrainFenceNotifyService", "BrainFenceNotifyService", "Brain 围栏联动")
        ),
        "com.hihonor.brain" to listOf(
            // BrainService is deliberately excluded: systemmanager binds it.
            component("com.hihonor.brain", "com.hihonor.brain.BrainMsgReceiverService", "BrainMsgReceiverService", "消息接收服务"),
            component("com.hihonor.brain", "com.hihonor.brain.UniCfgUpdateReceiver", "UniCfgUpdateReceiver", "统一云配置更新")
        ),
        "com.hihonor.suggestion" to listOf(
            component("com.hihonor.suggestion", "com.hihonor.decision.service.DecisionNotifyService", "DecisionNotifyService", "建议通知服务"),
            component("com.hihonor.suggestion", "com.hihonor.decision.service.DecisionReceiverService", "DecisionReceiverService", "建议决策接收")
        ),
        "com.hihonor.sceneservice" to listOf(
            component("com.hihonor.sceneservice", "com.hihonor.sceneservice.business.trigger.push.PushMessageService", "PushMessageService", "场景推送服务"),
            component("com.hihonor.sceneservice", "com.hihonor.sceneservice.core.infrastructure.awareness.AwarenessStartReceiver", "AwarenessStartReceiver", "情景感知启动"),
            component("com.hihonor.sceneservice", "com.hihonor.sceneservice.core.infrastructure.trigger.AwarenessReceiver", "AwarenessReceiver", "情景触发接收")
        )
    )
    private val coreAi: List<Target> = Targets.all
        .filter { it.packageName != Targets.ouc.packageName }
        .map {
            it.copy(
                category = TargetCategory.AI,
                components = aiComponents[it.packageName].orEmpty()
            )
        }

    val ai: List<Target> = coreAi + AiTargets.all.map {
        it.copy(
            category = TargetCategory.AI,
            components = aiComponents[it.packageName].orEmpty(),
        )
    } + listOf(
        Target(
            packageName = "com.hihonor.visionengine",
            label = "视觉引擎（相机/图库 AI）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.AI,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component(
                    "com.hihonor.visionengine",
                    "com.hihonor.vision.awareness.BrainFenceNotifyService",
                    "BrainFenceNotifyService",
                    "视觉与 Brain 联动服务"
                ),
                component(
                    "com.hihonor.visionengine",
                    "com.hihonor.dataupdate.receiver.DataUpdateReceiver",
                    "DataUpdateReceiver",
                    "视觉模型/资源云更新"
                )
            ),
            description = "相机/图库的 AI 识别与模型更新。停用组件不会删照片，" +
                "只影响 AI 识别/资源更新。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "相机/图库的 AI 识别联动和模型自动更新停止；拍照、相册浏览不受影响。",
                FreezeStrategy.SUSPEND to
                    "视觉引擎整体暂停：依赖它的识图、场景识别会暂时不可用，解冻后恢复。",
                FreezeStrategy.DISABLE_USER to
                    "视觉引擎被停用，相机中的 AI 功能可能报错或降级；数据保留，可恢复。",
                FreezeStrategy.HIDE to
                    "视觉引擎从系统隐藏，像已卸载；AI 识图/场景识别不可用，可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.magictranslate",
            label = "智慧翻译（候选）",
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI,
            defaultStrategy = FreezeStrategy.SUSPEND,
            description = "独立的翻译功能。不使用翻译时可冻结，不影响系统语言与输入法。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to "智慧翻译无法使用；其他应用和输入法不受影响。",
                FreezeStrategy.DISABLE_USER to "翻译应用被停用，桌面入口消失；数据保留，可恢复。",
                FreezeStrategy.HIDE to "翻译应用从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.magicavatar",
            label = "魔法形象",
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI,
            defaultStrategy = FreezeStrategy.SUSPEND,
            description = "独立的头像/形象功能。不用时可冻结。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to "魔法形象无法使用；不影响相机基础拍照。",
                FreezeStrategy.DISABLE_USER to "形象应用被停用，入口消失；数据保留，可恢复。",
                FreezeStrategy.HIDE to "形象应用从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.lens",
            label = "智慧视觉（HonorLens）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.AI,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component(
                    "com.hihonor.lens",
                    "com.hihonor.aolens.service.HonorLensService",
                    "HonorLensService",
                    "智慧视觉核心服务"
                ),
                component(
                    "com.hihonor.lens",
                    "com.hihonor.aolens.service.HonorLensRouterService",
                    "HonorLensRouterService",
                    "智慧视觉路由服务"
                )
            ),
            description = "相机里的识屏/识物入口。不用时可选暂停、停用或隐藏；" +
                "默认只观察，避免误伤相机。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "只停用识屏服务：相机仍能拍照，但识物/识屏入口可能无响应。",
                FreezeStrategy.SUSPEND to
                    "智慧视觉暂停：相机里的识屏/识物暂时不可用，解冻后恢复。",
                FreezeStrategy.DISABLE_USER to
                    "智慧视觉被停用，相机相关入口可能消失；数据保留，可恢复。",
                FreezeStrategy.HIDE to
                    "智慧视觉从系统隐藏，像已卸载；相机基础功能不受影响，可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.magichome",
            label = "荣耀 AI 空间（Magichome）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.AI,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "AI 空间入口界面，与桌面/负一屏有入口联动。" +
                "不用 AI 空间时可暂停、停用或隐藏。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "AI 空间入口暂停：点入口可能无响应，桌面其他功能不受影响。",
                FreezeStrategy.DISABLE_USER to
                    "AI 空间被停用，入口从桌面消失；数据保留，可恢复。",
                FreezeStrategy.HIDE to
                    "AI 空间从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.hiboard",
            label = "负一屏 / HiBoard（候选）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.AI,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component(
                    "com.hihonor.hiboard",
                    "com.hihonor.hiboard.cardstack.service.HiBoardNotifyService",
                    "HiBoardNotifyService",
                    "负一屏通知服务"
                ),
                component(
                    "com.hihonor.hiboard",
                    "com.hihonor.intelligent.feature.push.presentation.service.PushMessageService",
                    "PushMessageService",
                    "负一屏推送服务"
                ),
                component(
                    "com.hihonor.hiboard",
                    "com.hihonor.intelligent.remoteservice.LauncherOverlayService",
                    "LauncherOverlayService",
                    "桌面负一屏 Overlay 服务"
                ),
                component(
                    "com.hihonor.hiboard",
                    "com.hihonor.contentcard.ticket.data.BrainNotifyService",
                    "BrainNotifyService",
                    "负一屏 Brain 通知服务"
                )
            ),
            description = "桌面左滑的负一屏卡片/推送。不用负一屏时可暂停、停用或隐藏；" +
                "停用后负一屏可能空白。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "负一屏的通知、推送、卡片服务停用：左滑可能仍在，但卡片不更新或空白。",
                FreezeStrategy.SUSPEND to
                    "负一屏暂停：左滑页面可能无响应或空白，桌面和应用不受影响。",
                FreezeStrategy.DISABLE_USER to
                    "负一屏被停用，左滑入口可能消失；数据保留，可恢复。",
                FreezeStrategy.HIDE to
                    "负一屏从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        )
    )

    val cloud: List<Target> = listOf(
        Target(
            packageName = "com.hihonor.regservice",
            label = "运营商 DM（系统消息）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.DISABLE_USER,
            description = "CMCC DM：自注册、心跳、FOTA、应用清单上报；disable-user 可逆。",
            consequences = mapOf(
                FreezeStrategy.DISABLE_USER to
                    "运营商设备管理被停用：可能影响运营商 FOTA、自注册短信/VoLTE 配置下发；" +
                        "不会卸载，可在解冻页恢复。",
                FreezeStrategy.SUSPEND to
                    "运营商设备管理暂停：心跳/上报停止，配置下发可能延迟；解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.android.UEInfoCheck",
            label = "UEInfoCheck（电信上报）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.DISABLE_USER,
            description = "电信 UE 信息检查/上报；disable-user 可逆。",
            consequences = mapOf(
                FreezeStrategy.DISABLE_USER to
                    "电信 UE 信息上报停止：可能影响运营商侧的网络配置/统计；可恢复。",
                FreezeStrategy.SUSPEND to "UE 信息检查暂停，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.android.AutoRegSms",
            label = "AutoRegSms（电信自注册短信）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.DISABLE_USER,
            description = "电信自注册短信；停用后可能收不到运营商自注册短信。",
            consequences = mapOf(
                FreezeStrategy.DISABLE_USER to
                    "运营商自注册短信处理停止：首次插卡/换卡时可能收不到自动注册短信；可恢复。",
                FreezeStrategy.SUSPEND to "自注册短信处理暂停，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.hiview",
            label = "HiView 遥测采集",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            // The platform refuses `pm suspend` for hiview on this device
            // (reports "new suspended state: false"). COMPONENT is the
            // actionable default for a root user; SUSPEND remains selectable
            // for devices where it works.
            defaultStrategy = FreezeStrategy.COMPONENT,
            components = listOf(
                component(
                    "com.hihonor.hiview",
                    "com.hihonor.ncdft.NcDftService",
                    "NcDftService",
                    "诊断服务；停用组件不等于 persistent 进程退出"
                ),
                component(
                    "com.hihonor.hiview",
                    "com.hihonor.configupdate.CloudConfigUpdateReceiver",
                    "CloudConfigUpdateReceiver",
                    "云端配置更新接收器"
                ),
                component(
                    "com.hihonor.hiview",
                    "com.hihonor.lcagent.service.LogCollectService",
                    "LogCollectService",
                    "日志采集服务"
                ),
                component(
                    "com.hihonor.hiview",
                    "com.hihonor.mcs.system.diagnosis.service.DiagKitService",
                    "DiagKitService",
                    "诊断服务"
                )
            ),
            description = "遥测/诊断；组件停用可阻断部分启动绑定，但共享 system UID 进程可能仍存活。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "诊断、日志采集和云配置组件停用：荣耀的遥测/诊断数据采集大幅减少；" +
                        "系统更新、保修检测可能缺少部分日志，不影响日常使用。",
                FreezeStrategy.SUSPEND to
                    "本机实测系统会拒绝暂停 HiView（返回未暂停）；" +
                        "即使成功，共享 system UID 的进程也可能继续运行。",
                FreezeStrategy.DISABLE_USER to
                    "HiView 整包停用：遥测/诊断采集停止，系统“服务与反馈”可能提示异常；可恢复。",
                FreezeStrategy.HIDE to
                    "HiView 从系统隐藏，像已卸载；遥测停止，可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.hiviewpush",
            label = "HiView 推送",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.COMPONENT,
            components = listOf(
                component("com.hihonor.hiviewpush", "com.hihonor.hiviewpush.PushManagerService", "PushManagerService", "推送管理服务"),
                component("com.hihonor.hiviewpush", "com.hihonor.hiviewpush.PushMsgReceiverService", "PushMsgReceiverService", "推送消息接收服务"),
                component("com.hihonor.hiviewpush", "com.hihonor.push.sdk.PushReceiver", "PushReceiver", "推送 SDK 接收器")
            ),
            description = "遥测推送通道。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to "遥测推送组件停用：诊断消息不再推送；不影响微信/QQ 等普通推送。",
                FreezeStrategy.DISABLE_USER to "HiView 推送整包停用：遥测推送停止；可恢复。",
                FreezeStrategy.HIDE to "HiView 推送从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.hiviewtunnel",
            label = "HiViewTunnel",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.COMPONENT,
            components = listOf(
                component("com.hihonor.hiviewtunnel", "com.hihonor.idaptunnel.LogUploadService", "LogUploadService", "日志上传服务"),
                component("com.hihonor.hiviewtunnel", "com.hihonor.updateconfig.UpdateConfigService", "UpdateConfigService", "上传配置更新服务"),
                component("com.hihonor.hiviewtunnel", "com.hihonor.userexperience.provider.UserExperienceProvider", "UserExperienceProvider", "用户体验计划 Provider")
            ),
            description = "遥测上传通道。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to "日志上传组件停用：体验计划/日志不再上传；不影响系统使用。",
                FreezeStrategy.DISABLE_USER to "HiViewTunnel 整包停用：日志上传停止；可恢复。",
                FreezeStrategy.HIDE to "HiViewTunnel 从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.android.chr",
            label = "CHR 上报（共享 UID）",
            risk = RiskLevel.RED,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component(
                    "com.hihonor.android.chr",
                    "com.hihonor.android.chr.ChrCloudParaUpdateReceiver",
                    "ChrCloudParaUpdateReceiver",
                    "CHR 云参数更新"
                ),
                component(
                    "com.hihonor.android.chr",
                    "com.hihonor.android.chr.ChrCommonReceiver",
                    "ChrCommonReceiver",
                    "CHR 通用广播"
                ),
                component(
                    "com.hihonor.android.chr",
                    "com.hihonor.mdlog.MdlogBootReceiver",
                    "MdlogBootReceiver",
                    "MDLOG 开机广播"
                ),
                component(
                    "com.hihonor.android.chr",
                    "com.hihonor.mdlog.MdlogReceiver",
                    "MdlogReceiver",
                    "MDLOG 事件接收"
                )
            ),
            description = "与 hiview 共享 system UID；默认只观察，组件可阻断 CHR/MDLOG 上报。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "CHR/MDLOG 上报组件停用：崩溃日志/诊断上报减少；" +
                        "共享 system UID 的主进程仍可能运行，不影响日常使用。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.detectrepair",
            label = "智能检测 / 远程诊断",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component(
                    "com.hihonor.detectrepair",
                    "com.hihonor.detectrepair.configmanager.cloudConfig.CloudUpdateReceiver",
                    "CloudUpdateReceiver",
                    "远程诊断云配置更新"
                )
            ),
            description = "智能检测/远程维护；默认只观察，组件模式可阻断云配置。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "远程诊断的云配置更新停止：手机仍可本地检测，远程协助可能不可用。",
                FreezeStrategy.SUSPEND to
                    "智能检测暂停：设置里的检测/远程协助入口可能无响应；解冻后恢复。",
                FreezeStrategy.DISABLE_USER to
                    "智能检测被停用：远程诊断/检测功能不可用；数据保留，可恢复。",
                FreezeStrategy.HIDE to
                    "智能检测从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.assetsync",
            label = "COTA 参数同步调度",
            risk = RiskLevel.RED,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "社区资料指向 /data/cota/para 的参数同步。暂停后云控参数不再更新，" +
                "现有温控/功耗参数继续生效；默认只观察。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "云控参数同步暂停：厂商无法推送新的温控/功耗参数；" +
                        "现有参数仍生效，解冻后恢复同步。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.assetsyncservice",
            label = "COTA 参数同步服务",
            risk = RiskLevel.RED,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "COTA 参数同步服务。暂停后温控/反诈等云参数不再更新，" +
                "现有配置继续生效；默认只观察。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "COTA 参数同步暂停：温控/反诈/功耗参数不再接收云端更新；" +
                        "目前配置继续生效，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.cipservice",
            label = "HnCIPService（疑似上报）",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "疑似 CHR/诊断上报服务，具体角色未完全确认。可以暂停或停用；" +
                "如果遇到系统诊断/反馈异常，解冻即可。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "HnCIP 暂停：疑似诊断上报停止；角色未确认，若某个系统功能异常请解冻。",
                FreezeStrategy.DISABLE_USER to
                    "HnCIP 整包停用：上报停止，设置中显示已停用；可恢复。",
                FreezeStrategy.HIDE to
                    "HnCIP 从系统隐藏，像已卸载；可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.systemserver",
            label = "HONOR 系统服务（云参数组件）",
            risk = RiskLevel.RED,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component("com.hihonor.systemserver", "com.hihonor.systemserver.booster.framework.config.CommonCloudParaUpdateReceiver", "CommonCloudParaUpdateReceiver", "通用云参数更新"),
                component("com.hihonor.systemserver", "com.hihonor.systemserver.booster.framework.config.PowerCloudParaUpdateReceiver", "PowerCloudParaUpdateReceiver", "功耗云参数更新"),
                component("com.hihonor.systemserver", "com.hihonor.systemserver.appmcp.AppMcpCfgUpdatedReceiver", "AppMcpCfgUpdatedReceiver", "App MCP 云配置更新")
            ),
            description = "系统服务承载插件/运营商/热补丁；整包策略可用但高风险，建议用组件模式阻断云参数。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "通用/功耗/App MCP 云参数更新组件停用：厂商云控参数不再更新；" +
                        "现有系统服务继续运行。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.android.pushagent",
            label = "荣耀推送（高风险）",
            risk = RiskLevel.RED,
            category = TargetCategory.CLOUD,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "系统推送通道（敏感应用）。整包策略可用但会警告高风险；冻结可能导致通知/推送异常。"
        )
    )

    val safety: List<Target> = listOf(
        Target(
            packageName = "com.hihonor.systemmanager",
            label = "系统管家（反诈组件）",
            risk = RiskLevel.RED,
            category = TargetCategory.SAFETY,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component("com.hihonor.systemmanager", "com.hihonor.aiprotection.configmanager.receiver.AntiFraudConfigReceiver", "AntiFraudConfigReceiver", "反诈云配置更新"),
                component("com.hihonor.systemmanager", "com.hihonor.fraudnumber.provider.FraudContentProvider", "FraudContentProvider", "反诈号码 Provider"),
                component("com.hihonor.systemmanager", "com.hihonor.securitycenter.antifraud.fakedetect.FakeDetectService", "FakeDetectService", "伪造检测服务")
            ),
            description = "系统管家承载反诈/病毒/电池；整包策略可用但高风险，建议用组件模式。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "反诈云配置、反诈号码查询、伪造检测组件停用：骚扰/诈骗号码标记可能减少；" +
                        "病毒扫描和电池管理继续运行。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.numberidentity",
            label = "号码识别",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.SAFETY,
            defaultStrategy = FreezeStrategy.SUSPEND,
            description = "来电识别、骚扰/诈骗标记。冻结后仍能接打电话，但看不到号码标记和识别结果。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "号码识别暂停：来电/短信不再显示骚扰、诈骗标记；接打电话不受影响，解冻后恢复。",
                FreezeStrategy.DISABLE_USER to
                    "号码识别被停用：来电识别和诈骗标记消失；不会卸载，可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.securityserver",
            label = "安全公共服务（高风险）",
            risk = RiskLevel.RED,
            category = TargetCategory.SAFETY,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "反诈核心服务，负责安全能力/活体/风险判断。" +
                "直接冻结可能导致支付保护、反诈功能异常；可以暂停，但默认只观察。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "安全公共服务暂停：支付保护、风险网址/应用判断、部分反诈校验可能失灵；" +
                        "接打电话和普通应用不受影响，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.awareness",
            label = "情景感知",
            risk = RiskLevel.RED,
            category = TargetCategory.SAFETY,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "反诈/活体/情景判断的载体。暂停可能影响安全校验和情景功能；" +
                "默认只观察。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "情景感知暂停：活体检测、情景判断、部分反诈联动可能异常；" +
                        "基础传感器和日常应用通常不受影响，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.securitypluginbase",
            label = "安全插件底座",
            risk = RiskLevel.YELLOW,
            category = TargetCategory.SAFETY,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "安全插件的基础支撑包，平时没有界面。可以暂停；" +
                "如果停用后遇到安全/支付校验异常，请立即解冻。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "安全插件底座暂停：依赖它的安全插件可能加载失败；遇到支付/安全校验异常请解冻。",
                FreezeStrategy.DISABLE_USER to
                    "安全插件底座停用：相关安全插件不可用；可恢复，但停用期间可能影响安全校验。",
                FreezeStrategy.HIDE to
                    "安全插件底座从系统隐藏，像已卸载；可能影响安全校验，可恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        )
    )

    val thermal: List<Target> = listOf(
        Target(
            packageName = "com.hihonor.android.hnaps",
            label = "HnAps 画面自适应",
            risk = RiskLevel.RED,
            category = TargetCategory.THERMAL,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "负责屏幕刷新率/画面自适应的常驻服务。暂停可能让刷新率卡住或画面异常，" +
                "所以默认只观察；只有你明确知道风险时才暂停。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "画面自适应暂停：刷新率可能固定在当前档位，极端情况下出现卡顿/黑屏；" +
                        "重启或解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.powergenie",
            label = "PowerGenie 温控/功耗（系统保护）",
            risk = RiskLevel.RED,
            category = TargetCategory.THERMAL,
            defaultStrategy = FreezeStrategy.OBSERVE,
            // Device test: `pm disable` on powergenie components returns
            // "new state: default" (HONOR protects the package), so do not
            // offer a component button that cannot work. iaware's equivalent
            // receiver is disable-able and remains COMPONENT-capable.
            components = listOf(
                component(
                    "com.hihonor.powergenie",
                    "com.hihonor.powergenie.integration.eventhub.bcreceiver.CpuCfgCotaUpdateReceiver",
                    "CpuCfgCotaUpdateReceiver",
                    "CPU 云控参数更新；仅建议阻断此接收器"
                ),
                component(
                    "com.hihonor.powergenie",
                    "com.hihonor.powergenie.integration.eventhub.bcreceiver.PgConfigCotaUpdateReceiver",
                    "PgConfigCotaUpdateReceiver",
                    "PowerGenie 云控参数更新"
                ),
                component(
                    "com.hihonor.powergenie",
                    "com.hihonor.powergenie.integration.eventhub.bcreceiver.ThermalCfgCotaUpdateReceiver",
                    "ThermalCfgCotaUpdateReceiver",
                    "温控云控参数更新"
                )
            ),
            description = "负责 CPU/GPU 调频、温控和充电策略。整包冻结会导致系统大面积绑定失败、" +
                "可能过热或无法充电；而且荣耀在系统层保护它，实测组件停用命令会被拒绝。" +
                "整包策略已开放但高风险；组件命令实测被平台拒绝，默认观察。",
            consequences = mapOf(
                FreezeStrategy.OBSERVE to
                    "只显示状态。Nemu 不会对 PowerGenie 下发任何命令；" +
                        "这是有意设计，避免温控/充电异常。"
            )
        ),
        Target(
            packageName = "com.hihonor.iaware",
            label = "iAware 云配置组件",
            risk = RiskLevel.RED,
            category = TargetCategory.THERMAL,
            defaultStrategy = FreezeStrategy.OBSERVE,
            components = listOf(
                component("com.hihonor.iaware", "com.hihonor.iaware.CloudConfigUpdateReceiver", "CloudConfigUpdateReceiver", "iAware 云配置更新"),
                component("com.hihonor.iaware", "com.hihonor.iaware.CloudPushContentProvider", "CloudPushContentProvider", "云推送配置 Provider")
            ),
            description = "负责 CPU/GPU 调度，与 PowerGenie 共享进程。整包冻结会拖垮温控，" +
                "建议只停用两个“云配置更新”组件；整包策略可用但高风险，日常调度继续运行。",
            consequences = mapOf(
                FreezeStrategy.COMPONENT to
                    "iAware 不再接收云端下发的调度参数，现有调度策略继续运行；" +
                        "不会停止 iAware 主进程，也不会改温控阈值。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        ),
        Target(
            packageName = "com.hihonor.msdp",
            label = "MSDP 传感平台",
            risk = RiskLevel.RED,
            category = TargetCategory.THERMAL,
            defaultStrategy = FreezeStrategy.OBSERVE,
            description = "融合传感器数据的系统平台（抬腕、姿态等）。暂停可能影响依赖传感器的功能，" +
                "默认只观察；可暂停，解冻后恢复。",
            consequences = mapOf(
                FreezeStrategy.SUSPEND to
                    "传感器融合暂停：抬腕亮屏、姿态/运动识别等功能可能失灵；" +
                        "自动旋转等基础传感器通常仍可用，解冻后恢复。",
                FreezeStrategy.OBSERVE to "不改动，只显示状态。"
            )
        )
    )

    private val builtIn: List<Target> = update + ai + cloud + safety + thermal

    @Volatile
    private var customTargets: List<Target> = emptyList()

    val all: List<Target>
        get() = (builtIn + customTargets).distinctBy { it.packageName }

    /** Rebuilds the user-added portion; called at process start and on edits. */
    fun setCustomTargets(targets: List<Target>) {
        val builtInPackages = builtIn.mapTo(HashSet()) { it.packageName }
        customTargets = targets
            .filter { it.packageName !in builtInPackages }
            .distinctBy { it.packageName }
    }

    /** Any package the user adds from the custom picker. */
    fun customTarget(
        packageName: String,
        label: String,
        risk: RiskLevel = RiskLevel.YELLOW
    ): Target = Target(
        packageName = packageName,
        label = label.ifBlank { packageName },
        // Old records default to YELLOW; recovery-critical packages must still
        // surface the high-risk warning.
        risk = if (risk != RiskLevel.RED &&
            CustomTargetGuard.isCriticalPackage(packageName)
        ) {
            RiskLevel.RED
        } else {
            risk
        },
        category = TargetCategory.AUTOMATION,
        defaultStrategy = FreezeStrategy.SUSPEND,
        description = "自定义应用。建议优先用“暂停”；停用/隐藏会让它从桌面消失，" +
            "解冻后恢复。",
        consequences = mapOf(
            FreezeStrategy.SUSPEND to
                "这个应用在解冻前无法启动、无法后台运行；数据保留。",
            FreezeStrategy.DISABLE_USER to
                "这个应用会被停用，桌面图标消失，设置里显示“已停用”；数据保留，可恢复。",
            FreezeStrategy.HIDE to
                "这个应用会从系统隐藏，看起来像已经卸载，无法打开；数据和 APK 仍在，" +
                    "解冻后恢复。",
            FreezeStrategy.OBSERVE to "不改动，只显示状态。"
        )
    )

    fun byPackage(packageName: String): Target? =
        all.firstOrNull { it.packageName == packageName }

    fun byCategory(category: TargetCategory): List<Target> =
        all.filter { it.category == category }
}
