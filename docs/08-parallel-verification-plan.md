# 08 开机冻结 / 日志 / 检测 / UI 并行开发与验证计划

> 目标：把 `docs/07-boot-freeze-logging-ui-design.md` 拆成可并行的实现与独立验证任务。
> 所有真机写入、FinaSU 模块安装、重启由用户确认/执行；代理只写本地代码/文档，不碰分区。

## 1. 依赖与顺序

```text
设计文档（07/08）
  -> 共享 plumbing（主会话）
       Settings.lastShutdownTime
       FreezeController.clearCache 参数
       MainActivity/MainScreen 的接口约定
  -> 并行模块（独立 worktree）
       A. Boot/AI 冻结
       B. 日志
       C. 检测
       D. UI 布局
  -> 主会话集成（单次修改 MainActivity/MainScreen/Manifest）
  -> 构建 + 独立代理审查 + 真机验收
```

共享文件（只允许主会话修改）：

- `app/src/main/java/com/nemu/hibernate/MainActivity.kt`
- `app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt`（主会话适配 `DashboardScreen`）
- `app/src/main/java/com/nemu/hibernate/data/Settings.kt`（只加 `lastShutdownTime` 等核心字段）
- `app/src/main/java/com/nemu/hibernate/policy/SafetyTargets.kt`（追加 AI 目标）
- `app/src/main/AndroidManifest.xml`（`NemuApplication`、FileProvider、权限）
- `app/build.gradle.kts`（只有确实需要新依赖时；优先不加依赖）

## 2. 并行任务

### Agent A：Boot/AI 冻结

**文件所有权**

- Create: `core/AiTargets.kt`
- Create: `policy/AiFreezeController.kt`
- Create: `data/BootFreezeSettings.kt`
- Create: `policy/BootFreezePolicy.kt`
- Create: `policy/BootFreezeScheduler.kt`
- Create: `policy/BootFreezeWorker.kt`
- Test: `policy/BootFreezePolicyTest.kt`
- Test: `policy/AiFreezeControllerTest.kt`

**交付**

- AI 目标表/风险/默认关闭。
- `AiFreezeController`：只接受 `AiTargets` 白名单；`suspend + force-stop`；`restore` unsuspend；不经过通用 `DenyList` 拒绝。
- 纯策略 `BootFreezePolicy.shouldRun`，覆盖：首次 boot、上次成功+干净关机、上次成功+未干净关机、上次失败、用户手动跳过、boot count 变化。
- `BootFreezeScheduler`：唯一工作、延迟、REPLACE（避免过期 ENQUEUED 工作阻止新 boot 调度）、取消接口。
- `BootFreezeWorker`：root 优先；核心目标 `copy(clearCache=false)` 走 `FreezeController`；AI 目标走 `AiFreezeController`；记录 `last_boot_freeze_*`、日志钩子。
- 不修改共享文件；`SafetyTargets`/`Settings` 的 AI/时间字段由主会话集成。

**禁止**

- 不调用 `pm uninstall`/`pm disable-user`。
- 不冻结 `powergenie`/`iaware`/vendor HAL。
- 不在 Worker 里自动 reboot。

### Agent B：日志

**文件所有权**

- Create: `logging/PersistentLogger.kt`
- Create: `logging/LogFile.kt`（可选，文件轮转）
- Create: `NemuApplication.kt`
- Create: `data/LogSettings.kt`
- Test: `logging/PersistentLoggerTest.kt`（JVM 可测部分，文件用临时目录）

**交付**

- 日志开关（默认关）、按天文件、2 MB 上限、7 文件保留。
- `ActionLog.add` 镜像到文件（通过接口，避免循环依赖）。
- 启动日志：Application onCreate、MainActivity onCreate、BootReceiver/BootWorker/SafetyService 的事件钩子。
- 抓取启动 logcat：通过 `BackendManager` 执行 `logcat -b all -d -t 3000`，写 `startup-*.txt`。
- 日志页面所需的状态/路径接口（UI 由主会话/UI agent 接）。

**禁止**

- 不记录账号、短信、通话内容、文件内容。
- 不上传网络。
- 不在日志关闭时写文件。

### Agent C：检测

**文件所有权**

- Create: `detection/DetectionModels.kt`
- Create: `detection/DetectionReader.kt`
- Create: `ui/DetectionScreen.kt`
- Test: `detection/DetectionReaderTest.kt`（纯解析/映射）

**交付**

- 冻结检测：策略 vs 实际 `suspended/enabled`。
- 云控/遥测检测：`CloudTargets` + 已知 DM/hiview。
- COTA 只读检测：复用 `CotaController.listFiles()`，无删除/备份。
- 温控基础服务检测：只显示 powergenie/iaware 安装/启用状态，无操作按钮。
- 启动/安全状态：读取 `last_*` 设置。
- UI 组件只读，不提供危险操作。

**禁止**

- 不调用 `CotaController.delete/backup/restore`。
- 不停止/冻结 powergenie/iaware。
- 不读取用户数据。

### Agent D：UI 布局

**文件所有权**

- Create: `ui/DashboardScreen.kt`
- Create: `ui/OverviewScreen.kt` / `ui/FreezeScreen.kt` / `ui/SettingsScreen.kt`（可按需拆分）
- Create: `ui/theme/NemuTheme.kt` / `ui/theme/Color.kt`（替换现有简单 Theme）
- **不要修改** `ui/MainScreen.kt`；主会话用 `DashboardScreen` 替换 `MainScreen` 的布局并接线。
- Test: 无 JVM UI 测试；用编译 + 截图/真机检查

**交付**

- 底部导航（概览/冻结/检测/设置）或等价分层；参考 FinaSU/KSU 的 Material3 卡片/状态 chip/等宽日志。
- 保留现有所有回调/状态；新布局不能改变业务语义。
- 为 BootFreeze/Logging/Detection 预留 slot 参数；主会话负责接线。
- 适配 1272x2800 屏幕和手机宽度；不横向滚动。

**禁止**

- 不复制 FinaSU/KSU 业务代码；只参考布局语言。
- 不修改控制器/策略/安全路径。
- 不引入大型 UI 依赖（优先 Compose Material3 现有依赖）。

### Agent E：独立验收（集成后）

**角色**：只读审查 + 设备测试，不写生产代码。

- 审查 07 的安全不变量是否被代码实现。
- 检查 `MainActivity`/`MainScreen` wiring、日志开关、检测只读性。
- 在用户授权下执行 BF1–BF5、LOG1–LOG3、DET1–DET3、UI1。
- 报告格式：`通过/失败/未测` + 证据（命令输出、截图、日志）。
- 不把未执行项目写成通过。

## 3. 文件冲突规则

- 并行代理不得修改 §1 共享文件；如需接口变更，先在报告中提出，由主会话统一改。
- 每个代理只提交自己的新文件/测试；不运行 `git checkout`/`git merge`/`git rebase`。
- 主会话负责合并 worktree、跑全量测试、构建 APK、安装前确认。
- 若发现共享文件必须改，停止并回报，不要自行覆盖。

## 4. 验证矩阵

| 维度 | 验证者 | 方法 | 通过标准 |
|---|---|---|---|
| 功能 | Agent A + 主会话 | 单元测试 + 真机 BF1–BF5 | 根后端解冻、开机冻结、守卫生效 |
| 布局 | Agent D + Agent E | 编译 + 真机截图/导航 | 无溢出/无遮挡，主要操作≤2层 |
| 温控/云控检测 | Agent C + Agent E | DET2/DET3 只读检查 | 状态与 dumpsys/文件系统一致，无写操作 |
| 冻结检测 | Agent C + Agent E | DET1 | 策略 vs 实际状态可见，不一致有 warning |
| 日志 | Agent B + Agent E | LOG1–LOG3 | 默认不写；开启后含启动/Boot/安全解冻；关闭后停止 |
| 安全 | Agent E | 代码审查 + 强制重启测试 | 上次未干净关机时跳过自动冻结；无 uninstall/disable 越界 |
| 回归 | 主会话 | 全量单元测试 + 现有 T1–T10/CT/AF/COTA | 105+ 测试通过，现有功能不退化 |

## 5. 真机测试顺序（用户执行）

1. 安装新 APK（debug）。
2. FinaSU 给 Nemu root（已授权则跳过）。
3. 安装 `D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`；重启；确认 `SafetyService` 自动运行。
4. BF1：手动冻结/恢复（root）。
5. BF2：Nemu 自己 suspend OUC → 正常关机/`svc power reboot` → 开机验证 `suspended=false`，`last_shutdown_detail` 后端 Root。
6. BF3：重启 → 等待 120s → 验证已启用核心目标自动 suspend。
7. BF4：BF3 后强制重启（或 `adb reboot`）→ 下一次 boot 应跳过自动冻结并记录原因。
8. BF5：逐个开启 AI 目标，重复 BF3/BF2；出现异常立即全部恢复并关闭该目标。
9. LOG1–LOG3：开关、启动日志、logcat 抓取。
10. DET1–DET3：检测页与实际 `dumpsys`/`/data/cota/para` 对比。
11. UI1：截图/导航检查。

## 6. 交付物

- 代码合并到本地 `main`。
- 新 APK + SHA-256。
- `docs/07`、`docs/08`、更新后的 `README`/`03`/`04`。
- 日志路径/抓取步骤。
- 真机测试结果表（未执行写“未测”）。

## 7. 当前状态

- 设计文档：本文件与 `07` 已完成。
- 并行实现：Agent A（Boot/AI）、B（日志）、C（检测）、D（UI）均已完成并合并到本地 `main`；主会话完成 Dashboard/Boot/AI/日志/检测接线。
- 单元测试：178 个，0 失败（18 个测试类）。
- 根权限：FinaSU 已授权；`last_shutdown_detail` 实测后端为 `Root`。
- 真机结果：
  - Root 后端关机安全解冻：通过。
  - 核心开机自动冻结（root，60/120s 延迟）：通过。
  - AI 深度冻结单目标（`com.hihonor.mageruntime`）：通过；正常重启后安全解冻为 false。
  - 持久日志：通过，`nemu-*.log` 包含 startup/boot/freeze/safety。
  - BF4 bootloop 守卫：通过（boot=33 冻结后 `adb reboot`，boot=34 记录“未检测到干净关机，已跳过”且未再冻结）。
  - 检测页、UI 视觉：待用户解锁后检查；单元测试已覆盖检测模型。
- 共享文件（Settings/SafetyTargets/MainActivity/MainScreen/Dashboard）由主会话统一修改，避免并行覆盖。
- 2026-11-21 收尾：检测页新增 `FreezeSource.AI`（策略 = AI 总开关 && 单项开关）；
  AI 红色目标开启前新增确认框；代码已通过构建与 178 个单元测试。
- 独立审查修复：`BootFreezeScheduler` 改用 `REPLACE`（旧 boot 的 ENQUEUED 工作不再阻塞新调度）；
  手动“立即冻结”先写 `recordAttempt` 再执行命令；`SafetyUnfreezer` 把输出中的
  `new suspended state: true` 判为失败（防跨 suspendingPackage 假成功）；
  “全部恢复”先标记本 boot 跳过再解冻，并覆盖 hiview/numberidentity；
  开机结果 chip 区分成功/跳过/无目标/恢复；日志页过滤 `nemu-*.log` 并在进程重启后显示文件尾部；
  COTA 三按钮改为纵向排列；检测页增加 AI 行与守卫排序。
