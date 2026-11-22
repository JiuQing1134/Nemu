# 09 自动救砖（auto-unbrick）设计

> 日期：2026-11-21
> 状态：**module v1.4 与 App `BootGuard` 已实现；shell 语法与 JVM 测试通过。设备现装 v1.3 的 guard 是 CRLF，直接执行失败；v1.4 尚未部署，真机救援未验证。**
> 目标：连续 N 次启动未成功（bootloop）时，自动禁用 `nemu_boot_autostart` 模块，并尽可能早地 `pm unsuspend` 全部 15 个安全目标；不依赖用户点 UI。
> 前置：`docs/07-boot-freeze-logging-ui-design.md`、`AGENTS.md`、`D:\win_root\refs\kernelsu`、`D:\win_root\refs\finasu`。

## 当前实现校正（2026-11-21）

以 `tools/nemu-boot-autostart/` 当前源码为准：

- guard 位于 `/data/adb/nemu/boot_guard/boot_guard.sh`，公共入口在 `post-fs-data.d` 与 `service.d`；状态区分干净关机标记与 Nemu 自己 `disable-user` 的包归属标记。
- 解冻命令有超时并逐包核对 `dumpsys`；救援只重新启用带 Nemu 归属标记的包。清除救援会先验证解冻和重新启用，失败时保留 rescue flag 与模块禁用状态。
- 安装器拒绝覆盖内容不匹配的同名公共 hook；卸载器仅移除仍与 Nemu 生成内容完全匹配的 hook。安装时也会剔除源脚本中的 CR 字符。
- 设备当前只读状态：FinaSU 模块为 v1.3；guard 文件含 CRLF，直接执行返回 “No such file or directory”。本轮仅修改电脑上的源码和 ZIP，没有更新手机文件、应用包状态或重启设备。

## 0. 结论摘要

### 0.1 能做，但必须先分清 root 是否随内核启动

| 场景 | 自动禁用模块 | 自动尽早 unsuspend | 结论 |
|---|---|---|---|
| FinaSU/KernelSU **随内核启动**，Android 能起到 `post-fs-data` / `service` 阶段 | 可以。`post-fs-data.d` 的 guard 在模块脚本之前写 `disable`，本次后续阶段和下次 boot 都会跳过模块 | 可以做到“尽可能早”：`service` 阶段 `pm` 通常尚未就绪，需要 bounded retry；`sys.boot_completed=1` 后再补一次 | 推荐方案，仍然不是 100% 防砖 |
| 同上，但 **system_server 完全起不来** | 可以（`post-fs-data` 阶段仍可计数并写 `disable`） | **不行**。`pm` 依赖 package service；`post-fs-data` 里调用 `pm` 只会失败/阻塞 | 只能靠 safe mode、recovery/fastboot 或人工处理 |
| 当前常见的 **GhostLock late-load root** | 只能在 late-load 之后禁用；不能阻止本次 boot 已经发生的循环 | 只能在 late-load 之后 unsuspend；对 early bootloop 无效 | **不是早期自动救砖**，只是 root 加载后的清理 |
| 内核/ABL/boot image 在 `/data/adb` 可访问前就失败 | 不行 | 不行 | fastboot/recovery；本文不涉及刷写 |

**一句话结论**：方案在“root 随内核启动”的前提下可行；在当前“临时 root 仅 late-load”的前提下，无法做到 bootloop 早期的无人值守救援。这是一个部署前提，不是脚本写法能绕过的。

### 0.2 当前项目里有一个必须确认的矛盾

- `docs/01-device-and-findings.md` §2.1、`docs/03-design.md` §3、`docs/04-test-plan.md` 风险表都写着：root 是 KSU late-load，**开机早期没有 root**。
- `PROJECT.md` §10 又记录：FinaSU 模块 v1.1 已安装并通过重启激活，boot=35 时 19:59:47 模块自动拉起 Nemu，20:01:49 完成 120s 开机冻结。
- 这两件事只能由主会话/用户确认：模块到底是“内核启动早期执行”，还是“重启后先做 GhostLock late-load，再由 late-load 的 service 阶段执行”。本文把 **root 部署方式**列为第一决策点，不假设任意一边。

### 0.3 推荐架构（先给结论）

1. **不要**把救援逻辑放进要被禁用的 `nemu_boot_autostart` 模块。否则模块一被 `disable`，救援脚本也一起消失。
2. 用独立的 **common scripts guard**：
   - `/data/adb/nemu/boot_guard.sh`（主脚本，root-only，0755）
   - `/data/adb/post-fs-data.d/00nemu_boot_guard.sh`（计数、到达阈值时写 `disable`，**不调用 pm**）
   - `/data/adb/service.d/00nemu_boot_guard.sh`（尽早 `pm unsuspend`、等 boot_completed、补一次、启动成功 watchdog）
3. `nemu_boot_autostart/service.sh` 在启动 Nemu 之前先同步调用 `boot_guard.sh check`（只计数/触发，不调用 pm），再检查 rescue flag；命中则不 `am start`。
4. App 侧新增 `BootGuardState`：`BootFreezeScheduler` / `BootFreezeWorker` 在建任务和真正冻结前都检查 rescue flag，确保模块禁用后即使用户手动打开 Nemu 也不会重新冻结。
5. 计数器阈值默认 **3**；成功判定用 `sys.boot_completed=1` + 稳定窗口 **600s** + `init.svc.bootanim=stopped`（可选 SystemUI 存活）；干净关机标记由 App 在关机安全解冻成功后写。
6. 一旦救援触发，**保持救援标记和模块 disable 状态，直到用户明确确认**；不自动重新启用。

### 0.4 最大边界

- `post-fs-data` 里 **没有 `pm`**。任何“在 post-fs-data 里等 pm 就绪再 unsuspend”的设计都会拖慢启动、浪费 35s 共享预算，而且等不到。
- `pm unsuspend` 只能清 **suspend**，不能清 `pm disable-user`、AppOps/standby 限制，也不能修复 system_server 自身崩溃。
- KernelSU safe mode 会禁用所有模块并跳过 `/data/adb/service.d`，但它**不会**清 suspend；它适合“模块代码导致 bootloop”的人工兜底，不适合“suspend 导致 system_server 起不来”的自动恢复。
- 当前 late-load root 下，late-load 之前没有任何 Nemu 脚本执行机会；bootloop 时用户可能连进系统触发 late-load 都做不到。

---

## 1. 目标、非目标与术语

### 1.1 目标

1. 每个 boot 在尽可能早的用户空间阶段记录“本次启动尚未确认成功”。
2. 连续 N 次未确认成功时：
   - 给 `nemu_boot_autostart` 模块写 `disable`；
   - 写 rescue flag，供 App 和模块脚本读取；
   - 尽早对 15 个安全目标执行 `pm unsuspend`；
   - 记录救援日志，供用户事后查看。
3. 成功启动后重置计数；正常慢启动不能被误判为 bootloop。
4. 全程不依赖 UI、不依赖 `BOOT_COMPLETED` 广播、不重启设备。
5. 与现有 `BootFreezePolicy` 的 App 侧守卫兼容，不产生重复冻结或竞态。

### 1.2 非目标

- 不修改分区、boot/vbmeta、vendor、ABL/EFISP；不刷 boot。
- 不卸载、不 `pm disable-user` 任何系统应用。
- 不直接编辑 `/data/system/users/0/package-restrictions.xml` 或 package manager 数据库。
- 不承诺 100% 防 bootloop；不覆盖内核/ABL/boot image 级失败。
- 不在 `post-fs-data` 里阻塞等待 `pm`，不使用无限循环。
- 不在没有用户确认的情况下自动重新启用模块。
- 不把救援逻辑放进目标模块自身。

### 1.3 术语

- **bootloop**：设备连续重启，未达到“稳定可用”状态。本文用“未确认成功的连续启动次数”近似。
- **成功启动**：`sys.boot_completed=1`，且达到稳定窗口；或 App 在关机时成功完成安全解冻。
- **guard**：本文新增的独立计数/救援脚本。
- **rescue flag**：`/data/adb/nemu/boot_guard/rescue.flag`，表示自动救砖已触发。
- **15 个安全目标**：`SafetyTargets.suspendPackageNames` 的 15 个包，见附录 A。

---

## 2. 现状核查

### 2.1 自启动模块

`D:\win_root\nemu\tools\nemu-boot-autostart\service.sh` 当前逻辑：

1. 定义 15 个目标的硬编码列表（`TARGETS`）。
2. **Phase 1**：在等待 `sys.boot_completed` 之前，循环最多 60 次、每次间隔 2s 调 `pm unsuspend --user 0`；成功（exit 0）就 break。
3. **Phase 2**：等待 `sys.boot_completed=1` 最多约 120s；再 `sleep 5`；然后 `am start -n com.nemu.hibernate/.MainActivity`。
4. **Phase 3**：再 `pm unsuspend` 一次。

`module.prop`：`id=nemu_boot_autostart`，`version=1.1`，`versionCode=2`。

代码审查发现的三个可靠性问题（本次不修改，只记录）：

- **只看 exit code**：`pm unsuspend` 可能 exit 0，但输出仍然是 `new suspended state: true`（suspend 由其他 suspendingPackage 施加）。当前脚本会误以为成功并 break。Kotlin 的 `SafetyUnfreezer` 已经解析这个字符串，shell 没有。
- **等不到 boot_completed 也会启动 App**：Phase 2 的循环结束后没有再次确认 `sys.boot_completed=1`，仍然 `am start`。在慢启动/bootloop 场景可能提前拉起 Nemu，随后调度 120s 开机冻结。
- **硬编码目标列表**：15 个包名同时存在于 `service.sh` 和 `SafetyTargets.kt`，没有自动同步校验；Kotlin 侧新增/改名会静默漏掉 shell 侧。

### 2.2 App 侧现有守卫

`BootFreezeWorker.doWork()` 的顺序（`app/src/main/java/com/nemu/hibernate/policy/BootFreezeWorker.kt`）：

1. `SafetyService.start()`；
2. 拒绝上一个 boot 的过期 WorkManager 任务（`scheduledBootCount != bootCount`）；
3. 取后端；检查 `sys.boot_completed=1`；
4. **先** `SafetyUnfreezer.runUnsuspendPackages(SafetyTargets...)` 清残留 suspend；
5. `BootFreezePolicy.evaluate()`；
6. 真正执行前 `recordAttempt(bootCount, time)`；
7. 核心/ AI 冻结；记录结果；**失败也不自动重启**。

`BootFreezePolicy` 的规则（`BootFreezePolicy.kt`）：

- 总开关关 → 跳过；
- 本 boot 已处理/已跳过/已尝试 → 跳过；
- 上一个 boot 有冻结尝试，但之后没有干净关机 → 跳过（fail-closed）；
- 否则允许冻结。

`BootFreezeScheduler`：

- 唯一任务名 `nemu_boot_freeze`，`ExistingWorkPolicy.REPLACE`，延迟默认 120s、最低 60s；
- `BootCount` 用 `Settings.Global.BOOT_COUNT`，读取失败时用 `elapsedRealtime()/3600` 近似；
- `MainActivity.onCreate` 和 `BootWorker` 都会调用 `ensureScheduled`；
- `BootReceiver` 依赖 OEM 广播，HONOR 上不可靠，不是主路径。

`ShutdownSafety` / `SafetyService`：

- 正常关机/重启的运行时 receiver 里，8s 内用 root 优先 `pm unsuspend` 15 个目标；
- 结果写 `last_shutdown_result` / `last_shutdown_time`，`lastShutdownOk` 供 App 侧守卫使用；
- **没有**给 shell 侧写“干净关机”标记文件；shell guard 读不到 SharedPreferences（尤其 CE 存储未解锁时）。

### 2.3 15 个安全目标

来源（`SafetyTargets.kt`）：

- `Targets.all` 5 个：`com.hihonor.ouc`、`com.honor.yoyoappsug`、`com.honor.yoyocards`、`com.hihonor.suggestion`、`com.hihonor.sceneservice`
- `CloudTargets.suspendPackageNames` 3 个：`com.hihonor.hiview`、`com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel`
- `AntiFraudPolicy.safetyPackages()` 1 个：`com.hihonor.numberidentity`
- `AiTargets.all` 6 个：`com.hihonor.aipluginengine`、`com.hihonor.assistant`、`com.hihonor.magicvoice`、`com.hihonor.voiceengine`、`com.hihonor.brain`、`com.hihonor.mageruntime`

合计 **15**。运营商 DM 三件套是 `pm disable-user`，不在 suspend 安全集合中，`pm unsuspend` 也不会恢复它们。

### 2.4 root 获取方式（关键约束）

- `docs/01-device-and-findings.md` §2.1：`/proc/modules` 有 `kernelsu ... Live`，但 `adb shell su` 不可用；结论是 root 按 GhostLock late-load 流程，在系统启动后由 KSU 管理器/授权 App 使用，**开机早期没有 root**。
- `docs/03-design.md` §3：同样把“root 是 KSU late-load”列为约束。
- `docs/04-test-plan.md` 风险表：“root 仅 late-load 可用”。
- `PROJECT.md` §10 的 boot=35 记录又显示模块自动拉起 Nemu。两种解释：
  - A. FinaSU 已改为随内核启动（持久 root）；
  - B. 重启后用户先触发 GhostLock late-load，模块的 `service.sh` 在 late-load 阶段被执行（FinaSU `late_load.rs` 第 12 步会跑 `service` stage）。
- 如果是 B，则本文的“自动救砖”只能在 late-load 之后工作，**不能在 bootloop 早期工作**。这是第一决策点。

---

## 3. FinaSU/KernelSU 生命周期核查

以下结论同时核对 `D:\win_root\refs\finasu`（本机变体）和 `D:\win_root\refs\kernelsu`（上游），两者在本文关心的部分一致。

### 3.1 启动阶段与顺序

内核侧注入的静态 rc（`kernel/runtime/ksud_integration.c`）：

```text
on post-fs-data
    start logd
    exec u:r:ksu:s0 root -- /data/adb/ksud post-fs-data

on nonencrypted
    exec ... /data/adb/ksud services

on property:vold.decrypt=trigger_restart_framework
    exec ... /data/adb/ksud services

on property:sys.boot_completed=1
    exec ... /data/adb/ksud boot-completed
```

`ksud` 侧顺序（`userspace/ksud/src/init_event.rs`）：

| 阶段 | 触发 | 顺序 | `pm` 可用性 |
|---|---|---|---|
| `post-fs-data` | init `on post-fs-data`（`/data` 已挂载，zygote 之前） | 安全模式检查 → 公共 `post-fs-data.d` → 处理模块更新 → prune → `regenerate_preinit` → 模块 `post-fs-data.sh` → mount/post-mount | **不可用**；system_server 还没起 |
| `service` | `on nonencrypted` / `vold.decrypt=trigger_restart_framework`，接近 framework 启动 | 公共 `service.d` → metamodule → 模块 `service.sh`；`ScriptWait::NoWait`；`report_services` 保证本 boot 只跑一次 | **可能尚未就绪**；package service 起好后才行。现有脚本用 120s 重试就是为此 |
| `boot-completed` | `sys.boot_completed=1` | 公共 `boot-completed.d` → 模块 `boot-completed.sh` | 已就绪 |
| `late-load` | 用户/工具执行 `ksud late-load` | `late-load.d` → 模块 `late-load.sh` → post-mount → **service** → **boot-completed** | 通常已就绪（Android 已在运行） |

关键点：

- `post-fs-data` 的共享等待预算 `BOOT_STAGE_TIMEOUT = 35s`（`defs.rs`）。公共脚本和模块 `post-fs-data.sh` 共享这个 deadline；在这里 sleep/等 `pm` 会拖慢整个启动。
- `service` 阶段是 `NoWait`，脚本可以在后台等待 `pm`，不阻塞 ksud 返回；但仍然受进程生命周期和系统内存管理影响。
- `post-fs-data` 的公共 `post-fs-data.d` 脚本在 `regenerate_preinit()` **之前**执行。这个顺序对 `disable` 的生效时点很重要，见 3.2。
- `service` 阶段在一次正常 boot 中只跑一次（`report_services` 返回 false 时跳过）；但 `late-load` 会再次执行 `service` stage。因此 guard 必须用 boot_id 做幂等，不能假设“service 只跑一次”。
- `utils::has_magisk()` 为真时，FinaSU 会跳过 `post-fs-data` 和所有 stage 脚本；本机没有装 Magisk，但如果用户同时装 Magisk，guard 不会执行。这是共存边界。

### 3.2 模块目录里的 `disable` 到底什么时候生效

源码事实（`userspace/ksud/src/module.rs`）：

- `foreach_module(Active, ...)` 每次执行 stage 时都会检查 `module_dir/disable` 是否存在；存在就跳过该模块。
- `exec_stage_script(stage, ...)` 对 `post-fs-data` / `service` / `late-load` / `boot-completed` / `post-mount` 都走 `foreach_active_module`。
- `disable_all_modules()`（safe mode 用）就是对 `/data/adb/modules/*` 逐个创建 `disable`。
- `handle_updated_modules()` 在模块更新时，如果旧模块目录已有 `disable`，会给新模块也创建 `disable`。
- `next_boot_modules()` / `regenerate_preinit()` 在生成下一次 boot 的 `modules.rc` 时排除带 `disable` 的模块。

因此：

1. **在本次 boot 的 `service` stage 之前创建 `disable`**（例如在公共 `post-fs-data.d` 中）：
   - 本次 boot 的模块 `post-fs-data.sh`（如果还没跑）会被跳过；
   - 本次 boot 的模块 `service.sh` 会被跳过；
   - 本次 boot 的 `boot-completed.sh` 会被跳过；
   - 下次 boot 的 `service.sh` 也会被跳过。
2. **在模块自己的 `service.sh` 运行期间创建 `disable`**：
   - 当前这次 `service.sh` 调用不会被中断，会继续执行到脚本自己退出；
   - 本次 boot 的 `boot-completed.sh` 和下次 boot 的 `service.sh` 会被跳过。
3. **在模块自己的 `post-fs-data.sh` 中创建 `disable`**：
   - 本次 boot 的 `service.sh` 会被跳过；
   - 但 `regenerate_preinit()` 已经在该模块的 `post-fs-data.sh` 之前执行过了，所以**下一次 boot** 的 `initrc` 仍可能包含该模块的 rc；要到再下一次 boot 才会排除。对 Nemu 这种没有 `initrc` 的模块影响不大，但设计上应避免。
4. **公共 `post-fs-data.d` 中创建 `disable`**：
   - 因为公共脚本在 `regenerate_preinit()` 之前执行，本次 boot 就会把该模块排除出下一次 boot 的 `modules.rc`，不存在“initrc 晚一拍”的问题。
   - 这是推荐把计数/禁用放在公共 `post-fs-data.d` 的另一个理由。
5. **直接 `touch disable` 与 `ksud module disable` 的差别**：
   - `ksud module disable` 会额外调用 `regenerate_preinit()`，立即重建下一次 boot 的 `modules.rc`；
   - 直接写 `disable` 只靠本 boot 或下一次 boot 的 `on_post_fs_data` 重建。
   - Nemu 的 `nemu_boot_autostart` 没有 `initrc`，所以直接写 `disable` 已经足够让 `service.sh`/`post-fs-data.sh` 跳过；但如果未来模块加了 `initrc`，应改用 `ksud module disable` 或在公共 `post-fs-data.d` 中写。
6. **`disable` 不影响公共 `/data/adb/service.d` 脚本**：
   - 公共脚本由 `exec_common_scripts()` 直接扫描目录，不检查模块 `disable`。
   - 所以把救援逻辑放在 `service.d`，即使目标模块被禁用，救援仍然在。
7. **`disable` 不是 `remove`**：
   - `remove` 会在下次启动被 `prune_modules()` 删除；`disable` 只是跳过。
   - 自动救砖只能用 `disable`，绝不能用 `remove`。
8. **模块更新会保留 `disable`**：
   - `handle_updated_modules()` 会检查旧模块的 `disable` 并复制到新模块。
   - 但为了稳妥，guard 可以同时给 `/data/adb/modules/nemu_boot_autostart` 和 `/data/adb/modules_update/nemu_boot_autostart`（如果存在）写 `disable`。

### 3.3 safe mode 到底禁用了什么

源码事实：

- `userspace/ksud/src/utils.rs::is_safe_mode()` 检查：
  - `persist.sys.safemode == 1`
  - `ro.sys.safemode == 1`
  - 或内核 safe mode（音量下键计数 ≥ 3）
- 内核 safe mode（`kernel/runtime/ksud_integration.c`）：
  - `ksu_handle_input_handle_event()` 统计 `KEY_VOLUMEDOWN` 按下次数；
  - `ksu_is_safe_mode()` 如果 `ksu_late_loaded` 为 true，**直接返回 false**；
  - 内核在 `on_post_fs_data`（zygote 首次 exec 附近）会注销 input hook，所以按键窗口在第一屏到 boot animation 之前。
- `on_post_fs_data()` 的 safe mode 分支：
  - 跳过公共 `post-fs-data.d`；
  - `disable_all_modules()`：给 `/data/adb/modules/*` 每个模块创建 `disable`；
  - 直接 return，不执行模块 `post-fs-data.sh`、mount 等。
- `run_stage()` 在 safe mode 下直接 return：
  - 跳过公共 `service.d`；
  - 跳过模块 `service.sh`；
  - 跳过 `boot-completed.d` / 模块 `boot-completed.sh`。

因此对本文问题的直接回答：

- **safe mode 会禁用所有模块**（写 `disable`），并且**跳过本次 boot 的 `/data/adb/service.d`**。
- 它**不会**清 suspend，不会执行 `pm unsuspend`。
- 它**不会删除** `/data/adb/service.d` 里的脚本；退出 safe mode 后的下一次正常 boot，这些脚本仍会执行。
- 模块的 `initrc/*.rc` 在 safe mode 判断之前就已经被 init 读入并执行了；上游文档明确警告“initrc 里的坏代码在 safe mode 仍会执行”。Nemu 模块没有 `initrc`，不受影响。
- 如果模块使用 `preinit`，preinit 阶段可能更早，safe mode 的 `disable_all_modules` 也来不及阻止；Nemu 没有 preinit。
- **late-load 下内核 safe mode 返回 false**：`ksu_late_loaded` 为 true，音量键 safe mode 不生效。当前 GhostLock late-load 场景下，不能依赖 KernelSU safe mode 做早期兜底。

### 3.4 `post-fs-data` 阶段 `pm` 是否可用

**不可用。** 原因：

- `post-fs-data` 是 init 在 `/data` 挂载后、zygote/system_server 启动前执行的；`pm` 是 `/system/bin/pm`，最终要通过 binder 找 package service。
- 此时 package service 还不存在，`pm` 要么报 `Can't find service: package`，要么卡住。
- 同一个阶段的公共脚本共享 35s deadline；等待 `pm` 会让整个 post-fs-data 变慢，甚至超时。
- 正确时机是 `service` 阶段开始重试；现有 `service.sh` 的 120s 循环就是这个思路。`boot-completed` 阶段正常情况已就绪。

### 3.5 late-load 的差异

`userspace/ksud/src/late_load.rs` 的顺序：

1. 加载 `kernelsu.ko`（如果尚未加载）；
2. 安装 ksud、处理模块更新/prune/restorecon、加载 sepolicy、features；
3. `run_stage("late-load", ...)`；
4. 加载 system.prop；
5. mount；
6. `run_stage("post-mount", ...)`；
7. `run_stage("service", NoWait)`；
8. `run_stage("boot-completed", NoWait)`；
9. 重启 Manager。

差异：

- **不会**执行 `post-fs-data` / `post-fs-data.d`；这些触发点在内核模块加载前已经过去了。
- 会执行 `service.d` 和模块 `service.sh`（在 late-load 时刻）。
- 内核 safe mode 返回 false。
- `boot_id` 仍是当前 boot 的 ID，所以 guard 可以正确去重；但计数/救援发生在 Android 已经运行之后，对“bootloop 早期”没有帮助。

### 3.6 对设计的直接含义

1. 计数/禁用要放在公共 `post-fs-data.d`（最早、且在 `regenerate_preinit` 之前），但**不能**在这里调用 `pm`。
2. unsuspend 要放在公共 `service.d`（独立于模块 disable），并做 bounded retry，再在 `boot_completed` 后补一次。
3. 救援逻辑不能放在目标模块里，否则禁用模块会禁用救援。
4. 当前 late-load 场景下，`post-fs-data.d` 不会运行；只有 `service.d` 会运行。guard 必须能在 `service` 模式下独立完成计数、禁用、unsuspend。
5. safe mode 只能作为人工兜底；它不会清 suspend。

---

## 4. bootloop 判定设计

### 4.1 成功与失败的判定

**成功启动（可重置计数）**，满足任一：

1. **稳定窗口**：`sys.boot_completed=1`，且 `/proc/uptime` 达到 `STABILITY_SECONDS`（建议 600s），且 `init.svc.bootanim=stopped`；可选检查 `pidof com.android.systemui` 存活。
2. **干净关机标记**：App 在关机安全解冻成功后写 `/data/adb/nemu/boot_guard/clean_shutdown`；下一次 boot 读到相同 `boot_id` 即认为上一个 boot 是干净结束的。

**未确认成功（累加计数）**：

- 当前 boot 的 `boot_id` 与 state 里的 `last_boot_id` 不同，且上一个 boot 没有 success 标记、也没有干净关机标记 → 计数 +1（当前 boot 本身也算一次“未确认尝试”）。
- 如果上一个 boot 有 success/clean 标记 → 先把计数清零，再给当前 boot 计 1。

这样阈值 N=3 的含义是：**连续第 3 次启动仍未确认成功时触发救援**；不是“失败 3 次后再等下一次”。

**不把慢启动当失败**：

- 计数在 boot 开始就 +1，但成功 watchdog 在稳定后清零；慢启动如果最终稳定，不会累积到下一次。
- 如果用户在稳定窗口前主动重启，且 App 成功写了干净关机标记，也会清零。
- 如果没有干净标记（如 `adb reboot`、强制断电、系统崩溃），会计数；这是有意的保守行为。

### 4.2 文件、路径与内容

所有状态放在 `/data/adb/nemu/boot_guard/`（root-only，0700；文件 0600）：

| 路径 | 内容 | 写入方 | 说明 |
|---|---|---|---|
| `/data/adb/nemu/boot_guard/state.conf` | `format=1`、`last_boot_id=<uuid>`、`last_boot_seen_epoch=<int>`、`fail_streak=<int>`、`success_boot_id=<uuid>`、`success_epoch=<int>`、`success_uptime=<int>`、`clean_shutdown_boot_id=<uuid>`、`clean_shutdown_epoch=<int>`、`clean_shutdown_result=ok 或 failed`、`rescue_count=<int>`、`last_rescue_boot_id=<uuid>`、`last_rescue_epoch=<int>` | guard | 原子写：临时文件 + `mv` + `sync` |
| `/data/adb/nemu/boot_guard/success` | `boot_id=<uuid>`、`epoch=<int>`、`uptime=<int>`、可选 `systemui_pid=<pid>` | success watchdog | 稳定窗口达到后写；内容可读，App 用来显示 |
| `/data/adb/nemu/boot_guard/clean_shutdown` | `boot_id=<uuid>`、`epoch=<int>`、可选 `uptime=<int>`、`result=ok` | App（root，关机安全解冻成功后） | 只写 `result=ok` 才算干净；失败可写 `failed` 供诊断 |
| `/data/adb/nemu/boot_guard/rescue.flag` | `tripped=1`、`boot_id=<uuid>`、`epoch=<int>`、`fail_streak=<int>`、`module=nemu_boot_autostart`、`module_disabled=0 或 1` | guard | 救援已触发；App 和模块 `service.sh` 都读 |
| `/data/adb/nemu/boot_guard/rescue.log` | 追加文本，单文件上限约 64 KB，超出轮转 `.1` | guard | 供 App/用户查看，不参与判定 |
| `/data/adb/nemu/boot_guard/lock/` | 目录，内含 `boot_id` 文件 | guard | `mkdir` 锁；boot_id 不同视为 stale 锁 |
| `/data/adb/nemu/boot_guard/watchdog.lock/` | 目录，内含 `boot_id` 文件 | guard | 防止同一 boot 起多个 success watchdog；boot_id 不同视为 stale 锁 |
| `/data/adb/nemu/boot_guard.conf` | `THRESHOLD=3`、`STABILITY_SECONDS=600`、`UNSUSPEND_DEADLINE_SECONDS=180` | 用户/App（root） | 缺失时用默认；阈值 clamp 到 2..5 |
| `/data/adb/modules/nemu_boot_autostart/disable` | 空文件 | guard | **唯一必须放在模块目录里的状态**；不要放其他状态 |

说明：

- `success` 和 `clean_shutdown` 两个文件是**权威来源**；`state.conf` 里的 `success_boot_id` / `clean_shutdown_*` 只是缓存和诊断用途。
- `clean_shutdown` 由 App 在关机时写，guard 在下一次 boot 读；如果 App 没写成，即使 state 里有旧值，也不会匹配新的 `boot_id`（boot_id 每次 boot 都不同）。
- 不建议把计数器放模块目录，原因见 §7。

### 4.3 状态机与伪代码

```sh
# 伪代码，省略错误处理；完整示意见 §6.2
begin_boot():
    current_boot_id = cat /proc/sys/kernel/random/boot_id
    load_state()
    if current_boot_id == last_boot_id:
        return                      # 同一次 boot 的重复执行（late-load/double service），不重复计数
    if last_boot_id is not empty and previous_boot_confirmed():
        fail_streak = 0
    fail_streak = fail_streak + 1
    last_boot_id = current_boot_id
    save_state()
    if fail_streak >= THRESHOLD:
        trip_rescue()

previous_boot_confirmed():
    return success_boot_id == last_boot_id
        or (clean_shutdown_boot_id == last_boot_id and clean_shutdown_result == "ok")

trip_rescue():
    ensure_disable_file()           # 先写 disable，任何长等待之前
    if rescue.flag exists:
        return                      # 已经触发过，不重复写
    write_rescue_flag()             # 紧接着写 flag
    fail_streak / rescue_count 更新到 state
    log_rescue()

service_mode():
    if acquire_lock():
        begin_boot()                # post-fs-data 没跑过时，这里补计数/触发
        unsuspend_with_retry()      # 尽早；pm 可能还没就绪
        release_lock()
    wait_boot_completed(timeout)
    unsuspend_with_retry()          # 第二次，catch 晚启动的 package service
    start_success_watchdog()

success_watchdog():
    while uptime < STABILITY_SECONDS + margin:
        if boot_id changed: return
        if sys.boot_completed == 1
           and init.svc.bootanim == stopped
           and (optional: pidof com.android.systemui):
            write success marker
            fail_streak = 0
            save_state()
            return
        sleep 30
```

要点：

- `trip_rescue()` **先写 `disable`/flag，再做任何 `pm` 等待**。这样即使后面的 unsuspend 失败，下次 boot 也已经不会执行目标模块逻辑。
- `service_mode()` 无论是否触发救援，都执行早/晚两次 unsuspend；这是“尽可能早解冻”的主路径。
- `begin_boot()` 在 `service` 模式也会调用一次，保证没有 `post-fs-data.d` 的 late-load 场景也能计数。
- 所有 `pm` 调用都有 deadline；没有 `while true`。

### 4.4 阈值、稳定窗口与重置

**阈值建议**：

| N | 触发时机 | 适用 |
|---|---|---|
| 2 | 第二次未确认启动就救援 | 极保守；误报概率更高 |
| **3（推荐）** | 第三次未确认启动 | 常规 bootloop 与误报的平衡 |
| 4-5 | 更晚救援 | 用户经常快速重启/测试；但暴露在 bootloop 里的时间更长 |

- 默认 3，配置文件 clamp 到 2..5。
- 稳定窗口建议 600s。Nemu 的自动冻结在 120s，若冻结后系统在 130s 崩溃，600s 窗口能捕获；同时不会把正常的慢启动误判为失败。
- 重置条件：
  1. 稳定窗口达到；
  2. App 写入 `clean_shutdown` 且 `result=ok`；
  3. 用户在 App 里明确“清除救援标记”（调用 guard 的 `clear` 模式，重置 `fail_streak`、删除 flag、写 `success_boot_id=当前 boot_id`）；
  4. 用户重新安装/重置 guard 状态（删除 state 目录）——不推荐作为日常操作。
- **不自动重置救援 flag**：即使 watchdog 确认当前 boot 稳定，也只把 `fail_streak` 清零；`rescue.flag` 和模块 `disable` 保留，直到用户确认。原因是触发救援的根因可能仍然存在。

### 4.5 边界情况

| 情况 | 处理 |
|---|---|
| 同一 boot 内 `service.sh` 被 normal service + late-load 各执行一次 | `boot_id` 相同 → `begin_boot()` 不重复计数；unsuspend 幂等；watchdog 用 lock 防止重复 |
| `post-fs-data.d` 和 `service.d` 都执行 `begin_boot` | 同一个 boot_id，第二次 no-op |
| 设备在 `post-fs-data` 之前就 panic/重启 | guard 没有执行机会，不计数；这是脚本无法覆盖的 early bootloop |
| system_server 起不来，只有 `post-fs-data` | 可以计数并写 `disable`；不能 `pm unsuspend`；下一次 boot 继续计数，达到阈值后仍然只能禁用模块 |
| safe mode | 公共 `post-fs-data.d`/`service.d` 被跳过；所有模块被写 `disable`；guard 本次不执行；退出 safe mode 后公共脚本恢复，模块需要用户重新启用 |
| `adb reboot` / 强制断电 | 没有 `ACTION_SHUTDOWN`，App 不写 clean marker；计数 +1；连续 3 次可能误触发；文档要求日常测试用电源菜单/`svc power reboot` |
| 正常电源菜单重启 | `SafetyService` 若存活并成功解冻，写 clean marker；下一次 boot 清零 |
| App 被系统杀死，关机时没有 clean marker | 计数 +1；3 次阈值 + 用户可清除；可接受 |
| OTA/充电模式重启 | 可选读取 `ro.boot.bootreason`/`sys.boot.reason` 做提示；没有设备实测前不要硬编码排除，避免漏掉真实 bootloop |
| 模块 update 替换目录 | `handle_updated_modules` 保留 `disable`；guard 同时写 `modules` 和 `modules_update` 两条路径 |
| state 文件损坏/半写 | 原子 `mv` + `sync`；解析失败时 `fail_streak=0` 并记日志，避免误救援 |
| stale lock（持锁时重启） | lock 里存 boot_id；boot_id 不同 → 删除重建 |
| watchdog 被 lmkd 杀死 | 当前 boot 不会写 success，下一次 boot 计数 +1；3 次阈值可能误救援。建议 App 侧再加一个 10 分钟后的成功标记 worker，作为冗余（§6.4） |
| 用户连续快速重启（无 clean marker） | 3 次触发；UI 允许清除；文档提示；可选 bootreason 缓解 |
| 模块被用户手动禁用 | guard 仍计数/unsuspend；rescue flag 仍可能写；UI 显示模块 disabled，用户决定是否恢复 |
| guard 自己被用户删除 | 无救援；App 显示“guard 未安装”；文档给出重装步骤 |
| 充电模式/关机充电 | `service` 阶段可能不执行；不计数；不影响正常 boot |
| 时间回拨/时区 | 逻辑只用 `boot_id` 和 `/proc/uptime`；wall clock 只用于日志 |

---

## 5. 推荐架构

### 5.1 组件

```text
A. 公共 guard（独立于模块，推荐）
   /data/adb/nemu/boot_guard.sh                 主脚本（0755，root-only）
   /data/adb/post-fs-data.d/00nemu_boot_guard.sh  wrapper → boot_guard.sh post-fs-data
   /data/adb/service.d/00nemu_boot_guard.sh       wrapper → boot_guard.sh service
   状态：/data/adb/nemu/boot_guard/*
   配置：/data/adb/nemu/boot_guard.conf

B. 目标模块（可被禁用）
   /data/adb/modules/nemu_boot_autostart/service.sh
   - 启动 Nemu 前检查 rescue.flag
   - 命中则 exit 0，不 am start
   - 保留原有的 unsuspend/等待/补解冻作为 fallback

C. App（防重新冻结）
   BootGuardState（读 rescue.flag/state/module disable）
   BootFreezePolicy 增加 bootGuardTripped
   BootFreezeScheduler / BootFreezeWorker 检查 flag
   ShutdownSafety 成功解冻后写 clean_shutdown
   UI 显示救援状态/清除入口
```

### 5.2 正常启动流程

```text
boot
  -> post-fs-data.d/00nemu_boot_guard.sh post-fs-data
       begin_boot()
         - boot_id 不同 → fail_streak++
         - 上一个 boot 有 success/clean → fail_streak=0 后再 +1
         - < THRESHOLD → 只写 state
       （绝不调用 pm）
  -> service.d/00nemu_boot_guard.sh service
       begin_boot()（幂等）
       unsuspend_with_retry()（早）
       wait_boot_completed()
       unsuspend_with_retry()（晚）
       start_success_watchdog()
  -> nemu_boot_autostart/service.sh
       boot_guard.sh check（同步、只计数/触发）
       检查 rescue.flag
       未命中 → 原有流程：unsuspend、等 boot_completed、am start Nemu、补 unsuspend
       命中   → exit 0（不启动 App）
  -> success watchdog
       boot_completed + uptime >= 600 + bootanim stopped
       -> 写 success，fail_streak=0
```

### 5.3 bootloop 救援流程

```text
boot N（fail_streak 达到 3）
  -> post-fs-data.d/00nemu_boot_guard.sh post-fs-data
       begin_boot() → fail_streak=3
       trip_rescue():
         1. 给 /data/adb/modules/nemu_boot_autostart 写 disable
            （如果 modules_update 存在也写）
         2. 写 /data/adb/nemu/boot_guard/rescue.flag
         3. 写 rescue.log / state
         4. sync
       （不调用 pm）
  -> service.d/00nemu_boot_guard.sh service
       unsuspend_with_retry()
         - 默认 deadline 180s，每 5s 重试
         - 解析输出中的 "new suspended state: true"
         - 成功/部分成功/失败都写 rescue.log
       wait_boot_completed()
       unsuspend_with_retry()（第二次）
       start_success_watchdog()
  -> nemu_boot_autostart/service.sh
       disable 已存在 → ksud 根本不会执行它
  -> 用户打开 Nemu / App 被其他方式拉起
       BootGuardState 读到 rescue.flag
       BootFreezeScheduler 取消 nemu_boot_freeze
       BootFreezeWorker 在冻结前再次检查 → 跳过
       UI 显示“自动救砖已触发”
```

### 5.4 与现有 App 守卫的关系

现有 `BootFreezePolicy` 和新的 guard 是**两层**，不要合并：

| 层 | 关注点 | 数据 | 作用 |
|---|---|---|---|
| App `BootFreezePolicy` | 上一次**冻结尝试**后是否干净关机 | SharedPreferences `last_boot_freeze_*` / `last_shutdown_*` | 防止一次冻结导致的异常在下一 boot 继续冻结 |
| Shell guard | 连续 N 次 boot 是否**整体**未确认成功 | `/data/adb/nemu/boot_guard/*` | 防止持续 bootloop；自动禁用模块 + 尽早 unsuspend |

优先级：

1. `rescue.flag` 存在 → App 不调度、不冻结；模块不启动。
2. `BootFreezePolicy` 判定跳过 → 不冻结，但仍先 unsuspend。
3. 两者都允许 → 正常延迟冻结。

避免重复冻结/竞态：

- `BootFreezeScheduler.ensureScheduled()` 在建 WorkManager 任务前检查 rescue flag；如果存在，`cancelUniqueWork("nemu_boot_freeze")` 并 return。
- `BootFreezeWorker.doWork()` 在**残留 unsuspend 之后、policy 之前**再次检查 flag；如果存在，记录“已跳过：自动救砖已触发”，返回 success，不写 attempt、不冻结。
- `MainActivity.onCreate` 和 `BootWorker` 都调用 `ensureScheduled`，上面的检查覆盖所有入口。
- `BootReceiver` 不可靠，不作为主路径；即使它触发 `BootWorker`，也会走同一检查。
- 目标模块 `service.sh` 在 `am start` 前检查 flag；如果 guard 已经在 `post-fs-data` 写了 flag/disable，模块根本不会执行；如果只有 `service` 模式（late-load），模块脚本会先同步调用 `boot_guard.sh check`（只计数/触发，不调用 pm），然后再检查 flag，作为第二道防线。这能缩小 common `service.d` guard 是 `NoWait` 启动、模块脚本可能先跑的竞态窗口。
- 重复 `pm unsuspend`：guard、模块 `service.sh`、App `BootWorker` 都会调用；这是幂等的，允许重复。若要减少并发，可以给 guard 和模块脚本共用一个 `mkdir` 锁；不要用无限等待。
- 不要尝试把 shell 的 `fail_streak` 写进 App 的 SharedPreferences：early stage 读不到 CE 存储；两边各自维护，UI 通过 `BootGuardState` 读取 shell 状态即可。

---

## 6. 最小可行实现（MVI）

> 以下是实现建议，不是已经写好的代码。所有真机写入由用户执行。

### 6.1 新增/修改文件清单

**新增（建议）**

- `tools/nemu-boot-guard/boot_guard.sh` — guard 主脚本（源码；安装到 `/data/adb/nemu/boot_guard.sh`）。
- `tools/nemu-boot-guard/00nemu_boot_guard.post-fs-data.sh` — wrapper（安装到 `/data/adb/post-fs-data.d/00nemu_boot_guard.sh`）。
- `tools/nemu-boot-guard/00nemu_boot_guard.service.sh` — wrapper（安装到 `/data/adb/service.d/00nemu_boot_guard.sh`）。
- `app/src/main/java/com/nemu/hibernate/policy/BootGuardState.kt` — 读取 rescue flag/state/module disable。
- `app/src/test/java/com/nemu/hibernate/policy/BootGuardStateTest.kt`（可选，解析逻辑纯函数）。
- 测试：`SafetyTargetsScriptTest.kt`（校验脚本 15 个包名与 Kotlin 一致）。

**修改（后续实现，不在本次）**

- `tools/nemu-boot-autostart/service.sh`：启动前检查 flag；boot_completed 后才 `am start`；unsuspend 解析输出。
- `app/src/main/java/com/nemu/hibernate/policy/BootFreezePolicy.kt`：`BootFreezeInput` 增加 `bootGuardTripped`，新增规则。
- `app/src/main/java/com/nemu/hibernate/policy/BootFreezeScheduler.kt`：`ensureScheduled` 检查 flag。
- `app/src/main/java/com/nemu/hibernate/policy/BootFreezeWorker.kt`：构造 input 时传入 flag；在 policy 前再次检查。
- `app/src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt`：成功解冻后写 clean_shutdown。
- `app/src/main/java/com/nemu/hibernate/detection/DetectionModels.kt`：`describeGuard` 增加救援状态（可选）。
- UI（`DashboardScreen.kt` / `DetectionScreen.kt` / `MainActivity.kt`）：显示救援 banner/状态；提供清除入口。
- `docs/04-test-plan.md`：新增测试编号（见 §6.6）。
- `docs/README.md` / `PROJECT.md`：本文完成后更新状态（不变更“未实现”事实前不得写成已实现）。

**不修改**

- `module.prop` 的 `id` / 包名；模块版本号可在实现时递增。
- `SafetyTargets` 的 15 个包（除非未来策略变化；如果变化，必须同步脚本）。
- 任何分区、ABL/EFISP、boot image。

### 6.2 guard 主脚本（示意）

以下为结构示意，不是最终可执行文件；实现时需要 shellcheck 和真机验证。

```sh
#!/system/bin/sh
# Nemu boot guard. Install as /data/adb/nemu/boot_guard.sh (0755, root only).
# Wrappers:
#   /data/adb/post-fs-data.d/00nemu_boot_guard.sh -> exec ... post-fs-data
#   /data/adb/service.d/00nemu_boot_guard.sh       -> exec ... service
# Never call pm from post-fs-data.

umask 077

BASE="${NEMU_GUARD_BASE:-/data/adb/nemu}"
STATE_DIR="$BASE/boot_guard"
STATE="$STATE_DIR/state.conf"
SUCCESS="$STATE_DIR/success"
CLEAN="$STATE_DIR/clean_shutdown"
RESCUE="$STATE_DIR/rescue.flag"
LOG="$STATE_DIR/rescue.log"
LOCK="$STATE_DIR/lock"
WD_LOCK="$STATE_DIR/watchdog.lock"
CONF="$BASE/boot_guard.conf"
MODULE_ID="nemu_boot_autostart"
MODULE_DIR="${NEMU_MODULE_DIR:-/data/adb/modules/$MODULE_ID}"
MODULE_UPDATE_DIR="${NEMU_MODULE_UPDATE_DIR:-/data/adb/modules_update/$MODULE_ID}"
PM_BIN="${NEMU_PM_BIN:-/system/bin/pm}"

TARGETS="
com.hihonor.ouc
com.honor.yoyoappsug
com.honor.yoyocards
com.hihonor.suggestion
com.hihonor.sceneservice
com.hihonor.hiview
com.hihonor.hiviewpush
com.hihonor.hiviewtunnel
com.hihonor.numberidentity
com.hihonor.aipluginengine
com.hihonor.assistant
com.hihonor.magicvoice
com.hihonor.voiceengine
com.hihonor.brain
com.hihonor.mageruntime
"

MODE="${1:-}"
[ -n "$MODE" ] || case "$0" in
    *post-fs-data.d*) MODE=post-fs-data ;;
    *service.d*) MODE=service ;;
    *) MODE=service ;;
esac

now() { date +%s 2>/dev/null || echo 0; }
uptime_s() { cut -d. -f1 /proc/uptime 2>/dev/null || echo 0; }
boot_id() {
    cat /proc/sys/kernel/random/boot_id 2>/dev/null && return
    awk '/^btime/{print "btime_"$2}' /proc/stat 2>/dev/null
}

sget() { sed -n "s/^$1=//p" "$STATE" 2>/dev/null | tail -n1; }
cget() { sed -n "s/^$1=//p" "$CONF" 2>/dev/null | tail -n1; }
fget() { sed -n "s/^$2=//p" "$1" 2>/dev/null | tail -n1; }

load_conf() {
    THRESHOLD=$(cget THRESHOLD); STABILITY_SECONDS=$(cget STABILITY_SECONDS)
    UNSUSPEND_DEADLINE_SECONDS=$(cget UNSUSPEND_DEADLINE_SECONDS)
    case "$THRESHOLD" in ''|*[!0-9]*) THRESHOLD=3 ;; esac
    [ "$THRESHOLD" -ge 2 ] 2>/dev/null || THRESHOLD=2
    [ "$THRESHOLD" -le 5 ] 2>/dev/null || THRESHOLD=5
    case "$STABILITY_SECONDS" in ''|*[!0-9]*) STABILITY_SECONDS=600 ;; esac
    case "$UNSUSPEND_DEADLINE_SECONDS" in ''|*[!0-9]*) UNSUSPEND_DEADLINE_SECONDS=180 ;; esac
    [ "$UNSUSPEND_DEADLINE_SECONDS" -ge 30 ] 2>/dev/null || UNSUSPEND_DEADLINE_SECONDS=30
}

load_state() {
    LAST_BOOT_ID=$(sget last_boot_id)
    FAIL_STREAK=$(sget fail_streak)
    SUCCESS_BOOT_ID=$(sget success_boot_id)
    SUCCESS_EPOCH=$(sget success_epoch)
    SUCCESS_UPTIME=$(sget success_uptime)
    CLEAN_SHUTDOWN_BOOT_ID=$(sget clean_shutdown_boot_id)
    CLEAN_SHUTDOWN_EPOCH=$(sget clean_shutdown_epoch)
    CLEAN_SHUTDOWN_RESULT=$(sget clean_shutdown_result)
    RESCUE_COUNT=$(sget rescue_count)
    LAST_RESCUE_BOOT_ID=$(sget last_rescue_boot_id)
    LAST_RESCUE_EPOCH=$(sget last_rescue_epoch)
    case "$FAIL_STREAK" in ''|*[!0-9]*) FAIL_STREAK=0 ;; esac
    case "$RESCUE_COUNT" in ''|*[!0-9]*) RESCUE_COUNT=0 ;; esac
}

save_state() {
    tmp="$STATE.tmp.$$"
    {
        echo "format=1"
        echo "last_boot_id=$LAST_BOOT_ID"
        echo "last_boot_seen_epoch=$(now)"
        echo "fail_streak=$FAIL_STREAK"
        echo "success_boot_id=$SUCCESS_BOOT_ID"
        echo "success_epoch=$SUCCESS_EPOCH"
        echo "success_uptime=$SUCCESS_UPTIME"
        echo "clean_shutdown_boot_id=$CLEAN_SHUTDOWN_BOOT_ID"
        echo "clean_shutdown_epoch=$CLEAN_SHUTDOWN_EPOCH"
        echo "clean_shutdown_result=$CLEAN_SHUTDOWN_RESULT"
        echo "rescue_count=$RESCUE_COUNT"
        echo "last_rescue_boot_id=$LAST_RESCUE_BOOT_ID"
        echo "last_rescue_epoch=$LAST_RESCUE_EPOCH"
    } > "$tmp" && mv "$tmp" "$STATE" && chmod 0600 "$STATE" && sync
}

previous_boot_confirmed() {
    [ -n "$LAST_BOOT_ID" ] || return 1
    # 文件是权威来源：success 由 watchdog 写，clean_shutdown 由 App 写；
    # state 里的同名字段只是缓存/诊断。
    [ "$(fget "$SUCCESS" boot_id)" = "$LAST_BOOT_ID" ] && return 0
    [ "$(fget "$CLEAN" boot_id)" = "$LAST_BOOT_ID" ] &&
        [ "$(fget "$CLEAN" result)" = "ok" ] && return 0
    # 兼容 state 里已有的成功记录（例如文件被清理但 state 还在）
    [ "$SUCCESS_BOOT_ID" = "$LAST_BOOT_ID" ] && return 0
    [ "$CLEAN_SHUTDOWN_BOOT_ID" = "$LAST_BOOT_ID" ] &&
        [ "$CLEAN_SHUTDOWN_RESULT" = "ok" ] && return 0
    return 1
}

acquire_lock() {
    current=$(boot_id)
    if mkdir "$LOCK" 2>/dev/null; then
        echo "$current" > "$LOCK/boot_id"
        trap 'rm -rf "$LOCK"' EXIT INT TERM
        return 0
    fi
    old=$(cat "$LOCK/boot_id" 2>/dev/null)
    if [ "$old" != "$current" ]; then
        rm -rf "$LOCK" 2>/dev/null
        mkdir "$LOCK" 2>/dev/null || return 1
        echo "$current" > "$LOCK/boot_id"
        trap 'rm -rf "$LOCK"' EXIT INT TERM
        return 0
    fi
    return 1
}

log_rescue() {
    echo "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null) $*" >> "$LOG"
    # 可选：超过 64 KB 时 mv 到 .1
}

ensure_disable_file() {
    for d in "$MODULE_DIR" "$MODULE_UPDATE_DIR"; do
        [ -d "$d" ] || continue
        : > "$d/disable" 2>/dev/null && chmod 0644 "$d/disable" 2>/dev/null
    done
    sync
}

trip_rescue() {
    ensure_disable_file
    if [ -f "$RESCUE" ]; then
        return 0
    fi
    tmp="$RESCUE.tmp.$$"
    {
        echo "tripped=1"
        echo "boot_id=$CURRENT_BOOT_ID"
        echo "epoch=$(now)"
        echo "fail_streak=$FAIL_STREAK"
        echo "module=$MODULE_ID"
        if [ -f "$MODULE_DIR/disable" ]; then
            echo "module_disabled=1"
        else
            echo "module_disabled=0"
        fi
    } > "$tmp" && mv "$tmp" "$RESCUE" && chmod 0600 "$RESCUE" && sync
    RESCUE_COUNT=$((RESCUE_COUNT + 1))
    LAST_RESCUE_BOOT_ID="$CURRENT_BOOT_ID"
    LAST_RESCUE_EPOCH=$(now)
    save_state
    log_rescue "rescue triggered fail_streak=$FAIL_STREAK"
}

begin_boot() {
    CURRENT_BOOT_ID=$(boot_id)
    load_state
    if [ "$CURRENT_BOOT_ID" = "$LAST_BOOT_ID" ]; then
        # 同一 boot 的重复执行；如果上次在 save_state 和 trip_rescue
        # 之间被打断，这里补一次。
        if [ "$FAIL_STREAK" -ge "$THRESHOLD" ] && [ ! -f "$RESCUE" ]; then
            trip_rescue
        fi
        return 0
    fi
    if previous_boot_confirmed; then
        FAIL_STREAK=0
    fi
    FAIL_STREAK=$((FAIL_STREAK + 1))
    LAST_BOOT_ID="$CURRENT_BOOT_ID"
    save_state
    if [ "$FAIL_STREAK" -ge "$THRESHOLD" ]; then
        trip_rescue
    else
        log_rescue "boot attempt fail_streak=$FAIL_STREAK"
    fi
}

pm_cmd() {
    if command -v timeout >/dev/null 2>&1; then
        timeout 15 "$PM_BIN" "$@"
    else
        "$PM_BIN" "$@"
    fi
}

unsuspend_with_retry() {
    deadline=$(( $(uptime_s) + UNSUSPEND_DEADLINE_SECONDS ))
    attempts=0
    last_rc=1
    while [ "$(uptime_s)" -lt "$deadline" ]; do
        attempts=$((attempts + 1))
        out=$(pm_cmd unsuspend --user 0 $TARGETS 2>&1)
        last_rc=$?
        if [ "$last_rc" -eq 0 ] && ! printf '%s\n' "$out" | grep -q "new suspended state: true"; then
            log_rescue "unsuspend ok attempts=$attempts"
            return 0
        fi
        sleep 5
    done
    log_rescue "unsuspend incomplete attempts=$attempts last_rc=$last_rc"
    return 1
}

wait_boot_completed() {
    limit=$(( $(uptime_s) + ${1:-180} ))
    while [ "$(uptime_s)" -lt "$limit" ]; do
        [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && return 0
        sleep 5
    done
    return 1
}

start_success_watchdog() {
    current="$CURRENT_BOOT_ID"
    [ -n "$current" ] || current=$(boot_id)
    if ! mkdir "$WD_LOCK" 2>/dev/null; then
        old=$(cat "$WD_LOCK/boot_id" 2>/dev/null)
        [ "$old" = "$current" ] && return 0
        rm -rf "$WD_LOCK" 2>/dev/null
        mkdir "$WD_LOCK" 2>/dev/null || return 0
    fi
    echo "$current" > "$WD_LOCK/boot_id"
    (
        trap 'rm -rf "$WD_LOCK" 2>/dev/null' EXIT INT TERM
        limit=$(( $(uptime_s) + STABILITY_SECONDS + 300 ))
        while [ "$(uptime_s)" -lt "$limit" ]; do
            [ "$(boot_id)" = "$current" ] || exit 0
            if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] &&
               [ "$(getprop init.svc.bootanim 2>/dev/null)" = "stopped" ] &&
               [ "$(uptime_s)" -ge "$STABILITY_SECONDS" ]; then
                # 可选：pidof com.android.systemui >/dev/null 2>&1
                tmp="$SUCCESS.tmp.$$"
                {
                    echo "boot_id=$current"
                    echo "epoch=$(now)"
                    echo "uptime=$(uptime_s)"
                } > "$tmp" && mv "$tmp" "$SUCCESS" && chmod 0600 "$SUCCESS" && sync
                load_state
                SUCCESS_BOOT_ID="$current"
                SUCCESS_EPOCH=$(now)
                SUCCESS_UPTIME=$(uptime_s)
                FAIL_STREAK=0
                save_state
                log_rescue "boot confirmed success uptime=$SUCCESS_UPTIME"
                exit 0
            fi
            sleep 30
        done
    ) >/dev/null 2>&1 &
}

case "$MODE" in
    post-fs-data|check)
        # check 供目标模块 service.sh 在 am start 前同步调用：
        # 只做计数/触发，不调用 pm，不启动 watchdog。
        mkdir -p "$STATE_DIR" 2>/dev/null
        chmod 0700 "$BASE" "$STATE_DIR" 2>/dev/null
        load_conf
        acquire_lock || exit 0
        begin_boot
        rm -rf "$LOCK" 2>/dev/null
        trap - EXIT INT TERM
        ;;
    service)
        mkdir -p "$STATE_DIR" 2>/dev/null
        chmod 0700 "$BASE" "$STATE_DIR" 2>/dev/null
        load_conf
        CURRENT_BOOT_ID=$(boot_id)
        if acquire_lock; then
            begin_boot
            unsuspend_with_retry
            rm -rf "$LOCK" 2>/dev/null
            trap - EXIT INT TERM
        fi
        wait_boot_completed 180
        unsuspend_with_retry
        start_success_watchdog
        ;;
    clear)
        # App 在用户确认后调用；不自动重新启用模块
        load_conf
        CURRENT_BOOT_ID=$(boot_id)
        load_state
        FAIL_STREAK=0
        SUCCESS_BOOT_ID="$CURRENT_BOOT_ID"
        SUCCESS_EPOCH=$(now)
        SUCCESS_UPTIME=$(uptime_s)
        LAST_BOOT_ID="$CURRENT_BOOT_ID"
        save_state
        rm -f "$RESCUE" 2>/dev/null
        sync
        ;;
    *)
        echo "usage: $0 post-fs-data|service|check|clear" >&2
        exit 2
        ;;
esac
```

实现注意：

- 上文的 `mkdir` 锁在 `service` 模式下如果 `acquire_lock` 失败，会跳过第一次 `begin_boot`/unsuspend，但后面仍会 `wait_boot_completed` + 第二次 unsuspend + watchdog；另一个实例正在做第一次。可以接受。
- `trap` 在 `service` 模式手动释放锁后要清掉，避免退出时误删别人重建的锁；示意里已加 `trap - ...`。
- `start_success_watchdog` 在 rescue flag 存在时仍然运行：当前 boot 稳定就清零 `fail_streak`，但保留 flag；用户仍需确认。
- `clear` 模式只清计数和 flag，不删 `disable`；模块是否重新启用由用户/管理器决定。
- 脚本中的 `TARGETS` 必须与 `SafetyTargets.suspendPackageNames` 完全一致；建议加测试（§6.6）。
- 如果直接写模块目录的 `disable` 被 SELinux 拒绝，先检查 `ls -lZ`；备选是调用 `/data/adb/ksud module disable nemu_boot_autostart`（需要 `ksud` 可用），但 guard 不应依赖它完成主流程。
- 如果 `/data/adb/nemu` 在 SELinux 下不可写，先检查 `ls -lZ`；备选目录 `/data/adb/ksu/nemu_boot_guard`，但 App 读取路径要同步。

### 6.3 目标模块 `service.sh` 的改动

在现有脚本开头加：

```sh
RESCUE_FLAG=/data/adb/nemu/boot_guard/rescue.flag
GUARD=/data/adb/nemu/boot_guard.sh

# 先同步做一次计数/触发检查（不调用 pm），避免 common service.d 的
# guard 与模块 service.sh 在 late-load 下的调度竞态。
if [ -x "$GUARD" ]; then
    "$GUARD" check >/dev/null 2>&1
fi

if [ -f "$RESCUE_FLAG" ]; then
    # 自动救砖已触发：不启动 Nemu，避免重新调度开机冻结。
    exit 0
fi
```

说明：`check` 模式在 guard 里是“post-fs-data 的快速版”，只做 boot_id 计数和可能的 `disable`/rescue flag 写入；它不调用 `pm`，不启动 watchdog。common `service.d` 的 guard 仍然负责 early/late unsuspend。

并建议：

- Phase 2 结束后，只有确认 `sys.boot_completed=1` 才 `am start`：

```sh
if [ "$(getprop sys.boot_completed)" = "1" ]; then
    sleep 5
    am start -n com.nemu.hibernate/.MainActivity >/dev/null 2>&1
fi
```

- `unsuspend_all` 解析输出：

```sh
# 保留现有 `command -v timeout` 回退；不要在 pm 上无限等待。
if command -v timeout >/dev/null 2>&1; then
    out=$(timeout 15 pm unsuspend --user 0 $TARGETS 2>&1)
else
    out=$(pm unsuspend --user 0 $TARGETS 2>&1)
fi
rc=$?
if [ "$rc" -eq 0 ] && ! printf '%s\n' "$out" | grep -q "new suspended state: true"; then
    return 0
fi
return 1
```

- 如果 guard 已安装，可以让 guard 做主要 unsuspend，模块脚本保留 fallback；两者并发是幂等的。若要严格串行，共用 `/data/adb/nemu/boot_guard/unsuspend.lock`。

### 6.4 App 侧改动

#### 6.4.1 新增 `BootGuardState.kt`

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.PrivilegeBackend
import com.nemu.hibernate.privilege.RootPrivilegeBackend

data class BootGuardSnapshot(
    val rescueActive: Boolean,
    val moduleDisabled: Boolean,
    val failStreak: Int,
    val lastRescueEpoch: Long,
    val rawFlag: String?
)

object BootGuardState {
    private const val DIR = "/data/adb/nemu/boot_guard"
    private const val FLAG = "$DIR/rescue.flag"
    private const val STATE = "$DIR/state.conf"
    private const val MODULE_DISABLE =
        "/data/adb/modules/nemu_boot_autostart/disable"

    suspend fun read(backend: PrivilegeBackend?): BootGuardSnapshot? {
        if (backend !is RootPrivilegeBackend) return null
        val flag = backend.exec("cat $FLAG 2>/dev/null")
        val state = backend.exec("cat $STATE 2>/dev/null")
        val disabled = backend.exec(
            "[ -f $MODULE_DISABLE ] && echo 1 || echo 0"
        )
        val moduleDisabled = disabled.output.trim() == "1"
        if (!flag.ok && !state.ok && !moduleDisabled) return null
        return BootGuardSnapshot(
            rescueActive = flag.ok && flag.output.contains("tripped=1"),
            moduleDisabled = moduleDisabled,
            failStreak = parseValue(state.output, "fail_streak")?.toIntOrNull() ?: 0,
            lastRescueEpoch =
                parseValue(state.output, "last_rescue_epoch")?.toLongOrNull() ?: 0L,
            rawFlag = flag.output.takeIf { flag.ok }
        )
    }

    suspend fun clear(backend: PrivilegeBackend?): Boolean {
        if (backend !is RootPrivilegeBackend) return false
        val r: ShellResult = backend.exec(
            "/data/adb/nemu/boot_guard.sh clear 2>/dev/null || " +
                "rm -f $FLAG"
        )
        return r.ok
    }

    private fun parseValue(text: String, key: String): String? =
        text.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
}
```

> 上面的 `parseValue` 返回原始字符串，再由调用方转成 `Int`/`Long`；不要写同名不同返回类型的重载。

#### 6.4.2 `BootFreezePolicy`

```kotlin
data class BootFreezeInput(
    // ... existing fields ...
    val bootGuardTripped: Boolean = false
)

fun evaluate(input: BootFreezeInput): BootFreezeDecision {
    if (!input.bootFreezeEnabled) {
        return BootFreezeDecision(false, "开机自动冻结未开启")
    }
    if (input.bootGuardTripped) {
        return BootFreezeDecision(false, "自动救砖已触发，跳过开机自动冻结")
    }
    // ... existing rules ...
}
```

#### 6.4.3 `BootFreezeScheduler`

```kotlin
suspend fun ensureScheduled(context: Context) {
    val appContext = context.applicationContext
    val workManager = WorkManager.getInstance(appContext)
    val settings = BootFreezeSettings(appContext)

    if (!settings.bootFreezeEnabled) {
        runCatching { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
        return
    }

    val guard = BootGuardState.read(
        BackendManager(Settings(appContext)).getRoot()
    )
    if (guard?.rescueActive == true) {
        ActionLog.warn("自动救砖已触发：取消开机自动冻结")
        runCatching { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
        return
    }

    // ... existing bootCount / hasWorkForBoot / enqueue ...
}
```

注意：只在 `rescueActive` 时阻止；`moduleDisabled` 单独出现可能只是用户手动禁用模块，不应改变 App 原有的手动冻结语义，但 UI 应展示。

#### 6.4.4 `BootFreezeWorker`

在残留 unsuspend 之后、`BootFreezePolicy.evaluate` 之前：

```kotlin
val guard = BootGuardState.read(
    backend as? RootPrivilegeBackend
)
if (guard?.rescueActive == true) {
    recordResult(
        bootSettings = bootSettings,
        bootCount = bootCount,
        result = "已跳过：自动救砖已触发",
        detail = "rescue.flag active; moduleDisabled=${guard.moduleDisabled}",
        succeeded = false
    )
    ActionLog.warn("自动救砖已触发：跳过本次开机冻结")
    BootFreezeScheduler.cancel(applicationContext)
    return Result.success()
}
```

然后把 `bootGuardTripped = guard?.rescueActive == true` 传入 `BootFreezeInput`。即使上面的早退被绕过，policy 也会再拦一次。

#### 6.4.5 `ShutdownSafety` 写 clean marker

在 `runBlockingUnfreeze` 成功分支里，调用一个短的 root 命令：

```kotlin
private suspend fun writeCleanShutdown(backend: PrivilegeBackend, result: SafetyResult) {
    if (!result.ok || backend !is RootPrivilegeBackend) return
    val bootId = backend.exec("cat /proc/sys/kernel/random/boot_id")
        .output.trim().takeIf { it.isNotEmpty() } ?: return
    val cmd = buildString {
        append("mkdir -p /data/adb/nemu/boot_guard; ")
        append("t=/data/adb/nemu/boot_guard/clean.tmp.${System.currentTimeMillis()}; ")
        append("printf 'boot_id=%s\\nepoch=%s\\nresult=ok\\n' ")
        append("'$bootId' \"$(date +%s)\" > \"$t\"; ")
        append("mv \"$t\" /data/adb/nemu/boot_guard/clean_shutdown; chmod 0600 /data/adb/nemu/boot_guard/clean_shutdown; sync")
    }
    backend.exec(cmd)
}
```

注意：

- 这段必须在 8s 关机窗口内完成；如果 unfreeze 已经用掉大部分时间，宁可放弃 marker，也不要超时。
- `bootId` 是 UUID，固定格式；如果担心注入，先用正则校验 `[0-9a-f-]{36}`。
- 如果 App 没有 root（Shizuku 关机），不要写；下一次 boot 计数可能 +1，这是保守的。
- 可选：在 `Settings.lastShutdownResult` 里附带 marker 写入结果，便于诊断。

#### 6.4.6 UI

最小字段：

- “自动救砖：正常 / 已触发（fail_streak=N，模块已禁用）”
- “上次救援：时间 / boot_id / unsuspend 结果”
- 按钮“清除救援标记并恢复自动冻结”：二次确认；调用 `BootGuardState.clear(backend)`；可选再调用 `/data/adb/ksud module enable nemu_boot_autostart` 或移除 `disable`。UI 不自动执行，必须用户确认。
- 如果 guard 未安装：显示“自动救砖 guard 未安装（/data/adb/nemu/boot_guard.sh）”，给安装提示。

#### 6.4.7 可选：App 侧 success worker

如果 shell watchdog 在真机上不可靠（被 lmkd 杀死），可以增加一个 WorkManager 任务：boot 后延迟 10 分钟，若 `sys.boot_completed=1` 且 root 可用，直接写 `success` 文件（或在 guard 中增加一个 `success` 模式供 App 调用）。它与 shell watchdog 是冗余的，二选一成功即可。该任务不参与冻结，失败不影响安全路径。

### 6.5 安装、升级与回滚（用户执行）

**安装（用户用 root 终端/FinaSU 管理器执行）**

```sh
# 1. 目录
mkdir -p /data/adb/nemu/boot_guard /data/adb/post-fs-data.d /data/adb/service.d
chmod 0700 /data/adb/nemu /data/adb/nemu/boot_guard

# 2. 主脚本
#    把 boot_guard.sh 放到 /data/adb/nemu/boot_guard.sh
chmod 0755 /data/adb/nemu/boot_guard.sh

# 3. wrappers
printf '#!/system/bin/sh\nexec /data/adb/nemu/boot_guard.sh post-fs-data\n' \
  > /data/adb/post-fs-data.d/00nemu_boot_guard.sh
printf '#!/system/bin/sh\nexec /data/adb/nemu/boot_guard.sh service\n' \
  > /data/adb/service.d/00nemu_boot_guard.sh
chmod 0755 /data/adb/post-fs-data.d/00nemu_boot_guard.sh \
            /data/adb/service.d/00nemu_boot_guard.sh

# 4. 配置（可选）
printf 'THRESHOLD=3\nSTABILITY_SECONDS=600\nUNSUSPEND_DEADLINE_SECONDS=180\n' \
  > /data/adb/nemu/boot_guard.conf
chmod 0600 /data/adb/nemu/boot_guard.conf
```

**升级**：只替换 `/data/adb/nemu/boot_guard.sh`；wrappers/state 不动。替换后 `sync`。

**回滚**：

```sh
rm -f /data/adb/post-fs-data.d/00nemu_boot_guard.sh
rm -f /data/adb/service.d/00nemu_boot_guard.sh
rm -rf /data/adb/nemu/boot_guard /data/adb/nemu/boot_guard.sh /data/adb/nemu/boot_guard.conf
# 如果模块被 guard 禁用，按用户意愿重新启用：
#   在 FinaSU 管理器中启用 nemu_boot_autostart，或删除其 disable 文件后重启
```

回滚不涉及分区/boot。

### 6.6 测试计划（不诱导真 bootloop）

**静态/单测**

- `BootFreezePolicyTest`：新增 `bootGuardTripped=true` → `shouldRun=false`，reason 明确；现有用例默认 false 不回归。
- `BootGuardStateTest`：解析 flag/state 的边界（缺失、半写、非数字、旧 epoch）。
- `SafetyTargetsScriptTest`：读取 `tools/nemu-boot-guard/boot_guard.sh` 的 `TARGETS` 和 `SafetyTargets.kt` 的 15 个包名，断言集合相等。若测试不方便读 Kotlin 源码，可在构建脚本里生成一份 15 个包名的清单供两边引用。
- shellcheck（可用时）+ `sh -n` 语法检查。

**只读设备检查（用户执行，不改状态）**

```sh
getprop ro.boot.bootreason
getprop sys.boot.reason
cat /proc/sys/kernel/random/boot_id
ls -lZ /data/adb /data/adb/modules/nemu_boot_autostart
command -v timeout; command -v pidof; command -v sync
getprop init.svc.bootanim
pidof com.android.systemui
```

目的：确认 `ro.boot.bootreason` 的实际取值、`/data/adb/nemu` 的 SELinux context、toybox 工具是否存在、SystemUI 进程名。**不要**在没有这些证据前硬编码 bootreason 白名单。

**分阶段演练（用户执行；不需要真实 bootloop）**

1. 先用配置允许的最大值 `THRESHOLD=5` 安装 guard；只做一次正常重启；检查：
   - `state.conf` 的 `fail_streak` 在 boot 开始为 1（当前这次未确认尝试）；
   - 约 10 分钟后 `success` 出现，`fail_streak=0`；
   - 模块 `service.sh` 正常启动 Nemu；
   - `rescue.flag` 不存在。
   > 注意：配置阈值 clamp 在 2..5；写 99 会被压成 5，不能用来“永不触发”。如果要完全不触发，请临时把 `THRESHOLD` 设为 5 并只做一次重启。
2. 模拟计数：用 root 终端把 `state.conf` 改成 `last_boot_id=<一个不等于 success_boot_id/clean_shutdown_boot_id 的假值>`、`fail_streak=2`；重启；预期第 3 次启动触发：
   - 模块目录出现 `disable`；
   - `rescue.flag` 出现；
   - `rescue.log` 有 unsuspend 结果；
   - Nemu 没有被模块自动启动；
   - 15 个目标 `suspended=false`（用 `dumpsys package` 或 Nemu 检测页确认）。
3. 打开 Nemu：应显示救援 banner；`BootFreezeScheduler` 没有建 `nemu_boot_freeze`；手动触发“立即冻结”是否仍可用（按 UI 设计决定；建议救援期间禁用自动冻结，手动操作需二次确认）。
4. 清除救援标记 + 重新启用模块；正常重启；确认 `rescue.flag` 消失、计数归零、自动冻结恢复。
5. 不要用真实关键包制造 bootloop；不要在主设备上验证“system_server 起不来”的场景。

**safe mode 演练（可选，需持久 KSU）**

- 在第一屏后连续按音量下 3 次；确认所有模块被 `disable`，`service.d` 本次未执行；退出 safe mode 后公共 guard 恢复、模块仍 disabled。不要在只能 late-load 的设备上期待这个行为。

---

## 7. 计数器放在哪里：权限、持久性、安全性

| 位置 | 持久性 | 权限/可见性 | App 读取 | 安全性 | 结论 |
|---|---|---|---|---|---|
| `/data/adb/nemu/boot_guard/` | 跨重启、跨 OTA、跨模块更新；恢复出厂清除 | root-only（0700/0600）；adb shell 默认不可读 | root 后端可读 | 高；只有 root 能改 | **推荐** |
| `/data/adb/modules/<id>/` | 跨重启；模块 update 时目录替换（`disable` 会保留，其他文件可能丢） | root-only；模块管理器可见 | root | 中；状态与模块生命周期耦合 | 只放 `disable`，不放计数器 |
| `/data/local/tmp/` | 跨重启；可能被清理 | shell 可写、adb 可读写；SELinux 宽松 | 不需要 root | **低**；USB 调试已开，任何 adb shell 可篡改/重置计数，抑制救援 | 不推荐作为可信状态；最多做非安全提示 |
| App private `/data/data/.../shared_prefs` | 跨重启；卸载清除 | App 私有；CE 存储解锁前不可用 | App 自身 | 中；早期 shell 读不到，且用户清数据会丢 | 不适合 early counter；只用于 App 侧展示/原有 policy |
| App external files dir | 跨重启；卸载清除 | App + 外部存储；root 可写 | App 自身 | 中；可能在解锁前不可用 | 可作为救援通知的副本，不是主状态 |
| `/data/adb/ksu/` | 跨重启；KSU 工作目录 | root-only；已有 SELinux context | root | 高 | 备选；如果 `/data/adb/nemu` 写入被 SELinux 拒绝 |
| `persist.*` 属性 | 跨重启 | 属性全局可见；写入需 resetprop | 可读 | 低-中；属性污染、调试困难 | 不推荐 |
| `/metadata/...` | 跨重启；用于 preinit | root-only | root | 中；与 preinit 机制耦合 | 不推荐 |

补充：

- 所有状态文件用原子写（临时文件 + `mv` + `sync`），避免掉电半写。
- 状态目录 0700、文件 0600；不要 `chmod 0777`。
- 如果 `/data/adb/nemu` 在 SELinux 下不可写，先 `ls -lZ` 检查；可改到 `/data/adb/ksu/nemu_boot_guard`，但 App 的读取路径必须同步。不要为了方便改成 `/data/local/tmp`。
- `rescue.flag` 不是安全凭证，只是 root-only 状态；不要把它当认证。

---

## 8. 明确不建议的方案

1. **在 `post-fs-data` 里循环/阻塞等待 `pm`**：system_server 还没起，等不到；35s 共享预算会被耗尽；拖慢启动。只做文件计数和 `disable`。
2. **无限循环/无超时重试**：`service.d` 脚本会长期占用进程，可能被 lmkd 杀；没有收益。所有 `pm` 循环必须有 uptime deadline。
3. **把救援逻辑放进要被禁用的 `nemu_boot_autostart` 模块**：禁用模块会同时禁用救援；下次 boot 无人 unsuspend。
4. **修改分区、boot/vbmeta、vendor、ABL/EFISP，或刷 boot**：违反项目约束，风险高，本文不涉及。
5. **直接编辑 `/data/system/users/0/package-restrictions.xml` 或 package manager 数据库**：格式随 Android 版本变化，易损坏；`pm` 是唯一受支持路径。
6. **依赖 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` 广播**：HONOR 的 `HwMtmBroadcastResourceManager` 会拦截；bootloop 时 UI 也不可用。
7. **依赖用户点 UI**：救援必须 autonomous；UI 只用于事后确认/清除。
8. **把计数器放 `/data/local/tmp`**：adb shell 可篡改，救援可被静默抑制。
9. **自动重新启用模块**：根因未确认时可能再次 bootloop；必须用户确认。
10. **卸载/禁用 Nemu 自身或系统包**：不在范围；会引入新的恢复问题。
11. **用 `adb reboot` 作为日常重启/验收**：不触发 `ACTION_SHUTDOWN`，不会写 clean marker，容易把正常重启计成失败；用电源菜单/`svc power reboot`。
12. **在 `post-fs-data` 里 `am start`/`pm`/`settings`**：相应服务未就绪；只做文件操作。
13. **假设 safe mode 会清 suspend**：不会；safe mode 只禁用模块/跳过脚本。
14. **在 late-load 场景声称“已实现早期自动救砖”**：late-load 之前没有脚本执行机会，必须如实说明边界。

---

## 9. 风险与边界

| 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|
| root 仅 late-load，bootloop 时到不了 late-load | 高：救援完全不执行 | 当前配置下高 | 第一决策点：确认/改为持久 root；否则明确“只做 late-load 后清理” |
| system_server 起不来，`pm` 不可用 | 高：无法 unsuspend | 中 | `post-fs-data` 计数/禁用模块；safe mode/recovery/fastboot；不要编辑 PM 数据库 |
| 误判：连续 3 次快速重启（无 clean marker） | 中：自动禁用模块、解冻 | 中 | 阈值 3/4/5；clean marker；bootreason 提示；UI 清除；文档提醒 |
| 救援禁用模块后 Nemu 不再自动启动 | 中：SafetyService 不再自动武装 | 高 | 独立 guard 继续 unsuspend；UI 提示；用户手动打开 Nemu；可选通知 |
| watchdog 被 lmkd 杀死 | 中：下一次 boot 计数 +1，可能误救援 | 低-中 | App 侧 10 分钟 success worker 冗余；clean marker；阈值 |
| guard 与模块/App 重复 unsuspend | 低：并发但幂等 | 中 | 共享锁或接受幂等；日志区分 |
| guard 与 App 重复冻结 | 高：救援后再次 suspend | 中 | `rescue.flag` 在 scheduler/worker 双重检查；模块 `am start` 前检查 |
| 15 个包名在脚本/Kotlin 漂移 | 高：漏解冻关键目标 | 中 | 单测比对；构建时生成清单 |
| SELinux 拒绝写 `/data/adb/nemu` | 中：guard 不工作 | 低-中 | 先 `ls -lZ` 验证；备选 `/data/adb/ksu/nemu_boot_guard`；不要改全局 SELinux |
| App 读取 rescue flag 时 root 暂时不可用 | 中：手动打开 App 可能重新冻结 | 低 | 模块已被禁用，只有手动打开 App 才会调度；UI 提示无 root；可选把救援状态镜像到 `Settings.Global` 作为副本 |
| 模块 update 覆盖 `disable` | 低：下次 boot 又执行 | 低 | `handle_updated_modules` 保留；同时写 modules/modules_update |
| safe mode 禁用所有模块（包括 guard 模块） | 中：救援暂停 | 中 | 用 common service.d guard（不是模块）；退出 safe mode 后恢复；用户重新启用模块 |
| 脚本 bug 导致 boot 延迟 | 中：启动变慢 | 低 | `post-fs-data` 只做文件操作；`service` 有 deadline；shellcheck/演练 |
| 用户恢复出厂/清 `/data` | 低：guard 和 state 丢失 | 低 | 文档重装；不涉及分区 |
| `pm unsuspend` 仍被其他 suspendingPackage 持有 | 中：目标仍 suspend | 低 | 解析 `new suspended state: true`；retry；日志；人工 `pm unsuspend` |
| `disable-user` 不会被 unsuspend 恢复 | 中：numberidentity/DM 仍停用 | 低-中 | 明确救援只覆盖 suspend；UI 提供“全部恢复”手动路径；不要把 disable-user 当 suspend |
| OTA/充电模式被计数 | 低：可能误救援 | 中 | 可选 bootreason 提示；clean marker；阈值；用户清除 |
| 时间回拨/时区 | 低：日志时间不准 | 中 | 逻辑只用 boot_id/uptime；wall clock 只写日志 |

**必须写进用户文档的边界**：

- 本功能不覆盖“内核/ABL/boot image 在 `/data/adb` 可访问前失败”的 bootloop。
- 不覆盖“system_server 完全起不来且没有 safe mode/recovery”的场景。
- 不覆盖 `pm disable-user` 造成的启动问题。
- 不承诺 100%；救援失败时用户仍需 fastboot/recovery/售后。
- 当前 late-load 配置下，它不是早期自动救援；只有持久 root 才是。

---

## 10. 需要主会话确认的决策点

1. **root 部署方式（第一优先）**：FinaSU/KernelSU 是否随内核启动？
   - 如果是：本设计可以做早期计数/禁用 + service 阶段 unsuspend。
   - 如果否（GhostLock late-load）：只能做 late-load 后的清理；是否接受这个范围，或是否计划改变 root 部署？本文不涉及具体刷写方案。
2. **阈值 N**：默认 3 是否可接受？是否改成 4/5 以减少误报？是否允许用户在 App 里改（写 `/data/adb/nemu/boot_guard.conf`）？
3. **guard 放置方式**：推荐 common `post-fs-data.d` + `service.d` 脚本；是否接受手工安装（不在 FinaSU 模块管理器里显示）？如果不接受，是否接受第二个 `nemu_boot_guard` 模块（safe mode 会禁用它）？
4. **救援范围**：只对 15 个 suspend 目标 `pm unsuspend`，还是也要对 `com.hihonor.numberidentity`（反诈 disable-user 模式）做 `pm enable`？运营商 DM 三件套是否也恢复？建议默认只 unsuspend，disable-user 走 UI 的“全部恢复”。
5. **成功判定**：`sys.boot_completed=1` + 600s + `init.svc.bootanim=stopped` 是否足够？是否加 `pidof com.android.systemui`？`ro.boot.bootreason` 是否用于减少误报（需要真机只读采样后再定）？
6. **自动禁用后的行为**：救援触发后是否完全不启动 Nemu（推荐），还是启动 Nemu 但不自动冻结（保留 SafetyService）？如果完全不启动，用户需要手动打开 App 才能重新武装关机解冻。
7. **救援 flag 的清除**：必须用户确认（推荐），还是 App 在连续 M 次成功 boot 后自动清除？建议不自动。
8. **模块 `service.sh` 的 unsuspend**：保留原 fallback，还是完全交给 guard？建议保留但解析输出；如果保留，是否需要共享锁避免与 guard 并发？
9. **状态目录**：`/data/adb/nemu/boot_guard` 是否可写（SELinux）？如果不行，是否接受 `/data/adb/ksu/nemu_boot_guard`？
10. **App UI**：救援 banner 放在 Dashboard 还是 Detection 页？是否需要通知？清除按钮是否需要二次确认和 root 授权？
11. **测试策略**：是否允许用“模拟 state 计数”的方式做救援演练（推荐），而不是真实制造 bootloop？是否允许在备用机/非关键包上做一次真实 suspend bootloop？
12. **文档/交付**：本文是设计稿；实现后需要更新 `docs/04-test-plan.md`、`docs/README.md`、`PROJECT.md`。在真机验收前，所有状态保持“未实现/未实测”。

---

## 附录 A：15 个安全目标

| # | 包名 | 来源 |
|---|---|---|
| 1 | `com.hihonor.ouc` | `Targets.all` |
| 2 | `com.honor.yoyoappsug` | `Targets.all` |
| 3 | `com.honor.yoyocards` | `Targets.all` |
| 4 | `com.hihonor.suggestion` | `Targets.all` |
| 5 | `com.hihonor.sceneservice` | `Targets.all` |
| 6 | `com.hihonor.hiview` | `CloudTargets.telemetry` |
| 7 | `com.hihonor.hiviewpush` | `CloudTargets.telemetry` |
| 8 | `com.hihonor.hiviewtunnel` | `CloudTargets.telemetry` |
| 9 | `com.hihonor.numberidentity` | `AntiFraudPolicy.safetyPackages()` |
| 10 | `com.hihonor.aipluginengine` | `AiTargets.all` |
| 11 | `com.hihonor.assistant` | `AiTargets.all` |
| 12 | `com.hihonor.magicvoice` | `AiTargets.all` |
| 13 | `com.hihonor.voiceengine` | `AiTargets.all` |
| 14 | `com.hihonor.brain` | `AiTargets.all` |
| 15 | `com.hihonor.mageruntime` | `AiTargets.all` |

运营商 DM 三件套（`com.hihonor.regservice`、`com.hihonor.android.UEInfoCheck`、`com.hihonor.android.AutoRegSms`）是 `disable-user`，不在上表；`pm unsuspend` 不会恢复它们。

## 附录 B：源码证据

**FinaSU（本机变体）**

- `D:\win_root\refs\finasu\kernel\runtime\ksud_integration.c`
  - `KERNEL_SU_RC`：post-fs-data / nonencrypted / trigger_restart_framework / sys.boot_completed
  - `ksu_is_safe_mode()`：`ksu_late_loaded` 时返回 false；音量下键计数
- `D:\win_root\refs\finasu\kernel\core\init.c`
  - `ksu_late_loaded = (current->pid != 1)`（可加载模块场景）
- `D:\win_root\refs\finasu\userspace\ksud\src\init_event.rs`
  - `on_post_fs_data()`：safe mode → 公共 post-fs-data.d → handle updates → prune → regenerate_preinit → 模块 post-fs-data → mount/post-mount
  - `run_stage()`：safe mode 直接 return；公共 stage.d → metamodule → 模块 stage
  - `on_services()`：`report_services()` 去重 → `run_stage("service", NoWait)`
  - `on_boot_completed()`：`run_stage("boot-completed", NoWait)`
- `D:\win_root\refs\finasu\userspace\ksud\src\module.rs`
  - `foreach_module(Active, ...)` 检查 `disable`
  - `exec_stage_script()` 使用 `foreach_active_module`
  - `disable_all_modules()` / `mark_all_modules()`
  - `handle_updated_modules()` 保留 `disable`
  - `regenerate_preinit()` / `next_boot_modules()`
- `D:\win_root\refs\finasu\userspace\ksud\src\late_load.rs`
  - late-load → post-mount → service → boot-completed；不跑 post-fs-data
- `D:\win_root\refs\finasu\userspace\ksud\src\defs.rs`
  - `BOOT_STAGE_TIMEOUT = 35s`
- `D:\win_root\refs\finasu\userspace\ksud\src\utils.rs`
  - `is_safe_mode()`：`persist.sys.safemode` / `ro.sys.safemode` / kernel safe mode

**KernelSU（上游）**

- `D:\win_root\refs\kernelsu\kernel\runtime\ksud_integration.c`
- `D:\win_root\refs\kernelsu\userspace\ksud\src\init_event.rs`
- `D:\win_root\refs\kernelsu\userspace\ksud\src\module.rs`
- `D:\win_root\refs\kernelsu\userspace\ksud\src\late_load.rs`
- `D:\win_root\refs\kernelsu\website\docs\guide\rescue-from-bootloop.md`（safe mode 的用户说明）

**Nemu**

- `D:\win_root\nemu\tools\nemu-boot-autostart\service.sh`
- `D:\win_root\nemu\tools\nemu-boot-autostart\module.prop`
- `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\BootFreezeWorker.kt`
- `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\BootFreezePolicy.kt`
- `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\BootFreezeScheduler.kt`
- `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\SafetyTargets.kt`
- `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\SafetyUnfreezer.kt`
- `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\ShutdownSafety.kt`
- `D:\win_root\nemu\docs\07-boot-freeze-logging-ui-design.md`
- `D:\win_root\nemu\docs\01-device-and-findings.md`
- `D:\win_root\nemu\docs\03-design.md`
- `D:\win_root\nemu\docs\04-test-plan.md`

## 附录 C：现有 `service.sh` 的三个可改进点

1. `unsuspend_all` 只看 exit code；应解析 `new suspended state: true`，否则会把“exit 0 但没清掉”当成成功。
2. Phase 2 等待循环结束后没有再次确认 `sys.boot_completed=1` 就 `am start`；慢启动/bootloop 时可能提前启动 App。
3. `TARGETS` 与 Kotlin 的 15 个目标没有同步校验；建议加单测/生成清单。

这三项在实现自动救砖时应一并处理，否则 guard 的可靠性会被现有脚本的假成功/提前启动抵消。
