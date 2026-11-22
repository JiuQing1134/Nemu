# 03 设计

> 状态：P1/P2 核心 + 安全解冻已完成实机验收；FinaSU root 后端已授权并实测；P3 云控/遥测与反诈、P4 COTA 温控代码完成并通过单元测试，待用户实机验收。
> 2026-11-21 新需求（开机自动冻结、AI 深度冻结、可开关日志、检测页、UI 重设计）的设计见 `docs/07-boot-freeze-logging-ui-design.md`，并行验证计划见 `docs/08-parallel-verification-plan.md`。
> 原则：**只参考 Hail 的机制，不依赖、不调用 Hail。** 不使用 Hail 的包、API、Intent 或数据。

## 1. 问题

- HONOR MagicOS 10 上，直接**卸载/停用**系统更新（`com.hihonor.ouc`）会导致系统崩溃。
- 用户希望冻结系统更新（以及可选的 AI/云控/反诈/温控组件），但不要触发系统崩溃。
- 用户提出的“开机/关机时激活、开机稳定后清缓存再冻结”需要评估并落地。

## 2. 目标与非目标

### 目标

1. 用 **suspend（暂停）** 代替卸载/停用，保留包、保留 Provider/Service 解析，降低崩溃风险。
2. 对目标包执行 **suspend + force-stop + `pm clear --cache-only`** 的组合。
3. 支持 root（KSU）与 Shizuku 两种权限后端，自动检测（Root → Shizuku）；手动切换 UI 推迟到后续增量（`BackendManager` 已支持 `Settings.backend` 值，但核心 UI 不写入）。
4. 关机/重启安全解冻全部目标；开机后保持解冻，直到用户手动重新应用。
5. 提供明确的回滚（Restore all）和操作日志。
6. 对基础服务/AI 基础服务建立硬性禁止清单，防止误冻结。

### 非目标

- 不做 `pm uninstall`；系统应用默认不做 `pm disable-user`。唯一例外是云控模块的运营商 DM 三件套（可逆、逐项确认，见 §7.1）。
- 不修改 `build.prop`、系统版本号、OTA slot 状态。
- 不默认处理反诈/温控/云控（作为高级模块，默认关闭）。
- 不接管 Hail 的 UI/数据；不与 Hail 共存依赖。

## 3. 约束

| 约束 | 影响 |
|---|---|
| root 是 KSU late-load，`adb shell su` 不可用 | root 只在系统启动后通过 KSU 管理器/授权 App 可用；**开机早期无 root** |
| Shizuku server 重启后需重新拉起 | Shizuku 模式无法保证无人值守；thedjchi fork 支持开机自启，但依赖无线调试，需实测 |
| 未设置 Dhizuku/设备所有者 | Dhizuku 模式不是 v1 默认 |
| `suspend` 状态跨重启保留 | 只能在关机前解冻；正常电源菜单/`svc power reboot` 实测有效，`adb reboot` 不触发 |
| `suspend` 不阻止后台运行 | 必须配合 force-stop 和网络/后台限制 |
| 用户执行写入 | 助手只生成代码/文档/脚本；不直接改手机 |
| 构建为 `user`，`ro.debuggable=0` | 不能依赖 debug-only 能力；需走公开/隐藏 API 的合法路径 |

## 4. 核心设计

### 4.1 冻结操作的定义

“冻结”不是单一 API，而是三步：

1. **suspend**：`IPackageManager.setPackagesSuspended` / `pm suspend --user 0`。
   - 持久化；重启后仍生效。
   - 阻止用户启动、通知显示；**不阻止后台运行**。
2. **force-stop**：`ActivityManager.forceStopPackage` / `am force-stop`。
   - 停止当前进程；非持久，系统绑定可能重新拉起。
3. **clear cache**：`pm clear --cache-only`。
   - 清 OTA 下载缓存等；不清用户数据。

“解冻”对应 `unsuspend`。**永远不调用 uninstall；系统应用默认不 disable。** 云控模块的运营商 DM 三件套是唯一的 `disable-user` 例外（见 §7.1）。

### 4.2 权限后端

```kotlin
interface PrivilegeBackend {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun exec(command: String): ShellResult
}
```

> 说明：语义操作（suspend/unsuspend/force-stop/clear-cache）由 `FreezeController` 组合命令完成；`isSuspended`/`isRunning` 状态回读推迟到后续增量。

实现：

| 后端 | 实现 | 能力 | 限制 |
|---|---|---|---|
| `RootBackend` | `su -c` 执行 `pm suspend` / `am force-stop` / `pm clear --cache-only` | 全量 | 需 FinaSU/KSU 授权；开机早期不可用。依次尝试 `su`、`/system/bin/su`、`/data/adb/ksu/bin/su`；本机 FinaSU 包名 `me.yuki.fina` |
| `ShizukuBackend` | Shizuku API + 自建 `UserService`（shell uid） | suspend/unsuspend/force-stop/clear cache | 需 Shizuku server 运行；重启后需重新拉起 |
| `DhizukuBackend`（v2） | DevicePolicyManager.setPackagesSuspended（反射/客户端） | suspend/unsuspend | 不支持 force-stop/clear cache；需设备所有者 |

自动选择顺序：`Root → Shizuku → 提示用户`。UI 显示当前后端与可用状态。

> 已知限制：Shizuku 后端的 `newProcess`、流 getter 与 `destroy` 都是 binder 调用，不在 15s 超时范围内；Shizuku server 卡死时执行仍可能阻塞。

### 4.3 策略与数据

```kotlin
enum class RiskLevel { GREEN, YELLOW, RED }

data class TargetConfig(
    val packageName: String,
    val label: String,
    val enabled: Boolean = false,
    val suspend: Boolean = true,
    val forceStop: Boolean = true,
    val clearCache: Boolean = true,
    val risk: RiskLevel
)

data class AppSettings(
    val preferredBackend: String? = null,
    val dryRun: Boolean = true
)
```

> 实际实现：核心 `Settings` 只保存 backend/dryRun/last* 与核心目标开关；云控/反诈使用独立的 `CloudSettings` / `AntiFraudSettings`（同一 `nemu` SharedPreferences 文件、独立 key 前缀）；COTA 使用 `Settings.cotaEnabled`。安全解冻忽略 dryRun。

默认目标：

- v1 仅启用 `com.hihonor.ouc`（软件更新）。
- 绿色叶子包（`yoyoappsug`、`yoyocards`、`suggestion`）预置但默认关闭，由用户逐项开启。
- 红色包不在可选列表中，或显示为“受保护，不可冻结”。

硬编码禁止清单（示例）：

```
com.hihonor.systemserver
com.hihonor.systemmanager
com.hihonor.devicemanager
com.hihonor.aipluginengine
com.hihonor.assistant
com.hihonor.magicvoice
com.hihonor.brain
com.hihonor.awareness
com.hihonor.securityserver
com.hihonor.powergenie
com.hihonor.iaware
com.hihonor.android.pushagent
```

### 4.4 关机 / 重启安全解冻（始终开启）

目标：**绝不把 suspend 状态带过重启**。

1. `SafetyService` 前台服务在用户打开 App / 应用策略时启动，注册运行时 `ACTION_SHUTDOWN` / `ACTION_REBOOT` receiver。
2. 收到广播 → 在广播窗口内同步执行一次 `pm unsuspend --user 0 <全部目标>`（最多 8s），结果写入 `Settings.lastShutdownResult`。
3. `BOOT_COMPLETED` → `BootWorker` 做 best-effort 安全解冻；**不再自动重新冻结**。
4. 目标保持解冻，直到用户手动点“立即应用”。
5. 实机限制（AAP-AN00 / MagicOS 10）：
   - `adb reboot` 走 init，不发送 `ACTION_SHUTDOWN`；正常电源菜单重启或 `svc power reboot` 才会触发安全解冻。
   - 本机 HONOR 的 `HwMtmBroadcastResourceManager` 会以 `don't meet cpuload` 拦截 Nemu 的 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `USER_UNLOCKED` 启动；`last_boot_result` 可能延迟写入，但 `SafetyService` 不能可靠自动启动。**重启后必须打开一次 Nemu，或在“应用启动管理”中允许 Nemu 自启动/后台活动**；否则下一次关机的安全解冻不会运行。App 顶部提供“打开「应用启动管理」允许自启动”按钮。
   - 强制断电、`adb reboot`、系统崩溃可能跳过安全解冻；这不是 100% 的 bootloop 防护。
   - Shizuku/root 必须在关机时仍然可用；前台服务必须在运行（打开 App 或系统允许自启动后）。
   - **跨工具 suspend**：Android 的 suspend 记录 `suspendingPackage`；Nemu 只能清除同一后端/包施加的 suspend。若目标被 Hail、`adb shell` 或其他工具暂停，Nemu 的 `pm unsuspend` 可能返回 `new suspended state: true`；需用原工具或 adb 清除。`last_shutdown_detail` / `last_boot_detail` 会记录实际命令和输出。
   - **FinaSU 自启动兜底**：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip` 是 KernelSU/FinaSU 模块；`service.sh` **先**循环 `pm unsuspend` 全部安全目标（不等 `sys.boot_completed`，避免残留 suspend 导致启动死锁），再等启动完成、拉起 Nemu `MainActivity`（启动 `SafetyService`），最后补一次解冻。安装/授权由用户执行。

#### 关于 Shizuku / root

- 安全解冻优先用 Shizuku（binder 检查快），其次 root。
- Shizuku server 必须在本机运行；本次实测 `13.7.0-thedjchi` 可用。
- 不依赖 Shizuku 自启；用户打开 App 时服务启动。

### 4.5 安全设计

1. **Never uninstall / never disable（运营商 DM 例外）**：代码中不存在 `pm uninstall`；`pm disable-user` 只出现在 `CloudController`（运营商 DM 三件套，静态白名单 + 逐项确认 + `pm enable` 回滚）与 `AntiFraudPolicy`（仅 `numberidentity`，用户选择）中。`securityserver` / `awareness` 永不整包暂停/停用/强停。
2. **硬性禁止清单**：UI 与后端双重校验，拒绝冻结红色包。
3. **Dry run**：首次运行可只显示将执行的命令，不实际执行。
4. **Restore all**：一键对所有目标 `unsuspend`；如检测到目标被外部工具 disable，提示用户手动 `pm enable`（不自动执行）。
5. **安全解冻（必须）**：见 4.4；`SafetyUnfreezer` 不受 Dry run 影响。安全集合由 `SafetyTargets` 统一维护：核心目标 + hiview 三件套 + `numberidentity`；运营商 DM 三件套是 `disable-user`，安全路径不执行 `pm enable`。因为不再自动重新冻结，不需要 bootloop guard；用户手动重新应用后重启，由关机安全解冻负责恢复。
6. **日志**：每次操作记录时间、包、后端、命令、结果；App 内可查看，导出推迟到 P4。
7. **确认（已实现）**：运营商 DM 三件套停用、COTA 删除/恢复均有二次确认；反诈 `numberidentity` 停用有确认；`securityserver` / `awareness` 只允许 AppOps/standby 限制。

### 4.6 UI

- **首页（已实现）**：后端状态（自动选择 Root/Shizuku）、目标列表与“纳入策略”开关、上次关机/开机安全解冻结果、Dry run、Apply now / Restore all、App 内日志；手动“立即应用”是重新冻结的唯一入口。
- **包详情（推迟到 P4）**：包状态回读（installed/suspended/running）与单项操作（Suspend/Unsuspend、Force stop、Clear cache）；核心版本只提供首页的“全部恢复”。
- **设置（部分实现）**：Dry run 已实现；后端选择 UI、日志导出推迟到 P4；已移除“开机自动应用/延迟”。
- **高级（已实现，默认关闭）**：云控/遥测、反诈、COTA 温控三个独立 section；每个 section 有自己的开关、状态、确认和恢复入口。导入/导出 JSON、禁止清单详情仍推迟到 P4。

### 4.7 技术栈

- Kotlin + Jetpack Compose + Material3。
- `minSdk 30`，`compileSdk 37.2`，`targetSdk 36`。
- Shizuku API/provider（`dev.rikka.shizuku:api`, `dev.rikka.shizuku:provider`）。
- 自建 `su` 封装（未使用 libsu）。
- SharedPreferences（设置）；DataStore + kotlinx.serialization 推迟到 P4。
- WorkManager（延迟/重试）。
- 无 Hail 依赖。

## 5. 目录结构（计划）

```
nemu/
  README.md
  docs/
    01-device-and-findings.md
    02-package-inventory.md
    03-design.md
    04-test-plan.md
  app/
    src/main/java/com/nemu/hibernate/
      NemuApp.kt
      ui/...
      data/...
      privilege/
        PrivilegeBackend.kt
        RootBackend.kt
        ShizukuBackend.kt
        DhizukuBackend.kt (v2)
      policy/
        FreezeController.kt
        DenyList.kt
        BootWorker.kt
        ShutdownReceiver.kt
      util/...
    src/main/AndroidManifest.xml
  build.gradle.kts
  settings.gradle.kts
```

## 6. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| M0 | 项目文档 | 本文档 + 01/02/04 完成 |
| M1 | 工程骨架 + Root/Shizuku 后端 + OUC suspend/unsuspend | 手动 suspend/unsuspend OUC，状态可回读 |
| M2 | force-stop + clear cache + 开机 Worker + 日志 | 重启后延迟执行，日志完整 |
| M3 | 叶子 AI 包 + 禁止清单 + Dry run + Restore all | 绿色包可冻结，红色包被拒绝 |
| M4 | 运营商 DM/遥测 + 反诈 `numberidentity` + AppOps 限制 | 可逆停用；回滚验证 |
| M5 | COTA 备份/清理/恢复（root） | 备份可恢复；风险确认 |
| M6 | 关机安全解冻（已实现）/ Dhizuku / 网络防火墙 | 安全解冻实机通过；Dhizuku/防火墙未开始 |

## 7. 已确认决策（2026-11-21）

- **v1 范围：全套**——系统更新 + AI 叶子包 + 云控/遥测 + 反诈 + 温控（高级模块默认关闭，逐项确认）。
- **权限后端**：Root + Shizuku 双后端；Dhizuku 作为后续可选。
- **交付**：先 debug APK；包名 `com.nemu.hibernate`；签名/发布后置。
- **核心原则**：默认只 `suspend`，绝不 `uninstall`；系统应用不做 `disable`。唯一例外是运营商 DM 三件套的“停用”模式（见 7.1），需单独确认。
- **不依赖 Hail**：不调用 Hail 的 API/Intent/数据。

### 7.1 云控 / 遥测模块

| 目标 | 默认操作 | 实现（2026-11-21） |
|---|---|---|
| `com.hihonor.regservice`（伪装名“系统消息”，实际 CMCC DM） | `disable-user`（可逆） | `CloudController` 逐项确认后执行 `pm disable-user --user 0`；不卸载 |
| `com.hihonor.android.UEInfoCheck` | `disable-user`（可逆） | 同上 |
| `com.hihonor.android.AutoRegSms` | `disable-user`（可逆） | 同上 |
| `com.hihonor.hiview` / `hiviewpush` / `hiviewtunnel` | `suspend` + `force-stop`，默认关闭 | `CloudController` 执行 `pm suspend` + `am force-stop`；纳入 `SafetyTargets` 关机/开机安全解冻 |
| `com.hihonor.cipservice` | 仅展示，默认不动 | `CloudAction.OBSERVE_ONLY`，无执行路径 |
| `com.hihonor.android.pushagent` | 不提供一键操作 | 在 `DenyList` 中，`CloudTargets` 不包含 |

**实现约束（2026-11-21）**：

- 运营商 DM 三件套是云控核心，`suspend` 不能阻止其后台/开机接收器，因此该模块允许 `disable-user`（不是 uninstall），并在 UI 中逐项确认。
- `CloudTargets.disableUserAllowed` 精确等于三件套；控制器对任何其他 `DISABLE_USER` 目标 fail-closed。
- `CloudSettings.isConfirmed` 必须为 true；UI 在打开三件套开关时弹出确认框，取消则不写入。
- 回滚 `CloudController.restoreAll` 对三件套执行 `pm enable --user 0`、对遥测三项执行 `pm unsuspend --user 0`；忽略勾选状态，因此清空 App 数据后仍可回滚；不取消勾选。
- 状态回读只使用 `PackageManager.getApplicationEnabledSetting` 与 `isPackageSuspended`（只读）。
- 设置键：`cloud_target_` + 包名、`cloud_confirmed_` + 包名、`cloud_last_result`；`dry_run` 与核心共享。
- 遥测三项的 suspend 纳入 `SafetyTargets.suspendPackageNames`，正常关机/重启前会 `unsuspend`；三件套的 disable-user 不参与安全解冻，保持禁用直到显式回滚。
- 本模块不执行 `pm uninstall`、不清缓存、不做组件级禁用。

### 7.2 反诈模块

| 目标 | 默认操作 | 风险 |
|---|---|---|
| `com.hihonor.numberidentity`（号码识别） | 可选 `disable-user` / `suspend` | 失去号码识别/诈骗标记 |
| `com.hihonor.securityserver`（安全公共服务） | **不整包停用**；仅提供 `appops RUN_ANY_IN_BACKGROUND ignore` + `standby restricted` | 反诈核心 + 安全服务 |
| `com.hihonor.awareness`（情景感知） | **不整包停用**；仅提供 `appops RUN_ANY_IN_BACKGROUND ignore` + `standby restricted` | 反诈 + 人脸活体/深伪 |
| `com.hihonor.securitypluginbase` | 观察，不提供一键停用 | 关联安全服务 |

说明：`securityserver` / `awareness` 的反诈逻辑与安全/生物识别耦合，组件级禁用或整包停用都可能导致功能异常。v1 只做 `numberidentity` 的可逆停用 + 对两个核心包的后台/待机限制（AppOps/standby），不提供“一键反诈关闭”。如果后续要更彻底，需要独立模块 + 逐项实机验证。

**实现（2026-11-21）**：

- `AntiFraudPolicy` 是命令矩阵的唯一来源：`numberidentity` 允许 `suspend` / `disable-user`；`securityserver` / `awareness` 只允许 `appops RUN_ANY_IN_BACKGROUND ignore|allow` 与 `standby restricted|active`；`securitypluginbase` 无操作。
- `AntiFraudTargets` 不加入 `Targets.all`，`DenyList` 保护全部四个反诈包，防止通用冻结误操作。
- `Securityserver` / `awareness` 的 `appops` 与 `standby` 命令不保证在所有 MagicOS 构建上生效；失败只记录日志，不重试为整包操作。
- `numberidentity` 纳入 `SafetyTargets`；`pm unsuspend` 幂等，且不会自动取消 `disable-user`。
- 设置键：`antifraud_numberidentity_mode`、`antifraud_securityserver_restrict`、`antifraud_awareness_restrict`、`antifraud_last_result`；默认关闭。

### 7.3 温控 / COTA 模块（root-only）

- 读取 `/data/cota/para` 下的 `cpuconfig`、`pg_config`、`thermal_config`（以及同目录其他文件）的文件名、大小、SHA-256。
- **备份**到 App 外部目录或 `/sdcard/Nemu/cota-backup/<timestamp>/`。
- 用户确认后删除选中的云控参数文件；支持从备份恢复。
- **不触碰** `com.hihonor.powergenie`、`com.hihonor.iaware`、vendor HAL、内核 thermal zone。
- UI 必须显示风险确认：删除温控参数可能导致发热、充电变慢、降频策略改变；不保证提升性能；系统可能重新下发。
- 该模块默认关闭；只在 Root 后端可用。

**实现（2026-11-21）**：

- `CotaController` 的构造参数是 `RootPrivilegeBackend?`；`ShizukuBackend` 不实现该 marker，因此不存在 Shizuku 回退。
- 备份目录：`/storage/emulated/0/Nemu/cota-backup/<yyyyMMdd-HHmmss>/`；每个备份包含副本与 `manifest.tsv`（SHA-256/大小/mode/uid/gid），写入后回读校验。
- 删除前置条件：备份清单存在、备份副本 hash 与清单一致、当前文件 hash 与备份一致、文件名通过安全字符校验；只对选中文件执行 `rm -f`，没有 `rm -rf`。
- 恢复前先校验备份副本 hash；恢复后重新计算 SHA-256 并校验；mode/uid/gid 由 `chown`/`chmod` 恢复，SELinux context 不单独保存（风险见测试计划）。
- 不触碰 `com.hihonor.powergenie`、`com.hihonor.iaware`、vendor HAL、内核 thermal zone；COTA 模块不执行 `pm` 命令。
- 默认关闭（`Settings.cotaEnabled=false`）；开关状态持久化，但每次操作都重新检查 Root。

### 7.4 实施阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| P1 | 工程骨架、Root/Shizuku 后端、OUC suspend/unsuspend、日志、基础 UI | 代码完成；Shizuku 实机通过，Root 未测 |
| P2 | OUC force-stop + clear cache、安全解冻、AI 绿色叶子包、禁止清单、Restore all | 代码完成；绿色包 + OUC 实机通过 |
| P3 | 运营商 DM/遥测模块（disable-user + 回滚）、反诈 `numberidentity` + AppOps 限制 | 代码完成，待实机验收（CT1–CT5、AF1–AF6） |
| P4 | COTA 备份/清理/恢复（root）、风险确认、导出日志；可选 Dhizuku/网络防火墙 | COTA 代码完成，待实机验收（COTA1–COTA6）；导出日志/Dhizuku 未开始 |

#### 实现范围（2026-11-21）

- 已实现：Root/Shizuku 自动选择、suspend/unsuspend/force-stop/clear-cache、AI 叶子包、Dry run、禁止清单、Restore all、关机/重启安全解冻（前台服务 + 运行时广播）、best-effort 开机安全解冻。
- 已实现的高级模块：云控/遥测（Cloud Task 1–2 + UI）、反诈（AntiFraud Task 1–3 + UI）、COTA 温控（COTA Task 1–3 + UI）；三模块默认关闭，共享一个安全解冻集合。
- 2026-11-21 新实现：开机延迟自动冻结（`BootFreezeWorker`，默认 120s，bootloop 守卫）、AI 深度冻结（`AiTargets` + `AiFreezeController`，默认关闭）、可开关持久日志（含启动/Boot/安全解冻/冻结）、只读检测页（核心/AI/云控冻结、温控、COTA）、Dashboard 4-tab UI；FinaSU root 后端已实测。
- 推迟：手动后端切换 UI、核心目标状态回读的独立详情页、COTA 日志导出、Dhizuku/网络防火墙。

> 状态说明：“代码完成”仅表示通过构建与单元测试；实机结果见 `docs/04-test-plan.md` 第 7 节。
> 2026-11-21 已实测：Root 后端关机安全解冻；核心与 `mageruntime` AI 目标的 60/120s 开机冻结；强制重启后的 bootloop 守卫跳过；持久日志包含 startup/boot/freeze/safety。三个高级模块与 UI 视觉仍需用户逐项验收。

## 8. 审批

用户已在 2026-11-21 选择“全套（含云控/反诈/温控）”作为 v1 范围。以下基线继续有效：

- [x] 默认只 suspend、绝不 uninstall；系统应用不做 disable（运营商 DM 三件套的 disable-user 为单独例外）。
- [x] v1 范围：全套（分阶段实施，高级模块默认关闭）。
- [x] 权限后端：Root + Shizuku 双后端。
- [x] 交付形态：先 debug APK，包名 `com.nemu.hibernate`。
