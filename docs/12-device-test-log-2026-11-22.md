# Nemu 真机测试日志（2026-11-22）

设备：HONOR AAP-AN00，MagicOS 10.0.0.102 / Android 16，测试期间 boot #38→#39，
Root 后端 FinaSU（uid=0，ksu context）。
应用：`com.nemu.hibernate`，versionCode 4，versionName `1.2.0-nemu`，最终 APK SHA-256
`535bcc6d2c0e5b47fb178cf499a43f7ef43da1d0b2a4e828811fc076270ac5ba`（34,450,913 字节）。
模块：`nemu_boot_autostart` v1.7 源码/zip 已更新（10,039 字节）；设备当前仍安装 v1.5，
用户下次在 FinaSU 中安装后生效。

> 本日志只记录实机观察；每次操作后同时检查 PackageManager、SharedPreferences、
> 进程表（如适用）与 UI 文案。未执行的测试明确标注“未测”。

**结论**：Nemu 1.1.0 在 AAP-AN00 上已通过冻结/解冻/组件/disable-user/HIDE/自定义冻结/
安装/正常重启验收；guard v1.5 计数与开机 120 秒自动冻结正常；第二轮新增的图标、
三风格五配色、OLED 纯黑、授权模式卡、后果弹窗和低功耗安全服务均已实机验证。
仍未覆盖 guard rescue 触发、DM 原本启用时的 enable 路径、OTA 改名组件清理；
详见文末“仍未覆盖”。

## 0. 复现命令

```sh
# 运行态
adb shell dumpsys package <pkg> | grep -m1 -o 'suspended=[a-z]*'
adb shell dumpsys package <pkg> | grep -A10 disabledComponents
adb shell run-as com.nemu.hibernate cat shared_prefs/nemu.xml
adb shell /data/adb/nemu/boot_guard/boot_guard.sh status

# 重启验收（用户已同意）
adb shell svc power reboot
adb shell getprop sys.boot_completed
adb shell settings get global boot_count
```

不涉及 `fastboot flash/erase`、`set_active`、格式化或分区写入；所有包操作均为
可逆 `pm suspend/unsuspend/default-state/disable-user/enable`。

## 1. 已完成（本轮之前）

| ID | 操作 | 结果 | 证据 |
|---|---|---|---|
| PF1 | Freeze 页 Dry-run 应用 OUC | 通过 | 结果“成功：尝试 1”；未写 `strategy_applied_ouc`；suspended 不变 |
| PF2 | 实际应用 OUC | 通过 | `suspended=true`，`target_com.hihonor.ouc=true`，applied=true，卡片“已应用” |
| PF3 | Unfreeze 页解冻所选 OUC | 通过 | `suspended=false`，策略关闭，applied 清除 |
| PF4 | 重新启用并应用 OUC | 通过 | 恢复 `suspended=true`、applied=true |
| PF5 | Dry-run 全部解冻 | 通过（修复后） | 首轮发现 P0：residualUnfreeze 忽略 dry-run；修复后 OUC 保持 suspended |
| PF6 | 64 个目录组件对照设备 | 通过 | 全部存在于 `dumpsys package`，0 缺失 |
| PF7 | 组件/disable-user 实际冻结/恢复 | 部分通过 | 本轮 PF12/PF14 已补测；DM 三项基线为原本停用，未强制 enable |
| PF8 | 无 Root 时组件/整包策略 | 代码级 | UI 不提供组件/整包停用；服务 fail-closed |
| PF9 | hiview 三件套 suspend | 平台拒绝 | `pm suspend` 返回 `new suspended state: false`，默认策略改 COMPONENT |
| PF10 | 重装 APK 不触发解冻 | 通过 | 重装后 OUC/核心/AI 仍 suspended=true |
| PF11 | hiview 三件套组件模式 | 通过 | 10 个组件 disabled，所有权与原始状态已写入 |

## 2. 本轮新增测试

### PF12 numberidentity disable-user 完整往返

**基线**：`antifraud_numberidentity_mode=DISABLE_USER`，
`antifraud_numberidentity_original_enabled=true`，`strategy_applied=true`，
`enabled=3`（disabled-user）。

步骤与记录：

1. 基线检查（PackageManager / prefs）。
2. Unfreeze 页勾选号码识别 → 解冻所选。
3. 检查 `enabled`、`antifraud_numberidentity_mode`、applied/original 标记。
4. Freeze 页重新勾选号码识别（DISABLE_USER 二次确认）→ 应用。
5. 检查 `enabled=3`、applied=true、original=true。

结果：**通过**。
- 解冻前：`enabled=3`，mode=DISABLE_USER，original=true，applied=true。
- Unfreeze 页勾选 → 解冻所选：`enabled=1`，mode=OFF，applied=false，
  original 键清除；stored strategy 仍为 DISABLE_USER。
- 重新勾选（DISABLE_USER 风险确认）→ 应用（再次确认）：`enabled=3`，
  mode=DISABLE_USER，applied=true，original=true。
- 说明：`enabled=1` 表示包已启用（显式 enabled），不是 default；恢复逻辑按原始
  enabled=true 执行 `pm enable`，符合预期。

### PF13 Update 组实际冻结/恢复（systemappsupdater）

**基线**：`target_com.hihonor.systemappsupdater` 未设置（默认关闭），suspended=false。

步骤：

1. Freeze 页 Update 分类勾选系统应用更新器 → 应用。
2. 检查 `suspended=true`、applied=true、策略 target=true。
3. Unfreeze 页勾选 → 解冻所选。
4. 检查 `suspended=false`、策略关闭、applied 清除。

结果：**通过**。
- 应用前：`suspended=false`，target 未设置。
- Freeze 页勾选 → 应用：`suspended=true`，
  `target_com.hihonor.systemappsupdater=true`，applied=true。
- Unfreeze 页勾选 → 解冻所选：`suspended=false`，target=false，applied=false。
- 按钮/文案/策略与执行一致。

### PF14 温控组件模式（powergenie / iaware）

**基线**：powergenie 默认 OBSERVE，未启用；组件：
`CpuCfgCotaUpdateReceiver`、`PgConfigCotaUpdateReceiver`、`ThermalCfgCotaUpdateReceiver`。

步骤：

1. Freeze 页温控分类勾选 PowerGenie → 策略选“组件” → 应用（红色目标二次确认）。
2. 检查三个组件进入 disabledComponents，所有权标记写入。
3. Unfreeze 页解冻所选。
4. 检查组件恢复 default-state、applied/original 清除。

结果：**部分通过 + 平台限制**。
- **powergenie**：目录提供 COMPONENT，但实际 `pm disable` 对三个组件返回
  `new state: default`（HONOR 保护，未生效）；服务按实际状态复核后正确报
  `FAIL：组件未停用`，未写 applied/original。已将 powergenie 改为 **OBSERVE only**，
  不再提供无法生效的组件按钮。
- **iaware**：组件模式实际可用。勾选 iaware → 组件 → 应用后，
  `CloudConfigUpdateReceiver`、`CloudPushContentProvider` 进入 disabledComponents，
  applied=true、两个原始状态基线写入；Unfreeze 页解冻所选后组件恢复
  default、applied=false、基线清除。
- 手动验证：`pm disable` iaware CloudConfigUpdateReceiver 生效；
  powergenie CpuCfgCotaUpdateReceiver 返回 default。测试后已恢复 iaware 组件。
- 最终 APK 增加 `TargetPolicy.normalizeStrategies()`：启动时把不再支持的
  `strategy_com.hihonor.powergenie=COMPONENT` 残留改写为 OBSERVE，保证持久化与 UI 一致。

### PF15 Dry-run 解冻所选不改变状态

步骤：

1. 打开 Dry run。
2. 对仍处于冻结的目标执行“解冻所选”。
3. 检查实际 suspended/enabled 与策略均不变，结果为 DRY-RUN。
4. 关闭 Dry run。

结果：**通过**。
- Dry run 开启后，Unfreeze 页勾选 OUC → 解冻所选 → 确认。
- 结果文案：`DRY-RUN：解冻所选 1 项：成功：尝试 1，成功 1，失败 0；组件：成功：尝试 0...`，
  并显示“Dry run 已开启：恢复只记录命令，不改变实际状态”。
- 实际状态：`suspended=true`，`target_com.hihonor.ouc=true`，
  `strategy_applied_com.hihonor.ouc=true` 全部保持不变。
- 关闭 Dry run 后复检 `dry_run=false`。

### PF16 正常重启后的 guard v1.5 / 自动冻结

**用户已确认**：2026-11-22 选择“立即正常重启”。

重启前快照：
- `docs/audit-2026-11-22/prefs-before-reboot-20261122.xml`
- `docs/audit-2026-11-22/state-before-reboot-20261122.txt`
- boot #38；OUC/核心/AI `suspended=true`；hiview 三件套组件 disabled；
  DM 三件套 + numberidentity `enabled=3`；Dry run=false。

步骤：

1. `adb shell svc power reboot`（正常重启；触发 ShutdownSafety）。
2. 等待 `sys.boot_completed=1` 且 boot count 增加。
3. 开机后立即检查：`last_shutdown_result/detail`、`last_boot_result/detail`、
   guard `status`（attempts/rescue）、applied 标记与实际 suspend。
4. 等待至少 120 秒，检查 BootFreezeWorker 是否按策略重新冻结
   （OUC/核心/AI、hiview 组件、DM/numberidentity）。
5. 不制造 bootloop；如异常先 Unfreeze/guard 恢复。

结果：**通过**。

- 重启：`svc power reboot`，boot count 38→39。
- 关机：`last_shutdown_result=成功（12:31:27）`；detail 显示逐包 `pm unsuspend`
  和 `components=成功：尝试 10，成功 10，失败 0 | rescue_marker=true`。
- guard：`rescue=no attempts=1`，boot_id 已写入；模块 v1.5 已从
  `modules_update` 生效为当前模块。`guard.log` 记录第一轮有 23 项 `pm=20`
  超时未验证，12:32:09 第二轮 `unsuspend pass verified`，说明 rescue/service
  的双 pass 重试生效；之后 watchdog 在 600s 稳定窗口把 attempts 归零（12:42
  复查为 0，属正常）。
- 开机后立即（安全解冻窗口）：OUC/核心/AI `suspended=false`；hiview 三件套
  组件恢复；DM 三件套/numberidentity 仍 `enabled=3`（关机不启用 disable-user）。
- 120 秒后自动冻结：`last_boot_freeze_result=开机冻结成功`，
  detail：`目标=18（成功 18，失败 0） | UPDATE=1 AI=10 CLOUD=6 SAFETY=1`，
  DM 三项保持原有停用。
- 实际复核：11 个核心/AI 目标 `suspended=true`；hiview/hiviewpush/hiviewtunnel
  disabledComponents 4/3/3；DM 三项 + numberidentity `enabled=3`。
- 观察：关机后部分 `strategy_applied_*` 标记仍存在（清理路径未完全生效），但
  120 秒后 BootFreezeWorker 重新应用并重写标记；v1.0 的 UI 还会把 applied 与
  实际状态做交叉校验，active 目标不会显示“已应用”。
- PF16 在 v1.0 的 `d29ac916` 之前的构建上执行；该版本只增加了 applied 与实际状态
  交叉校验、residual 结果按包计数（冻结/恢复执行路径未变），因此 PF16 的
  ShutdownSafety/BootGuard/BootFreeze 结论仍适用。`d29ac916` 安装后复验
  OUC/aipluginengine 仍 `suspended=true`，未触发解冻（PF10 复验）。

## 3. 第二轮 v1.1（2026-11-22 下午）

> 本轮 APK：`versionCode 3` / `1.1.0-nemu`，SHA-256 见文末。测试期间 boot #40，
> Root 后端 FinaSU（adb shell uid=0, ksu context）。所有系统写入均为可逆
> `pm suspend/unsuspend/hide/unhide/disable/enable`；未刷分区、未重启（除用户此前的正常重启）。

### PF17 图标 / 三套风格 / 五套配色 / OLED 纯黑

- 桌面图标：自适应“雪人笑脸”（浅蓝渐变底、腮红、胡萝卜鼻子），Android 13 单色主题图标；
  桌面实拍确认不再是五角星。
- 设置 → 外观：Material 3、M3 Expressive、MIUIX 三套风格即时切换；松林绿（默认）/冰川蓝/暖琥珀/极光紫/石墨灰五套配色即时切换；跟随系统/浅色/深色即时切换。
- 深色：页面背景、状态栏、导航栏均为 `#000000`；卡片为中性深灰（`#0A0A0A`–`#222222`），
  不再出现褐色背景。三套风格各截图核对。

### PF18 授权状态卡（Root/Shizuku/ADB）

- 概览顶部与顶栏只显示当前授权模式（本轮 `Root`），不再显示“Root 可用”。
- 授权卡显示 `当前模式：Root`，并分行展示 Root、Shizuku、ADB 调试的真实状态；
  Shizuku 行区分 `已运行·ADB 授权` / `已运行·Root 授权`。
- “请求授权”按钮：先请求 Root，再请求 Shizuku；“ADB 授权说明”弹窗给出用电脑 ADB
  启动 Shizuku 的完整步骤（ADB 不能直接给 Nemu 授权）。

### PF19 冻结页直白按钮 + 具体后果弹窗

- 冻结页顶部：`已勾选 18`、`已冻结 15`，主按钮“立即冻结已勾选的 18 个应用”；
  每个目标另有“立即冻结/重新冻结”，执行前弹出“具体会发生什么”。
- 对 OUC 的暂停弹窗明确显示：“系统更新会被暂停：手机不会在后台检查/下载系统更新，
  设置里的‘软件更新’可能提示检查失败。不会影响已安装的系统。”
- 全部冻结弹窗逐条列出 18 个目标的策略与具体后果，可滚动确认。

### PF20 HIDE（伪装卸载）实际冻结

- 目标：`com.hihonor.android.upgradeguide`（升级引导界面，无害叶子目标）。
- 选择“隐藏（彻底伪装）”→ 应用前确认弹窗 → 确认冻结。
- 设备：`dumpsys package` 显示 `installed=true hidden=true`；`pm list packages` 中消失。
- 所有权：`strategy_original_hidden_<pkg>=false`、`strategy_hidden_owned_<pkg>=true`、
  `strategy_applied_<pkg>=true`；UI 显示“已隐藏 · 对系统不可见”“已冻结”。
- 说明：HIDE 需要 `MANAGE_USERS`，在本机只有 uid 0 可用；Shizuku ADB（uid 2000）
  会被系统拒绝；UI 只在有 Root 时提供 HIDE。

### PF21 HIDE 解冻（所有权基线 + 精确恢复）

- 解冻页显示 upgradeguide → `已隐藏 · 对系统不可见`，恢复提示“应用解除隐藏，桌面图标和
  入口回来，数据不丢”；勾选 → 解冻所选 → 确认。
- 设备：`hidden=false`，`pm list packages` 重新出现；`strategy_applied`、
  `strategy_original_hidden`、`strategy_hidden_owned` 全部清除；目标策略关闭。
- 说明：恢复只对 `original_hidden=false && owned=true` 的目标执行 `pm unhide`；
  用户/系统自己隐藏的应用不会被 Nemu 接管或解除。

### PF22 低功耗：无冻结目标时安全服务停止

- 解冻页“全部解冻（恢复这里的所有应用）”→ 确认。
- 设备：OUC `suspended=false`，`strategy_applied_*=true` 计数 0；
  `dumpsys activity services com.nemu.hibernate` 中 `SafetyService` 消失（服务已停止）。
- 再执行“立即冻结已勾选的 18 个应用”：15 个 applied 标记恢复，OUC `suspended=true`，
  SafetyService 重新出现。
- 结论：只有存在冻结目标时才保留前台保护服务；没有冻结时不常驻、不轮询。

### PF23 P0（本轮发现并修复）：WorkManager 迟到解冻覆盖用户冻结

- 现象：13:10 前 15 个目标已冻结；13:20 App 被 force-stop 后重新打开，
  延迟约 25 分钟的 `BootWorker` 此时才执行，解除 33 个目标的 suspend 并清空 applied 标记，
  导致 UI 从“15 个已冻结”变成“1 个”。
- 根因：`BootWorker` 只校验 boot count，没有检查“本次开机是否已经执行过冻结”；
  WorkManager 在 Doze/force-stop/重新打开后可能非常晚才送达。
- 修复：`BootSafetyDecision.frozenThisBoot(lastAttemptTime, lastFreezeTime, bootStart)`
  在冻结尝试时间晚于本次开机时刻时跳过解冻；`BootWorker` 记录
  `lastBootResult=已跳过：本次开机已经执行过冻结`。
- 单测：`BootSafetyDecisionTest`（3 例）。修复后重新冻结 18 个目标，设备恢复正常。

### PF24 模块 v1.6（未在设备安装）

- `boot_guard.sh` 的 `KNOWN_SUSPEND` 增加 `cipservice`、`assetsync`、`assetsyncservice`、
  `hnaps`、`msdp`、`securityserver`、`awareness`；`module.prop` 升到 v1.6/versionCode 7，zip 重新打包（8,276 字节）。
- 设备当前仍是 v1.5 模块；用户下次在 FinaSU 中安装 v1.6 后生效。正常关机解冻由 App
  `ShutdownSafety` 覆盖新目标，不依赖模块更新。

### 本轮结束状态

- boot #40；15 个 applied 目标（18 个策略目标中含 3 个原本停用的运营商 DM）；
  OUC/AI/App 建议等 `suspended=true`；hiview 三件套组件停用；SafetyService 运行中。
- upgradeguide 已恢复可见，测试策略重置为 OBSERVE/关闭；无残留隐藏应用。
- APK：34,450,913 字节，SHA-256 `535bcc6d2c0e5b47fb178cf499a43f7ef43da1d0b2a4e828811fc076270ac5ba`。

### PF26 颜色/文字/直觉性修正（用户反馈后）

- **重叠**：解冻页右侧“中风险”与“已暂停”两个 chip 原先紧贴/重叠；已改为
  `Column(verticalArrangement = spacedBy(6.dp))`，实机截图确认分开。
- **配色重做**：不再手写互相冲突的 Material 角色，改为每套配色一个种子色，
  用 HSL 统一生成 primary/secondary/tertiary/表面色（参考 FinaSU 的 materialkolor
  色调思路）。浅色表面是“种子色与白色之间”的淡色；深色背景纯 `#000000`。
  当前五套：松林绿（默认）/冰川蓝/暖琥珀/极光紫/石墨灰。
- **状态 chip**：PRIMARY 改为实心主色（在 primaryContainer 大卡上也清楚），
  SUCCESS/INFO/WARNING 使用调色板/语义色，去掉了刺眼的固定蓝。
- **Dry run 反直觉**：设置里的 Dry run 开关已删除；改为两个明确选项
  “仅预览（不修改手机） / 实际执行”，当前模式下方用中文说明。
  UI 中的 “Dry run/DRY-RUN” 全部改为“预览”。
- **其他直觉性**：解冻按钮改为“解冻所选 N 个，并关闭计划”和
  “临时全部解冻（保留开机冻结计划）”；Boot count/STOPPED/force-stop/
  SafetyService 等英文术语改为中文解释。
- **主题**：M3E 标签缩短为“M3E（大圆角）”；主题说明改为“每种配色只使用该色系
  与白色之间的颜色；深色背景纯黑”。
- 实机截图：`docs/audit-2026-11-22/nemu-green-light-20261122.png`、
  `nemu-green-dark2-20261122.png`、`nemu-execution-mode2-20261122.png`、
  `nemu-unfreeze-spacing-20261122.png`。

### PF27 自定义冻结（任意应用）

- 冻结页新增“添加任意应用（自定义冻结）”；搜索 `alibaba` 添加“阿里巴巴”
  (`com.alibaba.wireless`)，写入 `custom_targets` 与 `target_com.alibaba.wireless=true`。
- 执行全部冻结（20 目标）：`com.alibaba.wireless` `suspended=true`，
  `strategy_applied_com.alibaba.wireless=true`。
- 解冻页勾选并“解冻所选并关闭计划”：`suspended=false`，applied 清除。
- 另试 `com.hihonor.airlink`：系统 `pm suspend` 返回
  `new suspended state: false`，App 正确报 `FAIL：PackageManager 未确认暂停`，
  未写 applied；说明平台保护目标会如实失败，不会假装成功。
- 测试后已从 `custom_targets` 移除两个测试包，设备恢复原策略。
- 关机恢复脚本 `/data/adb/nemu/restore_frozen.sh` 已生成（0700，含 suspend/组件/enable 命令）。

### PF28 图标（历史记录；最终已改为白底几何字母）

- 历史阶段曾用 CC0 动漫头像；最终版本已改为白底黑色几何图形（月亮/风止），
  不再手绘、不再雪人/五角星；不使用《碧蓝航线》等角色官方版权图。
- Android 自适应图标 + 应用内顶栏头像实机确认；无障碍设置中也能看到新图标。

### PF29 更新守卫独立 App（初步）

- APK `com.nemu.updateguard` 1.0.0（3,755,360 字节）已安装，可启动。
- Root 模式授权成功；可通过 Root 暂停/恢复 OUC。
- 无障碍服务在系统设置中显示“更新守卫 已开启”；打开 OUC 主界面后 Activity 立即
  onStop/onDestroy（拦截行为一致），但未抓到稳定 logcat 证据。
- LSPosed 模块已安装，作用域预选“系统框架 + 软件更新”，管理器提示
  “模块已更新，需要重启以应用更改”；**未重启**，因此LSPosed 模式（d()→false）
  尚未做重启后验收。
- 未在 LSPosed 激活时，仅安装 App 仍会看到更新提示，符合预期。
- 详见 `docs/14-update-guard.md`。

## 4. 仍未覆盖 / 已知限制

1. **guard rescue 分支未实机触发**：PF16 证明 guard 计数（attempts=1）和正常
   服务阶段工作，但没有制造 bootloop 验证“3 次后禁用模块 + 恢复所有所有权”。
   该分支的脚本逻辑只做了语法/代码审查。
2. **HIDE 的异常分支未实机触发**：预先已被用户/系统隐藏的包拒绝接管、
   `pm hide` 后校验失败自动回滚、Root 消失后无法 unhide 的救援路径，均为代码级 + 单测。
   PF20/PF21 覆盖了正常隐藏与恢复。
3. **DM 三件套 enable 路径未测**：三项的 `cloud_original_enabled=false`，属于
   用户原本停用状态；按设计 restore 不会 enable。没有主动把基线改成 true 来制造
   一个假的“原本启用”状态。numberidentity 已覆盖 enable 往返（PF12）。
4. **powergenie 组件被平台保护**：`pm disable` 返回 `new state: default`，目录已
   改为 OBSERVE only；不再提供无效按钮。iaware 组件模式已实测通过。
5. **关机 applied 标记清理未完全生效**：观察到的标记在 BootWorker/BootFreezeWorker
   后仍被重新写入；v1.1 APK 的 UI 以实际状态为准（active 不显示“已应用”），
   但持久化清理的根因未再单独修复。最终 APK（`ad522852`）没有再执行干净重启验收；PF23 修复了迟到 BootWorker，
   但关机 applied 标记的持久化清理仍未单独复测。
6. **OTA 改名/删除组件的自动清理**只做了代码级处理（Unknown component 清理），
   未在真实 OTA 后验证。
7. **温控/HAL 原生进程**（thermal HAL、perfservice 等）不在 `pm` 冻结范围内，
   目录只展示可冻结包；UI 已注明 HAL/原生进程不受包冻结覆盖。
