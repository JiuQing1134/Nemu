# 18 Nemu 目标能力矩阵（2026-11-22）

> 配套文档：`docs/17-redesign-proposal-2026-11-22.md`。
> 证据口径：`PF` 编号来自 `docs/12-device-test-log-2026-11-22.md`（1.1/1.2 构建）；
> 1.3.3 于 2026-11-23 完成启动冒烟；冻结/停用/隐藏仍未逐一复测，没有设备记录的标“未测”，不得写成已验证。
> 结构：41 个内置目标、64 个登记组件（powergenie 3 个平台拒绝，61 个可进选择器）。
>
> **2026-11-22 真机反馈更新**：DenyList 不再硬性禁止整包暂停/停用/隐藏；
> 矩阵中因 DenyList 或红色风险标 ✕ 的格子一律改为“可用 + 高风险提醒”。
> 整包暂停/停用/隐藏对所有已安装目标开放；仅保留平台拒绝、未登记组件与
> Root 缺失这些真实限制。`supportedStrategies` / `denyExceptions` 等旧能力字段已从代码与目录删除。
> 本表其余证据/未测口径不变。

## 图例

| 符号 | 含义 |
|---|---|
| ✓ | 可用（有实测或完整代码路径） |
| ○ | 可用，但本机未测 |
| R | 可用，但需要 Root；未测 |
| ✕ | 不可用（原因见备注） |
| — | 无该维度（0 个登记组件，或该策略不适用） |
| 观 | 观察（始终可用，不下发命令） |

列：`观` 观察、`暂` 暂停（仅 `pm suspend`）、`冻` 冻结（`pm suspend` + `am force-stop`）、
`组` 组件（`pm disable` 所选组件）、`停` 停用（`pm disable-user`）、
`隐` 隐藏（`pm hide`，伪卸载）、`后` 限制后台（AppOps）、`待` 限制待机（standby）。

**注意**：PF 记录中的“暂停”实际是 `pm suspend + am force-stop`，因此计入 `冻`；
纯 `暂`（不结束进程）在本机全部未测。

---

## 1. UPDATE（3）

| # | 目标 | 险 | 软件功能 | 观 | 暂 | 冻 | 组 | 停 | 隐 | 后 | 待 | 证据 / 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `com.hihonor.ouc` 软件更新 | 黄 | 系统更新检查、下载与安装 | ✓ | ○ | ✓ | ○ | ✕ | ✕ | — | — | 冻 PF1-4/15；组件 8 个未测；停/隐不在支持集 |
| 2 | `com.hihonor.systemappsupdater` 系统应用更新器 | 黄 | 更新系统自带应用 | ✓ | ○ | ✓ | ○ | R | R | — | — | 冻 PF13；组件 1 个未测 |
| 3 | `com.hihonor.android.upgradeguide` 升级引导界面 | 黄 | 系统升级说明界面 | ✓ | ○ | ○ | — | R | ✓ | — | — | 隐 PF20/21；只有 Activity，组件维度无意义 |

## 2. AI（16）

| # | 目标 | 险 | 软件功能 | 观 | 暂 | 冻 | 组 | 停 | 隐 | 后 | 待 | 证据 / 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 4 | `com.honor.yoyoappsug` App 建议 | 绿 | 桌面应用使用建议 | ✓ | ○ | ✓ | — | R | R | — | — | 冻 PF16；无组件 |
| 5 | `com.honor.yoyocards` YOYO 建议卡片集 | 绿 | YOYO 建议卡片 | ✓ | ○ | ✓ | — | R | R | — | — | 冻 PF16；无组件 |
| 6 | `com.hihonor.suggestion` 智能提醒 | 绿 | 场景提醒与建议通知 | ✓ | ○ | ✓ | ○ | R | R | — | — | 冻 PF16；组件 2 个未测 |
| 7 | `com.hihonor.sceneservice` 智慧空间基础服务 | 黄 | 智慧空间场景联动 | ✓ | ○ | ✓ | ○ | R | R | — | — | 冻 PF16；组件 3 个未测 |
| 8 | `com.hihonor.aipluginengine` 荣耀 AI·基础服务 | 红 | 端侧 AI 模型与能力 | ✓ | ○ | ✓ | ○ | ✕ | ✕ | — | — | 冻 PF16；组件 4 个未测；红色包禁止整包停/隐 |
| 9 | `com.hihonor.assistant` YOYO 建议基础服务 | 红 | YOYO 卡片与建议服务 | ✓ | ○ | ✓ | ○ | ✕ | ✕ | — | — | 冻 PF16；组件 6 个未测 |
| 10 | `com.hihonor.magicvoice` 智慧语音 | 红 | 语音唤醒与交互 | ✓ | ○ | ✓ | ○ | ✕ | ✕ | — | — | 冻 PF16；组件 3 个，短时实测；跨重启未测 |
| 11 | `com.hihonor.voiceengine` 语音服务 | 黄 | 语音合成与唤醒联动 | ✓ | ○ | ✓ | ○ | R | R | — | — | 冻 PF16；组件 1 个；TtsService 不得登记；停/隐未测 |
| 12 | `com.hihonor.brain` 智慧决策 | 红 | 端侧决策与云配置 | ✓ | ○ | ✓ | ○ | ✕ | ✕ | — | — | 冻 PF16；组件 2 个；BrainService 不得登记 |
| 13 | `com.hihonor.mageruntime` MageEngineRuntime | 黄 | AI 引擎运行时 | ✓ | ○ | ✓ | — | R | R | — | — | 冻 PF16；无组件；停/隐未测 |
| 14 | `com.hihonor.visionengine` 视觉引擎 | 黄 | 相机图库 AI 识别 | ✓ | ○ | ○ | ○ | R | R | — | — | 仅观察到 suspended；组件 2 个未测 |
| 15 | `com.hihonor.magictranslate` 智慧翻译 | 绿 | 翻译文本与对话 | ✓ | ○ | ○ | — | R | R | — | — | 未测；无组件 |
| 16 | `com.hihonor.magicavatar` 魔法形象 | 绿 | 生成头像与形象 | ✓ | ○ | ○ | — | R | R | — | — | 未测；无组件 |
| 17 | `com.hihonor.lens` 智慧视觉 | 黄 | 相机识屏识物 | ✓ | ○ | ○ | ○ | R | R | — | — | 未测；组件 2 个 |
| 18 | `com.hihonor.magichome` 荣耀 AI 空间 | 黄 | AI 功能入口 | ✓ | ○ | ○ | — | R | R | — | — | 未测；无组件 |
| 19 | `com.hihonor.hiboard` 负一屏 | 黄 | 桌面负一屏卡片 | ✓ | ○ | ○ | ○ | R | R | — | — | 未测；组件 4 个 |

## 3. CLOUD（13）

| # | 目标 | 险 | 软件功能 | 观 | 暂 | 冻 | 组 | 停 | 隐 | 后 | 待 | 证据 / 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 20 | `com.hihonor.regservice` 运营商 DM | 黄 | 运营商配置与自注册 | ✓ | ○ | ○ | — | R | ✕ | — | — | 基线原本停用；enable 往返未测；隐藏不在支持集 |
| 21 | `com.hihonor.android.UEInfoCheck` 电信上报 | 黄 | 电信信息检查上报 | ✓ | ○ | ○ | — | R | ✕ | — | — | 同上 |
| 22 | `com.hihonor.android.AutoRegSms` 自注册短信 | 黄 | 运营商自注册短信 | ✓ | ○ | ○ | — | R | ✕ | — | — | 同上 |
| 23 | `com.hihonor.hiview` HiView 遥测采集 | 黄 | 系统诊断与遥测 | ✓ | ✕ | ✕ | ✓ | R | R | — | — | 暂停被平台拒绝 PF9；组件 PF11/16 |
| 24 | `com.hihonor.hiviewpush` HiView 推送 | 黄 | 遥测推送通道 | ✓ | ✕ | ✕ | ✓ | R | R | — | — | 同上；组件 3 个 |
| 25 | `com.hihonor.hiviewtunnel` HiViewTunnel | 黄 | 日志上传通道 | ✓ | ✕ | ✕ | ✓ | R | R | — | — | 同上；组件 3 个 |
| 26 | `com.hihonor.android.chr` CHR 上报 | 红 | 崩溃与诊断上报 | ✓ | ○ | ○ | ○ | R | R | — | — | 共享 system UID；整包可选但高风险；组件 4 个未测 |
| 27 | `com.hihonor.detectrepair` 智能检测 | 黄 | 本地检测与远程诊断 | ✓ | ○ | ○ | ○ | R | R | — | — | 未测；组件 1 个 |
| 28 | `com.hihonor.assetsync` COTA 同步调度 | 红 | 同步厂商云控参数 | ✓ | ○ | ○ | — | R | R | — | — | 未测；整包可选但高风险 |
| 29 | `com.hihonor.assetsyncservice` COTA 同步服务 | 红 | COTA 参数同步 | ✓ | ○ | ○ | — | R | R | — | — | 同上 |
| 30 | `com.hihonor.cipservice` HnCIPService | 黄 | 疑似诊断上报 | ✓ | ○ | ○ | — | R | R | — | — | 角色未确认；整包可选，默认观察 |
| 31 | `com.hihonor.systemserver` HONOR 系统服务 | 红 | 系统插件与云参数 | ✓ | ○ | ○ | ○ | R | R | — | — | 整包可选但高风险；组件 3 个未测 |
| 32 | `com.hihonor.android.pushagent` 荣耀推送 | 红 | 系统通知推送通道 | ✓ | ○ | ○ | — | R | R | — | — | 高风险提醒；无登记组件，整包可选 |

## 4. SAFETY（5）

| # | 目标 | 险 | 软件功能 | 观 | 暂 | 冻 | 组 | 停 | 隐 | 后 | 待 | 证据 / 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 33 | `com.hihonor.systemmanager` 系统管家 | 红 | 系统管家与反诈 | ✓ | ○ | ○ | ○ | R | R | — | — | 整包可选但高风险；组件 3 个未测 |
| 34 | `com.hihonor.numberidentity` 号码识别 | 黄 | 来电识别与诈骗标记 | ✓ | ○ | ○ | — | ✓ | R | — | — | 停用 PF12；暂停未测；隐藏可选、未测 |
| 35 | `com.hihonor.securityserver` 安全公共服务 | 红 | 支付与风险校验 | ✓ | ○ | ○ | — | R | R | ○ | ○ | 反诈核心；整包可选但高风险；未测 |
| 36 | `com.hihonor.awareness` 情景感知 | 红 | 活体与情景判断 | ✓ | ○ | ○ | — | R | R | ○ | ○ | 同上 |
| 37 | `com.hihonor.securitypluginbase` 安全插件底座 | 黄 | 安全插件公共支撑 | ✓ | ○ | ○ | — | R | R | — | — | 高风险提醒；整包可选，未测 |

## 5. THERMAL（4）

| # | 目标 | 险 | 软件功能 | 观 | 暂 | 冻 | 组 | 停 | 隐 | 后 | 待 | 证据 / 备注 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 38 | `com.hihonor.android.hnaps` HnAps | 红 | 刷新率画面自适应 | ✓ | ○ | ○ | — | R | R | — | — | 未测；整包可选但高风险 |
| 39 | `com.hihonor.powergenie` PowerGenie | 红 | 调频温控与充电 | ✓ | ○ | ○ | ✕ | R | R | — | — | 平台拒绝组件停用 PF14；整包可选但高风险 |
| 40 | `com.hihonor.iaware` iAware | 红 | CPU/GPU 调度与云参数 | ✓ | ○ | ○ | ✓ | R | R | — | — | 两个云配置组件 PF14；整包可选但高风险 |
| 41 | `com.hihonor.msdp` MSDP 传感平台 | 红 | 融合传感器数据 | ✓ | ○ | ○ | — | R | R | — | — | 未测；整包可选，传感器融合风险 |

## 6. 自定义目标

| 目标 | 观 | 暂 | 冻 | 组 | 停 | 隐 | 后 | 待 | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| 用户添加的应用 | ✓ | ✓ | ✓ | — | R | R | — | — | 无组件；仅 Nemu 自身不可添加，关键应用警告后可添加 |

---

## 7. 组件清单（64）

> `R` 表示平台拒绝或不应操作；其余进入选择器。组件的“停用效果”除标注 PF 外均未逐一实测。
> **组件风险当前未分级**：`TargetComponent.risk` 全部默认 YELLOW；补 RED/YELLOW 前
> UI 统一显示“未分级”，不得在中/高风险文案里假装已分级。

### 7.1 UPDATE

| 目标 | 组件 | 角色 | 状态 |
|---|---|---|---|
| OUC | DownloadReceiver | 下载/安装广播 | 可进选择器，未测 |
| OUC | CotaReceiver | COTA 广播 | 可进选择器，未测 |
| OUC | CotaDownloadReceiver | COTA 下载广播 | 可进选择器，未测 |
| OUC | CheckNewVersionReceiver | 版本检查广播 | 可进选择器，未测 |
| OUC | FirmwareAutoDownloadReceiver | 固件自动下载 | 可进选择器，未测 |
| OUC | FirmwareInstallReceiver | 固件安装广播 | 可进选择器，未测 |
| OUC | NightUpgradeReceiver | 夜间自动升级 | 可进选择器，未测 |
| OUC | SelfUpdateReceiver | OUC 自更新 | 可进选择器，未测 |
| systemappsupdater | UpdaterService | 系统应用远程更新服务 | 可进选择器，未测 |

### 7.2 AI

| 目标 | 组件 | 角色 | 状态 |
|---|---|---|---|
| suggestion | DecisionNotifyService | 建议通知服务 | 可进选择器，未测 |
| suggestion | DecisionReceiverService | 建议决策接收 | 可进选择器，未测 |
| sceneservice | PushMessageService | 场景推送服务 | 可进选择器，未测 |
| sceneservice | AwarenessStartReceiver | 情景感知启动 | 可进选择器，未测 |
| sceneservice | AwarenessReceiver | 情景触发接收 | 可进选择器，未测 |
| aipluginengine | AIModelService | 端侧 AI 模型服务 | 可进选择器，未测 |
| aipluginengine | LLMAbilityProvider | 大模型能力 Provider | 可进选择器，未测 |
| aipluginengine | ResourceUpdateProvider | 模型/资源更新 | 可进选择器，未测 |
| aipluginengine | CloudCfgReceiver | AI 云配置更新 | 可进选择器，未测 |
| assistant | YOYOAssistantMcpService | YOYO MCP 工具服务 | 可进选择器，未测 |
| assistant | DisplayCardProvider | 桌面卡片 Provider | 可进选择器，未测 |
| assistant | Recommend22WidgetProvider | 推荐卡片/Widget | 可进选择器，未测 |
| assistant | BrainNotifyService | Brain 通知服务 | 可进选择器，未测 |
| assistant | ThirdDisplayCardProvider | 第三方卡片 Provider | 可进选择器，未测 |
| assistant | OceanUpdateReceiver | YOYO 资源/配置更新广播 | 可进选择器，未测 |
| magicvoice | MagicVoiceInteractionService | 系统语音交互主服务 | 短时实测；跨重启未测 |
| magicvoice | WakeupService | 语音唤醒服务 | 可进选择器，未测 |
| magicvoice | HonorPushMsgService | 语音助手推送服务 | 可进选择器，未测 |
| voiceengine | BrainFenceNotifyService | Brain 围栏联动 | 可进选择器，未测 |
| brain | BrainMsgReceiverService | 消息接收服务 | 可进选择器，未测 |
| brain | UniCfgUpdateReceiver | 统一云配置更新 | 可进选择器，未测 |
| visionengine | BrainFenceNotifyService | 视觉与 Brain 联动服务 | 可进选择器，未测 |
| visionengine | DataUpdateReceiver | 视觉模型/资源云更新 | 可进选择器，未测 |
| lens | HonorLensService | 智慧视觉核心服务 | 可进选择器，未测 |
| lens | HonorLensRouterService | 智慧视觉路由服务 | 可进选择器，未测 |
| hibo | HiBoardNotifyService | 负一屏通知服务 | 可进选择器，未测 |
| hibo | PushMessageService | 负一屏推送服务 | 可进选择器，未测 |
| hibo | LauncherOverlayService | 桌面负一屏 Overlay 服务 | 可进选择器，未测 |
| hibo | BrainNotifyService | 负一屏 Brain 通知服务 | 可进选择器，未测 |

### 7.3 CLOUD

| 目标 | 组件 | 角色 | 状态 |
|---|---|---|---|
| hiview | NcDftService | 诊断服务；停用不等于 persistent 进程退出 | PF11/16 |
| hiview | CloudConfigUpdateReceiver | 云端配置更新接收器 | PF11/16 |
| hiview | LogCollectService | 日志采集服务 | PF11/16 |
| hiview | DiagKitService | 诊断服务 | PF11/16 |
| hiviewpush | PushManagerService | 推送管理服务 | PF11/16 |
| hiviewpush | PushMsgReceiverService | 推送消息接收服务 | PF11/16 |
| hiviewpush | PushReceiver | 推送 SDK 接收器 | PF11/16 |
| hiviewtunnel | LogUploadService | 日志上传服务 | PF11/16 |
| hiviewtunnel | UpdateConfigService | 上传配置更新服务 | PF11/16 |
| hiviewtunnel | UserExperienceProvider | 用户体验计划 Provider | PF11/16 |
| chr | ChrCloudParaUpdateReceiver | CHR 云参数更新 | 可进选择器，未测 |
| chr | ChrCommonReceiver | CHR 通用广播 | 可进选择器，未测 |
| chr | MdlogBootReceiver | MDLOG 开机广播 | 可进选择器，未测 |
| chr | MdlogReceiver | MDLOG 事件接收 | 可进选择器，未测 |
| detectrepair | CloudUpdateReceiver | 远程诊断云配置更新 | 可进选择器，未测 |
| systemserver | CommonCloudParaUpdateReceiver | 通用云参数更新 | 可进选择器，未测 |
| systemserver | PowerCloudParaUpdateReceiver | 功耗云参数更新 | 可进选择器，未测 |
| systemserver | AppMcpCfgUpdatedReceiver | App MCP 云配置更新 | 可进选择器，未测 |

### 7.4 SAFETY

| 目标 | 组件 | 角色 | 状态 |
|---|---|---|---|
| systemmanager | AntiFraudConfigReceiver | 反诈云配置更新 | 可进选择器，未测 |
| systemmanager | FraudContentProvider | 反诈号码 Provider | 可进选择器，未测 |
| systemmanager | FakeDetectService | 伪造检测服务 | 可进选择器，未测 |

### 7.5 THERMAL

| 目标 | 组件 | 角色 | 状态 |
|---|---|---|---|
| powergenie | CpuCfgCotaUpdateReceiver | CPU 云控参数更新 | **平台拒绝**（PF14） |
| powergenie | PgConfigCotaUpdateReceiver | PowerGenie 云控参数更新 | **平台拒绝**（PF14） |
| powergenie | ThermalCfgCotaUpdateReceiver | 温控云控参数更新 | **平台拒绝**（PF14） |
| iaware | CloudConfigUpdateReceiver | iAware 云配置更新 | PF14 |
| iaware | CloudPushContentProvider | 云推送配置 Provider | PF14 |

---

## 8. 默认观察与真实限制（不是策略拦截）

| 目标 | 限制 / 提醒 | 证据 |
|---|---|---|
| `powergenie` | 平台拒绝组件停用；整包策略可选但高风险 | PF14 |
| `pushagent` | 高风险提醒（DenyList）；整包策略可选 | `DenyList.kt` |
| `securitypluginbase` | 高风险提醒（DenyList）；整包策略可选 | `DenyList.kt` |
| `cipservice` | 默认观察；整包策略可选 | `TargetCatalog.kt` |

所有默认 `OBSERVE` 的目标都**不是只能观察**；UI 显示可点击策略，
只有平台拒绝、未登记组件与缺 Root 会置灰。DenyList/红色只触发高风险提醒。

---

## 9. 未测清单（不得写成已验证）

1. 当前 1.3.3 / code 9 的任何真机冻结/解冻记录。
2. 纯暂停（`forceStop=false`）：全部 41 目标未测。
3. 组件子集：仅 hiview 三件套、iaware、magicvoice（短时）有实测；其余未测。
4. 组件停用的 15 个未测目标：OUC、systemappsupdater、suggestion、sceneservice、
   aipluginengine、assistant、voiceengine、brain、visionengine、lens、hiboard、
   detectrepair、chr、systemserver、systemmanager。
5. 停用：仅 numberidentity（PF12）、DM 三件套基线原本停用；其余未测。
6. 隐藏：仅 upgradeguide（PF20/21）；其余未测。
7. AppOps/standby：securityserver、awareness 无设备记录。
8. `boot_guard.sh` v1.8 未安装；rescue 分支未实机触发。
9. OTA 删除组件后的孤儿恢复未在真实 OTA 后验证。
10. 专家模式（若采用）：未设计实现，全部未测。

---

## 10. 证据索引

| 结论 | 证据 |
|---|---|
| OUC 暂停/解冻往返 | PF1–PF4、PF15 |
| systemappsupdater 暂停/解冻 | PF13 |
| numberidentity 停用往返 | PF12 |
| hiview 三件套组件模式 | PF11、PF16 |
| hiview 家族暂停被平台拒绝 | PF9 |
| iaware 组件停用/恢复 | PF14 |
| powergenie 组件被平台保护 | PF14 |
| upgradeguide 隐藏往返 | PF20/PF21 |
| AI 组暂停与进程仍存活 | PF16、`docs/10:256` |
| MagicVoice 组件短时阻断 system bind | `docs/10:104-107` |
| 关机安全解冻 + 组件/隐藏恢复 | PF16、`ShutdownSafety.kt` |
| guard rescue 未实机触发 | `docs/12:323-325` |
