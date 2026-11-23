# 02 包清单与风险分级

> 标签来自设备上 APK 的 `aapt2 dump badging`（中文 label 优先）；版本来自 `dumpsys package`。
> 风险分级：**红 = 不要整包冻结/停用**；**黄 = 需逐包实测**；**绿 = 可作为叶子包冻结**。
> “证据”列注明是源码/Manifest/dumpsys/社区实测中的哪一类。

## 1. 系统更新 / OTA

| 包 | 中文标签 | 版本 | 作用 | 风险 | 建议 |
|---|---|---|---|---|---|
| `com.hihonor.ouc` | 软件更新 | 17.0.12.100 | 系统 OTA 客户端；含 COTA 下载/安装、Setup Guide 服务、推送接收器 | **黄→红（用户报告卸载后异常；现有日志未复现）** | **只 suspend**；不卸载、不停用；配合强停 + 清缓存 |
| `com.hihonor.systemappsupdater` | （系统应用更新器） | 16.0.17.307 | 通过应用市场更新系统应用 | 黄 | 可选；停用可能影响系统应用更新 |

**关键事实**：

- 用户报告的原始序列：卸载 `com.hihonor.ouc` → 对全部应用强制 dex2oat 编译 → 刷入类似 Magisk 的 root 模块 → 系统崩溃；重启能进桌面但马上卡住重启。Nemu 因此坚持“不卸载”；现有日志分析见 `docs/05-ouc-crash-analysis.md`（全量 dex2oat + root/zygisk 模块是更优先的解释）。
- 2026-11-21 对现有日志的重新分析（`docs/05-ouc-crash-analysis.md`）：dropbox/tombstone 中没有 OUC 或 system_server 崩溃证据；重启簇与 KSU/LSPosed 引导期故障、`PS_HOLD` 硬复位/人工重启一致；系统侧对 OUC 的静态依赖均为 fail-soft。原始事件的日志可能已在 11:20 系统更新前丢失，不能据此否定用户当时的经历。
- OUC Manifest 中有 `CotaService`（权限 `COTA_SERVICE_FOR_SETUP_GUIDE`）、`DownloadReceiver`（BOOT_COMPLETED，priority 1000）、`SuggestionStateProvider`、`SettingsSearchProvider` 等；这些可能被设置/开机向导/搜索引用。
- 当前 OUC 已 `suspended=true`；实测在 suspend 状态下仍能收到 `BOOT_COMPLETED`，说明 suspend 不阻止启动期接收器。

## 2. 系统 AI / YOYO

| 包 | 中文标签 | 版本 | 作用/依赖 | 风险 | 建议 |
|---|---|---|---|---|---|
| `com.hihonor.aipluginengine` | **荣耀 AI · 基础服务** | 7.1.7.340 | 跑 `:llmservice`，注册 `LLMAbilityProvider`；AI 插件/模型服务 | **红** | 不整包冻结；可限制网络/后台或做组件级禁用 |
| `com.hihonor.assistant` | **YOYO 建议基础服务** | 10.0.0.390 | 注册 `DisplayCardProvider` / `ThirdDisplayCardProvider`；桌面/负一屏依赖 | **红** | 不整包冻结 |
| `com.hihonor.magicvoice` | YOYO 智能体/智慧语音 | 90.10.35.036（506 MB） | 系统 `VoiceInteractionService`，被 `system` 绑定 | **红/黄** | 不用语音助手时可 suspend，但需实测系统语音/按键行为 |
| `com.hihonor.voiceengine` | 语音服务 | 10.0.0.108 | TTS/ASR/NLU | 黄 | 不用语音时可 suspend；注意其他 App 的 TTS |
| `com.hihonor.brain` | 智慧决策 | 10.0.0.370 | 系统决策/能力服务；被系统管家等绑定 | 红/黄 | 不整包冻结；逐项评估 |
| `com.hihonor.mageruntime` | MageEngineRuntime | 10.0.0.217 | AI 模型运行时 | 黄 | 可测试 suspend，观察相机/图库 AI |
| `com.hihonor.suggestion` | 智能提醒 | 16.1.0.300 | 场景/功能建议 | 绿 | 可 suspend |
| `com.honor.yoyoappsug` | App 建议 | 9.0.1.202 | 应用建议 | 绿 | 可 suspend |
| `com.honor.yoyocards` | YOYO 建议卡片集 | 9.0.1.202 | YOYO 卡片 | 绿 | 可 suspend |
| `com.hihonor.sceneservice` | 荣耀智慧空间基础服务 | 10.0.8.301 | AI 空间/场景服务 | 黄 | 可测试 suspend |
| `com.hihonor.awareness` | 情景感知 | 10.0.0.375 | 情景感知、反诈、人脸活体/深伪 | **红** | 默认观察；用户确认后可整包操作（见反诈节） |

**结论**：v1 的 AI 冻结只做绿色叶子包；红色基础服务保持完整，最多做网络/后台/组件级限制。红色包整包冻结很可能就是用户之前“系统 AI 一禁用就崩”的来源（待用户确认具体包）。

**组件停用实机结果（2026-11-21）**：在 `com.hihonor.magicvoice` 上，短时 `pm disable --user 0 com.hihonor.magicvoice/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService` 后，system 绑定和包进程在观察窗口内消失；用 `pm default-state` 恢复后，system 自动重新绑定。短时 `pm disable-user --user 0 com.hihonor.magicvoice` 也阻止了包进程，但会禁用整包所有组件。两轮都已回滚，未重启；细节见 [设备实测记录](01-device-and-findings.md)。该结果只适用于 MagicVoice 的在线观察，不证明跨重启或其他包同样安全；Nemu 若支持此模式，必须把它作为逐组件的显式策略，并纳入关机/开机恢复保护。

## 3. 反诈 / 安全

| 包 | 中文标签 | 版本 | 作用 | 风险 | 建议 |
|---|---|---|---|---|---|
| `com.hihonor.securityserver` | **安全公共服务** | 12.1.0.1 | 反诈核心：采集/上报通话、短信、位置、应用列表等；uid=system | **红** | 默认只观察；通用目标模型允许用户明确选择暂停/停用，但会破坏支付保护/反诈，需自行确认后果 |
| `com.hihonor.awareness` | **情景感知** | 10.0.0.375 | 41 处 fraud 逻辑 + 独立上报；人脸活体/深伪载体 | **红** | 默认只观察；整包操作同样可破坏活体/情景功能 |
| `com.hihonor.numberidentity` | **号码识别** | 18.0.10.305 | 在线识别骚扰/诈骗号码；上传未知来电去电号码 | 黄 | Nemu 提供 `suspend`/`disable-user`/`hide`（用户选择、可恢复）；会失去号码识别/标记 |
| `com.hihonor.securitypluginbase` | HnSecurityPluginBase | 16.0.0.101 | 安全插件底座（疑似） | 黄 | 默认观察；通用目标模型可选，风险自负 |
| `com.hihonor.systemmanager` | 系统管家 | （125 MB） | 安全扫描、隐私助手 UI | 红 | 不要停用 |
| `com.hihonor.trustspace` | **支付保护中心** | 16.0.0.001 | 识别支付应用、支付保护 | 黄 | 不是反诈；不用支付保护可停 |
| `com.hihonor.contactscamcard` | **扫名片** | 17.0.0.5 | 名片扫描（Camcard = business card） | 绿/无关 | 与反诈无关，不要误杀 |
| `com.hihonor.intelligentmessage` | **智能信息插件** | 10.0.44.309 | SmartNotificationPlugin（不是短信反诈） | 无关 | 不要误杀 |

**证据**：

- `securityserver` 的 Manifest 组件：`HwSecurityService`、`SafetyCtrlService`、`HealthSceneRecommendService`、`ParentControlManagerService`、`DeviceHealthService`、`CloudParaReceiver`。
- `HonorAdvancedSettings` 源码注释：反诈主体是 `com.hihonor.securityserver`（uid=system），`com.hihonor.awareness` 自身有 41 处 fraud 逻辑和独立上报通道；`awareness` 同时是人脸活体/深伪检测的载体。
- `numberidentity` APK 字符串明确写着在线号码识别会上传未知来电/去电号码，并标记 scam/fraud。
- 社区反馈：反诈相关开关在系统更新后可能默认重新开启。

**Nemu 实现（2026-11-23 更新）**：反诈包的独立 `AntiFraudPolicy`/`DenyList` 硬阻断已移除，统一进入通用目标模型（用户此前明确要求所有已安装应用都可冻结）。`securityserver`/`awareness` 默认 `OBSERVE`、红色风险提示；用户明确选择后可执行通用策略。`numberidentity` 的 legacy mode 仍映射 SUSPEND/DISABLE_USER/HIDE，并纳入关机/开机安全解冻。

## 4. 云控 / 遥测 / 运营商

| 包 | 中文标签 | 版本 | 作用 | 风险 | 建议 |
|---|---|---|---|---|---|
| `com.hihonor.regservice` | **“系统消息”**（伪装名） | 15.10.0.104 | 实际是 `HnCmccDm` 中国移动终端管理 DM：`cn.richinfo.dm.*`，自注册、心跳、FOTA、应用清单上报 | **绿/黄** | **Nemu 提供 `disable-user`（可逆、逐项确认）**；不卸载 |
| `com.hihonor.android.UEInfoCheck` | （UEInfoCheck） | - | 电信数据上报组件 | 绿/黄 | Nemu 提供 `disable-user`（可逆、逐项确认） |
| `com.hihonor.android.AutoRegSms` | （ChnTelecomAutoReg） | - | 电信自注册短信 | 绿/黄 | Nemu 提供 `disable-user`（可逆、逐项确认） |
| `com.hihonor.hiview` | hiview | 17.0.0.001 | 遥测采集 | 黄 | Nemu 提供 `suspend` + `force-stop`（可逆；默认关闭） |
| `com.hihonor.hiviewpush` | （无 label） | 17.0.0.001 | 遥测推送 | 黄 | Nemu 提供 `suspend` + `force-stop`（可逆；默认关闭） |
| `com.hihonor.hiviewtunnel` | HiViewTunnel | 17.0.0.000 | 遥测上传通道 | 黄 | Nemu 提供 `suspend` + `force-stop`（可逆；默认关闭） |
| `com.hihonor.cipservice` | HnCIPService | 10.0.0.1 | 含 `SET_CHR_DATA`、定位/Wi-Fi 权限，疑似 CHR 上报 | 黄 | Nemu 仅展示状态，不提供操作（先观察） |
| `com.hihonor.android.pushagent` | 荣耀推送服务 | 11.0.11.313 | Honor Push 通道 | 黄 | Nemu 不提供操作；在 `DenyList` 中 |
| `com.hihonor.systemserver` | 系统服务 | 70.1.0.1 | HONOR 核心系统服务 | **红** | 绝对不要动 |
| `com.hihonor.devicemanager` | 系统管家 | 16.0.0.340 | 系统管理 UI；拥有 `com.hihonor.permission.sec.MDM.v2`、`ACCESS_UDID` | 红 | 不要动 |
| `/data/cota/para` | （目录） | - | 云控性能/温控参数：`cpuconfig`、`pg_config`、`thermal_config`；真机 102 上为子目录，例如 `thermal_config/thermal_config_cloud.xml` | **红（root 操作）** | Nemu 递归只读清单并按子系统分组；备份后逐项删除；不要盲目删除 |

**Nemu 实现（2026-11-21）**：`CloudTargets` / `CloudController`。运营商 DM 三件套是云控模块中唯一允许 `pm disable-user` 的目标（静态白名单 + 逐项确认 + `pm enable` 回滚）；遥测三项固定用 `suspend` + `force-stop`，不使用 `disable-user`；`cipservice` 仅观察；`pushagent` 不提供操作。遥测三项纳入 `SafetyTargets` 关机/开机安全解冻。

**Hiview 停用实机结果（2026-11-21）**：组件 `com.hihonor.hiview/com.hihonor.ncdft.NcDftService` 和整包 `com.hihonor.hiview` 均能改变 PackageManager enabled state，但 8 秒内已有服务绑定与 PID 4670 仍存活。该进程为 `uid=1000`、`persistent=true`，并承载 `hiviewpush` 与 `android.chr` 包；本次没有对其 force-stop/杀进程。Nemu 的“命令成功”不能据此显示为“进程已停止”，必须继续显示实际进程状态；不要把 Hiview 整包 disable 作为安全停进程保证。

**关键证据**：

- `HnCmccDm.apk` 的 Manifest 包含 `cn.richinfo.dm.service.DMService`、`cn.richinfo.mt.service.MTService`、`MTHeartJobService`、`SimStateChangeReceiver`、`VolteStateReceiver`。
- 权限：`READ_PHONE_STATE`、`READ_PRIVILEGED_PHONE_STATE`、`ACCESS_FINE_LOCATION`、`PACKAGE_USAGE_STATS`、`QUERY_ALL_PACKAGES`、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`、`FOREGROUND_SERVICE`。
- 中文标签是“系统消息”，不是“中国移动终端管理”；这是典型的隐藏式运营商组件。
- 开源项目 **TelemetryCleaner** 内置列表：`com.hihonor.regservice`（荣耀/移动）、`com.hihonor.android.UEInfoCheck`（荣耀/电信数据）、`com.hihonor.android.AutoRegSms`（荣耀/电信短信），均标注已实测。

**边界说明**：

- 能确认这些组件具备云端下发/采集能力；无法从包本身证明“销售商故意借此降性能”。真正影响性能/温控的是 COTA 参数 + PowerGenie/iaware 策略。

## 5. 温控 / 性能

| 包/服务 | 中文标签 | 版本 | 作用 | 风险 | 建议 |
|---|---|---|---|---|---|
| `com.hihonor.powergenie` | HAware | 17.25.25.3 | PowerGenie：功耗、温控、充电策略；`PowerKitService` 被 10+ 系统组件绑定 | **红** | 不要整包停用 |
| `com.hihonor.iaware` | iAware | 10.0.2.001 | CPU/GPU 调度；PowerGenie 服务实际跑在 iaware 进程 | **红** | 不要整包停用 |
| `com.hihonor.msdp` | 综合传感信息处理平台 | 11.0.0.40 | 传感器数据平台 | 黄/红 | 不建议停 |
| `vendor.honor.hardware.iawareperf-service` | - | - | 底层性能 HAL | **红** | 不要动 |
| `vendor.qti.hardware.perf2-hal-service` | - | - | 高通 perf HAL | **红** | 不要动 |
| `perfservice` | - | - | 底层 perf 服务 | **红** | 不要动 |
| `com.ruoqing.freqcap` | - | - | 第三方 CPU 频率限制工具（用户安装） | 无关 | 与本项目无关 |

**PowerKitService 的实际绑定方（dumpsys）**：

`com.hihonor.gameassistant`、`com.hihonor.iaware`、`com.hihonor.dataupdateservice`、`com.hihonor.appmarket`、`com.hihonor.brain`、`com.hihonor.hiview`、`com.hihonor.systemmanager`、`com.hihonor.nearby`、`com.hihonor.aipluginengine`、`com.hihonor.ouc`、`com.hihonor.android.pushagent`。

结论：**停用 powergenie/iaware 会造成大面积绑定失败，并失去温控/充电保护**。如果目标是阻断云端性能/温控参数，只能走 COTA 参数清理（root，备份后逐项），并接受发热/充电风险。

**Nemu COTA 实现（2026-11-23 更新）**：递归只读 `/data/cota/para`（相对路径最多 4 层，拒绝绝对路径、空段与 `..`），按子系统分组显示；备份到 App 私有 `filesDir/cota-backup/<timestamp>/`，保留子目录并写 `manifest.tsv`（相对路径/SHA-256/大小/mode/uid/gid）；删除前校验备份存在、备份副本与清单一致、当前 hash 与备份一致；只对选中文件执行 `rm -f`（不删目录）；恢复前校验备份副本，必要时重建目标子目录，恢复后重新校验 SHA-256。只接受 `RootPrivilegeBackend`，无 Shizuku 回退；默认关闭。**不触碰** powergenie/iaware/vendor HAL/内核 thermal zone。

## 6. 风险分级汇总

### 红（禁止整包 freeze/disable）

`com.hihonor.ouc`（允许 suspend，禁止 uninstall/disable）、`com.hihonor.aipluginengine`、`com.hihonor.assistant`、`com.hihonor.magicvoice`、`com.hihonor.brain`、`com.hihonor.awareness`、`com.hihonor.securityserver`、`com.hihonor.systemmanager`、`com.hihonor.systemserver`、`com.hihonor.powergenie`、`com.hihonor.iaware`、`com.hihonor.android.pushagent`、`com.hihonor.devicemanager`、底层 HAL。

> 整包 `disable-user` 的例外（都必须逐项确认、可逆）：运营商 DM 三件套（云控模块）；`com.hihonor.numberidentity`（反诈模块）。`securityserver` / `awareness` 没有整包操作路径。

### 黄（逐包实测）

`com.hihonor.mageruntime`、`com.hihonor.voiceengine`、`com.hihonor.sceneservice`、`com.hihonor.numberidentity`、`com.hihonor.securitypluginbase`、`com.hihonor.trustspace`、`com.hihonor.hiview*`、`com.hihonor.cipservice`、`com.hihonor.systemappsupdater`。

### 绿（可作为叶子包 suspend）

`com.honor.yoyoappsug`、`com.honor.yoyocards`、`com.hihonor.suggestion`、运营商 DM 三件套（`com.hihonor.regservice`、`com.hihonor.android.UEInfoCheck`、`com.hihonor.android.AutoRegSms`；云控模块使用可逆 `disable-user`，不是 suspend）。

## 7. 数据来源与置信度

| 来源 | 用途 | 置信度 |
|---|---|---|
| 设备 `dumpsys package` / `aapt2 dump badging` | 包名、版本、中文标签、用户状态 | 高 |
| 设备 `dumpsys activity services/providers` | 实际绑定关系 | 高 |
| APK Manifest（`aapt2 dump xmltree/permissions`） | 组件、权限 | 高 |
| Hail 1.11.0 源码 | suspend/force-stop 语义、无 BOOT_COMPLETED | 高 |
| TelemetryCleaner README/源码 | 运营商 DM 三件套实测 | 中高（作者/网友实测） |
| HonorAdvancedSettings 源码/README | securityserver/awareness 反诈、COTA 路径 | 中（MagicOS 11，需在 10 上复核） |
| 社区帖子 | powergenie/iaware 副作用、YOYO 基础服务 | 中（经验性） |
