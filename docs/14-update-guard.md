# 14 凪（Nagi）

独立应用：`D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`
（源码命名空间 `com.nemu.updateguard`），显示名 **凪**，版本 `1.3.8` / `versionCode 35`。

Release APK：`app\build\outputs\apk\release\app-release.apk`
（2,161,898 字节，SHA-256
`cbacd197801032093ba953fc33c06484438c65bb3e38b034504d4cc44b89d181`）。

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

“已激活”只以**本次进入前台/点刷新后收到的 fresh hook 报告**为准。模块通过绑定式
`HookReportService`（Messenger）向 App 上报：

- 只接受 `Message.sendingUid == com.hihonor.ouc` 的报文；普通 App 无法伪造；
  `onBind` 不检查 UID（实测该处 `Binder.getCallingUid()` 是服务自身，不是绑定方），
  真正的校验在消息层；
- 只有 OUC 主进程上报，避免次要进程的失败覆盖主进程的成功；
- 报文带 `hookOk`：找到并 hook 了 `result.d()` 为 true，注入但目标缺失为 false；
- Nagi 每次进入前台/点刷新会发请求广播，OUC 主进程的 hook 收到后立即重报；
- App 给这次请求 2 秒窗口；没有新报告就显示“当前软件更新进程未上报（上次 hook 已生效）”，
  不再把上次开机的旧报告当成当前激活；
- 报告附带 OUC 进程的 PID 和一个 Binder token；OUC 进程死亡时，Nagi 里的 Binder death
  会立即把 `processGone` 置 true 并刷新界面——即使凪一直停在前台，也不需要轮询；
  新进程的新报告会清除这个状态；
- 如果 `Application.onCreate` 的激活 hook 失败，第一次 `result.d()` 调用会用
  `AndroidAppHelper.currentApplication()` 兜底注册请求接收器并上报。

管理器开关是另一层配置：关闭开关不会卸载已经运行的 OUC 进程中的 hook，
只会影响新启动的 OUC 进程。只有 fresh 报告为成功时，才显示
“已激活（管理器已关闭，新进程不再生效）”；没有 fresh 报告时只显示“未上报”。

## 4. 管理器开关读取

App 每次进入前台（启动/从后台切回）时探测一次，点“刷新”再查一次；
没有周期轮询，界面退到后台后也不会继续读数据库。

1. `su -c "stat ..."` 记录源 DB/WAL 的 size+mtime；
2. `cat` 复制主数据库和 `-wal`（不复制 `-shm`，让 SQLite 从 WAL 重建索引）；
3. 复制后再 stat 一次；前后不一致说明复制期间 LSPosed 写入/checkpoint 了，重试一次，
   仍不稳定就返回 null（未知），不信任撕裂快照；`stat` 本身失败（`DB_ERROR`/`WAL_ERROR`）
   也返回 null，不会把“stat 失败”当成“WAL 不存在”；
4. WAL 不存在或 size=0 时跳过 WAL 复制；WAL 有数据但复制失败也返回 null，不回退到旧主库；
5. 在 App 缓存目录打开 SQLite，查询：
   `SELECT enabled FROM modules_state WHERE module_pkg_name='com.nemu.windstop' AND user_id=0`；
   无行按“未启用”处理，不当作读取失败；
6. 再查 `scope` 表确认 `com.hihonor.ouc` 仍在作用域：
   `SELECT 1 FROM scope WHERE module_pkg_name='com.nemu.windstop' AND app_pkg_name='com.hihonor.ouc' AND user_id=0`。

结果只用于补充管理器开关提示，不改变 hook 的激活判定。读取失败（无 Root、
授权弹窗超时、数据库查询失败）时显示“管理器状态读取失败，点刷新重试”；
如果之前有已知管理器状态，会静默重试一次，仍失败才提示，避免连续触发 Root 弹窗。
不会把旧的管理器状态静默当成当前状态，也不会反复提示 Root。

**已知修复（2026-11-22）**：旧实现直接在探测线程读取 `su` 的 stdout。首次 Root
授权弹窗未处理时 `read()` 会永久阻塞，`lsposedProbeRunning` 不再复位，之后所有探测
都被跳过，界面就表现为“状态未知”。现在 stdout 由独立泵线程读取，主线程最多等
15 秒并销毁超时进程；点“刷新”可以重新发起探测。

状态显示：

| 条件 | 标题下文字 | 底部状态 |
|---|---|---|
| fresh hook 成功，管理器开 | 已激活，软件更新已返回最新 | LSPosed · 已激活 |
| fresh hook 成功，管理器关 | 已激活（管理器已关闭，新进程不再生效） | LSPosed · 已激活（管理器已关） |
| fresh hook 成功，作用域取消 | 已激活（作用域已取消，新进程不再生效） | LSPosed · 已激活（作用域已取消） |
| fresh hook 失败 | 已注入，hook 未生效 | LSPosed · 已注入，hook 未生效 |
| 2 秒内没有 fresh hook 报告，或 OUC 进程退出 | 已启用，当前软件更新进程未上报（上次 hook 已生效） | LSPosed · 已启用，当前进程未上报 |
| 管理器关，无 fresh 报告 | 已在管理器中关闭 | LSPosed · 已在管理器中关闭 |
| 管理器开，作用域未勾选 | 已启用，但作用域未勾选软件更新 | LSPosed · 作用域未勾选软件更新 |
| 管理器开，未注入 | 已启用，重启软件更新后生效 | LSPosed · 已启用，重启软件更新后生效 |
| 管理器状态读取失败 | 管理器状态读取失败，点刷新重试 | LSPosed · 管理器状态读取失败，点刷新重试 |
| 读不到管理器、无 hook 报告 | 未能读取管理器开关，点芯片打开管理器 | LSPosed · 未能读取管理器开关 |

覆盖安装新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。
App 会在下一次进入前台/点刷新时显示管理器关闭；只有本次前台收到新的 hook 报告才显示“已激活”。

## 5. 构建

```powershell
cd D:\win_root\nemu-update-guard
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease
.\gradlew.bat :app:assembleRelease
```

- `testDebugUnitTest` 9 项（`ProbeStatTest` 5 + `StatusModelTest` 4）；
  `lintRelease` 0 errors / 0 warnings；CI 工作流同时跑测试、lint、release 构建；
- `compileOnly` Xposed API 82：`app/libs/api-82.jar`；
- 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`；
- 没有 `keystore.properties` 时 release 复用 debug 签名（会打印警告，仅适合本机覆盖安装）；
- 本机已配置私有签名 `keystore/nagi-release.jks` + `keystore.properties`（均 gitignore），
  备份在 `D:\win_root\nagi-signing-backup\`；证书 SHA-256 `1F:45:…:61:BB`。
  换签名必须卸载旧版；密码在 `keystore.properties`，丢失后只能卸载重装；
- 版本名固定 `1.3.8`，内部 `versionCode` 递增。

## 6. 当前实测（2026-11-23，v35）

- 安装：已卸载旧 debug 签名版，安装私有签名 v35（UID 10309，证书
  `1F:45:7A:B9:…:61:BB`）；卸载后 FinaSU allowlist 条目被删，已在 App Profile
  重新授权“超级用户”；重启 OUC 后 hook 正常加载；
- 管理器开关/作用域：LSPosed `modules_state` 中 `com.nemu.windstop` 当前
  `enabled=1`，`scope` 里仍有 `com.hihonor.ouc`；App 日志实测
  `manager switch=true oucScope=true`；复制前后 stat 校验稳定；
- 按需重报：Nagi 打开时发请求广播，约 0.5 秒后收到
  `hook report hookOk=true from uid=10121`，不再依赖 OUC 启动时那一次上报；
- fresh 超时：force-stop OUC 后打开凪，2 秒后日志 `hook report timeout`，
  界面显示“已启用，当前软件更新进程未上报（上次 hook 已生效）”，不再显示“已激活”；
  重启 OUC 后再次进入前台，收到新报告并恢复“已激活”；
- Binder death：凪保持前台时 force-stop OUC，日志立即出现
  `OUC reporting process gone`，界面马上变为“当前进程未上报”，不需要等下一次前台/刷新；
  重启 OUC 后新 pid 的报告（日志带 `pid=`）恢复“已激活”；
- 界面：fresh hook 成功，管理器开启 → “已激活，软件更新已返回最新”，底部
  “LSPosed · 已激活”，芯片带 ✓；点芯片会直接打开 LSPosed 管理器，底部状态不被覆盖；
- 首次探测完成前显示“正在检查 LSPosed 状态…”，不会先闪“状态未知”；
- 首次 Root 授权超时不再卡死探测线程；点“刷新”可重试；
- 管理器关闭/作用域取消时，只有 fresh 报告成功才显示“已激活”并补充说明；
  没有 fresh 报告只显示“当前进程未上报”；
- 关机兜底 hook 已删除，模块只 hook `com.hihonor.ouc`，作用域不再需要 `system`；
- `testDebugUnitTest` 9 项通过；`lintRelease` 0 errors / 0 warnings（有意项已在 lint.xml/manifest 注明）；
- Release 未配置私有 keystore 时仍用 debug 签名，Gradle 会打印警告；该 APK 不应公开分发；
- 120 Hz：打开 App 后 `dumpsys display` 为 `mActiveModeId=3`、`renderFrameRate 120.00001`；
- OUC 未被冻结：`suspended=false`。
