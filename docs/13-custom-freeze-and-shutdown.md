# 13 自定义冻结与关机强制解冻（2026-11-22）

## 1. 目标

- 允许冻结任意已安装应用，而不仅是目录内的 41 个包。
- 提供 暂停 / 停用 / 隐藏（伪装卸载）/ 只观察 四种方式。
- 任意来源的关机或重启（电源键、系统设置、其他应用调用 PowerManager）前，**即使
  Nemu 进程没有运行**，也要先执行一次解冻脚本。
- 下次正常开机后，按原策略自动冻结。

## 2. 自定义冻结

### 2.1 入口

冻结页 → “添加任意应用（自定义冻结）”，搜索应用名称或包名后点选。

- 包名/标签保存在 `custom_targets`（SharedPreferences，`pkg\tlabel`）。
- `NemuApplication.onCreate` 把自定义包恢复成 `Target` 并注入
  `TargetCatalog.setCustomTargets`，所有 Worker/安全路径启动前即可见。
- 自定义目标默认为 `YELLOW`，支持 `SUSPEND / DISABLE_USER / HIDE / OBSERVE`，
  不支持组件模式（没有登记组件）。

### 2.2 安全边界

`CustomTargetGuard` 拒绝以下目标（不显示在选择器里，回调也会二次拒绝）：

- `android`、Nemu 自己、FinaSU、Shizuku、LSPosed 管理器；
- SystemUI、系统设置、电话/Telecom、包安装器、权限控制器、桌面/Launcher；
- 输入法（包名含 `inputmethod`）；
- 目录中风险为 RED 的包（powergenie、systemmanager、securityserver、awareness 等）。

这不是“不能冻结任何应用”的限制，而是避免隐藏桌面/电话/输入法后无法开机或无法恢复。
用户仍可冻结绝大多数第三方应用和黄色系统叶子应用。

### 2.3 “伪装卸载”语义

`HIDE` = `pm hide --user 0 <pkg>`：

- 应用从 PackageManager（普通查询）、桌面、设置的应用列表中消失；
- 进程被杀，组件不再解析，用户无法打开；
- APK 和数据仍在 `/data/app`、`/data/data`；
- 解冻执行 `pm unhide` 后图标、入口和数据恢复。

需要 `MANAGE_USERS`，本机只有 uid 0 可用；Shizuku ADB 模式会被系统拒绝。
执行前用 `dumpsys package` 记录 `strategy_original_hidden=false` 和
`strategy_hidden_owned=true`；预先已被用户/系统隐藏的包拒绝接管，恢复只解除
Nemu 自己隐藏的包。

## 3. 关机强制解冻

### 3.1 App 生成恢复脚本

每次冻结/解冻后（以及打开 App 时），`ShutdownScript` 通过 root 重写：

`/data/adb/nemu/restore_frozen.sh`

内容是从当前所有权推导出的**自包含命令**：

- 所有已应用 SUSPEND 目标 → `pm unsuspend --user 0 ...`（自定义目标也包含）；
- 已应用 COMPONENT 目标 → 按 `strategy_component_original_*` 恢复组件；
- 已应用 DISABLE_USER → **不恢复**。disable-user 是持久状态，关机安全解冻
  不会逆转它；只有用户在恢复页执行显式解冻才会 `pm enable`；
- 已应用 HIDE 且属于 Nemu → `pm unhide`。

没有冻结目标时文件为 0 字节，模块用 `[ -s ]` 判断，不会执行无意义命令。

### 3.2 三层触发

1. **KernelSU initrc（首选）**：`initrc/nemu_shutdown.rc`
   ```rc
   on shutdown
       exec u:r:su:s0 root root -- /system/bin/sh /data/adb/modules/nemu_boot_autostart/restore_if_frozen.sh
   ```
   Android init 在处理 `sys.powerctl` 时先执行所有 `on shutdown`，再 `DoReboot`。
   只要模块的 initrc 注入可用（非 late-load 或内核已支持），任何正常关机/重启都会执行。
2. **root FIFO 守护进程**：`shutdown_daemon.sh` 由 `service.sh` 启动，阻塞读取
   `/data/adb/nemu/shutdown.pipe`；LSPosed 更新守卫在 `ShutdownThread.run` 前写入
   FIFO，然后等待 `/data/adb/nemu/restore_done`。
3. **sys.powerctl 轮询兜底**：仅当 `restore_frozen.sh` 非空时每 1 秒检查一次
   `sys.powerctl`，看到关机请求就执行脚本。它只在有冻结目标时运行。

### 3.3 覆盖范围与限制

- 覆盖：电源键关机/重启、系统设置、任何应用调用 `PowerManager.reboot()`、
  `svc power reboot`（最终都走 `sys.powerctl`）。
- **不覆盖**：强制断电、拔电池、内核崩溃——这些场景没有任何用户态代码能执行。
- 若 KernelSU 是 late-load 且 initrc 注入不可用、LSPosed 也未启用，则只剩 FIFO
  （需要 LSPosed 写入）和 App 自己的 `SafetyService`；此时无法 100% 保证。
- 模块 v1.7 已包含 `restore_if_frozen.sh`、`shutdown_daemon.sh`、`initrc/`；
  **设备上仍是 v1.5，需要用户在 FinaSU 中安装 v1.7 后才具备 initrc/FIFO 层**。

## 4. 开机自动冻结

自定义目标已进入 `TargetCatalog.all`，因此：

- `BootFreezeWorker` 在开机 120 秒后按策略重新冻结（包括自定义目标）；
- `TargetPolicy` 的 `strategy_<pkg>`、`target_<pkg>` 持久化；
- `SafetyTargets` 每次访问重新计算，自动包含可 SUSPEND 的自定义目标；
- `BootGuard.disableTargets` 每次访问重新计算，自定义 disable-user 也能写所有权标记。

## 5. 验证记录（2026-11-22）

- 添加自定义 `com.alibaba.wireless`（阿里巴巴）：`target_com.alibaba.wireless=true`；
  冻结后 `suspended=true`，`strategy_applied_com.alibaba.wireless=true`；
  解冻所选后 `suspended=false`、applied 清除。
- 添加 `com.hihonor.airlink`：`pm suspend` 被平台拒绝（`new suspended state: false`），
  App 正确报 `FAIL：PackageManager 未确认暂停`，未写 applied；说明平台限制被如实展示。
- `/data/adb/nemu/restore_frozen.sh` 已生成，包含 11 个 suspend、10 个组件恢复、
  numberidentity enable；权限 0700。
- 测试后已从 `custom_targets` 移除 AirLink / 阿里巴巴，设备恢复原策略。
