# 14 凪（Nagi）

独立应用：`D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`
（源码命名空间 `com.nemu.updateguard`），显示名 **凪**，版本 `1.3.8` / `versionCode 31`。

Release APK：`app\build\outputs\apk\release\app-release.apk`
（2,149,802 字节，SHA-256
`013d3d0bc35a725e239cbbb755d8f487f29750c63c9be795ea832506defe899e`）。

## 1. 目标

让系统更新（`com.hihonor.ouc`）的检查结果显示“当前系统已是最新”，
不下载、不安装、不修改系统版本号，也不暂停/停用/冻结 OUC。

## 2. 唯一的拦截方式：LSPosed

反编译本机 MagicOS 10 的 `com.hihonor.ouc` 后确认：

- 更新检查结果类：`com.hihonor.android.hnouc.check.model.result.a`
- `d()` 返回“是否有新版本”；
- 多数 UI/通知路径用 `next.d() && next.b()` 判断是否展示更新。

`UpdateHook` 使用 LSPosed API 82，对 `result.a.d()` 返回 `Boolean.FALSE`：

```java
XposedHelpers.findAndHookMethod(
    "com.hihonor.android.hnouc.check.model.result.a",
    lpparam.classLoader, "d",
    XC_MethodReplacement.returnConstant(false));
```

作用域：`android`（系统框架，用于关机 hook）+ `com.hihonor.ouc`。
在 LSPosed 管理器中启用模块并重启一次后生效。

1.3.5 起删除了无 LSPosed 的独立模式（无障碍覆盖层）、Root / Shizuku / ADB
开启通道，以及隐藏桌面图标。没有 LSPosed 时，本 App 不会拦截更新。

## 3. 激活状态：hook 优先

“已激活”只以 OUC 进程内的 hook 是否真正安装成功为准。模块在
`findAndHookMethod` 后，通过绑定式 `HookReportService`（Messenger）向 App 上报：

- 只接受 `Message.sendingUid == com.hihonor.ouc` 的报文；普通 App 无法伪造；
- 只有 OUC 主进程上报，避免次要进程的失败覆盖主进程的成功；
- 报文带 `hookOk`：找到并 hook 了 `result.d()` 为 true，注入但目标缺失为 false。

管理器开关是另一层配置：关闭开关不会卸载已经运行的 OUC 进程中的 hook，
只会影响新启动的 OUC 进程。因此管理器关闭时，App 仍按 hook 报告显示
“已激活（管理器已关闭，新进程不再生效）”，不会把激活状态清掉。

## 4. 管理器开关读取

App 回到前台时强制探测一次，前台期间每 5 秒轮询一次：

1. `su -c "cat /data/adb/lspd/config/modules_config.db"` 复制主数据库；
2. 复制 `-wal`（不复制 `-shm`，让 SQLite 从 WAL 重建索引）；
3. 在 App 缓存目录打开 SQLite，查询：
   `SELECT enabled FROM modules_state WHERE module_pkg_name='com.nemu.windstop' AND user_id=0`。

结果只用于补充管理器开关提示，不改变 hook 的激活判定。读取失败（无 Root、
授权弹窗超时、数据库查询失败）时静默回退到 hook 报告，不在界面上反复提示 Root。

**已知修复（2026-11-22）**：旧实现直接在探测线程读取 `su` 的 stdout。首次 Root
授权弹窗未处理时 `read()` 会永久阻塞，`lsposedProbeRunning` 不再复位，之后所有探测
都被跳过，界面就表现为“状态未知”。现在 stdout 由独立泵线程读取，主线程最多等
15 秒并销毁超时进程；点“刷新”可以重新发起探测。

状态显示：

| 条件 | 标题下文字 | 底部状态 |
|---|---|---|
| hook 成功，管理器开 | 已激活，软件更新已返回最新 | LSPosed · 已激活 |
| hook 成功，管理器关 | 已激活（管理器已关闭，新进程不再生效） | LSPosed · 已激活（管理器已关） |
| 已注入，hook 失败 | 已注入，hook 未生效 | LSPosed · 已注入，hook 未生效 |
| 管理器关，无 hook 报告 | 已在管理器中关闭 | LSPosed · 已在管理器中关闭 |
| 管理器开，未注入 | 已启用，打开软件更新后生效 | LSPosed · 已启用，打开软件更新后生效 |
| 读不到管理器、无 hook 报告 | 状态未知，点芯片打开管理器 | LSPosed · 状态未知 |

覆盖安装新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。
App 会在下一次探测时显示管理器关闭，但已运行 OUC 进程的 hook 仍按 hook 优先显示。

## 5. 关机 hook（与 Nemu 联动）

`UpdateHook` 在 system_server 中 hook `com.android.server.power.ShutdownThread.run`，
在真正关机前：

1. 删除 `/data/adb/nemu/restore_done`；
2. 向 `/data/adb/nemu/shutdown.pipe` 写入 `shutdown`；
3. 最多等待 8 秒，直到 Nemu 模块的守护进程写入 `restore_done`。

模块 `restore_if_frozen.sh` 执行完 `restore_frozen.sh` 后写入 ACK（v1.8 修复）。
这条路径只服务于主 App 的关机解冻，不影响更新 hook。

## 6. 构建

```powershell
cd D:\win_root\nemu-update-guard
.\gradlew.bat :app:assembleRelease
```

- `compileOnly` Xposed API 82：`app/libs/api-82.jar`；
- 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`；
- 没有 `keystore.properties` 时 release 复用 debug 签名（可覆盖现有 debug 安装，
  但 `debuggable=false`）；换私有签名需要先卸载旧版；
- 版本名固定 `1.3.8`，内部 `versionCode` 递增。

## 7. 当前实测（2026-11-22，v31）

- 管理器开关：LSPosed `modules_state` 中 `com.nemu.windstop` 当前 `enabled=1`；
  App 前台每 5 秒 root 只读复制 `modules_config.db` + `-wal`，实测读到 `enabled=1`；
- 界面：hook 成功，管理器开启 → “已激活，软件更新已返回最新”，底部
  “LSPosed · 已激活”，芯片带 ✓；
- 首次 Root 授权超时不再卡死探测线程；点“刷新”可重试；
- 管理器关闭时不清除 hook 状态，界面按 hook 优先显示，并补充管理器关闭说明；
- 120 Hz：打开 App 后 `dumpsys display` 为 `mActiveModeId=3`、`renderFrameRate 120.00001`；
- OUC 未被冻结：`suspended=false`。
