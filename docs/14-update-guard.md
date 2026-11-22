# 14 凪（Nagi）

独立应用：`D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`
（源码命名空间 `com.nemu.updateguard`），显示名 **凪**，版本 `1.3.8` / `versionCode 33`。

Release APK：`app\build\outputs\apk\release\app-release.apk`
（2,149,682 字节，SHA-256
`a8f0d231f011b6c9d3bd3e04f4367527960f09eed8acdb1d17989fb94cc8b90c`）。

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

作用域：仅 `com.hihonor.ouc`（软件更新）。
在 LSPosed 管理器中启用模块并重启一次后生效。

1.3.5 起删除了无 LSPosed 的独立模式（无障碍覆盖层）、Root / Shizuku / ADB
开启通道，以及隐藏桌面图标。没有 LSPosed 时，本 App 不会拦截更新。

## 3. 激活状态：hook 优先

“已激活”只以 OUC 进程内的 hook 是否真正安装成功为准。模块在
`findAndHookMethod` 后，通过绑定式 `HookReportService`（Messenger）向 App 上报：

- 只接受 `Message.sendingUid == com.hihonor.ouc` 的报文；普通 App 无法伪造；
- 只有 OUC 主进程上报，避免次要进程的失败覆盖主进程的成功；
- 报文带 `hookOk`：找到并 hook 了 `result.d()` 为 true，注入但目标缺失为 false；
- Nagi 回到前台/点刷新时会发请求广播，OUC 主进程的 hook 收到后立即重报，
  避免 Nagi 覆盖安装或曾被强制停止时只有旧的 hook 报告。

管理器开关是另一层配置：关闭开关不会卸载已经运行的 OUC 进程中的 hook，
只会影响新启动的 OUC 进程。因此管理器关闭时，App 仍按 hook 报告显示
“已激活（管理器已关闭，新进程不再生效）”，不会把激活状态清掉。

## 4. 管理器开关读取

App 每次进入前台（启动/从后台切回）时探测一次，点“刷新”再查一次；
没有周期轮询，界面退到后台后也不会继续读数据库。

1. `su -c "cat /data/adb/lspd/config/modules_config.db"` 复制主数据库；
2. 复制 `-wal`（不复制 `-shm`，让 SQLite 从 WAL 重建索引）；
3. 在 App 缓存目录打开 SQLite，查询：
   `SELECT enabled FROM modules_state WHERE module_pkg_name='com.nemu.windstop' AND user_id=0`；
4. 再查 `scope` 表确认 `com.hihonor.ouc` 仍在作用域：
   `SELECT 1 FROM scope WHERE module_pkg_name='com.nemu.windstop' AND app_pkg_name='com.hihonor.ouc' AND user_id=0`。

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
| hook 成功，作用域取消 | 已激活（作用域已取消，新进程不再生效） | LSPosed · 已激活（作用域已取消） |
| 已注入，hook 失败 | 已注入，hook 未生效 | LSPosed · 已注入，hook 未生效 |
| 管理器关，无 hook 报告 | 已在管理器中关闭 | LSPosed · 已在管理器中关闭 |
| 管理器开，作用域未勾选 | 已启用，但作用域未勾选软件更新 | LSPosed · 作用域未勾选软件更新 |
| 管理器开，未注入 | 已启用，重启软件更新后生效 | LSPosed · 已启用，重启软件更新后生效 |
| 读不到管理器、无 hook 报告 | 未能读取管理器开关，点芯片打开管理器 | LSPosed · 未能读取管理器开关 |

覆盖安装新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。
App 会在下一次探测时显示管理器关闭，但已运行 OUC 进程的 hook 仍按 hook 优先显示。

## 5. 构建

```powershell
cd D:\win_root\nemu-update-guard
.\gradlew.bat :app:assembleRelease
```

- `compileOnly` Xposed API 82：`app/libs/api-82.jar`；
- 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`；
- 没有 `keystore.properties` 时 release 复用 debug 签名（会打印警告，仅适合本机覆盖安装）；
- 本机已配置私有签名 `keystore/nagi-release.jks` + `keystore.properties`（均 gitignore），
  备份在 `D:\win_root\nagi-signing-backup\`；证书 SHA-256 `1F:45:…:61:BB`。
  换签名必须卸载旧版；密码在 `keystore.properties`，丢失后只能卸载重装；
- 版本名固定 `1.3.8`，内部 `versionCode` 递增。

## 6. 当前实测（2026-11-23，v33）

- 安装：已卸载旧 debug 签名版，安装私有签名 v32（UID 10309，证书
  `1F:45:7A:B9:…:61:BB`）；卸载后 FinaSU allowlist 条目被删，已在 App Profile
  重新授权“超级用户”；重启 OUC 后 hook 正常加载；
- 管理器开关/作用域：LSPosed `modules_state` 中 `com.nemu.windstop` 当前
  `enabled=1`，`scope` 里仍有 `com.hihonor.ouc`；App 日志实测
  `manager switch=true oucScope=true`；
- 按需重报：Nagi 打开时发请求广播，约 0.5 秒后收到
  `hook report hookOk=true from uid=10121`，不再依赖 OUC 启动时那一次上报；
- 界面：hook 成功，管理器开启 → “已激活，软件更新已返回最新”，底部
  “LSPosed · 已激活”，芯片带 ✓；点芯片会直接打开 LSPosed 管理器，底部状态不被覆盖；
- 首次探测完成前显示“正在检查 LSPosed 状态…”，不会先闪“状态未知”；
- 首次 Root 授权超时不再卡死探测线程；点“刷新”可重试；
- 管理器关闭/作用域取消时不清除 hook 状态，界面按 hook 优先显示并补充说明；
- 关机兜底 hook 已删除，模块只 hook `com.hihonor.ouc`，作用域不再需要 `system`；
- Release 未配置私有 keystore 时仍用 debug 签名，Gradle 会打印警告；该 APK 不应公开分发；
- 120 Hz：打开 App 后 `dumpsys display` 为 `mActiveModeId=3`、`renderFrameRate 120.00001`；
- OUC 未被冻结：`suspended=false`。
