# Nemu 项目总览与审计指南

> 项目名：**Nemu（眠梦）**；应用 applicationId `com.nemu.dream`（源码命名空间 `com.nemu.hibernate`）；模块 ID
> `nemu_boot_autostart`；根目录 `D:\win_root\nemu`。2026-11-22 完成彻底改名，
> 旧包名、旧模块和旧数据已清理。
> 文档日期：2026-11-21（2026-11-22 增补冻结/解冻分页、多策略、改名与 UI 重设计）
> **2026-11-22 当前状态**：`versionCode 9` / `1.3.3`；256 个 JVM 测试、
> 30 个测试类、0 失败；统一目录 41 个包 / 64 个组件；模块 v1.8（33 个安全解冻目标）。
> 下文出现的 190 测试 / 19 类 / 15 个安全目标 / v1.2 等是历史基线，保留用于审计。
> **参考项目**：Hail（雹）— https://github.com/aistra0528/Hail（只参考 suspend 机制与公开文档）
> **项目地址（本地）**：`D:\win_root\nemu`（尚未配置远程仓库）
> 适用设备：HONOR AAP-AN00，MagicOS 10.0.0.102，Android 16

本文档是 Nemu 的**项目地图**：代码在哪里、哪些文件有用、开发流程、技术栈、
核心逻辑、构建测试方法、已知问题、审计入口。历史设计细节仍在 `docs/` 下。

---

## 1. 项目目的与非目标

### 1.1 目的

Nemu 用 Android 原生的 **package suspension**（`pm suspend`）冻结系统更新、
可选 AI 服务和可选云控组件，阻止 Activity 启动/用户交互并配合 `force-stop`
尽量停掉当前进程；suspend **不能保证后台 Service/Provider/Broadcast 永不
重启**，这是 Android 机制限制。所有操作可逆，**不卸载系统应用**。

核心目标：

- 冻结系统更新 `com.hihonor.ouc`，阻止自动更新；
- 可选冻结 AI 叶子包与 AI 基础服务（默认关闭，逐项选择）；
- 可选限制运营商 DM/遥测、反诈、COTA 温控参数（默认关闭）；
- **安全解冻始终开启**：正常关机/重启前解冻全部 suspend 目标；
- 开机延迟自动重新冻结（默认 120s，最低 60s），带 bootloop 守卫；
- 只读检测页展示“策略 vs 实际”、云控/温控/COTA 状态；
- 可开关持久日志（含启动日志）。

### 1.2 明确非目标

- **不卸载**任何系统应用；不删除系统分区文件；
- **不修改 ABL/EFISP/boot/分区**；本项目与 `D:\win_root` 下 hnubl/EFISP/GhostLock
  研究是**不同工作流**，Nemu 不读取也不写入那些镜像；
- 不依赖 Hail（雹）：只参考其 suspend 机制，不调用其 API/Intent/数据；
- 不冻结绝对禁止清单（`DenyList`）中的基础服务；
- 不绕过 Android 安全模型（不 remount /system、不改 SELinux）；
- 不提供网络功能，不上传日志或设备数据。

> ABL 硬约束（来自 `AGENTS.md`）：如果另一个工作流涉及 ABL，只能用本机原厂
> `D:\win_root\efisp_pc_generated\stock_pair\abl_stock_a.img`
> （4,194,304 字节，SHA-256 `9ff5114c22fe42762f2b364fdae34b755273feb7698fe4c1342a686e221e7c25`）；
> 禁止内置 hnubl ABL。Nemu 构建不需要 ABL，不涉及刷写。

---

## 2. 工作区地图：哪些是软件代码，哪些不是

`D:\win_root` 是多个研究/工具共用的工作区，**不是**一个单一项目。Nemu 的代码
和构建文件只在：

```text
D:\win_root\nemu\
```

### 2.1 属于 Nemu 的目录/文件

| 路径 | 说明 |
|---|---|
| `D:\win_root\nemu\app\` | Android 应用源码、资源、单元测试、构建产物 |
| `D:\win_root\nemu\docs\` | 设计、测试、崩溃分析、并行开发计划 |
| `D:\win_root\nemu\tools\nemu-boot-autostart\` | FinaSU/KernelSU 自启动模块源码和 zip |
| `D:\win_root\nemu\gradle\`、`gradlew*`、`*.gradle.kts` | Gradle 构建 |
| `D:\win_root\nemu\README.md` | 快速使用说明 |
| `D:\win_root\nemu\PROJECT.md` | 本文件：项目总览与审计指南 |
| `D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk` | 当前 debug APK（生成物，不入 git） |

### 2.2 属于参考资料/其他研究、不要混入 Nemu 构建的目录

| 路径 | 性质 |
|---|---|
| `D:\win_root\refs\kernelsu` | KernelSU 源码参考（只读） |
| `D:\win_root\refs\finasu` | FinaSU 源码参考（只读） |
| `D:\win_root\research\` | Hail / OUC 崩溃 / Shizuku 等研究材料（只读） |
| `D:\win_root\ghostlock-*`、`ghostlock-app` | GhostLock 临时 root 研究，不属于 Nemu |
| `D:\win_root\hnubl-*`、`honor_8e5_ubl*`、`efisp*`、`uefi_log*` | ABL/EFISP 研究，不属于 Nemu |
| `D:\win_root\phone_backup`、`update_sd_base` | 设备备份/研究数据 |
| `D:\win_root\*.txt`、`*.xml`、`*.img`、`*.apk`（根目录） | 调查日志、UI dump、APK 副本等；不是 Nemu 源码 |

清理时**不要**删除 ABL/EFISP/GhostLock 研究材料；它们与 Nemu 无关，但可能是
其他工作流的证据。Nemu 的 `git status` 只覆盖 `D:\win_root\nemu`。

### 2.3 已清理的并行工作树

并行开发时创建的 `nemu-wt-boot`、`nemu-wt-detect`、`nemu-wt-log`、
`nemu-wt-ui` 四个 git worktree 已确认全部合并、工作区干净，现已删除；
对应分支 `feature/boot-freeze`、`feature/detection`、`feature/logging`、
`feature/ui-layout` 也已删除。主分支为 `main`。

---

## 3. 技术栈与开发语言

| 项 | 值 |
|---|---|
| 语言 | Kotlin（主代码 + JVM 单元测试） |
| UI | Jetpack Compose + Material3（BOM `2025.12.01`） |
| 最低/目标 SDK | minSdk 30 / targetSdk 36；compileSdk 37.2 |
| 构建 | Gradle 9.7.1、Android Gradle Plugin 9.1.0、Kotlin Compose 插件 2.4.20 |
| Java/JVM | JDK 21（`jvmToolchain(21)`） |
| 异步/调度 | Kotlin Coroutines 1.10.2、WorkManager 2.11.0 |
| 权限后端 | Root（FinaSU/KernelSU）优先，Shizuku 回退（API 13.1.5） |
| 测试 | JUnit 4，JVM 单元测试；无 Robolectric/仪器化 UI 测试 |
| applicationId | `com.nemu.dream`（源码命名空间 `com.nemu.hibernate`） |
| 版本 | `versionName 1.3.3`，`versionCode 9`（debug 构建） |

构建前提：Windows + JDK 21 + Android SDK（compileSdk 37.2）；`local.properties`
指向本机 SDK。构建不需要 ABL、不需要设备 root，单元测试在 JVM 运行。

---

## 4. 开发流程（从需求到真机验收）

Nemu 采用“先文档、后代码；并行模块、单点集成”的流程：

1. **设备调研**：`docs/01-device-and-findings.md`、`docs/02-package-inventory.md`
   记录机型、包清单、风险分级、HONOR 限制。
2. **设计与风险分析**：`docs/03-design.md`、`docs/05-ouc-crash-analysis.md`。
3. **拆分并行任务**：`docs/06-module-integration-plan.md`、
   `docs/08-parallel-verification-plan.md`；每个 agent 只拥有独立文件，
   共享文件（`MainActivity`、`Settings`、`SafetyTargets`、Manifest）由主会话统一改。
4. **TDD/单元测试**：纯策略/命令 builder/解析器优先做成无 Android 依赖的
   Kotlin 对象，JVM 测试覆盖；控制器用 fake backend 测试命令面。
5. **集成构建**：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug`。
6. **独立审查**：功能、UI、检测、设备状态分别由只读 agent 审查；审查发现
   记录在 `docs/04-test-plan.md` 和 `docs/08`。
7. **真机验收**：用户明确授权后安装 APK/模块；写入命令由 Nemu 以 root 执行；
   分区/ABL/`fastboot` 永不触碰。
8. **文档回写**：测试结果、APK/模块哈希、限制、回滚步骤写回 README/04/07/08。

### 4.1 并行开发的工作树

历史 worktree 已清理。若未来再并行开发：

- 使用 git worktree/隔离目录，避免共享文件冲突；
- 不允许多个 agent 同时改 `MainActivity`/`Settings`/`SafetyTargets`/Manifest；
- 合并前跑全量 `testDebugUnitTest`；
- 代码合并后再由主会话更新文档和 APK 哈希。

---

## 5. 核心代码逻辑

### 5.1 权限后端

```text
MainActivity / Worker
  -> BackendManager(settings).get()
       -> RootBackend（su / /system/bin/su / /data/adb/ksu/bin/su）
       -> ShizukuBackend（回退）
  -> PrivilegeBackend.exec(command) -> ShellResult(exitCode, output)
```

- `privilege/PrivilegeBackend.kt`：统一接口。
- `RootBackend.kt`：root shell；带超时、候选 su 路径、失败回退。
- `ShizukuBackend.kt`：Shizuku `newProcess`；shell 权限。
- `BackendManager.kt`：root 优先；关机路径使用短超时（root 3s）。
- 跨 `suspendingPackage` 限制：Nemu 冻结和 Nemu 解冻必须用同一后端；
  被 Hail/adb shell 冻结的包，Nemu 的 `pm unsuspend` 可能输出
  `new suspended state: true`，安全解冻会把它判为失败。

### 5.2 目标与命令面

- `core/Targets.kt`：核心 5 个目标（OUC + 4 个绿色 AI 叶子包），默认启用。
- `core/AiTargets.kt`：AI 深度冻结 6 个目标（含红色高风险），默认全关；
  只有 `AiFreezeController` 能操作。
- `core/CloudTargets.kt`：运营商 DM 三件套（`disable-user` 唯一例外）、
  hiview 三件套（suspend）、`cipservice` 只读。
- `core/AntiFraud.kt` / `AntiFraudPolicy.kt`：`numberidentity` 可 suspend/disable；
  `securityserver` / `awareness` 只做 AppOps/standby，不整包冻结。
- `core/DenyList.kt`：绝对禁止整包冻结的基础服务。
- `core/CommandBuilder.kt` / `ShellQuote.kt`：所有 shell 命令集中构建、单引号转义；
  **不提供 uninstall 命令**。
- `core/Target.kt`：目标元数据（risk、defaultEnabled、suspend、forceStop、clearCache）。

### 5.3 冻结/恢复控制器

- `policy/FreezeController.kt`：核心目标；只处理启用且不在 `DenyList` 的目标；
  `apply` = `pm suspend` → `am force-stop` → 可选 `pm clear --cache-only`；
  `restore` = `pm unsuspend`。
- `policy/AiFreezeController.kt`：只接受 `AiTargets` 白名单；只发
  `pm suspend`/`am force-stop`/`pm unsuspend`；忽略 clearCache；不 disable/uninstall。
- `policy/CloudController.kt`：DM 用 `disable-user`，遥测用 suspend；只接受白名单。
- `policy/AntiFraudController.kt`：numberidentity + AppOps/standby 限制。
- `cota/CotaController.kt`：root-only，只读列表/备份/显式删除/恢复；
  不触碰 powergenie/iaware/vendor HAL/内核 thermal zone。

### 5.4 安全解冻（始终开启）

```text
正常关机/重启
  -> ACTION_SHUTDOWN / ACTION_REBOOT
  -> SafetyService 运行时 receiver（主路径；前台服务）
     或 ShutdownReceiver（Manifest 后备）
  -> ShutdownSafety.runBlockingUnfreeze()（最多 8s）
  -> BackendManager.getForShutdown()（root 优先，root 3s 超时）
  -> SafetyUnfreezer.runUnsuspendPackages(SafetyTargets.suspendPackageNames)
  -> 写 last_shutdown_result / last_shutdown_time / last_shutdown_detail
```

- `SafetyTargets.suspendPackageNames` = 核心 5 + AI 6 + hiview 3 + numberidentity
  + 运营商 DM 3 + 新目录 suspend 目标 = **33 个**（DM 也支持 SUSPEND；
  disable-user 恢复仍只按所有权基线）。
- `SafetyUnfreezer` 检查输出里的 `new suspended state: true`；有则判失败，
  避免“exit 0 但没解冻”的假成功。
- 正常关机时间戳在解冻**之前**写入；并发冻结 attempts 会被守卫判为可疑。

### 5.5 开机自动冻结与 bootloop 守卫

```text
BootFreezeScheduler.ensureScheduled()
  -> 唯一工作 nemu_boot_freeze，延迟 120s（最低 60s），REPLACE
  -> BootFreezeWorker
       SafetyService.start()
       拒绝上一个 boot 的过期任务（inputData.scheduled_boot_count）
       BackendManager.get()（root 优先）
       检查 sys.boot_completed=1
       SafetyUnfreezer 先清除全部 15 目标的残留 suspend（安全优先，忽略 dry-run）
       BootFreezePolicy.shouldRun()
       recordAttempt()（在命令之前，持久化）
       FreezeController(核心, clearCache=false)
       AiFreezeController(AI 勾选项)
       记录 last_boot_freeze_*
```

`BootFreezePolicy` 规则（全部满足才冻结）：

1. 总开关开；
2. 本 boot 未处理过（`lastFreezeBootCount`）；
3. 用户未手动跳过本 boot；
4. 本 boot 未尝试过；
5. 紧邻的上一个 boot 若尝试过冻结，之后必须有干净关机（`lastShutdownOk` 且
   `lastShutdownTime >= lastAttemptTime`）；否则跳过。

`BootFreezeWorker` 先解冻再评估守卫：即使守卫跳过新冻结，上次强制断电/崩溃遗留的
suspend 也会被清除；清理失败则跳过冻结。`REPLACE` 防止上一 boot 的 ENQUEUED
任务阻塞新调度。

### 5.6 自启动模块（FinaSU/KernelSU）

`tools/nemu-boot-autostart/service.sh`：

1. **先**循环 `pm unsuspend` 全部 33 个目标（不等 `sys.boot_completed`，
   避免残留 suspend 导致启动卡死）；
2. 等待 `sys.boot_completed=1`；
3. `am start com.nemu.dream/com.nemu.hibernate.MainActivity` 启动 SafetyService 并调度冻结；
4. 启动完成后再解冻一次。

模块 zip：`tools\nemu-boot-autostart\nemu-boot-autostart.zip`。
安装后需重启一次激活（从 `/data/adb/modules_update` 生效）。

### 5.7 日志

- `data/ActionLog.kt`：内存环形日志（最近 200 条），同时镜像到文件日志。
- `logging/PersistentLogger.kt`：默认关闭；开启后写
  `/sdcard/Android/data/com.nemu.dream/files/logs/nemu-YYYYMMDD.log`；
  单文件 2MB、最多 7 个；可抓取 `logcat -b all -d -t 3000` 到 `startup-*.txt`。
- `NemuApplication.onCreate`：初始化日志并记录 `[startup] pid/boot/version/process`。
- UI 设置页可开关日志、刷新、清空、抓取 logcat；进程重启后显示文件尾部。

### 5.8 只读检测

- `detection/DetectionReader.kt`：读取 PackageManager + `PackageStatusReader`；
  核心、AI、云控策略 vs 实际；COTA 根文件与子目录索引只读；powergenie/iaware 只观察。
- `detection/DetectionModels.kt`：`FreezeState`/`CloudState`/`CotaState`/`ThermalState`/
  `BootSafetyState`；mismatch 只在已安装、可读、策略与实际的布尔值不同时置位。
- UI 的检测 Tab 只读，不提供修复/冻结按钮。

### 5.9 UI

- `MainActivity.kt`：持有 Settings、BackendManager、状态；读取主题/授权状态，
  调度 BootFreezeWorker，并在每次刷新后按“是否有冻结目标”同步 SafetyService。
- `ui/DashboardScreen.kt`：Scaffold + 5 Tab（概览/冻结/解冻/检测/设置）；
  概览含当前授权模式、现在是什么状态、授权卡、开机冻结、安全结果；冻结页含
  直白的“立即冻结”按钮与逐目标后果确认；解冻页只做恢复；检测页只读；
  设置页含外观（三风格/五配色/明暗）、日志与高级控制。
- `ui/NemuScreens.kt`：冻结/解冻页的卡片、按钮、后果弹窗与状态解释。
- `ui/theme/ThemeModels.kt`、`ColorPalettes.kt`、`NemuTheme.kt`：三套风格 +
  五套配色；深色背景为纯黑 OLED。
- `privilege/PrivilegeOverview.kt`：Root/Shizuku/ADB 授权模式与请求文案。
- `privilege/PackageSnapshotReader.kt`：用 `dumpsys package` 读取 hidden/suspended
  等真实状态（HIDE 的唯一权威校验）。
- 参考 FinaSU/KernelSU 的布局语言，不复制其业务代码。

---

## 6. 代码树

```text
D:\win_root\nemu\
├─ app\
│  ├─ build.gradle.kts
│  └─ src\
│     ├─ main\
│     │  ├─ AndroidManifest.xml
│     │  ├─ java\com\nemu\hibernate\
│     │  │  ├─ NemuApplication.kt          # 进程入口：日志初始化/启动日志
│     │  │  ├─ MainActivity.kt              # Compose 状态、全部回调、权限/调度接线
│     │  │  ├─ core\                        # 纯 Kotlin 目标/命令/风险模型
│     │  │  │  ├─ Targets.kt / Target.kt
│     │  │  │  ├─ AiTargets.kt
│     │  │  │  ├─ CloudTarget.kt / CloudTargets.kt / CloudStatus.kt
│     │  │  │  ├─ AntiFraud.kt / AntiFraudPolicy.kt
│     │  │  │  ├─ CommandBuilder.kt / ShellQuote.kt / ShellResult.kt
│     │  │  │  └─ DenyList.kt
│     │  │  ├─ cota\                        # COTA 路径/解析/命令/控制器
│     │  │  ├─ data\                        # SharedPreferences 设置与 ActionLog
│     │  │  ├─ detection\                   # 只读检测模型/读取
│     │  │  ├─ logging\                     # 持久日志/轮转/logcat
│     │  │  ├─ policy\                      # 冻结/云控/反诈/安全解冻/开机 Worker
│     │  │  ├─ privilege\                   # Root/Shizuku/BackendManager/授权模式/dumpsys 状态
│     │  │  └─ ui\                          # Dashboard + 冻结/解冻页 + 主题（三风格/五配色）+ Section
│     │  └─ res\                            # 图标/字符串/主题资源
│     └─ test\java\com\nemu\hibernate\       # 30 个 JVM 测试类，256 个测试
├─ docs\
│  ├─ 01-device-and-findings.md             # 设备/发现
│  ├─ 02-package-inventory.md               # 包清单/风险
│  ├─ 03-design.md                          # 总体设计
│  ├─ 04-test-plan.md                       # 测试/验收/回滚
│  ├─ 05-ouc-crash-analysis.md              # OUC 崩溃根因分析
│  ├─ 06-module-integration-plan.md         # 三模块并行集成
│  ├─ 07-boot-freeze-logging-ui-design.md   # 开机冻结/AI/日志/检测/UI 设计
│  ├─ 08-parallel-verification-plan.md      # 并行验证计划/结果
│  ├─ 09-auto-unbrick-design.md             # （由自动救砖设计代理生成）
│  └─ superpowers\plans\                    # 历史实现计划（保留绝对路径，仅供审计）
├─ tools\
│  └─ nemu-boot-autostart\
│     ├─ module.prop                        # v1.8 / auto-unbrick
│     ├─ service.sh                         # 安装 guard、救援检查、启动 Nemu
│     ├─ post-fs-data.sh                    # 计数/阈值/写 disable
│     ├─ install_guard.sh                   # 把 guard 安装到 /data/adb
│     ├─ boot_guard.sh                      # 独立救援 guard（3 次阈值）
│     └─ nemu-boot-autostart.zip           # 可安装模块
├─ gradle\wrapper\gradle-wrapper.properties
├─ build.gradle.kts / settings.gradle.kts / gradle.properties
├─ gradlew / gradlew.bat
├─ README.md                                # 快速使用
├─ PROJECT.md                               # 本文件
└─ .gitignore
```

> `app/build/`、`.gradle/`、`.kotlin/`、`local.properties`、`.superpowers/` 是
> 生成/本机文件，已在 `.gitignore` 中忽略，不属于代码审计范围。

### 6.1 主要源文件职责速查

| 文件 | 职责 |
|---|---|
| `policy/FreezeController.kt` | 核心目标 suspend/force-stop/clear/unsuspend |
| `policy/AiFreezeController.kt` | AI 白名单独立冻结，拒绝 clear/disable |
| `policy/CloudController.kt` | DM disable-user / 遥测 suspend |
| `policy/AntiFraudController.kt` | numberidentity + AppOps/standby |
| `policy/SafetyService.kt` | 前台服务 + 运行时关机广播 |
| `policy/ShutdownSafety.kt` | 关机同步解冻（8s 窗口） |
| `policy/SafetyUnfreezer.kt` | 批量 `pm unsuspend` + 假成功检测 |
| `policy/SafetyTargets.kt` | 33 个安全解冻目标 |
| `policy/BootFreezeScheduler.kt` | WorkManager 唯一任务/REPLACE/延迟 |
| `policy/BootFreezeWorker.kt` | 开机恢复→守卫→冻结→记录 |
| `policy/BootFreezePolicy.kt` | 纯 Kotlin bootloop 守卫 |
| `policy/BootGuard.kt` | 读取/清除自动救砖 rescue flag（root） |
| `policy/BootWorker.kt` | 开机安全解冻 worker |
| `data/Settings.kt` | 核心开关/dry-run/后端/last_shutdown |
| `data/BootFreezeSettings.kt` | 开机/AI 设置键、attempt/result 持久化 |
| `logging/PersistentLogger.kt` | 文件轮转、启动日志、logcat 抓取 |
| `detection/DetectionReader.kt` | 只读状态采集 |
| `ui/DashboardScreen.kt` | 五 Tab 主界面 |
| `MainActivity.kt` | 状态/回调/调度接线（共享文件，改动需谨慎） |

---

## 7. 构建、测试、安装

### 7.1 构建与测试

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

当前基线（2026-11-22）：

- 256 个单元测试，0 失败 / 0 错误 / 0 跳过，30 个测试类；
- APK：`D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`
  - 33,375,281 字节
  - SHA-256 `811c36d6a59a6321613f43a35dc8eb0588a5b7ec4776a2d2b364e4a5af105961`
- 模块：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  - 10,043 字节
  - SHA-256 `e3320a28c9c1de004f782af6a94ee1258f5b402a45d17002be2d77cfa59dd89f`

### 7.2 安装到设备

```powershell
adb install -r D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk
```

FinaSU 模块：在 FinaSU 模块管理器中安装
`tools\nemu-boot-autostart\nemu-boot-autostart.zip`，重启一次激活。
或把 `service.sh` 放到 `/data/adb/service.d/99nemu.sh`（权限 755）。

### 7.3 日志与诊断

```text
/sdcard/Android/data/com.nemu.dream/files/logs/nemu-YYYYMMDD.log
/sdcard/Android/data/com.nemu.dream/files/logs/startup-*.txt
```

设置页可打开日志开关；root/Shizuku 可用时可抓取系统 logcat。

---

## 8. 已知问题与限制（审计重点）

1. **HONOR 拦截开机广播**：`HwMtmBroadcastResourceManager` 会以
   `don't meet cpuload` 拦截从停止状态拉起 Nemu 的 `BOOT_COMPLETED`/
   `LOCKED_BOOT_COMPLETED`。可靠路径是 FinaSU 模块；否则重启后要手动打开 Nemu。
2. **强制断电/崩溃无法在关机瞬间解冻**：兜底是“下次开机早解冻”：
   FinaSU 脚本先解冻再等 `boot_completed`；`BootFreezeWorker` 冻结前先清残留。
   若模块未装、应用未拉起、残留又是红色 AI 包，仍可能带病启动；无法覆盖
   内核/早期 system_server 崩溃。
3. **跨 `suspendingPackage`**：被 Hail/adb shell 冻结的包，Nemu 的 root
   `pm unsuspend` 可能清不掉；安全解冻现在会把输出 `new suspended state: true`
   判为失败（fail-closed），但无法跨工具清除。
4. **红色 AI 目标风险 + suspend 不是后台完全停止**：
   `aipluginengine`/`assistant`/`magicvoice`/`brain` 等可能影响 YOYO/桌面/语音/
   相机 AI。默认关闭；建议一次只开一个。当前设备 6 个 AI 目标均已启用并随
   boot=35 冻结；只读审计发现 5/6 的进程仍被 Honor Service/Provider/Broadcast
   重新拉起（`stopped=false`）。suspend 只阻止 Activity 启动，不阻止后台绑定；
   检测页现在把“suspended=true 但 stopped=false”显示为“已暂停（未强停）”，
   并给出警告，不再显示为完全“已冻结”。
5. **策略 vs 实际不一致（已定位并修复 UI 语义）**：用户看到的多数“不一致”来自
   云控六项策略已勾选、但从未点“立即应用云控”，实际仍是未冻结/未停用。
   旧检测把“策略已勾选但未应用”误报为 mismatch，且云控行在冻结区和云控区
   重复出现。新版本改为：
   - `CloudController` 尝试执行后写 `cloud_applied_<pkg>`；
   - 只勾选、未应用 → 显示“未应用”，不计 mismatch；
   - 执行后实际仍不符 → 显示“不一致”；
   - `freezeStates` 只含核心/AI，云控只在云控区显示，避免重复计数。
   升级后需要重新点一次“立即应用云控”才会把六项策略真正执行。若仍显示
   “不一致”，才是命令/跨工具/suspendingPackage 的真实问题。
   另外，启动时的检测快照早于 120s 开机冻结，进入检测页/应用冻结/云控操作后
   现在会自动刷新；旧快照不再作为长期状态展示。
6. **WorkManager 120s 是最小延迟**：Doze/后台限制可能让实际执行晚于 120s；
   boot=35 实测约 122s。
7. **COTA 温控是 root-only 且高风险**：删除厂商参数可能影响降频/充电；必须
   备份+校验；不保证性能提升；系统可能重新下发。COTA 真机写入默认未执行。
8. **只提供 debug APK**：debug keystore 签名、`versionCode=2`；不是 release
   分发构建。UI 没有仪器化/截图自动化测试，视觉验收靠真机。
9. **HONOR 可能杀前台服务**：SafetyService 是 START_STICKY/FGS，但
   PowerGenie 仍可能冻结/杀死；关机解冻不是 100%。
10. **`adb reboot` 不触发关机解冻**：正常电源菜单/`svc power reboot` 才会
    发送 ACTION_SHUTDOWN；不要用 adb reboot 验收关机解冻。
11. **反诈/云控的实际状态展示不完整**：检测页覆盖核心/AI/云控；反诈的
    actual 状态在其独立卡片中较弱，属于后续改进项。
12. **模块版本 1.5**：按 boot id 计数、逐包解冻、按原始状态恢复组件与
    disable-user；升级需重装 zip 并重启。
13. **APK 更新不再解冻全部目标（已修复）**：旧 `BootReceiver` 把
    `MY_PACKAGE_REPLACED` 当成开机，触发 `BootWorker` 解冻安全目标；
    安装新 APK 后所有冻结会静默失效，检测页随即显示大量 mismatch。
    现在 APK 更新只重新启动 `SafetyService`，不执行开机解冻；真正的
    boot/unlock 才执行。
14. **自动救砖已实现并完成正常重启验收（PF16）**：设计见 `docs/09-auto-unbrick-design.md`。
    模块 v1.8 会把独立 `boot_guard.sh` 安装到
    `/data/adb/nemu/boot_guard/`，并注册 `/data/adb/post-fs-data.d` 与
    `/data/adb/service.d` 包装脚本。连续 **3** 次启动未确认成功时：
    - 写 `rescue.flag`；
    - 给 `nemu_boot_autostart` 写 `disable`；
    - **解冻所有 dumpsys 中 suspended 的包**（动态列表，不限于 33 个），
      并 `pm enable` Nemu 的 disable-user 目标；
    - 之后每次 boot 由独立 guard 继续解冻，模块不再自动启动 Nemu。
    App 侧 `BootGuard` 在开机冻结/手动冻结前检查 rescue flag；Dashboard 显示
    救援卡片，用户确认后可清除 flag/disable（下次 boot 恢复模块）。
    已知边界：`post-fs-data` 不能调用 `pm`；system_server 早期起不来时只能
    safe mode/recovery/fastboot。阈值 3、稳定窗口 600s 可能把连续快速重启误判，
    但救援动作是解冻+禁用，不是破坏性操作。

---

## 9. 代码审计指南

### 9.1 首先确认边界

```powershell
cd D:\win_root\nemu
# 不应出现 uninstall 命令
rg -n "pm uninstall|uninstall" app\src\main\java
# disable-user 只应出现在 CloudController / AntiFraudController 白名单路径
rg -n "disable-user" app\src\main\java
# 不应有 ABL/efisp/fastboot/分区写入代码
rg -n "fastboot|efisp|abl_|set_active|mkfs|dd if=" app\src\main\java
# 不应依赖 Hail
rg -n "hail|Hail" app\src\main\java app\build.gradle.kts
```

预期：无 `pm uninstall`；`disable-user` 只在云控 DM 和反诈 numberidentity 的
白名单控制器中；无 ABL/fastboot/分区代码；无 Hail 依赖。

### 9.2 关键不变量

- `DenyList` 中的包不能被 `FreezeController` 整包冻结；
- `AiFreezeController` 只接受 `AiTargets`，只发 suspend/force-stop/unsuspend；
- `SafetyTargets` 必须包含全部可能被 suspend 的目标（33 个）；
- 关机安全解冻不能被 `dry-run` 关闭；
- `BootFreezeWorker` 必须先 `recordAttempt` 再发冻结命令；
- bootloop 守卫只在上次尝试后的干净关机存在时允许再次自动冻结；
- COTA 不删除无备份/校验失败的文件；不碰 powergenie/iaware/vendor HAL。

### 9.3 建议阅读顺序

1. `README.md` → 本文件第 1/5/8 节；
2. `docs/03-design.md` → 总体设计；
3. `policy/` 下控制器与安全路径；
4. `core/` 目标/命令/DenyList；
5. `docs/04-test-plan.md` → 真机证据与未测项；
6. `docs/05-ouc-crash-analysis.md` → 为什么不能卸载 OUC；
7. `docs/07`/`08` → 开机冻结/日志/检测/UI 的设计与验证。

### 9.4 绝对路径审计

- **代码/Gradle/Manifest/脚本**中没有硬编码的 `D:\win_root` 绝对路径；
  构建不依赖工作区绝对路径。
- 绝对路径只出现在**文档/历史计划**中（例如 `docs/superpowers/plans/*`
  与 `docs/05` 的证据路径）。这些路径用于追溯证据，不参与编译。
- 本次整理后模块 zip 的规范路径为
  `D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`；
  README/docs 中的旧根目录路径已同步更新。
- 如需再次移动目录，先运行：
  ```powershell
  rg -n "D:\\\\win_root" app gradle tools *.kts
  ```
  确认代码无命中；再更新文档引用。编译验证仍以
  `gradlew :app:testDebugUnitTest :app:assembleDebug` 为准。

---

## 10. 产物与记录

| 产物 | 路径 | 备注 |
|---|---|---|
| Debug APK | `app\build\outputs\apk\debug\app-debug.apk` | 33,375,281 B，SHA-256 `811c36d6...`（1.3.3 / code 9） |
| FinaSU 模块 | `tools\nemu-boot-autostart\nemu-boot-autostart.zip` | 10,043 B，SHA-256 `e3320a28...` |
| 模块源码 | `tools\nemu-boot-autostart\service.sh` | 先解冻/启动/补解冻 |
| 设计 | `docs/03`、`docs/07` | 总体/新功能 |
| 测试 | `docs/04` | 真机结果、未测项、回滚 |
| 崩溃分析 | `docs/05` | OUC 卸载序列 |
| 并行计划 | `docs/06`、`docs/08` | 历史计划/验证 |
| 自动救砖设计/实现 | `docs/09-auto-unbrick-design.md` | module v1.8/App BootGuard；PF16 正常重启验收 |

当前设备状态（2026-11-21 ADB root 只读确认，boot=37）：

- FinaSU 模块 v1.3（自动救砖）已安装；只读执行 `/data/adb/nemu/boot_guard/boot_guard.sh status` 返回 “No such file or directory”，已从脚本 CRLF shebang 确认无法直接执行。修复后的本地 v1.4 包未部署。
- boot=36 时观察到：模块 v1.2 未自动拉起 Nemu，因此 v1.3 增加了
  `dumpsys`/`pm` 超时和 guard 外层超时；仍需重启后验证。
- 设备上安装的 APK 是 `31b77cd1...`（检测修复版）；包含
  `BootGuard` 救援卡片的最终 APK `7233b93e...` **尚未安装到设备**。
- 之前已确认：模块 v1.1 可自动拉起 Nemu；120s 后核心 5 + AI 6 suspend 成功；
  检测页曾显示核心/AI 0 不一致、云控 6 项“未应用”。
- 2026-11-21 ADB root 只读快照：核心 5 个目标 + AI 深度 6 个目标，共 11 个目标 `suspended=true`；hiview 三件套未 suspend。DM 三件套为 `enabled=3`（用户停用）。numberidentity、securityserver、awareness、securitypluginbase、PowerGenie、iAware 均安装且启用；设备没有 `disabled_by_nemu` 标记目录，不能把现有 DM 停用状态归因于新版本 Nemu。`/data/cota/para/antifraud/` 有 12 个反诈/恶意软件 JSON 规则文件，`thermal_config/thermal_config_cloud.xml` 为 452,048 B；这些内容只读索引，未拉取或修改。
- 这次审计没有改变设备包状态、安装 APK/模块或重启；关机/重启测试仍需先解冻所有目标。

---

## 11. 变更记录

- 2026-11-21：实现 Dashboard/开机冻结/AI/日志/检测；178 单测；FinaSU root 实测。
- 2026-11-21：修复 stale WorkManager、手动冻结 attempt、跨 suspendingPackage
  假成功、恢复顺序/覆盖、UI 结果语义、日志文件尾部、COTA 按钮溢出。
- 2026-11-21：FinaSU 模块安装并重启验证；boot=35 自动启动、120s 全量冻结通过。
- 2026-11-21：整理文档与工作区：删除已合并 worktree/旧模块副本，规范模块 zip
  路径，新增本文件与 `docs/README.md`。
- 2026-11-21：检测不一致专项审计并修复：云控“未应用”语义/去重、检测刷新、
  AI“已暂停（未强停）”警告、APK 更新不再解冻；178 单测通过。
- 2026-11-21：自动救砖模块 v1.4 完成本地实现；真机部署与救援验收未执行。
- 2026-11-21：审计修复 package enabled 基线回滚、Nemu 所有权标记、救援退出校验、Shizuku 下 root 救援检测、子目录 COTA 只读索引、模块 hook 覆盖保护及 CRLF 脚本问题；Dashboard 改为冰青冷色主题并加入自绘导航图标。

> 本文件描述的是当前工作树状态；历史设计以 `docs/` 编号文档为准。任何“未测”
> 项不得在审计中当作“通过”。
