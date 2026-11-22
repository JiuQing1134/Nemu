# 06 模块并行开发与集成计划

> 目标：云控/遥测、反诈、COTA 温控三个模块并行准备，但共享文件只由一个集成任务修改，避免并行冲突。
> 所有真机写入必须由用户执行；子代理只写文档/代码，不碰手机。

## 1. 并行任务状态

| 模块 | 计划文档 | 状态 |
|---|---|---|
| 云控 / 遥测 | `docs/superpowers/plans/2026-11-21-nemu-cloud-telemetry.md` | 计划已完成，待一致性审查 |
| 反诈 | `docs/superpowers/plans/2026-11-21-nemu-antifraud.md` | 计划已完成，待一致性审查 |
| COTA / 温控 | `docs/superpowers/plans/2026-11-21-nemu-cota-thermal.md` | 计划已完成，待一致性审查 |
| OUC 卸载崩溃根因 | `docs/05-ouc-crash-analysis.md` | 子代理分析中 |

三份模块计划的代码片段是**实现参考**；共享文件以本文件的集成规则和最终集成任务为准。计划里对共享文件的“整文件替换”片段（例如 Cloud Task 3 与 Anti-fraud Task 4 都替换 `ShutdownSafety.kt`，COTA Task 4 整文件替换 `MainActivity.kt`）**不得逐字照搬**，否则后合并的模块会覆盖先合并的模块。

## 2. 共享文件与冲突规则

以下文件**不允许**三个模块各自并行修改；由一个集成任务统一改：

- `app/src/main/java/com/nemu/hibernate/data/Settings.kt` — 各模块开关/选择状态集中添加。
- `app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt` — 只负责组合 `CloudSection` / `AntiFraudSection` / `CotaSection`，每个 section 独立文件。
- `app/src/main/java/com/nemu/hibernate/MainActivity.kt` — 统一收集模块状态、回调。
- `app/src/main/java/com/nemu/hibernate/privilege/BackendManager.kt` — 统一添加 `getRoot()` 等能力。
- `app/src/main/java/com/nemu/hibernate/core/CommandBuilder.kt` / `ShellQuote.kt` — 先合并共享引用层。
- `app/src/main/java/com/nemu/hibernate/policy/SafetyUnfreezer.kt`、`ShutdownSafety.kt`、`BootWorker.kt`、`SafetyTargets.kt` — 统一安全解冻集合，见 §2.1。
- `app/src/main/AndroidManifest.xml` — 统一添加三个模块的 `<queries>` 包名。

每个模块的**新文件**（controller、command builder、parser、section、settings、tests）可以并行实现：

- 云控：`core/CloudTarget.kt`、`core/CloudTargets.kt`、`core/CloudStatus.kt`、`data/CloudSettingsStore.kt`、`data/CloudSettings.kt`、`policy/CloudController.kt`、`ui/CloudSection.kt`、`privilege/PackageStatusReader.kt`、测试。
- 反诈：`core/AntiFraud.kt`、`core/AntiFraudPolicy.kt`、`data/AntiFraudSettings.kt`、`policy/AntiFraudController.kt`、`ui/AntiFraudSection.kt`、测试。
- COTA：`cota/CotaModels.kt`、`CotaKnownFiles.kt`、`CotaPaths.kt`、`CotaCommandBuilder.kt`、`CotaParsers.kt`、`CotaController.kt`、`ui/CotaSection.kt`、测试。

### 2.1 统一安全解冻集合（关键决议）

三个模块对“关机/重启安全解冻”的诉求必须合并为一个集合，禁止各写各的：

```kotlin
object SafetyTargets {
    val suspendPackageNames: List<String> =
        (Targets.all.map { it.packageName } +
            CloudTargets.suspendPackageNames +
            AntiFraudPolicy.safetyPackages())
            .distinct()
}
```

其中：

- `Targets.all` — 核心更新/AI 冻结目标。
- `CloudTargets.suspendPackageNames` — 只包含 hiview 三件套
  （`com.hihonor.hiview`、`com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel`）。
- `AntiFraudPolicy.safetyPackages()` — 只包含 `com.hihonor.numberidentity`
  （它可能被本模块 `suspend`；`securityserver` / `awareness` 只做 AppOps/standby 限制，
  从不 suspend，因此不进安全集合）。
- 运营商 DM 三件套是 `pm disable-user`，不是 `pm suspend`；关机安全路径**不**执行
  `pm enable`，它们保持停用直到用户点“恢复全部云控”。这是云控模块的明确设计，
  与“suspend 不跨正常重启”的规则不冲突。
- `SafetyUnfreezer.unsuspendPackages(backend, packages)` 是唯一安全解冻入口；
  `unsuspendAll(backend, targets)` 保留并委托给它。
- `ShutdownSafety`、`BootWorker` 都只调用 `SafetyUnfreezer.unsuspendPackages(
  backend, SafetyTargets.suspendPackageNames)`；不再各自拼 `Targets.all + ...`。

### 2.2 统一 UI 集成

三个 section 都是独立 Composable，`MainScreen` 只增加参数并组合：

- `CloudUiState` + `CloudSection`
- `AntiFraudUiState` + `AntiFraudSection`
- COTA 的 `CotaUiState` + `CotaSection`

`MainActivity` 统一持有：

- `Settings`、`CloudSettings`、`AntiFraudSettings`、`BackendManager`、`PackageStatusReader`。
- 一个 `busy` 状态供三个模块共用；每个模块的回调各自 `scope.launch`，结束时复位。
- `LaunchedEffect(Unit)` 一次性加载 core/cloud/anti-fraud/COTA 状态。
- 三个 section 的插入顺序：核心目标卡片 → 云控 → 反诈 → COTA → 日志。
- 顶栏文案改为：“核心冻结只 suspend，不卸载系统应用；云控区的运营商 DM 三件套是可逆 disable-user 例外。”

`AndroidManifest.xml` 的 `<queries>` 统一补：

```xml
<package android:name="com.hihonor.hiview" />
<package android:name="com.hihonor.hiviewpush" />
<package android:name="com.hihonor.hiviewtunnel" />
<package android:name="com.hihonor.cipservice" />
<package android:name="com.hihonor.numberidentity" />
<package android:name="com.hihonor.securityserver" />
<package android:name="com.hihonor.awareness" />
<package android:name="com.hihonor.securitypluginbase" />
```

（`com.hihonor.regservice`、`UEInfoCheck`、`AutoRegSms` 已在 manifest 中。）

### 2.3 测试编号

核心 T1–T10 已执行；三个新模块不得复用 `T11–T16`，否则测试计划会互相覆盖。统一使用模块前缀：

| 模块 | 编号 | 说明 |
|---|---|---|
| 云控 / 遥测 | `CT1–CT5` | 云控计划已使用 |
| 反诈 | `AF1–AF6` | 对应原计划的 T11–T16 |
| COTA / 温控 | `COTA1–COTA6` | 对应原计划的 T11–T16 |

实施时把反诈计划中的 `T11–T16` 替换为 `AF1–AF6`，把 COTA 计划中的 `T11–T16` 替换为 `COTA1–COTA6`；`docs/04-test-plan.md` 最终只保留这一套编号。

## 3. 实施顺序

1. **审查崩溃根因报告**：确认 OUC 依赖是否会改变云控/反诈/COTA 的范围与风险。
2. **合并共享引用层**（串行，主工作区）：
   - `ShellQuote`、`RootPrivilegeBackend`、`RootBackend` 实现 marker、
     `BackendManager.getRoot()`、`Settings.cotaEnabled`。
   - 对应 COTA 计划 Task 1；先跑全量单元测试，确认 `CommandBuilderTest` 不变。
3. **并行实现模块核心**（各自独立文件 + 单元测试，使用 worktree 或互不重叠的文件）：
   - 云控：Cloud Task 1–2。
   - 反诈：Anti-fraud Task 1–3（其中 `DenyList` 只由该任务修改）。
   - COTA：COTA Task 2–3（依赖第 2 步的 `ShellQuote` / `RootPrivilegeBackend`）。
   - 并行代理不得修改 §2 列出的共享文件，不得运行 `git commit`/`git checkout`/`git merge`；
     完成后由主会话统一合并、跑测试、审查。
4. **单一集成任务**（主工作区，串行）：
   - 新建 `policy/SafetyTargets.kt`；统一改 `SafetyUnfreezer`/`ShutdownSafety`/`BootWorker`。
   - 统一改 `MainScreen.kt`/`MainActivity.kt`/`AndroidManifest.xml`，接入三个 section。
   - 合并三套单元测试，跑全量 `:app:testDebugUnitTest :app:assembleDebug`。
5. **构建 + 代码审查 + 用户真机测试**：每个模块单独验收；设备写入由用户确认。
   顺序：CT（云控）→ AF（反诈）→ COTA（温控，root-only，最后做）。
6. **更新文档**：README、`03-design.md`、`04-test-plan.md`、`02-package-inventory.md`、
   `06-module-integration-plan.md`、根因报告。

## 4. 文档要求

- 每个模块计划必须包含：目标/非目标、精确文件路径、接口、代码片段、TDD 步骤、UI 集成点、风险确认、回滚、真机测试步骤。
- 测试记录必须区分 `通过` / `失败` / `未测`；未执行的真机项目不得写成通过。
- 崩溃分析必须区分证据、假设、红鲱鱼（例如 dex2oat/Alipay tombstone）与未知项。
- 所有模块必须遵守：不依赖 Hail、不 `pm uninstall` 系统应用、红色基础服务不整包冻结、不要在未确认风险时删除 COTA 文件。
- 任何共享文件冲突以本文件 §2 为准；计划片段与本文件冲突时，以本文件为准。

## 5. 当前进度

- 2026-11-21：云控、反诈、COTA 三份计划已返回并统一裁决（`7c1d51d`）。
- 实施分支：`feature/nemu-modules`（从 `main` 的 `7c1d51d` 拉出）。
- 已完成并合并：
  - 共享 plumbing：`549f558`（`ShellQuote`、`RootPrivilegeBackend`、`getRoot`、`Settings.cotaEnabled`）。
  - 云控核心：`a05be1e` → 合并 `b74df16`（Cloud Task 1–2）。
  - 反诈核心：`765d738`/`2c40fdb`/`8d89e25` → 合并 `d56a0f1`（Anti-fraud Task 1–3）。
  - COTA 核心：`61c0f7b`/`7af81f2` → 合并 `b55bc38`（COTA Task 2–3）。
  - 统一安全解冻：`4b90689`（`SafetyTargets`）。
  - 三模块 UI 集成：`bc3e977`（CloudSection/AntiFraudSection/CotaSection + MainScreen/MainActivity/manifest）。
- 构建与测试：`178` 个单元测试全部通过（18 个测试类）；`assembleDebug` 成功，APK `32,564,940` 字节，SHA-256 `7233b93e935409e8405a233f7b2fadc6405487d39d5dfc033824a95e6f411dd3`。
- OUC 崩溃根因报告已完成：`docs/05-ouc-crash-analysis.md`；现有日志不支持“OUC 卸载导致 system_server 崩溃”，但未做危险复现。
- 代码审查（2026-11-21）：未发现会导致崩溃、错误命令、绕过守卫或跨正常重启残留冻结的 Important 缺陷。修复了 4 项 Minor（反诈取消勾选后的恢复、失败时不保存配置、COTA 关闭时刷新竞态、云控 dry-run 结果标注）和 2 项安全加固（COTA 删除/恢复前校验备份副本；关机后端回退 + root 3s 短超时）。
- 真机状态：三个模块的所有写入测试仍为 `未测`；手机未进行新的设备写入，OUC 仍 `suspended=true`，`SafetyService` 前台运行。
- 下一步：用户在真机上按 CT1–CT5 → AF1–AF6 → COTA1–COTA6 的顺序验收；每次只做一个模块，先 dry-run，再执行，正常电源菜单/`svc power reboot` 重启验证安全解冻。
