# 01 设备与实测发现

> 记录时间：2026-11-21
> 初次审计为只读。2026-11-21 后续按用户要求，通过 PackageManager 临时切换了 MagicVoice/Hiview 的 user 0 组件/包启用状态，并回滚；没有卸载 APK、刷写或擦除原始分区，也没有重启手机。详见 §6.1。

## 1. 设备与系统

| 项 | 值 |
|---|---|
| 型号 | HONOR AAP-AN00 |
| 系统 | MagicOS 10.0.0.102（`AAP-AN00 10.0.0.102(SP1C00E100R101P1)`） |
| Android | 16（SDK 36） |
| 构建指纹 | `HONOR/AAP-AN00/HNAAP:16/HONORAAP-AN00/10DLDLD102SP1C00E100:user/release-keys` |
| 安全补丁 | 2025-11-01 |
| ABI | arm64-v8a |
| 构建类型 | `user` / `ro.debuggable=0` / `ro.secure=1` |
| 内核 | `6.12.23-android16-5-gcd85666d7884-abogki452537391-4k`（来自 AGENTS.md） |
| 当前槽位 | `a`，GPT 逻辑扇区 4096（来自 AGENTS.md） |
| ADB 序列号 | `<device-serial-redacted>`（USB） |

## 2. 权限与 root 现状

### 2.1 KernelSU

- `/proc/modules` 中存在 `kernelsu ... Live`，说明 KSU 模块当前已加载。
- `adb shell su -c id` 失败：`/system/bin/sh: su: inaccessible or not found`。
- `adb shell /data/adb/ksu/bin/su -c id` 失败：`inaccessible or not found`；`ls /data/adb/ksu/bin/` 被 `Permission denied`。
- 结论：**root 不是通过 adb shell 直接可用的**。按 AGENTS.md 的 GhostLock late-load 流程，root 在系统启动后由 KSU 管理器/授权 App 使用，**开机早期没有 root**。
- KSU 管理器包 `me.weishu.kernelsu` 当前未在包列表中检索到（可能未安装/改名）；KSU 模块本身为 Live。授权方式待确认。

### 2.2 Shizuku

- 已安装 `moe.shizuku.privileged.api`，版本 `13.7.0-thedjchi`（第三方 fork，支持通过无线调试实现开机自启动的版本）。
- 安装/更新时间：2026-11-21 15:11:24；安装器 `io.github.vvb2060.packageinstaller`。
- 当前 `shizuku_server` 进程未运行。
- `settings get global adb_enabled` = `1`（USB 调试开）；`adb_wifi_enabled` = `0`（无线调试关）。
- 结论：Shizuku 当前不可用；要用 Shizuku 模式，需要先用 Shizuku Store/无线调试把 server 拉起。该 fork 支持开机自启，但依赖无线调试与 `WRITE_SECURE_SETTINGS`，需实际配置验证。

### 2.3 Dhizuku / 设备所有者

- `dpm list-owners` 输出 `no owners`。
- 未设置 Dhizuku/设备所有者。
- 若采用 Dhizuku 模式，需要先设为设备所有者；通常要求设备无账号/无副用户，荣耀上不保证一次成功。

## 3. 已安装的相关第三方应用

| 包 | 版本 | 说明 |
|---|---|---|
| `com.aistra.hail` | 1.11.0 | Hail（雹）已安装，**仅作机制参考，不作为依赖** |
| `moe.shizuku.privileged.api` | 13.7.0-thedjchi | Shizuku fork，当前 server 未运行 |
| `me.timschneeberger.shizustore` | - | Shizuku Store（用于拉起 Shizuku） |
| `com.omarea.vtools` | - | Scene（其冻结使用 `pm disable`，本项目不使用） |
| `com.ruoqing.freqcap` | - | 第三方 CPU 频率限制工具（与本项目无关） |

## 4. 当前冻结/暂停状态

`dumpsys package packages | grep -B 80 'suspended=true'` 只命中一个包：

```
Package [com.hihonor.ouc]
versionName=17.0.12.100
User 0: installed=true hidden=false suspended=true suspendedByPrivacy=false
        distractionFlags=0 stopped=false iAwareStopped=false notLaunched=false
        enabled=1 instant=false virtual=false quarantined=false
```

- `com.hihonor.ouc`（中文标签：**软件更新**）当前已安装、`suspended=true`。
- `enabled=1`，不是 `disable-user`。
- `stopped=false`，且 `dumpsys activity providers` 中仍能看到 OUC 进程记录（约 25 分钟前启动），说明 **suspend ≠ 强停**。
- 尚未在 suspend 状态下重启过手机，跨重启安全性未知。

### 4.1 用户报告的崩溃

- 用户报告的原始序列：**卸载“系统更新”（`com.hihonor.ouc`）→ 对全部应用强制 dex2oat 编译 → 刷入类似 Magisk 的 root 模块 → 系统崩溃；重启能进桌面但马上卡住重启**。
- 2026-11-21 复核：该包仍安装、`enabled=1`、`suspended=true`；当前状态不是 uninstall/disable-user。
- 2026-11-21 对现有日志的重新分析见 `docs/05-ouc-crash-analysis.md`：dropbox 的 44 条记录中没有 OUC 或 system_server 崩溃；重启簇与 KSU/LSPosed 引导期故障、`PS_HOLD` 硬复位/人工重启一致；系统侧对 OUC 的静态依赖均为 fail-soft。原始事件的日志可能已在 11:20 系统更新前丢失，因此**不能据此否定用户当时的经历**，也不能据此认定 OUC 可以安全卸载。
- 仍未确认：原始操作的时间、具体方式（系统设置“卸载更新” / `pm uninstall --user 0` / 第三方工具）、崩溃表现（卡开机/设置闪退/SystemUI/恢复模式）。安全复现只能在备用机上进行，见 05 §5。

## 5. Hail 机制研究（仅参考）

从 Hail 1.11.0 源码/README 得到的事实：

1. Hail 的“冻结”有三种实现：`disable`、`hide`、`suspend`。
2. Suspend 模式调用 `PackageManager.setPackagesSuspended`（设备所有者/Dhizuku/Shizuku/root 各自的实现）。
3. README 明确：**suspend 只阻止用户与 App 交互，不阻止 App 在后台运行**。
4. `force-stop` 是单独的操作；Hail 的 `MODE_*_STOP` 才调用 `forceStopPackage`。
5. Hail 源码中没有 `BOOT_COMPLETED` 接收器；自带自动化只有 `FREEZE_AUTO`（回桌面时冻结）等，**没有开机/关机触发器**。
6. Hail 的 Dhizuku 模式只支持 hide/suspend，不支持强停/停用。
7. Hail 提供外部 Intent API（`com.aistra.hail.action.FREEZE` 等），但本项目**不使用**。

对本项目的意义：

- 不能把“suspend”当成“冻结进程”；必须明确产品语义是“暂停 + 强停 + 清缓存”的组合。
- 开机窗口不能靠 Hail 现成能力实现；必须自己写逻辑。

## 6. 关键依赖与绑定观察（系统 App）

只读 `dumpsys activity services/providers` 观察到：

- `com.hihonor.magicvoice` 的 `MagicVoiceInteractionService` 被 **system（uid 1000）** 以 `android.service.voice.VoiceInteractionService` 绑定。
- `com.hihonor.aipluginengine` 运行 `:llmservice`，注册 `LLMAbilityProvider`（`com.hihonor.aipluginengine.llm.service.LLMAbilityProvider`）。
- `com.hihonor.assistant` 注册 `DisplayCardProvider` / `ThirdDisplayCardProvider`（`com.hihonor.assistant.displaycard` / `com.hihonor.assistant.yoyocard`）。
- `com.hihonor.awareness` 的 `AwarenessService` 被 `com.hihonor.magicvoice` 绑定；同时承载情景感知/反诈/人脸活体相关逻辑。
- `com.hihonor.powergenie` 的 `PowerKitService` 被大量系统组件绑定：`gameassistant`、`iaware`、`dataupdateservice`、`appmarket`、`brain`、`hiview`、`systemmanager`、`nearby`、`aipluginengine`、`ouc`、`pushagent`。

结论：这些包不能按“普通用户 App”整包冻结；整包 suspend/disable 会让调用方出现绑定/Provider 获取失败，进而崩溃或卡死。

### 6.1 组件与整包停用实测

2026-11-21 在 HONOR AAP-AN00 / MagicOS 10.0.0.102 / Android 16 上，以 root 按“组件 → 整包”的顺序分别测试 `magicvoice` 和 `hiview`。每轮观察 8 秒后立即恢复 PackageManager enabled state；没有重启。

| 包 / 操作 | 组件停用 | 整包停用 |
|---|---|---|
| `com.hihonor.magicvoice` | `MagicVoiceInteractionService` 消失；`dumpsys voiceinteraction` 显示 `(No active implementation)`；快照没有该包进程 | `enabled=3`；语音服务消失；快照没有该包进程 |
| `com.hihonor.hiview` | `NcDftService` enabled state 变为 disabled，但已存在的 `NcDftService` 绑定及 PID 4670 仍在 | 包状态变为 `enabled=3`，但 `NcDftService`、`LogCollectService`、`DiagKitService` 和 PID 4670 仍在 |

- MagicVoice 组件：`com.hihonor.magicvoice/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService`。APK 清单声明 `BIND_VOICE_INTERACTION`，进程名 `:interactor`；`settings secure voice_interaction_service` 指向它，ActivityManager 客户端为 `android`。
- Hiview 组件：`com.hihonor.hiview/com.hihonor.ncdft.NcDftService`。清单声明权限 `com.hihonor.android.permission.ACCESS_NCDFT_BIND_SERVICE`；ActivityManager 客户端包括 `com.android.nfc`、`system` 和 Hiview 自身。
- 用于组件的正确命令是 `pm disable --user 0 PACKAGE/COMPONENT`，整包使用 `pm disable-user --user 0 PACKAGE`。此前误用 `disable-user` 禁用组件只返回 `new state: default`，没有改变组件状态。
- MagicVoice 两种停用都在窗口内阻止了包进程运行；用 `pm default-state` 恢复后，系统重新绑定 VoiceInteractionService。Hiview 两种停用只改了组件/包的 enabled state，没有停止已运行服务或进程。
- Hiview 的进程是 `uid=1000`、`persistent=true`，共享包列表含 `com.hihonor.hiviewpush` 与 `com.hihonor.android.chr`。本次没有对它执行 force-stop 或杀进程，避免影响共享 system UID 服务；因此不能断言 Hiview 的进程能被安全终止。
- 每次回滚后，magicvoice 与 hiview 都回到 `enabled=0`，Hiview 的 `NcDftService` 和 MagicVoice 的 VoiceInteractionService 均重新绑定。测试未看到 `system_server` 崩溃或 watchdog 超时签名；NFC 测试期间保持关闭。
- `boot_count=38` 在测试操作期间未变化。稍后 Nemu 的日志记录 boot 38 的安全解冻 Worker 成功解冻 15 个目标；当前只读检查未发现任何 `suspended=true` 包。该延迟解冻发生在 PM enabled-state 对照之后，属于 Nemu 安全路径；目前 MagicVoice 为 `suspended=false`。当前 `pm list packages -d` 仍列出运营商 DM 和 Android 虚拟化/设备锁包，本次没有改动这些包。
- 结论只适用于在线短时观察：组件停用可有效阻断 MagicVoice 的 system voice bind；Hiview 因 persistent/shared UID 和已有绑定，单改 enabled state 不会让现有服务/进程退出。没有测试跨重启行为、语音按键/热词、NFC 刷卡或其他 AI 功能。

## 7. 平台能力确认

`adb shell pm help` 确认当前 Android 16 支持：

```
clear [--user USER_ID] [--cache-only] PACKAGE
suspend [--user USER_ID] PACKAGE [PACKAGE...]
unsuspend [--user USER_ID] PACKAGE [PACKAGE...]
```

说明：

- `pm suspend` / `pm unsuspend` 可用（具体执行身份需要 shell/root；Hail 通过 Shizuku 以 shell 身份调用成功过）。
- `pm clear --cache-only` 可清缓存，不清数据。
- `/data/cota`、`/data/ota_package` 对 shell 返回 `Permission denied`；COTA 云控参数需要 root 才能读取/清理。

## 8. 待确认问题

1. 卸载系统更新时的原始崩溃表现与操作方式（见 05 §7 未知项；需用户回忆或备用机复现）。
2. 当前 OUC 的 suspend 由 Nemu 设置；已实测跨正常重启由安全解冻恢复，见 `04-test-plan.md` T1/T5。
3. root 的实际使用方式：KSU 管理器授权 App、MT Manager 终端；COTA 模块需要 KSU 给 Nemu 授权。
4. 交付对象：自用还是客户设备；是否需要可分发 APK/签名。
5. 范围：已在 2026-11-21 确定为全套（更新 + AI 叶子包 + 云控/遥测 + 反诈 + COTA 温控），高级模块默认关闭。
