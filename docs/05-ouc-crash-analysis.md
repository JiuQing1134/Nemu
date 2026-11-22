# 05 `com.hihonor.ouc` 卸载/停用崩溃分析

> 分析时间：2026-11-21 17:47–18:15
> 设备：HONOR AAP-AN00 / MagicOS 10.0.0.102（Android 16），adb 序列号 `<device-serial-redacted>`
> 方式：**全程只读**。未安装/卸载、未改写分区、未重启、未尝试提权。所有 APK/日志副本仅落在 PC 的 `D:\win_root\research\ouc_crash\`。
> 结论先行：**现有设备日志不支持“卸载/停用 OUC 导致 system_server 崩溃或启动失败”。** 可观测的重启簇与 KSU/LSPosed 引导期崩溃（`lspd`、`dexoptanalyzer`）以及 `PS_HOLD` 硬复位/强制重启一致；OUC 侧静态依赖在缺失时全部 fail-soft（功能降级，不抛异常）。
>
> **用户补充（2026-11-21）**：原始操作顺序是“卸载系统更新 → 对全部应用强制 dex2oat 编译 → 刷入一个类似 Magisk、会显示 root 授权的模块 → 崩溃；重启能进桌面但马上卡住重启”。这使直接触发因素更倾向于 **全量强制 dex2oat + root/zygisk 模块**，而不是 OUC 卸载单独造成 bootloop；详见 §0 与 §4.3。

---

## 0. 用户补充的操作序列（2026-11-21）

用户确认原始操作顺序为：

1. 卸载“系统更新”（`com.hihonor.ouc`）。
2. 对手机上的全部应用执行强制 `dex2oat` 编译。
3. 刷入一个类似 Magisk、会显示 root 授权的模块。
4. 系统崩溃：重启能进入桌面，但马上卡住并再次重启。

这组信息把归因排序修正为：

- **全量强制 dex2oat + root/zygisk 模块更可能是直接触发因素**：两者都直接作用于启动、编译和系统进程，能解释“进桌面后立即卡住重启”；OUC 卸载本身只是功能降级（静态依赖 fail-soft）。
- 现有 12 条 tombstone 中，1 条是 `dex2oat64` 编译支付宝（与第 2 步吻合），11 条是 `lspd` / `dexoptanalyzer` 对 `/data/adb/modules/zygisk_lsposed/daemon.apk` 的 SIGSEGV（与第 3 步吻合）。
- 14:10–14:26 与 17:34–17:47 的重启簇是 `PS_HOLD` / `NORMALBOOT`，不是 `BOOTLOADER_CRASH`；但“卡住后重启”可能由 system_server/zygote 卡死或用户长按电源触发，现有日志无法区分。
- 原始事件日志可能已在 11:20 系统更新前丢失，因此不能 100% 归因；也不要在日常机上复现。

**安全含义**：这次崩溃不能证明 OUC 卸载是安全的，也不能证明它是唯一原因。Nemu 仍然坚持“不卸载 OUC”；如果要复现，只能在备用机上按 §5 的隔离变量方案进行。

## 1. 时间线与证据表

| 时间（2026-11-21） | 来源 | 发现 |
|---|---|---|
| 11:20:32–11:20:33 | `dumpsys package`（设备） | 所有核心系统包 `lastUpdateTime` 同步为 11:20:32–33（Settings/SystemUI/HnSystemServer/HnStartupGuide/OUC）；与系统镜像更新/重刷或包数据库重建一致。精确原因未知。 |
| 11:21:32–11:21:55 | `/data/log/dropbox`、`dropbox_all.txt` | dropbox 可读窗口从 11:21:32 的 `storage_trim` 开始；11:21:55 `SYSTEM_BOOT`。11:21 之前的 dropbox 条目已不存在。 |
| 11:36:11 | `/data/log/hnouc/hnouc_log` | OUC 正常联网检查版本（`BlCheckManager`，返回 hnoffice/fileservice 更新信息）；**这是现存最后一条 OUC 自身日志**，之后 OUC 无异常记录。 |
| 12:30:25 | `dumpsys package me.yuki.fina` | `me.yuki.fina` v1.6.5 由 Chrome 安装；当前 `enabled=0`（已禁用）、`stopped=true`。 |
| 12:34:24 / 12:34:32 | `dropbox data_app_crash` | 两条微信 `xweb_sandboxed_process` 的 NPE，与 OUC 无关。 |
| 14:09:21 | `tombstone_00`、`dropbox SYSTEM_TOMBSTONE` | `dex2oat64` 编译 **`com.eg.android.AlipayGphone`** 时 SIGSEGV（`DeduplicateLinkerPatches`）。uid 1082，应用编译进程，**不是** system_server，也不是 OUC。 |
| 14:10:21–14:26:28 | `/data/log/reliability/faultdetect/history.log` | 7 次复位，全部 `reason [PS_HOLD], sysreboot [true], category [NORMALBOOT]`；对应 `SYSTEM_BOOT`：14:10:41、14:13:14、14:15:18、14:16:50、14:19:21、14:22:34、14:26:48。 |
| 14:15:17 | `log.db`（HiView event） | `com.hihonor.android.launcher/.unihome.UniHomeLauncher#LAUNCHER_SLIDE_ANIMATION` 帧率事件：该次启动后 Launcher **曾正常绘制**。 |
| 14:25:59 | `faultlog/freeze/history.log`、`log.db` | `PNAME:me.yuki.fina` `UIP_FREEZE`；29 秒后 14:26:28 出现 `PS_HOLD`。 |
| 14:49:24 → 14:49:42 → 14:50:54 | `tombstone_01`、`dropbox SYSTEM_RESTART` | `lspd`（LSPosed daemon，uid 0，`u:r:ksu:s0`）SIGSEGV；随后 `SYSTEM_RESTART` 与下一次 `SYSTEM_BOOT`。 |
| 14:53:18–17:47:06 | `/data/tombstones/tombstone_02..11`、`dropbox` | 10 次 `dexoptanalyzer` SIGSEGV，全部针对 **`/data/adb/modules/zygisk_lsposed/daemon.apk`**，uid 0、SELinux `u:r:ksu:s0`；每次崩溃后约 10–20 秒出现下一次 `SYSTEM_BOOT`。当前启动（17:47:06）仍有 `tombstone_11`。 |
| 17:33:12 / 17:36:06 | `log.db` | `com.nemu.hibernate` `PACKAGE UPDATED`（Nemu debug APK 安装/覆盖）；对应 17:34–17:47 的重启簇与 dexoptanalyzer 崩溃。 |
| 17:47:24.782 | 当前 boot `logcat` | OUC `DownloadReceiver` 被 `am_proc_start` 拉起（uid 10121），**在 `suspended=true` 状态下仍能收到 BOOT_COMPLETED**。 |
| 17:47:40.963–994 | 当前 boot `logcat` | `am_kill ... stop com.hihonor.ouc`，随后 `pm_clear_app_data_caller` / `installer_clear_app_data_caller`（Nemu 测试路径，调用方非 OUC）。 |
| 当前状态 | `dumpsys package com.hihonor.ouc` | `installed=true`、`enabled=1`、`suspended=true`、`stopped=true`、`lastStoppedCaller=1000`；**不是** disable-user，**不是** uninstalled。 |
| 全窗口 | `dumpsys dropbox --print`（44 条） | **没有** `system_server_crash`、`system_server_watchdog`、`system_app_crash`、`system_app_anr`、`system_app_wtf`、`system_server_wtf`。唯一 `SYSTEM_RESTART` 在 14:49:42（紧邻 lspd 崩溃）。 |
| 当前 boot | `dumpsys activity lastanr` | `<no ANR has occurred since boot>`。 |
| 全窗口 | `log.db` 事件库 | 11-15 与 11-21 00:32–00:59 有内核 `PANIC`（`_raw_spin_trylock`/`rt_mutex_adjust_prio_chain`，`page_request`）；00:49、02:37 有 `BOOTLOADER_CRASH/PSHOLD`。这些早于 11:20 系统更新，属于 KSU/GhostLock 实验窗口，**与 OUC 无关**。 |

### 1.1 证据纠正（重要）

任务上下文原有的“SYSTEM_TOMBSTONE 都是 dex2oat64 编译支付宝”并不完整：

- `tombstone_00`（14:09:21）确实是 `dex2oat64` 编译 `com.eg.android.AlipayGphone`。
- `tombstone_01`（14:49:24）是 `lspd`。
- `tombstone_02..11`（10 条，14:53–17:47）全部是 `dexoptanalyzer`，目标是 `/data/adb/modules/zygisk_lsposed/daemon.apk`，进程上下文 `u:r:ksu:s0`。

也就是说，**现存 12 条 tombstone 中没有任何一条与 OUC、system_server、Settings 或 SystemUI 有关**；除支付宝编译外，其余全部是 KernelSU/LSPosed 的引导期脚本/守护进程。

---

## 2. 日志“没有”显示什么（红鲱鱼）

1. **没有 OUC 崩溃痕迹。** tombstone、dropbox、`log.db`、`/data/log/hnouc/hnouc_log` 中均无 `com.hihonor.ouc` 的 crash/ANR/WTF。OUC 自身日志最后一条是 11:36 的正常版本检查。
2. **没有 system_server 崩溃或 watchdog。** dropbox 无 `system_server_crash`/`system_server_watchdog`；当前 boot `lastanr` 为空；`logcat -b all -d` 无 `FATAL EXCEPTION`/`am_crash`/`am_anr`（除微信 12:34 的两条 data_app_crash）。
3. **没有 bootloader 崩溃。** 14:10–14:26 与 17:34–17:47 的复位原因是 `PS_HOLD` + `NORMALBOOT`，不是 `BOOTLOADER_CRASH`（后者只出现在 00:49、02:37，早于系统更新）。
4. **14:09 的 dex2oat 墓碑不是 OUC。** 它编译的是支付宝；进程 uid 1082，`compiler-filter=everything`，`compilation-reason=cmdline`（由某个 `cmd package compile`/优化逻辑触发），与 OUC 包无任何关系。
5. **微信 xweb 崩溃不是系统崩溃。** 12:34 的两条 `data_app_crash` 是 `com.tencent.mm:xweb_sandboxed_process_0` 的 NPE。
6. **当前 OUC 的 `suspended=true` 不影响启动。** 当前 boot（17:47）在 OUC suspend 状态下正常启动，OUC 的 BOOT_COMPLETED receiver 甚至被拉起；Nemu 随后在 17:47:40 强制停止/清理。
7. **14:10–14:26 的 7 次启动没有伴随任何 crash/WTF/ANR。** 它们只有 `SYSTEM_BOOT`，且 14:15:17 还有 Launcher 帧率事件；这更像“系统能起来，但随后被硬复位/人工强制重启”，而不是 system_server 崩溃循环。
8. **没有 OUC 卸载/停用的历史事件。** `log.db` 中没有 uninstall/disable 记录；OUC 当前仍是 installed/enabled。现有状态是 suspend。

---

## 3. 静态依赖图（文件 / 方法 / 字符串证据）

> 所有 APK 仅作为数据处理：从 zip 中读 `classes*.dex` / `resources.arsc`，用自写 dex 解析器与 androguard 4.1.3 做方法级 xref；未执行任何 APK。副本位于 `D:\win_root\research\ouc_crash\apks\`，扫描脚本位于 `D:\win_root\research\ouc_crash\scripts\`。

### 3.1 OUC 自身导出的关键组件（`/system/app/HnOUC/HnOUC.apk`）

| 组件 | 类型 | 约束 | 潜在消费方 |
|---|---|---|---|
| `com.hihonor.android.hnouc.newBiz.service.CotaService` | Service | 权限 `com.hihonor.android.hnouc.COTA_SERVICE_FOR_SETUP_GUIDE`（signature\|privileged） | HnStartupGuide（Setup Guide） |
| `com.hihonor.android.hnouc.provider.SuggestionStateProvider` | Provider | authority `com.hihonor.android.hnouc.spaceclearoobe.spaceclearprovider` | Launcher/系统管家等建议入口 |
| `com.hihonor.android.hnouc.search.SettingsSearchProvider` | Provider | authority `com.hihonor.android.hnouc.SettingsSearchProvider` | Settings 搜索索引 |
| `com.hihonor.characterupdateservice.PmsCharacterUpdateService` | Service | `android.permission.INSTALL_PACKAGES`；action `INSTALL_DEPENDENCY` | system_server PMS `HnInstallDependencyHelper` |
| `com.hihonor.characterupdateservice.CharacterUpdateService` | Service | `hihonor.android.permission.HW_SIGNATURE_OR_SYSTEM` | 应用市场/依赖升级 |
| `com.hihonor.hnouc.plugin.PluginService` | Service | `com.hihonor.hnouc.permission.PLUGIN_SIGNATURE`；action `check`/`update` | HnSystemServer 插件管理 |
| `com.hihonor.android.hnouc.provider.DownloadReceiver` | Receiver | `BOOT_COMPLETED`（priority 1000） | 系统开机广播 |

### 3.2 系统侧消费方与“缺失 OUC 时”的行为

| 消费方（进程） | 引用点 | 缺失 OUC 时的代码行为 | 证据 |
|---|---|---|---|
| **system_server / PackageManagerService** | `HnInstallDependencyHelper.bindToDependencyInstallerIfNeeded`：`Intent("com.hihonor.android.content.pm.action.INSTALL_DEPENDENCY").setPackage("com.hihonor.ouc")` → `queryIntentServicesInternal` | 列表为空时打印 `No action ACTION_HN_INSTALL_DEPENDENCY found for user` 并 **return false**；不会抛异常 | `services.jar/classes2.dex` 反汇编（`ag_dump.py`） |
| **system_server / framework Service** | `com.android.server.cota.CotaService`：`<clinit>` 仅把 `"com.hihonor.ouc"` 存入 `OUC_PACKAGENAME` 常量；`onBind` 返回本地 `ICotaInterface$Stub` | 没有 OUC 时只是无人绑定；服务本身不查询包 | `services-magic.jar/classes.dex` 反汇编 |
| **system_server / 其他** | `HwStorageManagerService$HnCloudCfgReceiver`、`HwAppActUpdateReceiver`、`AudioAlgorithmReceiver`、`VoipDenoiseCfgReceiver`、`LbsNoncellReceiver`、`MagicWindowCloudUpdateReceiver`、`BlueNotificationControlUpdateReceiver`、`HnPermission/PmsCloudConfigReceiver`、`HnAppClone/DependUpgrade/HwWhitelistUpdateReceiver`、`ThemeXmlUpdateReceiver`、`WifiCfgReceiver` 等 | 全部是 **`hihonor.android.hnouc.intent.action.CFG_UPDATED` 的接收者**；OUC 不存在时广播不会发出，接收者闲置 | `services*.jar` / `hnWifiEnhance.jar` / `framework-magic.jar` |
| **system_server / 白名单类代码** | `CotaCopyInstallManager.startCotaCopyInstall`（`getNameForUid` 比较）、`HwPileApplicationManager.sendResultBroadcast`（`setPackage("com.hihonor.ouc")` 发广播）、`HnThermalManagerForPreRebootDexopt`（跟踪 OUC 前台）、`HwLockSettingsService`/`TimeKeeper`/`CustomTaskTransitionAnimUtils` 包名白名单 | 仅字符串比较或发广播；不解析 OUC 组件 | `services.jar`/`services-magic.jar` |
| **system_server / SettingsProvider** | `Lcom/android/providers/settings/D;->a`：构造默认设置 Map，键 `cota2_apk_list_version`，值来自含 `"com.hihonor.ouc"` 的数组 | **没有 PackageManager 调用**；只是默认值/迁移数据 | `SettingsProvider.apk/classes.dex` 反汇编 |
| **HnSystemServer（com.hihonor.systemserver）** | `AppPluginQueryManager.bindQueryService`、`PluginQueryManager.bindQueryService`、`InstallManager.bindUpdaterService`、`AppPluginInstallManager.bindUpdaterService`：`bindService(action check/update, setPackage("com.hihonor.ouc"))`；`PluginQueryProvider.isCallerAllowed` 比较调用包 | `bindService` 返回 false 被**忽略**，任务只会超时/得不到回调；无空指针、无异常抛出 | `HnSystemServer.apk/classes2.dex` 反汇编 |
| **HnStartupGuide（Setup Guide）** | `EmbeddingActivity.Y`：`bindService(ICotaService)`；`f.onServiceConnected`/`onServiceDisconnected`；`FinalPage/needShow` 显式检查 `"CotaService is null"`；`g.run` 有 Timer 超时 | bind 失败只打印 `no permission to bind cotaservice`；判空后再决定是否 `startNewCotaDownload`；不会因缺失服务崩溃 | `HnStartupGuide.apk/classes.dex` |
| **Settings（com.android.settings）** | `SystemUpdateSettingsController$a.c`；`HwCustDevelopmentSettingsImpl.isShowHwSoftwareUpdate`；`ShortcutHwOucReceiver.onReceive`；`ParamUpdatedReceiver.onReceive`；`Ln6/b.c`（`CloudRomUpdaterService` bind）；`Ln6/f.i`（注册 `DOWNLOAD_STATUS`/`INSTALL_STATUS`，权限 `com.hihonor.hnouc.permission.CLOUD_INSTALL`） | 启动前用 `Utils.k3(PackageManager, "com.hihonor.ouc")` 检查；失败路径打印 `can't start startSystemUpdate application!`；接收者/绑定在 OUC 缺失时闲置 | `Settings.apk/classes2.dex`、`classes3.dex` |
| **SystemUI** | `HwAssistManager.<init>` 注册 `hnouc_keyguard_view_on_top` 的 ContentObserver；`HwAssistManager$2.onChange` 读 `Settings.Secure.getInt`；`CloudUpdateReceiver` 收 CFG_UPDATED；`TileUtils`/`Index.isWellKnownProvider`/`Ranking` 含 OUC 包名白名单；`MagazineProvider.call` 处理 OUC 的 `get_ouc_screen_data` | 只读 Secure setting / 包名比较 / 接收广播；**没有对 OUC Provider/Service 的启动期查询** | `SystemUI.apk/classes.dex`、`classes3.dex` |
| **HnLauncher6** | `CustStartAppManager.startHnOuc`（启动 `JumpCotaActivity`，含 `"CotaActivity is not found"` 日志）；`NotificationListener.isOucUpdate`；`ProviderCallUtils.checkCallBadgeByPackageName`；`SentienceIconProvider` | 启动失败被捕获/记录；其余只是包名/通知判断 | `HnLauncher6.apk/classes.dex`、`classes2.dex` |
| **HnSystemManager / TeleService / HAware / Booster** | CFG_UPDATED 接收者、`mscw.hnouc.patch.version` 系统属性读取、`JumpSetupActivity` UI 跳转、`COTA_INSTALL_COMPLETED` 接收者注册 | 广播不会发出；属性仍存在（构建属性）；无启动期包查询 | 各 APK 方法级扫描 |
| **framework.jar** | 仅 `android.content.res.Resources.<clinit>` 的静态包名列表包含 `com.hihonor.ouc` | 无包解析 | `framework.jar/classes.dex` |
| **framework-magic.jar** | `android.hnouc.HnOucNative`（`hnouc_jni` 本地库、bspatch/签名校验）、`IChecker`/`IPluginChecker`/`IUpdater` AIDL；`ComponentRecoManager` 读 `/data/system/iaware/hnouc/...` | 这些是给 OUC 用的库/接口，系统侧不主动查包 | `framework-magic.jar/classes.dex` |
| **init 配置** | `init.rc` 的 `on property:mscw.hnouc.*` 触发 operator/cotaversion 动作；`update_engine.rc` 的 `mscw.hnouc.update_engine.up` 只负责 start/stop，`update_engine` 本身由 `ro.boot.slot_suffix=*` 启用；`initial-package-stopped-states.xml` 将 OUC 设为 `stopped="false"` | OUC 缺失只会让这些属性触发器不触发；update_engine 仍会正常启动 | 设备上直接读取 |

### 3.3 判定

- 静态依赖的**唯一硬约束方向**是 OUC → 系统（OUC 自己请求/使用系统接口），而不是系统 → OUC。
- 系统侧所有可确认的调用点都是以下三类之一：`bindService`（失败返回 false）、`queryIntentServices`（空列表显式处理）、字符串/白名单比较。**没有发现会在 OUC 缺失时抛 `NameNotFoundException`、`ActivityNotFoundException`、`IllegalArgumentException` 或空指针的启动期路径。**
- 因此，“卸载/停用 OUC”最可能的结果是**功能降级**：设置里的“软件更新”入口消失/无法打开、Setup Guide 的 CotaService 绑定超时、HnSystemServer 插件更新任务超时、数据更新/云控广播不再到达。

---

## 4. 假设排序（H1–H6）

### H1（首要结论）：观测到的重启簇不是 OUC 卸载引起的系统崩溃，而是 KSU/LSPosed 引导期异常与 `PS_HOLD` 硬复位/人工强制重启的叠加

**证据**

- 12 条 tombstone 中 11 条是 KSU/LSPosed（`lspd` + `dexoptanalyzer` 对 `/data/adb/modules/zygisk_lsposed/daemon.apk`），0 条与 OUC 相关。
- `faultdetect/history.log` 的全部启动记录都是 `PS_HOLD` + `NORMALBOOT`（硬件电源保持复位），不是 `BOOTLOADER_CRASH`，也不是 system_server 崩溃。
- dropbox 无 `system_server_crash`/watchdog；唯一 `SYSTEM_RESTART` 出现在 lspd 崩溃之后 18 秒。
- 14:25:59 有 `me.yuki.fina` 的 UIP_FREEZE，29 秒后即 `PS_HOLD`；17:33/17:36 有 Nemu APK 覆盖安装，随后 17:34–17:47 重启簇。
- 14:15:17 Launcher 曾正常绘制，说明至少有一次启动并未“卡死在内核/系统服务之前”。

**置信度**

- “现有日志不支持 OUC 导致系统级崩溃/启动失败”：**中高**。
- “所有重启簇都由 KSU/LSPosed 解释”：**中**（14:10–14:26 这一簇没有 dexoptanalyzer 崩溃，只能确认存在 Fina 冻结与硬复位，无法完全归因）。

**区分性测试**：在备用机（或已不再承载日常数据的测试机）上禁用 `zygisk_lsposed` KSU 模块后连续冷启动 5 次，观察 `lspd`/`dexoptanalyzer` tombstone 是否消失、重启簇是否消失；再单独测试 OUC disable。见第 5 节。

### H2：卸载/停用 OUC 只会造成功能降级，不会造成系统启动失败

**证据**：第 3 节全部静态路径均为 fail-soft；PMS 对空服务列表显式 `return false`；Setup Guide 有 null 检查与 Timer；Settings 启动前检查包存在；HnSystemServer 忽略 `bindService` 返回值；SettingsProvider 不查询包；framework 仅有静态包名列表。

**置信度**：**中高**（静态证据强；尚未在备用机上实机卸载复现，不能 100% 排除未扫描到的 HONOR 私有路径）。

**区分性测试**：备用机 `pm disable-user --user 0 com.hihonor.ouc`，冷启动 10 次并抓取 dropbox/logcat；预期启动正常、无 crash tag，仅设置入口/插件任务降级。

### H3：如果用户当时看到的是“设置闪退”而非启动失败，触发点最可能是 Settings 的系统更新入口或搜索索引

**证据**：Settings 中 OUC 引用最多；`SystemUpdateSettingsController` 虽然有包检查与 `can't start ...` 日志，但 `ShortcutHwOucReceiver`、`ParamUpdatedReceiver`、`ExternalAppSearchDataRegister` 等路径在 OUC 缺失时行为未被实机覆盖。

**置信度**：**低–中**（代码显示有防护；无法解释“无法开机”）。

**区分性测试**：备用机 disable OUC 后打开 设置 → 系统和更新 → 软件更新，以及设置搜索“更新”，抓 `logcat` 中的 `ActivityNotFoundException`/`FATAL EXCEPTION`。

### H4：OUC 缺失破坏 HnSystemServer 插件管理或 PMS 依赖安装，导致系统服务异常

**证据（反向）**：PMS `HnInstallDependencyHelper` 对空列表显式返回 false；HnSystemServer 的 `bindService` 返回值被忽略，最坏结果是任务超时，不是崩溃。未发现未捕获异常路径。

**置信度**：**低（基本被代码否定）**。

**区分性测试**：disable OUC 后调用一次应用市场/插件更新，观察是否只有 `No action ACTION_HN_INSTALL_DEPENDENCY`/超时日志。

### H5：OUC 缺失使 Setup Guide（HnStartupGuide）状态机卡死或循环，表现为“开机向导起不来/像 bootloop”

**证据**：Setup Guide 确实绑定 `CotaService`；但 `EmbeddingActivity` 有 bind 失败日志、Timer 超时、`FinalPage` 的 `CotaService is null` 判空。设备已处于已设置状态（Launcher 正常），不是 OOBE。

**置信度**：**低**。

**区分性测试**：仅在备用机恢复出厂设置的 OOBE 流程中，先 disable OUC，再走完开机向导并抓日志。

### H6：原始 OUC 卸载事件发生在 11:20 系统镜像更新之前，相关日志已经不存在

**证据**：dropbox 可读内容从 11:21:32 开始；所有核心系统包时间戳集中在 11:20:32–33；OUC 自身日志也从 11:21 后开始；`log.db` 虽保留更早事件，但没有任何 OUC 卸载/崩溃记录。11:21 之前（例如 03:20 的 `PS_HOLD`、11-15 的内核 panic）属于早期 KSU/GhostLock 实验窗口。

**置信度**：**高**（证据缺口确实存在）；具体根因**未知**。

**区分性测试**：向用户确认原始操作的日期/方式（卸载 vs 停用 vs “卸载更新”，哪台设备/哪个用户），并检查 HONOR HiView 已上传日志或备份；否则只能在备用机上复现。

### 4.1 uninstall / disable-user / suspend 的区别

| 操作 | 对 user 0 的组件解析 | 对系统消费方 | 数据/可逆性 | 本次证据 |
|---|---|---|---|---|
| `pm uninstall --user 0` | 组件不可见（等同移除） | 与 disable 相同：bind/查询失败，但代码路径 fail-soft | 系统 APK 仍在 `/system`；`pm install-existing --user 0` 恢复 | 无历史事件；当前未处于此状态 |
| `pm disable-user` | 组件被过滤，不可解析 | 同上 | 数据保留；`pm enable --user 0` 恢复 | 当前 `enabled=1`，未处于此状态 |
| `pm suspend` | 组件仍可解析 | 可解析；后台/通知/启动受限制 | `pm unsuspend` 恢复 | **当前状态**；17:47 启动正常，DownloadReceiver 仍被拉起 |

### 4.2 崩溃层级区分

| 层级 | 本次是否出现 | 证据 |
|---|---|---|
| Settings/SystemUI 应用崩溃 | 未出现（当前窗口） | 无 `data_app_crash`/`system_app_crash` 对应条目；仅微信 xweb |
| system_server 崩溃/watchdog | 未出现 | dropbox 无对应 tag；`lastanr` 为空 |
| bootloader 崩溃 | 未出现于 14:xx/17:xx | `faultdetect` 为 `PS_HOLD`/`NORMALBOOT`；`BOOTLOADER_CRASH` 仅在 00:49、02:37 |
| 硬件复位/人工强制重启 | 出现 | `PS_HOLD` 复位记录；与 UIP_FREEZE、Nemu 安装时间相关 |
| 缺失 Provider/Service vs 缺失 App 数据 | 两者均无崩溃证据 | 静态路径 fail-soft；OUC 数据目录仍存在，包状态为 suspend |

---

### 4.3 结合用户补充后的假设排序（2026-11-21）

1. **全量强制 dex2oat 编译 + root/zygisk 模块（最可能直接触发）**：用户的操作顺序、`dex2oat64`/`dexoptanalyzer` tombstone、`lspd` 崩溃与 `u:r:ksu:s0` 上下文相互吻合；全量编译会让大量进程在启动期竞争 CPU/IO，root 模块会改变 zygote/system_server 启动环境，二者都可能造成“进桌面后卡住重启”。
2. **root 模块与 MagicOS 10.0.0.102 / LSPosed 版本不兼容**：`dexoptanalyzer` 对 `zygisk_lsposed/daemon.apk` 的重复 SIGSEGV 是直接证据；但当前 boot 也复现该崩溃而系统保持稳定，说明它可能只是加重因素，不足以单独解释每一次重启。
3. **OUC 卸载造成功能降级**：设置“软件更新”入口、Setup Guide 的 `CotaService`、HnSystemServer 插件更新会失败；静态路径均 fail-soft，单独难以解释系统级 bootloop。
4. **原始日志缺失**：11:20 前的 dropbox/log 已被清理，无法还原完整因果链；以上排序是证据权重，不是已证实的根因。

## 5. 安全验证计划（用户执行，不冒险日常机）

> 原则：不在日常主力机上做 uninstall；一次只改一个变量；每步抓日志；先隔离 KSU/LSPosed 变量，再测 OUC。

1. **准备**
   - 使用备用 AAP-AN00（或至少一个独立的 Android 用户/工作资料）与数据备份。
   - 确认 `adb` 可用；准备好回滚命令（第 6 节）。
   - 不要在 `ro.debuggable=0` 的日常机上尝试 uninstall 系统应用；系统 APK 实际无法真正删除（除非改分区）。

2. **基线（测试机）**
   - 连续冷启动 3 次，每次抓取：
     ```powershell
     adb shell dumpsys dropbox --print > dropbox_baseline.txt
     adb shell logcat -b all -d -v threadtime > logcat_baseline.txt
     adb shell dumpsys activity lastanr
     adb shell "for f in /data/tombstones/tombstone_*; do echo == \$f; head -12 \$f; done"
     ```
   - 记录 `dumpsys package com.hihonor.ouc | grep 'User 0:'`。

3. **隔离 KSU/LSPosed（关键对照）**
   - 在测试机上临时禁用 `zygisk_lsposed` 模块（KSU 管理器内操作，需用户执行），冷启动 5 次。
   - 预期：`lspd`/`dexoptanalyzer` tombstone 消失；若重启簇仍出现，则 H1 的 KSU/LSPosed 归因需要下调。

3b. **隔离全量 dex2oat（不要和 OUC 操作同时做）**
   - 如果必须复现用户当时的全量编译，只在无 root/zygisk 模块的测试机上单独执行；执行前后各抓一次 dropbox/logcat/lastanr/tombstone。
   - 不要在已经 disable/uninstall OUC 的机器上同时做全量编译，否则无法区分变量；不建议在日常机上执行。

4. **用户级测试：disable-user（优先于 uninstall）**
   - 创建/切换到一个非主用户（或使用测试机 user 0）：
     ```powershell
     adb shell pm disable-user --user 0 com.hihonor.ouc
     ```
   - 冷启动 10 次，每次抓第 2 步日志；重点检查：
     - 是否能正常到桌面（`sys.boot_completed=1`）；
     - 设置 → 系统和更新 → 软件更新 是否闪退；
     - 设置搜索“更新”是否异常；
     - dropbox 是否出现 `system_server_crash`/`system_app_crash`/`ActivityNotFoundException`。
   - 预期（H2）：启动正常，入口缺失/降级，无系统级 crash。

5. **卸载测试（仅备用机）**
   - 仅在测试机上执行：
     ```powershell
     adb shell pm uninstall --user 0 com.hihonor.ouc
     ```
   - 冷启动 10 次，重复日志采集；测试 Settings/搜索/Setup Guide（若可进入 OOBE）。
   - 若出现异常，立即回滚（第 6 节）并保存 `adb bugreport`（若能连上）。

6. **suspend 路径**
   - 当前日常机已在 suspend 状态且 17:47 正常启动；如需复测：
     ```powershell
     adb shell pm suspend --user 0 com.hihonor.ouc
     ```
   - 用正常电源菜单/`svc power reboot` 重启（不要用 `adb reboot` 验证 Nemu 安全解冻），开机后确认 `suspended=true` 与桌面正常。

**注意**：如果测试导致 bootloop 且 adb 不可用，只能长按电源/进入 recovery 或 fastboot；本机 root 是 late-load，不能假设 bootloop 时能靠 root 修复。不要在主力机上验证“卸载”。

---

## 6. 回滚

```powershell
# 解冻（suspend）
adb shell pm unsuspend --user 0 com.hihonor.ouc

# 若被 disable
adb shell pm enable --user 0 com.hihonor.ouc

# 若被 uninstall --user 0
adb shell pm install-existing --user 0 com.hihonor.ouc

# 查状态
adb shell dumpsys package com.hihonor.ouc | Select-String 'User 0:'
```

若 adb 不可用：用 KSU 管理器/MT Manager 的 root 终端或 Shizuku 终端执行同样的 `pm` 命令；不要格式化数据。

---

## 7. 未知项（无法由现有证据回答）

1. 用户原始“卸载 OUC 导致崩溃”的**具体时间、操作方式**（`pm uninstall --user 0`、系统设置里的“卸载更新”、`disable-user` 还是第三方工具）、**哪个用户/哪台设备**。
2. 11:20:32–33 的**系统包时间戳同步更新**到底是 OTA、系统分区重刷、包数据库重建还是数据恢复；这决定了 11:20 之前的日志是否被清理。
3. 14:10–14:26 的 7 次 `PS_HOLD` 复位是否全部由用户长按电源触发；目前只能确认 14:25:59 有 `me.yuki.fina` 冻结，其余 6 次没有对应的 UI/崩溃记录。
4. `dexoptanalyzer` 对 `/data/adb/modules/zygisk_lsposed/daemon.apk` 的 SIGSEGV 是否只是每次启动的良性副作用，还是与某些重启相关；当前 boot 也复现了该崩溃但系统保持稳定，说明它**不足以单独解释重启**。
5. HONOR 私有、未包含在已扫描 APK/框架中的组件（例如未读取的 `/apex` 内容、vendor 服务、`/data` 内的动态代码）是否存在硬 OUC 依赖。
6. 是否存在跨重启的 OUC 数据/偏好（例如 `mscw.hnouc.*` 属性、`hnouc_*` settings）在包被移除后导致其他组件行为异常；当前未发现读取这些值的启动期代码会因缺失包而失败。
7. dropbox 的 `isPrevious` 字段语义未用于本报告结论；所有时间线结论基于时间戳、`faultdetect` 复位记录和当前包状态。

---

## 8. 证据文件与复现命令

**PC 侧现有证据**

- `D:\win_root\dropbox_all.txt`（44 条 dropbox 全文）
- `D:\win_root\nemu_crash_140921.txt`、`nemu_crash_145327.txt`、`nemu_crash_150652.txt`、`nemu_crash_173923.txt`、`nemu_restart_144942.txt`
- `D:\win_root\research\ouc_crash\logs\`：
  - `dumpsys_package_ouc.txt`、`dumpsys_package_all.txt`、`dumpsys_providers.txt`
  - `log.db`、`ue_log.db`（HONOR HiView 事件库，只读副本）
  - `faultdetect_history.log`、`zerohung_history.log`
  - `settings_ouc_refs.txt`、`sysmanager_ouc_refs.txt`、`framework_refs.txt`、`framework_magic_refs.txt`
- `D:\win_root\research\ouc_crash\apks\`：HnOUC、HnSystemServer、SystemUI、Settings、HnLauncher6、HnStartupGuide、SettingsProvider、SettingsIntelligence、HnSystemManager、TeleService、HAware、Booster、framework.jar、services.jar、services-magic.jar、framework-magic.jar、hnWifiEnhance.jar
- `D:\win_root\research\ouc_crash\scripts\`：`dexscan.py`、`ag_scan.py`、`ag_dump.py`、`pkg_refs.py`、`search_events.py` 等（只读分析脚本）

**关键设备只读命令**

```powershell
adb shell dumpsys dropbox --print
adb shell dumpsys activity lastanr
adb shell dumpsys package com.hihonor.ouc
adb shell dumpsys package
adb shell logcat -b all -d
adb shell cat /data/log/reliability/faultdetect/history.log
adb shell cat /data/log/reliability/zerohung/history.log
adb shell cat /data/log/hnouc/hnouc_log
adb shell "for f in /data/tombstones/tombstone_*; do echo == \$f; head -12 \$f; done"
```

**限制**

- `/sys/fs/pstore`、`/proc/cmdline`、`/data/vendor`、`/data/system/dropbox` 目录列表、部分 `/data/log` 子目录对 shell 不可读；`adb shell su` 不可用（KSU late-load，未尝试提权）。
- `/apex` 根目录不可枚举，未读取 `com.hihonor.hnextservices` 之外的 apex 内容；其 `javalib` 已确认与本问题无直接启动依赖。
- 本报告未做任何设备写入。
