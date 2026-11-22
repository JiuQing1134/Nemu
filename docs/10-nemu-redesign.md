# 10 Nemu 重设计计划

> 状态：**Phase 1–4 已实施并完成真机验收；v1.1 第二轮（HIDE/主题/授权/低功耗）已完成（2026-11-22，boot #40）**  
> 日期：2026-11-22  
> 代码根：`D:\win_root\nemu`  
> 目标：把当前 Nemu 重命名为 **Nemu**，把“冻结”和“解冻”拆成两个独立页面，
> 用多模式冻结覆盖系统更新/AI/云控/遥测/温控/反诈组件，并保持自动救砖与关机安全。

## 1. 背景与现状

当前 248 个 JVM 测试通过；实机测试从 boot 38 开始，第二轮为 boot #40；
OUC/核心与 AI 目标处于 suspend，DM 三件套处于 disable-user。

审计代理刚刚补入：

- 进程快照（`ProcessListParser`/`PackageProcessListReader`），能区分“PM 显示已暂停”
  和“进程仍存活”；
- 逐目标解冻（`SelectedRestorePlan`），会先把选中策略移出开机冻结；
- 云控/反诈的原始 enabled 状态、Nemu 所有权标记 `disabled_by_nemu`；
- 模块 v1.4 / 自动救砖 guard 的 clean-shutdown 与所有权回滚；
- 冰青主题和自绘导航图标。

但仍有结构性问题：

1. 冻结页把“策略开关、模式、批量解冻、COTA、反诈、云控”混在一个页面；
2. “立即冻结”只应用核心 + AI，云控/反诈/COTA 分散在各自卡片，用户不知道
   一次冻结到底覆盖哪些；
3. `suspend` 不阻止后台 Service/Provider/Broadcast 重新拉起进程（实测 5/6 AI 包
   仍有进程）；仅靠 `force-stop` 不是持久的；
4. 包清单只覆盖 5 个核心 + 6 个 AI + 6 个云控 + 4 个反诈；系统里还有
   `systemappsupdater`、`android.chr`、`associateassistant`、`visionengine` 等
   未纳入的候选；温控包只有只读展示；
5. UI/文案/标题仍叫 Nemu（霜冻），且包名是 `com.nemu.hibernate`。

## 2. 产品命名

- 展示名：**Nemu**（永久冻土；英文、无中文、与“深层冻结”语义一致）。
- 应用标题/通知/模块显示名改为 Nemu；`README`/`PROJECT` 同步。
- **applicationId 保持 `com.nemu.hibernate`**：避免丢失已保存策略、root 授权、
  FinaSU 模块的 `am start` 目标、WorkManager 历史与安全解冻；真正的包名迁移
  需要一次独立的数据迁移，不在本轮做。
- 模块 ID 保持 `nemu_boot_autostart`（已安装、路径被 guard 引用），只改显示名；
  若要改 ID 需卸载/重装模块并迁移 `/data/adb/nemu`。

## 3. 冻结模式（核心变更）

每个目标增加一个 `FreezeMode`，必须由用户显式选择；默认取安全值：

| 模式 | 命令面 | 适用 | 恢复 | 说明 |
|---|---|---|---|---|
| `SUSPEND` | `pm suspend` + `am force-stop` | 绿色/黄色包；默认 | `pm unsuspend` | 可逆；组件仍可能被系统绑定拉起 |
| `COMPONENT` | 对目标列出的 Service/Provider/Receiver 执行 `pm disable --user 0 pkg/component`，可再 `force-stop` | 红色 AI/遥测中“已知会被系统绑定”的组件 | `pm default-state --user 0 pkg/component` | 阻止绑定/广播，不整包停用；必须逐组件登记 |
| `DISABLE_USER` | `pm disable-user --user 0 pkg` | 绿色/黄色叶子包、运营商 DM、可选遥测整包 | 仅当“操作前 enabled=true”时 `pm enable` | 最强；红色包禁止；必须保存原始状态和所有权 |
| `OBSERVE` | 无 | 温控/PowerGenie/iAware、HAL、未知包 | 无 | 只读展示，避免把系统搞崩 |

约束：

- 红名单（`powergenie`、`iaware`、`securityserver`、`awareness`、systemserver、
  systemmanager、vendor HAL、pushagent）不允许整包 `DISABLE_USER`；
- 组件模式只允许目录中登记的组件，不允许运行时任意字符串；
- 每个组件/整包操作前写“Nemu 所有权”标记；关机/开机/救援只恢复 Nemu 自己
  改过的状态，不覆盖用户原本就 disabled 的包；
- 模式变更后旧的冻结状态必须先恢复，或明确提示“切换模式可能残留旧状态”。

## 4. 目标目录（待设备/联网盘点后定稿）

分类：

1. **系统更新**：`com.hihonor.ouc`，`com.hihonor.systemappsupdater`，
   `com.hihonor.android.upgradeguide`（候选）。
2. **AI / YOYO**：`aipluginengine`、`assistant`、`magicvoice`、`voiceengine`、
   `brain`、`mageruntime`、`suggestion`、`yoyoappsug`、`yoyocards`、
   `sceneservice`、`associateassistant`、`visionengine`、`magicavatar`、
   `magictranslate`、`lens`（后几个需确认是否 AI 基础服务）。
3. **云控/遥测/运营商**：`hiview`、`hiviewpush`、`hiviewtunnel`、
   `android.chr`、`cipservice`、`regservice`、`UEInfoCheck`、`AutoRegSms`、
   `pushagent`（只读/保护）。
4. **反诈/安全**：`numberidentity`、`securityserver`、`awareness`、
   `securitypluginbase`；后三者只 AppOps/standby 或组件模式，禁止整包。
5. **温控/功耗**：`powergenie`、`iaware`、`msdp`；默认 `OBSERVE`，不提供
   整包冻结按钮；如提供组件模式必须单独风险确认。
6. **COTA 参数**：保留独立只读/备份/删除模块，不混入包冻结。

候选组件（已实测/待补）：

- `com.hihonor.magicvoice/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService`
  （实测组件停用可阻断 system voice bind）；
- `com.hihonor.hiview/com.hihonor.ncdft.NcDftService`
  （实测只改 enabled state，persistent PID 仍存活，不能宣称进程已停）；
- `com.hihonor.ouc` 的 `CotaService`、`CotaReceiver`、`CotaInstallReceiver`、
  `DownloadReceiver`、`CotaDownloadReceiver`、`FirmwareAutoDownloadReceiver`、
  `CheckNewVersionReceiver`、`PullUpProcessReceiver`（PullUpProcessReceiver 是否
  安全禁用需实测）；
- `com.hihonor.aipluginengine` 的 `LLMAbilityProvider`、`ResourceUpdateProvider`、
  `DataUpdateReceiver`、`AIModelService`、`BrainFenceNotifyService`；
- `com.hihonor.assistant` 的 `DisplayCardProvider`、`Recommend22WidgetProvider`、
  `AntiProcessDeathService`、`YOYOAssistantMcpService`；
- `com.hihonor.hiview` 的 `NcDftService`、`CloudConfigUpdateReceiver`、
  `LogCollectService`、`DiagKitService`、`HiViewSysReceiver`；
- `com.hihonor.powergenie` 的 `CpuCfgCotaUpdateReceiver`、
  `PgConfigCotaUpdateReceiver`、`ThermalCfgCotaUpdateReceiver`（只做云端参数
  更新阻断；PowerKitService/ThermalTraceService 不得禁用）。

**重要**：组件禁用不是“杀进程”的替代品。Hiview 的 persistent/shared UID 进程
在组件 disabled 后仍存活（已实测 8 秒窗口），所以 UI 必须显示实际进程，不能把
组件命令成功当成后台已停止。MagicVoice 的 system bind 被组件禁用阻断（已实测
短时窗口），但仍需跨重启/语音按键测试。

## 5. UI 信息架构（冻结/解冻分离）

底部导航改为 **5 个目的地**：

1. **Overview**：后端状态、救援模式、开机冻结状态、上次安全结果、快捷入口；
   不再放“立即冻结/全部恢复”两个混杂按钮。
2. **Freeze**：策略编辑与执行，专用。
   - 分类 chips：更新 / AI / 云控遥测 / 反诈安全 / 温控（只读）/ 自定义；
   - 每个目标卡片：名称、包名、风险、当前模式、策略开关、实际状态、进程状态；
   - 模式选择（SUSPEND / COMPONENT / DISABLE_USER / HIDE / OBSERVE，按风险限制）；
   - “应用选中”与“应用全部启用策略”；dry-run 预览；逐项确认红色/disable；
   - 不提供“解冻所选”（解冻在 Unfreeze 页）。
3. **Unfreeze**：实际状态与恢复，专用。
   - 列出当前实际 `suspended=true`、`enabled=false`、组件 disabled、进程存活的
     Nemu 目标；按类别分组；
   - 多选；“解冻所选”；“全部解冻”；显示恢复方式和原始状态；
   - 不提供策略开关；恢复后自动把该策略移出后续开机冻结；
   - 危险/失败确认。
4. **Detect**：只读策略/实际/进程/COTA/温控。
5. **Settings**：日志、dry-run、开机冻结、启动管理、救援清除、关于。

设计语言沿用现有冰青 Material3、自绘雪花/导航图标；新增“减号/解锁”图标用于
Unfreeze 页。页面保持单 LazyColumn、无嵌套滚动、手机宽度不横向溢出。

## 6. 数据与安全模型

- 新增 `FreezeMode`（core），`Target` 增加 `supportedModes`、`components`、
  `defaultMode`；新增 `TargetComponent`。
- 新增 `FreezeOwnershipStore`（SharedPreferences，按包/组件）保存：
  - `mode_<pkg>`；
  - `original_enabled_<pkg>`；
  - `component_<pkg>_<hash>` 的 `original_state`（default/enabled/disabled）；
  - `owned_by_nemu` 标记。
- 新增 `FreezeRestorePlan`：根据当前实际状态 + ownership 生成恢复命令，不依赖
  用户当前勾选；`Unfreeze` 页显示的计划与执行命令同源。
- 安全路径扩展：
  - `SafetyTargets` 不再只是 15 个 suspend 包，还包括所有 component-disable 与
    Nemu 所有的 disable-user；`ShutdownSafety` 调用统一 `SafetyRestorer`；
  - `BootFreezeWorker` 冻结前先恢复残留（全部模式），再按模式冻结；
  - `boot_guard.sh` 救援动态解冻所有 suspend 包 + `pm default-state` Nemu 组件
    + 仅对 Nemu 所有的 disable-user 执行 enable；
  - 组件/disable 操作写 `/data/adb/nemu/boot_guard/owned_components/` 标记，
    guard 可在 App 未启动时恢复。

## 7. 实施阶段

### Phase 0：盘点与定稿（本文件）

- 代码/设备/联网三份盘点报告；补全 package/component 表；
- 确认命名、模式、温控边界、是否允许红色包 DISABLE_USER（默认禁止）。

### Phase 1：核心模型与控制器

- `FreezeMode`、`TargetComponent`、`Target` 扩展、目录迁入；
- `ComponentFreezeController`、`DisableUserController`（复用 CloudController 的
  原始状态/所有权逻辑）、统一 `FreezeApplyPlan`；
- 单元测试：模式命令面、禁止红名单、原始状态、恢复计划、所有权。

### Phase 2：UI 重排

- `FreezeScreen` 与 `UnfreezeScreen` 拆分；
- 5 Tab 导航、Nemu 名称；
- Overview 精简；Detect/Settings 复用；
- 状态/回调接线，空状态，危险确认，dry-run。

### Phase 3：安全与救砖

- 扩展 `SafetyTargets`/`SafetyRestorer`/`ShutdownSafety`/`BootFreezeWorker`；
- 扩展 `boot_guard.sh`/module v1.5（v1.6 增加新 suspend 目标）的所有权恢复；
- 测试：组件恢复、disable-user 恢复、救援动态解冻、干净关机标记。

### Phase 4：构建与真机验收

- 全量单测 + assembleDebug；
- 设备只读盘点核对目录；
- 每个类别先 dry-run，再逐类实际冻结；Unfreeze 页恢复；
- 正常重启验证安全恢复；不制造 bootloop，救援用受控模拟。

## 8. 风险与回滚

- 温控/功耗/反诈基础服务整包停用可能造成系统不稳定：默认 OBSERVE/组件模式，
  禁止 DISABLE_USER；任何红色操作二次确认。
- 组件名写错会导致 `pm disable` 失败但不影响包状态；执行前校验组件存在于
  PackageManager，并记录输出。
- 模式切换可能残留旧模式：切换前要求先“恢复该目标”，或自动生成恢复计划。
- 保留现有 SafetyTargets/自动救砖作为兜底；任何新命令必须进入 ownership 恢复集。
- 失败时回滚代码到本计划提交前的 checkpoint；设备侧用 Unfreeze/模块 guard 恢复。

## 9. 验收标准

- `Freeze` 页只做策略/模式/执行，`Unfreeze` 页只做实际状态/恢复；
- 每类至少一个目标可冻结、可在 Unfreeze 页恢复；
- 关闭策略/解冻后重启不被开机冻结重新拉起；
- 组件模式对 MagicVoice 能阻止 system voice bind（以实测为准）；
- 禁用/组件/整包操作全部有原始状态与所有权，救援可恢复；
- 201+ 单测通过，构建成功，文档包表与设备实际一致。

## 10. 功能分组与逐行审计

用户要求“一个功能的代码放一起、每行代码逐行分析、检查按键是否真正生效”。执行时按功能域建立审计单元（不强行移动已稳定的旧包，避免无谓的 import/编译风险）：

| 功能域 | 入口/按钮 | 策略存储 | 执行器 | 实际状态来源 | 恢复路径 |
|---|---|---|---|---|---|
| Update / OUC | Freeze 页 Update 组、Apply | `target_<pkg>` / mode | FreezeController + 新组件/整包控制器 | PackageManager + ps | Unfreeze 页/安全解冻 |
| AI | AI 组开关、红色确认、Apply | `ai_deep_freeze_enabled`/`ai_target_*`/mode | AiFreezeController + 组件控制器 | PackageManager + ps | AI restore/Unfreeze |
| Cloud/Telemetry | Cloud 组开关、确认、Apply | `cloud_target_*`/`cloud_applied_*`/mode | CloudController + 组件控制器 | CloudStatus + ps | Cloud restore/Unfreeze |
| Anti-fraud | Safety 组、numberidentity 三态 | `antifraud_*` | AntiFraudController | PackageManager + AppOps/standby 读取 | AntiFraud restore/Unfreeze |
| Thermal | 只读组 | 无（默认 OBSERVE） | 无整包冻结；仅 COTA/组件只读或显式 | Thermal/Cota state | 无 |
| Automation | Boot auto-freeze | `boot_freeze_*` | BootFreezeWorker | last_* + boot guard | Unfreeze + guard |
| Safety/Rescue | 全部解冻、救援清除 | guard 文件 | SafetyUnfreezer + BootGuard | detection + guard status | guard/Unfreeze |

逐行审计表（每个功能必须填）：

1. 按钮/开关 → 回调名 → MainActivity 函数 → 控制器方法 → 具体 shell 命令；
2. 命令 exit code → 是否复核实际状态（PackageManager/ps/组件 enabled）→ 失败是否回滚/保留；
3. 策略写入时机 → 是否 `commit()`/sync → 进程被杀后是否可恢复；
4. 开机/关机/救援是否覆盖该模式；
5. UI 是否显示 pending/applied/actual/unknown，是否与后端结果一致；
6. 是否会重复冻结、重复解冻、误 enable 用户原有 disabled 状态。

验收时用独立只读 agent 对每个按钮做“点击/回调/命令/状态”对照，不能只看编译通过。

## 11. 执行顺序（本轮）

1. 定稿本计划（补全设备/联网盘点）。
2. Phase 1：`FreezeMode`/组件目录/统一控制器/所有权（测试先行）。
3. Phase 2：Nemu 命名 + 5 Tab + Freeze/Unfreeze 两页。
4. Phase 3：安全/救砖恢复扩展（组件/disable-user）。
5. Phase 4：构建、逐行功能审计、真机只读+逐类 dry-run/实际测试。

> 任何阶段发现计划与代码事实冲突，先改计划再改代码；未真机验证的项目在文档中写“未测”。

## 12. 2026-11-22 设备盘点结论（只读）

采集：`pm list packages/-d/-e`、`dumpsys package`、`dumpsys activity`、`ps -A`、`pidof`、`getprop`、`am get-standby-bucket`。设备 373 个包（314 系统 + 59 三方）；当前 `suspended=true` 11 个，全部是更新/AI 组；`enabled=3` 4 个：DM 三件套 + numberidentity。

关键修正：

- `com.hihonor.associateassistant` 是无线投屏/WFD，不是 AI；不要放进 AI 组。
- `com.hihonor.ddmp`（进程 `com.hihonor.drsp`）是分布式文件/网络协同，不是遥测。
- `com.hihonor.devicemanager` 是 MDM 设备管理，不是运营商 DM；运营商 DM 是 `com.hihonor.regservice`（HnCmccDm）。
- `com.hihonor.contactscamcard` 是名片扫描（CamCard），不是反诈。
- `com.hihonor.hisight` 是投屏/共享（AirSharingClient），不是 HiView 遥测。
- `com.hihonor.systemserver` 承载插件/运营商/热补丁/云参数；禁止整包，但可组件模式阻断 `CommonCloudParaUpdateReceiver`/`PowerCloudParaUpdateReceiver`/`AppMcpCfgUpdatedReceiver`。
- 反诈能力主要在 `com.hihonor.systemmanager`（securitycenter.antifraud.*），而非 securityserver/awareness；systemmanager 禁止整包，只组件模式。
- 温控：`com.hihonor.powergenie` 的 `PowerKitService` 实际运行在 `com.hihonor.iaware` 进程；整包禁用会破坏大量绑定。实机 `pm disable` 对 powergenie 的 CpuCfg/PgConfig/ThermalCfg 组件返回 `new state: default`（HONOR 保护），因此目录把 powergenie 设为 **OBSERVE only**；iaware 的 `CloudConfigUpdateReceiver`/`CloudPushContentProvider` 可组件停用，保留 OBSERVE+COMPONENT。
- 原生/厂商进程无法用 `pm` 冻结：`hiview`（root native）、`vendor.honor.hardware.hwhiview.service`、`android.hardware.thermal-service.qti`、`vendor.honor.hardware.iawareperf-service`、`perfservice`、`powerlogd` 等；UI 必须标注“包冻结不覆盖 HAL/原生进程”。
- `suspended=true` 时仍有进程：aipluginengine、assistant、magicvoice、brain、visionengine、hiview 等；组件/包冻结后必须显示实际进程，不能把 suspend 当停止。
- `numberidentity` 当前是 `disable-user`；恢复必须保留“原本 disabled”的所有权基线，不能误 enable。
- `systemappsupdater` 当前有进程，是系统应用更新器；应纳入 Update 组（默认关闭）。
- `com.hihonor.android.upgradeguide` 只有 `UpgradeWizardActivity`，是升级引导 UI，不是后台更新服务；只观察/不纳入冻结。
- `com.honor.gpudrivers` 无导出组件，不应作为冻结目标。

## 13. 2026-11-22 实施记录

### 13.1 已落地代码

- **统一策略模型**：`FreezeStrategy`、`TargetComponent`、`Target.supportedStrategies`；
  `TargetPolicy` 统一读写旧命名空间（核心 `target_*`、AI `ai_deep_freeze_enabled`/`ai_target_*`、
  云控 `cloud_target_*`、反诈 `antifraud_numberidentity_mode`），避免迁移丢失策略。
- **统一执行器**：`TargetFreezeService`（App 手动、`BootFreezeWorker` 开机冻结、
  `ShutdownSafety` 关机路径共用）；`FreezeStrategyController` 负责命令、复核与所有权回调。
- **所有权**：disable-user 先持久化原始 enabled + guard marker；组件先持久化
  PackageManager 原始 enabled state（0/1/2/3/4），只接管原本 default/enabled 的组件；
  恢复时按原始状态 `pm default-state`/`enable`/`disable` 精确回滚。
- **UI**：显示名 Nemu；5 Tab（概览/冻结/解冻/检测/设置）；
  `FreezeScreen` 只做策略、模式、应用；`UnfreezeScreen` 只做实际状态、选择性/全部恢复；
  COTA/旧云控/旧反诈移入设置-高级控制。
- **检测**：`DetectionReader` 增加 `FreezeSource.CATALOG` 与 `FreezeMode.COMPONENT`，
  读取组件实际 enabled state，不再把“命令成功”当作组件已停用。
- **安全/救砖**：`ShutdownSafety` 在关机时额外恢复所有权组件；`BootFreezeWorker` 先做
  残留 unsuspend + 组件恢复；guard v1.5 的 rescue/service/clear 均恢复组件所有权标记，
  `KNOWN_SUSPEND` 覆盖新目录。

### 13.2 设备/联网盘点补充

- 新增目录目标：`com.hihonor.hiboard`（负一屏，AI 候选）、
  `com.hihonor.detectrepair`（智能检测/远程诊断，云控观察+组件）、
  `com.hihonor.android.hnaps`（刷新率/画面自适应，温控只读）。
- 组件名按 `audit-2026-11-22/components-raw.txt`（设备 `dumpsys package`）修正：
  assistant/suggestion/sceneservice/visionengine/lens/hiviewpush/hiviewtunnel/systemmanager；
  删除有绑定风险的 `CotaService`、`BrainService`、`TtsService`。
  最终目录中 **64 个组件全部用设备 `dumpsys package` 逐一核对，0 缺失**
  （含系统更新、AI、云控、反诈、温控组件）。
- 联网核对：以上包名与组件在 HONOR 社区、APKMirror、Bayton 系统应用库和 XDA
  debloat 清单中可交叉确认；温控/显示/安全基础服务只观察，不做整包冻结。
- 平台事实（AOSP 文档与 Hail README）：`pm suspend` 只阻止前台交互/通知，
  **不停止后台 Service/Provider**；组件级 `pm disable` 在 Android 12+ 对
  adb/Shizuku 受限，必须 Root/特权，因此 COMPONENT 策略在 UI 中标注需要 Root，
  无 Root 时只提供暂停/观察。
- COTA 参数同步不只经过 OUC：社区资料指向 `com.hihonor.assetsync` /
  `com.hihonor.assetsyncservice`；本目录新增两者为只读目标，不做冻结。

### 13.3 验收结果（原“待真机”项已完成）

- 新 APK（`b4ac9756...`）与模块 v1.6 源码已更新；PF1–PF26 实际冻结/解冻已执行。
- 组件模式、disable-user 往返、guard 组件恢复均已实测（见 PF12/PF14/PF16）。
- 全量单测 248 个通过（0 失败，含 `TargetCatalogTest`、`CatalogModelsTest` 与
  控制器失败路径测试）；`assembleDebug` 产物哈希见 README。
- 完整逐项真机记录见 `docs/12-device-test-log-2026-11-22.md`。

### 13.4 真机功能验证（v1.0，2026-11-22，只操作 OUC）

- Dry run 下在 Freeze 页对 OUC 点“应用”：结果显示“成功：尝试 1，成功 1，失败 0”，
  `strategy_applied_com.hihonor.ouc` 未被写入，`suspended` 不变。
- 关闭 Dry run 后实际应用 OUC：`suspended=true`，`target_com.hihonor.ouc=true`，
  `strategy_applied_com.hihonor.ouc=true`，卡片显示“已应用”。
- Unfreeze 页勾选 OUC → “解冻所选”并确认：`suspended=false`，
  `target_com.hihonor.ouc=false`，applied 标记清除；随后在 Freeze 页重新勾选并应用，
  恢复 `suspended=true`。
- **发现并修复 P0**：Dry run 下“全部解冻”仍调用了 always-on 的 `residualUnfreeze()`，
  实际把 OUC unsuspend。修复为 dry-run 时跳过 residual 清理；重装后再次验证
  Dry run 全部解冻，OUC 保持 `suspended=true`。
- 修复后设备最终状态：Dry run=false，OUC 策略启用且已暂停；未改动其他目标的策略。
- **hiview 三件套不能 suspend**：实机 `pm suspend --user 0 com.hihonor.hiview`
  返回 `new suspended state: false`（hiviewpush 相同），平台拒绝。目录已把三者默认策略
  改为 COMPONENT，并让检测页用策略感知的目录行展示它们，而不是旧的 SUSPEND 行。
- **PF12 numberidentity**：disable-user 完整往返通过（enabled 3→1→3，mode
  DISABLE_USER→OFF→DISABLE_USER，applied/original 同步）。
- **PF13 systemappsupdater**：实际冻结/选择性解冻通过（suspended false→true→false）。
- **PF14 温控**：iaware 组件模式实际停用/恢复通过；powergenie 三个组件被平台
  保护（`pm disable` 返回 `new state: default`），目录改为 OBSERVE only。
- **PF15 dry-run 解冻所选**：结果带 DRY-RUN，OUC suspended/策略/applied 均不变。
- **PF16 重启验收**：boot 38→39；关机逐包 unsuspend + 10/10 组件恢复、
  rescue_marker=true；guard attempts=1、rescue=no；120 秒后开机冻结 18 目标成功
  （UPDATE1/AI10/CLOUD6/SAFETY1），核心/AI suspended、hiview 组件 disabled、
  DM/numberidentity 保持 disable-user。
- **安装 APK 不应解冻**：最终安装时观察到核心/AI 的 suspend 被全部清除（冻结数
  18→7）。原因是 WorkManager 把上一轮 `BootWorker`（开机安全解冻）跨 APK 更新
  持久化并重跑。修复：`MY_PACKAGE_REPLACED` 先
  `cancelUniqueWork("nemu_safety_unfreeze")`；`BootWorker` 入队时写入 boot count，
  运行时与当前 boot 不一致则放弃。之后安装不再触发全量解冻。

## 14. v1.1 第二轮（2026-11-22）

在 Phase 1–4 基础上完成：

1. **外观**：雪人笑脸自适应图标（含 monochrome）；Material 3 / M3E（大圆角）/ MIUIX
   三套风格；松林绿（默认）/冰川蓝/暖琥珀/极光紫/石墨灰五套配色。每套配色只用一个
   种子色，所有控件颜色从该色系与白色之间推导（参考 FinaSU 的 materialkolor 色调思路）；
   深色背景纯 `#000000`，卡片中性深灰（无褐色）。
2. **授权**：`PrivilegeOverview` + 授权卡显示当前模式（Root / Shizuku ADB / Shizuku Root /
   未授权）与 Root、Shizuku、ADB 三行真实状态；“请求授权”依次尝试 Root/Shizuku；
   “ADB 授权说明”给出用电脑 ADB 启动 Shizuku 的步骤。
3. **冻结页**：勾选=计划、立即冻结=执行；逐目标与全部冻结都先显示“具体会发生什么”；
   Unfreeze 页显示 HIDE 行与恢复后会怎样。
4. **新策略 HIDE（伪装卸载）**：`pm hide/unhide`，仅 Root（MANAGE_USERS）；
   用 `dumpsys package` 验证 hidden；写入 `strategy_original_hidden_<pkg>=false` 与
   `strategy_hidden_owned_<pkg>=true` 后才执行；预隐藏包拒绝接管；校验失败自动回滚；
   关机/开机残留清理只解除自有隐藏。
5. **目录**：观察目标按“没用就允许停用/隐藏、重要就具体警告”调整；
   powergenie 仍 OBSERVE only（平台保护），pushagent 仍禁止操作；
   iaware 仅组件；securityserver/awareness 可选暂停并警告。
6. **低功耗**：`SafetyService` 只在有冻结目标时启动，全部恢复即停止；
   `START_NOT_STICKY`；无目标时不调度开机冻结 Worker；BootWorker 迟到时
   （冻结尝试晚于本次开机）跳过解冻。
7. **P0**：实机发现延迟约 25 分钟的 BootWorker 把用户刚冻结的 15 个目标全部解冻；
   已用 `BootSafetyDecision` 修复并加 3 个单测。
8. **测试**：248 个 JVM 测试 / 29 类；PF17–PF29 实机验证（图标、主题、授权、HIDE 往返、
   服务停止、迟到 Worker、颜色/文字/直觉性修正）。详见 `docs/12`。
9. **执行方式**：删除 Dry run 开关，改为“仅预览（不修改手机） / 实际执行”两个选项；
   所有 DRY-RUN 文案改为“预览”。解冻按钮明确“关闭计划”与“保留计划”的区别。
10. **布局**：修复解冻页风险 chip 与状态 chip 的像素重叠；选择 chip 的对比度提高。

