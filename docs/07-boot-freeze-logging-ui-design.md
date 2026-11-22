# 07 开机冻结、日志、检测与 UI 重设计

> 日期：2026-11-21
> 状态：已实现并合并到本地 `main`；当前工作树 190 个单元测试通过。检测页已加入 AI 目标（`FreezeSource.AI`）。
> 真机已验证 Root 关机解冻、核心开机冻结、AI 单目标冻结/解冻、bootloop 守卫、持久日志；UI 视觉与检测页待手机解锁后检查。
> 2026-11-22 更新：Nemu 重设计（`docs/10`）后，iaware 已进入统一目录并支持 COMPONENT 策略（PF14 实测）；powergenie 仍为 OBSERVE-only。本文其余内容保留为当时设计记录。
> 前置：`docs/03-design.md`、`docs/04-test-plan.md`、`docs/05-ouc-crash-analysis.md`、`docs/06-module-integration-plan.md`
> 本机 root：FinaSU（`me.yuki.fina`），已授权 Nemu；`last_shutdown_detail` 已实测显示 `Root`。

## 1. 目标

1. **关机安全解冻**：正常关机/重启前，用 root 解冻所有可能被 Nemu suspend 的目标；保持现有 `SafetyService` 主路径。
2. **开机自动冻结**：开机稳定后，按用户策略自动重新冻结已启用的核心目标和 AI 目标；不再要求每次手动点“立即应用”。
3. **AI 服务冻结**：提供可选的 AI 深度冻结目标；默认关闭，逐项确认，不把红色基础服务偷偷加入。
4. **冻结/温控/云控检测**：只读展示目标真实 suspend 状态、云控/遥测组件、COTA 参数和温控基础服务状态。
5. **可开关日志**：用户需要时打开；日志包括 App 启动、Boot 流程、关机安全解冻、冻结命令和错误；支持抓取系统启动 logcat。
6. **UI 重设计**：参考 FinaSU/KernelSU 的 Material3 卡片、状态标签、底部导航和等宽日志风格，重排 Nemu 的信息层级。
7. **安全优先**：任何自动冻结都必须有“稳定延迟 + 上次未干净关机则跳过”的 bootloop 守卫。

## 2. 非目标

- 不修改分区、boot/vbmeta、vendor、内核 thermal zone。
- 不卸载系统应用。
- 不把 `powergenie` / `iaware` / vendor HAL 加入冻结目标。
- 不在 boot 阶段冻结红色 AI 基础服务，除非用户显式开启“AI 深度冻结”并逐项选择。
- 不承诺 100% 防 bootloop；强制断电、系统崩溃、`adb reboot` 仍可能跳过关机解冻。

## 3. 当前已知事实

- 正常关机/重启会触发 `SafetyService` 的运行时 `ACTION_SHUTDOWN`/`ACTION_REBOOT` receiver；root 后端已实测可用。
- `adb reboot` 不发送 `ACTION_SHUTDOWN`，不能用于安全解冻验收。
- HONOR 的 `HwMtmBroadcastResourceManager` 会以 `don't meet cpuload` 拦截 Nemu 的 `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`USER_UNLOCKED`；因此用 FinaSU 的 `service.d` 脚本启动 Nemu 是可靠路径。
- Android suspend 记录 `suspendingPackage`；Nemu 只能清除同一后端/包施加的 suspend。跨工具（Hail/adb shell）的 suspend 需原工具清除。
- 门店/用户此前卸载 OUC + 全量 dex2oat + root 模块导致崩溃；不能把慢启动或崩溃简单归因于 OUC。

## 4. 整体运行流程

### 4.1 开机（正常情况）

```text
FinaSU service.d/99nemu.sh
  -> 先循环 pm unsuspend 全部安全目标（不等 sys.boot_completed）
       // 防止残留 suspend 让 boot_completed 永远不到
  -> 等待 sys.boot_completed=1
  -> am start Nemu MainActivity
  -> 启动完成后再做一次 pm unsuspend
  -> MainActivity.onCreate:
       SafetyService.start()                 // 先武装关机解冻
       PersistentLogger.startup()            // 日志开启时记录启动
       BootFreezeScheduler.ensureScheduled() // 120s 后尝试自动冻结
  -> BootFreezeWorker (120s 延迟):
       BackendManager.get() (root 优先)
       SafetyUnfreezer: 先清除全部安全目标的残留 suspend
       BootFreezePolicy.shouldRun()
       FreezeController.apply(coreEnabled + aiEnabled, clearCache=false)
       记录 last_boot_freeze_* 和日志
```

### 4.2 关机/重启

```text
电源菜单 / svc power reboot
  -> ACTION_SHUTDOWN
  -> SafetyService 运行时 receiver / ShutdownReceiver
  -> getForShutdown() (root 优先)
  -> pm unsuspend --user 0 <SafetyTargets.suspendPackageNames>
  -> 写 last_shutdown_result / last_shutdown_detail
```

### 4.3 用户手动操作

- “立即冻结”：取消本 boot 的自动冻结任务，立即应用当前策略。
- “全部恢复”：取消本 boot 的自动冻结任务，立即 unsuspend 全部目标，并写 `boot_freeze_skipped_boot_count`，本次开机不再自动冻结。
- 关闭“开机自动冻结”开关：取消任务并持久关闭。

## 5. 开机冻结策略

### 5.1 设置键

使用独立的 `data/BootFreezeSettings.kt`（同一 `nemu` SharedPreferences，独立 key，避免与核心 `Settings` 冲突）：

| key | 默认 | 含义 |
|---|---|---|
| `boot_freeze_enabled` | `true` | 总开关 |
| `boot_freeze_delay_seconds` | `120` | 开机后延迟；最短 60s |
| `ai_deep_freeze_enabled` | `false` | AI 深度冻结总开关 |
| `ai_target_<pkg>` | `false` | 单个 AI 目标开关 |
| `last_boot_freeze_boot_count` | `-1` | 已尝试/跳过的 boot count（同 boot 不重复） |
| `last_boot_freeze_attempt_boot_count` | `-1` | 最近一次真正开始执行冻结的 boot count |
| `last_boot_freeze_attempt_time` | `0` | 最近一次真正开始执行冻结的墙钟毫秒（**执行前**写入） |
| `last_boot_freeze_time` | `0` | 最近一次完成的墙钟毫秒 |
| `last_boot_freeze_result` | `null` | 人类可读结果 |
| `last_boot_freeze_detail` | `null` | 后端/命令/输出摘要 |
| `last_boot_freeze_succeeded` | `false` | 最近一次是否成功（仅 UI/诊断） |
| `boot_freeze_skipped_boot_count` | `-1` | 用户手动恢复后跳过本次 boot |

`Settings` 增加 `lastShutdownTime`（与 `last_shutdown_result` 一起用 `commit` 写入），用于判断“冻结后是否发生过干净关机”。

### 5.2 Boot count

- 使用 `Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)`。
- 若读取失败，用 `SystemClock.elapsedRealtime()` 的粗略分区（`elapsed / 1000 / 3600`）作为 fallback，并记录日志。

### 5.3 `BootFreezePolicy.shouldRun`

纯函数，输入：

- `bootFreezeEnabled`
- `bootCount`
- `lastSkippedBootCount`
- `lastFreezeBootCount`
- `lastAttemptBootCount`
- `lastAttemptTime`
- `lastFreezeSucceeded`
- `lastFreezeTime`
- `lastShutdownTime`
- `lastShutdownOk`

规则（全部满足才运行）：

1. 总开关开启；
2. `bootCount != lastFreezeBootCount`（本 boot 未尝试/未跳过）；
3. `bootCount != lastSkippedBootCount`（用户没有手动恢复/跳过）；
4. **Bootloop 守卫**：如果 `lastAttemptBootCount == bootCount - 1` 且
   `lastShutdownOk == true && lastShutdownTime >= lastAttemptTime` 不成立，
   则 **跳过本次自动冻结**，记录“上次冻结尝试后未检测到干净关机”；无论上次
   `lastFreezeSucceeded` 是什么，都不能在可能由冻结引起异常后的下一次开机继续冻结；
5. 没有任何“上次冻结尝试后未干净关机”的迹象。

`lastFreezeSucceeded == false` 且上次没有尝试记录（例如后端不可用、目标为空）时，允许下一次 boot 重试。

安全解冻不能只看 `pm unsuspend` 的 exit code：如果 suspend 由另一个
`suspendingPackage`（例如 Shizuku/shell）施加，而关机时用 root 解冻，命令仍会
exit 0 并输出 `new suspended state: true`。`SafetyUnfreezer` 会把输出中的
`true` 视为失败，使 `lastShutdownResult` 不为“成功”，守卫按未干净关机处理。

**开机恢复优先于开机冻结**：`BootFreezeWorker` 在评估策略之前，先用 root
`pm unsuspend` 全部安全目标，清除强制断电/崩溃遗留的 suspend；清理失败则跳过本次
自动冻结并记录“残留冻结未清除”。这一步属于安全路径，**即使 dry-run 开启也会执行**。
因此即使上次没有干净关机，守卫跳过新冻结时，残留 suspend 仍会被清除。
Worker 在真正执行命令**之前**写 `lastAttemptBootCount` / `lastAttemptTime`，再开始冻结；这样即使冻结过程中系统崩溃，下一次 boot 也能识别。
手动“立即冻结”在有目标且非 dry-run 时同样先取消调度并写 attempt 记录，再执行命令；
否则手动冻结导致的崩溃会绕过守卫。dry-run 不写 attempt、不取消自动冻结。

### 5.4 延迟与稳定性

- WorkManager `OneTimeWorkRequest`，`initialDelay = boot_freeze_delay_seconds`，唯一工作名 `nemu_boot_freeze`，`ExistingWorkPolicy.REPLACE`。
  （不能用 `KEEP`：上一 boot 遗留的 `ENQUEUED` 工作会让新 boot 的入队被静默忽略；`hasWorkForBoot`
  已保证当前 boot 的活跃工作不会重复入队，`REPLACE` 只会取消过期工作。）
- 最短 60s，默认 120s；给 HONOR 的启动清理/dexopt/系统服务稳定留时间。
- Worker 运行前再检查 `sys.boot_completed=1`、`bootCount` 未变化；否则放弃。
- 若用户已在本 boot 手动恢复，Worker 直接返回成功并记录“已跳过”。

### 5.5 冻结命令面

- 核心目标：`Targets.all` 中 `Settings.isEnabled(pkg) == true` 的项。
- AI 目标：`ai_deep_freeze_enabled == true` 且 `ai_target_<pkg> == true` 的项。
- 核心目标：复制为 `target.copy(clearCache = false)` 后交给 `FreezeController.apply`；开机只做 `suspend + force-stop`，不做 `pm clear --cache-only`，减少启动期 IO/风险。手动“立即冻结”保留 clear cache。
- AI 目标：`AiFreezeController` 独立执行 `suspend + force-stop`，不经过 `FreezeController` 的 `DenyList` 通用拒绝；`AiFreezeController` 只接受 `AiTargets` 白名单。
- 目标为空时不执行，记录“没有启用的目标”。
- 不调用 `uninstall`/`disable-user`；AI 目标也不使用 disable。

### 5.6 Boot 失败/回退

- 后端为空：记录失败，不重试无限循环；用户下次打开 App 可手动应用。
- `FreezeController` 单目标失败：继续其他目标，结果记录失败项；不因一个失败退出。
- 如果 Worker 在延迟期间被系统杀死：WorkManager 会按策略重试；如果不再运行，用户打开 App 时 `BootFreezeScheduler` 重新检查（同一 boot 最多一次）。
- **绝不**在 boot freeze 失败后自动 reboot 或改分区。

## 6. AI 冻结策略

### 6.1 目标表

| 包 | 标签 | 风险 | 默认 | 说明 |
|---|---|---|---|---|
| `com.hihonor.aipluginengine` | 荣耀 AI · 基础服务 | **红** | 关 | LLM/provider；系统可能绑定。深度冻结可能影响 YOYO/AI 功能 |
| `com.hihonor.assistant` | YOYO 建议基础服务 | **红** | 关 | DisplayCardProvider；桌面/负一屏可能受影响 |
| `com.hihonor.magicvoice` | YOYO 智能体/智慧语音 | **红/黄** | 关 | VoiceInteractionService；不用语音时可尝试 |
| `com.hihonor.voiceengine` | 语音服务 | 黄 | 关 | TTS/ASR；影响其他 App 的语音能力 |
| `com.hihonor.brain` | 智慧决策 | 红/黄 | 关 | 系统管家等可能绑定 |
| `com.hihonor.mageruntime` | MageEngineRuntime | 黄 | 关 | AI 模型运行时；相机/图库 AI 可能受影响 |

现有 `Targets.all` 中的 `yoyoappsug` / `yoyocards` / `suggestion` / `sceneservice` 继续作为核心目标，默认关闭、由用户逐项开启。

### 6.2 安全约束

- AI 深度冻结默认关闭；UI 必须显示红色风险说明和逐项开关。
- 首次开启一个红色 AI 目标时弹出确认框；确认文本包含“可能影响桌面/YOYO/语音/相机 AI，重启后可恢复”。
- AI 目标不加入 `FreezeController` 的通用路径；由 `AiTargets` + `AiFreezeController` 独立控制，避免普通策略误选。`DenyList` 保持不变。
- AI 目标不进入 `CloudController`/`AntiFraudController` 的 disable-user 路径。
- **安全解冻必须包含 AI 目标**：`SafetyTargets.suspendPackageNames` 追加全部 `AiTargets` 包名（无条件，`pm unsuspend` 幂等）；`ShutdownSafety`/`BootWorker`/FinaSU 的 `service.sh` 都要覆盖 AI 目标。否则 AI 开机冻结后关机不会解冻，可能 bootloop。
- 建议用户一次只开一个 AI 目标，正常关机/重启验证一周后再加下一个。

### 6.3 AI 目标检测

- AI 卡片分别显示 `PackageManager.isPackageSuspended`、`FLAG_STOPPED` 和 root 可读的进程表；`force-stop` 返回成功不代表绑定服务不会重新启动进程。
- 检测页把 AI 目标列为独立来源（`FreezeSource.AI`），策略值 = AI 总开关 && 单项开关；
  因此“已开启但未暂停”会显示为不一致。
- 冻结页允许选择一个或多个目标执行“解冻所选”；所选核心/AI 策略会关闭，避免下次开机重新冻结；其他策略不变。Dry run 不修改策略。
- 如果冻结后系统出现 YOYO/桌面异常，用户可解冻该目标或使用“全部恢复”；关机安全解冻仍会兜底。

## 7. 日志设计

### 7.1 目标

- 默认关闭；用户打开后才写文件。
- 包括 App 启动、Boot、关机安全解冻、冻结/恢复命令、后端选择、错误。
- 支持抓取系统启动 logcat（root/Shizuku 可用时）。
- 日志只写 App 私有/外部私有目录，不需要存储权限。

### 7.2 文件与格式

- 目录：`context.getExternalFilesDir(null)/logs/`（不存在时回退 `filesDir/logs/`）。
- 当前文件：`nemu-YYYYMMDD.log`；按天切分，单文件上限 2 MB，最多保留 7 个文件。
- 行格式：

```text
2026-11-21T19:12:33.123+08:00 INFO  [startup] pid=12345 boot=42 version=0.1.0
2026-11-21T19:12:35.001 INFO  [safety] Root | pm unsuspend ... | exit=0
```

- 字段：ISO 时间、级别、tag、消息；命令输出截断 1000 字符。
- 不记录用户数据、账号、文件内容；只记录包名、命令、状态。

### 7.3 开关与生命周期

- `data/LogSettings.kt`：`logging_enabled` 默认 `false`。
- `PersistentLogger.init(context)` 在 `NemuApplication.onCreate` 调用；读取开关。
- `ActionLog.add()` 在开关开启时镜像到 `PersistentLogger`；控制器/安全路径无需改调用点。
- `NemuApplication` 记录启动日志（进程、pid、boot count、版本、后端状态稍后异步补充）。
- `BootReceiver`/`BootWorker`/`SafetyService`/`ShutdownSafety` 记录 Boot/解冻事件。
- MainActivity 启动时记录 `activity_start`，Intent action/extras 只记录白名单字段。
- Logs 页面显示：开关、当前文件路径、大小、最近 100 行、抓取启动 logcat、刷新、清空（需确认）。
- “抓取启动 logcat”：使用 `BackendManager.getRoot() ?: get()` 执行
  `logcat -b all -d -t 3000`，写入 `logs/startup-YYYYMMDD-HHmmss.txt`；失败只记录错误，不影响 UI。

### 7.4 隐私与体积

- 日志目录在 App 外部私有目录，卸载 App 时清除。
- 不自动上传；不集成第三方分析 SDK。
- 清空日志/关闭开关不删除历史文件，用户可在页面手动清空。

## 8. UI 重设计

### 8.1 参考

- FinaSU/KernelSU 的 Material3 风格：`Scaffold` + `TopAppBar` + `NavigationBar`、圆角卡片、状态标签、等宽日志。
- 不复制其业务代码；只借鉴信息层级和视觉语言。

### 8.2 信息架构

底部导航 4 个目的地（避免底部栏拥挤）：

1. **概览**
   - 顶部状态卡：Nemu 标题、root/Shizuku 状态 chip、当前 boot 的安全状态。
   - “开机自动冻结”开关 + 延迟说明；上次 boot freeze 结果。
   - “立即冻结” / “全部恢复” 大按钮（恢复需确认）。
   - 上次关机安全解冻结果。
2. **冻结**
   - 核心、AI、云控/遥测、反诈和 COTA 使用页内分类切换，避免把所有卡片堆成一个长列表。
   - 核心目标卡片包含策略开关和独立恢复选择框；AI 与云控行也可选择恢复。
   - 选中一个或多个目标后显示“解冻所选”；这只处理所选目标，不改变其他目标的冻结状态。
3. **检测**
   - 冻结状态、云控/遥测、温控/COTA、启动/安全状态；全部只读。
4. **设置 / 日志**
   - “偏好设置”和“运行日志”分开切换；日志默认只显示最近几条，可展开。
   - Dry run、后端状态、应用启动管理入口、关于。

### 8.3 视觉约定

- 背景：`MaterialTheme.colorScheme.surfaceContainer`；卡片：`surfaceContainerHigh`/`secondaryContainer`，圆角 20–24dp。
- 状态 chip：Root/Shizuku（绿/蓝）、已冻结（primary）、未冻结（outline）、失败（error）。
- 危险操作：错误色按钮 + 二次确认；AI 红色目标用 errorContainer 提示。
- 日志：`FontFamily.Monospace`，小字号，`LazyColumn` 显示最近行。
- 顶栏标题“Nemu（霜冻）”，副标题显示 boot count/后端；不使用花哨动画。

## 9. 检测设计

### 9.1 冻结检测

- 输入：核心目标、AI 目标；云控目标在独立“云控/遥测”区展示，
  `DetectionSnapshot.freezeStates` 不再重复包含云控行，避免重复计数。
  反诈目标在其独立卡片中展示，其中 `numberidentity` 也纳入统一安全解冻集合。
- 读取：
  - `PackageManager.isPackageSuspended(pkg)`；
  - `PackageManager.getApplicationEnabledSetting(pkg)`；
  - `ApplicationInfo.FLAG_STOPPED`（停止标记，不当作进程是否存活）；
  - root 可读时用 `ps -A -o PID,ARGS` 精确匹配包名及 `pkg:process` 子进程。
- 展示：
  - “策略：已纳入/未纳入”；
  - 挂起、启用状态、STOPPED 标记、实际进程分别展示；
  - 已暂停但仍有进程显示为部分生效，进程表读取失败则明确标为未知；
  - 不一致时用 warning chip（例如策略开启但实际未暂停）。
- 只读，不自动修复。

### 9.2 云控/遥测检测

- 列出 `CloudTargets.all`，显示 action、enabled、suspended、说明。
- 列出已知运营商 DM 三件套与 hiview 三件套；`pushagent` 标记“禁止操作”。
- 云控勾选只是策略；`CloudController` 执行/尝试后写 `cloud_applied_<pkg>`。
  策略已勾选但从未应用时显示“未应用”，不计入“不一致”；只有尝试后实际
  仍不符才显示“不一致”。`detection` 的冻结区不重复渲染云控行。
- 应用后复核 PackageManager 挂起状态和进程表。仍有进程时记为部分结果，说明系统服务可能重新拉起；不循环强杀。
- 不做网络抓包、不读取账号/短信/通话内容。

### 9.3 温控/COTA 检测

- 根权限可用时调用 `CotaController.listFiles()`（只读）：
  - 文件、大小、mode/uid/gid、SHA-256、kind。
  - 不删除、不修改、不备份（备份仍是 COTA 页面显式操作）。
- 另外只读索引 `/data/cota/para` 的子目录文件名与大小，按子系统汇总并最多展示三个样例路径；不读取文件内容，也不把这些嵌套文件加入删除/恢复流程。
- 设备只读检查确认存在 `antifraud/`、`thermal_config/`、`iaware/`、`hiview/` 等配置目录；此前只枚举根目录会漏报这些现存配置。
- 显示 `powergenie` / `iaware` 是否安装/启用；**不提供停止按钮**。
- 显示“未检测到 / 无 root / 读取失败”状态，不把未知当成功。

### 9.4 启动/安全检测

- 显示 `lastShutdownResult` / `lastShutdownDetail`、`lastBootResult` / `lastBootDetail`、`lastBootFreezeResult`。
- 显示 boot count、上次 boot freeze 时间、上次干净关机时间。
- 用于判断“开机冻结是否被 bootloop 守卫跳过”。

## 10. 数据流与文件所有权

| 文件 | 责任 | 修改方 |
|---|---|---|
| `core/AiTargets.kt` | AI 目标表/风险 | Boot/AI agent |
| `policy/AiFreezeController.kt` | AI 独立冻结/恢复 | Boot/AI agent |
| `data/BootFreezeSettings.kt` | boot/AI 设置键 | Boot/AI agent |
| `policy/BootFreezePolicy.kt` | 纯策略判定 | Boot/AI agent |
| `policy/BootFreezeScheduler.kt` | WorkManager 调度 | Boot/AI agent |
| `policy/BootFreezeWorker.kt` | 延迟冻结/守卫 | Boot/AI agent |
| `logging/PersistentLogger.kt` | 文件日志/镜像 | Logging agent |
| `NemuApplication.kt` | 启动日志 | Logging agent |
| `data/LogSettings.kt` | 日志开关 | Logging agent |
| `detection/*` | 只读检测模型/读取 | Detection agent |
| `ui/DetectionScreen.kt` | 检测 UI | Detection agent |
| `ui/DashboardScreen.kt` / `ui/theme/*` | 新布局 | UI agent |
| `MainActivity.kt` / `MainScreen.kt` / `AndroidManifest.xml` | 整合 wiring | 主会话（单一集成任务） |
| `Settings.kt` | 核心设置；只加 `lastShutdownTime` | 主会话 |
| `SafetyTargets.kt` | 追加 AI 目标 | 主会话 |

`FreezeController` 不修改：BootFreezeWorker 通过 `target.copy(clearCache = false)` 复用核心冻结逻辑；AI 用独立控制器。

共享文件不允许并行代理直接改；由主会话统一集成，避免上一轮的覆盖问题。

## 11. 测试编号

在现有 T/CT/AF/COTA 之外新增：

| 编号 | 内容 | 执行 |
|---|---|---|
| `BF1` | root 后端 + 手动冻结/恢复 | 用户 |
| `BF2` | 关机安全解冻（root） | 用户 |
| `BF3` | 开机自动冻结（120s 延迟，核心目标） | 用户 |
| `BF4` | bootloop 守卫：上次冻结后强制重启，下一次跳过自动冻结 | 用户 |
| `BF5` | AI 深度冻结（逐项，一次一个） | 用户 |
| `LOG1` | 日志开关关闭时不写文件 | 代码级/用户 |
| `LOG2` | 开启后记录启动、Boot、安全解冻、冻结命令 | 用户 |
| `LOG3` | 抓取启动 logcat | 用户 |
| `DET1` | 冻结状态检测（策略 vs 实际） | 用户 |
| `DET2` | 云控/遥测检测 | 用户 |
| `DET3` | COTA 只读检测 | 用户 |
| `UI1` | 新布局在手机上的可读性/导航 | 用户 |

## 12. 失败与回滚

- 关闭 `boot_freeze_enabled` 并点“全部恢复”；取消 `nemu_boot_freeze` 工作。
- 关闭 AI 深度冻结；`FreezeController.restore` 或“全部恢复”unsuspend。
- 如果开机冻结导致异常：下一次 boot 的守卫应跳过自动冻结；用户可用 FinaSU 模块/打开 Nemu 手动恢复。
- 如果日志导致崩溃（不应发生）：关闭日志开关；删除日志目录不影响策略。
- 不提供“删除 COTA 文件”的自动回滚之外的系统级回滚；COTA 仍以备份为准。

## 13. 验收标准

- [ ] 正常关机/重启前，`SafetyService` 用 root 解冻所有 suspend 目标；`last_shutdown_detail` 后端为 `Root`。
- [ ] 开机 120s 后，已启用的核心目标自动冻结；UI/日志可见；不冻结未启用目标。
- [ ] 上次冻结后未干净关机时，下一次开机跳过自动冻结并记录原因。
- [ ] AI 深度冻结默认关闭；开启后只冻结用户勾选的目标；`powergenie`/`iaware` 永不进入目标（2026-11-22 更新：iaware 现支持 COMPONENT，见 `docs/10`）。
- [ ] 日志默认关闭；开启后启动/Boot/安全解冻/冻结均有记录；关闭后不新增文件。
- [ ] 检测页只读展示冻结/云控/温控/COTA 状态，不提供危险操作。
- [ ] UI 使用底部导航/卡片/状态 chip；日志等宽；主要操作不超过两层。
- [ ] 所有真机写入由用户确认；`adb reboot` 不作为安全解冻验收。
