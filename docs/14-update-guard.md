# 14 凪（独立更新 App）

独立应用：`D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`），
显示名 **凪**（日语风格中文名，不含假名；原“更新守卫”），版本 `1.3.3` / `versionCode 9`。

APK：`app\build\outputs\apk\debug\app-debug.apk`（2,653,436 字节，SHA-256
`d400b5165cf31760c613e69c555bb2719246e2c0c448e1b1ac3b2ef87ce7c7bb`）。

## 1. 唯一目标

让系统更新（`com.hihonor.ouc`）的检查结果显示“当前系统已是最新”，
不下载、不安装、不修改系统版本号，也不暂停/停用/冻结 OUC。

## 2. LSPosed 模式

反编译本机 MagicOS 10 的 `com.hihonor.ouc` 后确认：

- 更新检查结果类：`com.hihonor.android.hnouc.check.model.result.a`
- `d()` 返回“是否有新版本”；
- 多数 UI/通知路径用 `next.d() && next.b()` 判断是否展示更新。

`UpdateHook` 使用 LSPosed API 82：

```java
XposedHelpers.findAndHookMethod(
    "com.hihonor.android.hnouc.check.model.result.a",
    lpparam.classLoader, "d",
    XC_MethodReplacement.returnConstant(false));
```

作用域：`android`（系统框架，用于关机 hook）+ `com.hihonor.ouc`。
在 LSPosed 管理器中启用模块并重启一次后一直生效，App 内没有开关。

模块被注入到任一作用域进程后，会向 `com.nemu.windstop` 发送
`com.nemu.windstop.action.LSPOSED_ACTIVE` 广播；App 的接收器记录当前 boot count
并立即刷新界面。App 还会在回到前台/点刷新时用 root 只读查询 LSPosed 的
`modules_state`，因此管理器里把模块**关掉**也会马上显示“已在管理器中关闭”，
不需要等重启。三种状态：
- 管理器关闭 → “已在管理器中关闭”，点芯片才打开管理器；
- 已启用但本次开机还没注入 → “已启用，重启后生效”，点芯片不弹框；
- 已注入 → “已激活，更新检查返回已最新”。
root 不可用或读取失败时，回退到激活广播/本次开机标记。

## 3. 无 LSPosed 的独立模式

`UpdateAccessibilityService` 监听 `com.hihonor.ouc`。出现
“发现新版本 / 立即更新 / 下载并安装 / 新版本可用 / 有可用更新”等文本时，
不关闭、不冻结 OUC，而是显示一个全屏无障碍覆盖层：

- 标题：“当前系统已是最新版本”；
- 说明：凪已把这次检查结果处理为“已是最新”，不会下载/安装/修改系统；
- 按钮“知道了”只关闭覆盖层，不发送返回键、不退出更新页；同一页面 60 秒内不再弹出。

覆盖层忽略自己包名产生的事件，避免刚显示就被自己的窗口变化关掉；只有切到
非 OUC 页面时才清理残留覆盖层。无障碍权限只在用户点选独立模式或打开开关时按需
申请，App 启动不会自动开启无障碍。

Root / Shizuku / ADB 只用于**自动开启这个无障碍服务**，不会执行
`pm suspend`、`pm disable`、`pm hide` 或任何冻结命令。App 内不再提供暂停 OUC 按钮。

开启方式（2×2 模式芯片 + 启用开关）：

- 手动：系统设置 → 无障碍 → 凪；
- LSPosed：已启用/已激活时点芯片直接切换、不弹框；只有管理器关闭时才弹说明框并跳转；
- Root：点芯片请求/使用 Root 并开启；
- Shizuku：点芯片请求/使用 Shizuku 并开启；
- ADB：点芯片复制下面的命令，即

```sh
adb shell settings put secure enabled_accessibility_services com.nemu.windstop/com.nemu.updateguard.UpdateAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### 3.1 为什么这不是代码注入

Android 的应用沙箱不允许一个普通 App 向另一个 App 的进程写代码。
`adb shell` / Shizuku 的 uid 是 shell（或受限的 root 代理），能执行包管理命令，
但没有 Xposed/Zygisk/Frida 之类的进程内注入能力。因此：

- **ADB 单独**：无法 hook OUC 的 Java 方法；上面的无障碍覆盖层是唯一能显示
  “已最新”的独立路径；
- **Shizuku 单独**：同上；
- **Root 单独**：可以修改系统文件/挂载/注入原生库，但要让 OUC 的 Java 方法返回 false，
  仍需一个进程内 hook 框架。当前版本没有内置这种框架，只用 root 自动开启覆盖层。

如果必须做到“root 单独、无 LSPosed 也改变 OUC 进程内的方法返回值”，可选路线是：

1. 做一个 Zygisk 模块，把 LSPlant/Pine 等 Java hook 库加载进 OUC 进程；
2. 或做一个 systemless 的 OUC APK/dex 修补，并用每进程 bind mount 加载。

两条路线都需要额外模块、重启，且涉及系统应用完整性风险；在没有单独设计和
回滚方案前，不应把它们混进本 App 的“已完成”功能里。当前代码不执行这些操作。

## 4. Root / Shizuku 状态

- Root：执行 `su -c id`，成功才显示“已授权”；可一键开启无障碍。
- Shizuku：`Shizuku.pingBinder()` + `checkSelfPermission()`；可一键开启无障碍。
- 两者都不会暂停 OUC；状态卡只表示“能不能帮你开启独立模式”。

## 5. 隐藏桌面图标

通过 `activity-alias`（`com.nemu.updateguard.Launcher`（源码命名空间；applicationId 为 `com.nemu.windstop`））的组件启用状态隐藏/显示，
并回读校验；隐藏后发布常驻通知（id 42），点通知打开凪，通知内可“显示图标”。
隐藏/显示后通过 Shizuku/root 重启桌面，使旧图标缓存消失；无授权时回退 HOME Intent。
BootReceiver 在重启/应用更新后补发通知。

图标统一为白底黑色的“凪”图形（三道风线 + 竖条）：`mipmap/ic_app`（自适应）、
`ic_launcher_large`（通知大图标）、`ic_notification`（状态栏小图标，白色图形）。通知显式设置大图标，避免系统把
旧 APK 的缓存图标放进通知。MagicOS 通知左侧的小头像由系统按包名缓存，卸载重装、
重启 SystemUI 和切换头像资源都不会刷新；旧图标会保留到手机重启后由系统重新
读取当前 APK 图标。代码中已不存在任何插画/图标包图片资源。

## 5.1 四种模式的状态含义

- LSPosed：模块在管理器启用 `android` + `com.hihonor.ouc` 作用域并重启后生效；
- Root：只在点选时执行 `su -c` 校验和开启独立模式，不冻结 OUC；
- Shizuku：只在点选时走 Shizuku 授权并开启独立模式；
- ADB：只复制开启独立模式所需的 adb 命令，不注入代码。

关闭“独立模式”开关会调用 `disableSelf()` 关闭无障碍，并清空系统无障碍列表。

## 6. 关机 hook（与主 App 联动）

`UpdateHook` 在 system_server 中 hook `com.android.server.power.ShutdownThread.run`，
在真正关机前：

1. 删除 `/data/adb/nemu/restore_done`；
2. 向 `/data/adb/nemu/shutdown.pipe` 写入 `shutdown`；
3. 最多等待 8 秒，直到 Nemu 模块的守护进程写入 `restore_done`。

模块 `restore_if_frozen.sh` 执行完 `restore_frozen.sh` 后写入 ACK（v1.8 修复）。
这条路径只服务于主 App 的关机解冻，不影响更新 hook。

## 7. 实测记录（2026-11-22）

- 无障碍覆盖层：已在真实 OUC 更新页验证，显示“当前系统已是最新版本”；
  本轮 OUC 页面本身返回“已是最新版本”时不显示覆盖层（没有更新提示文本，符合设计）；
- 模式芯片：LSPosed 已激活时直接切换、不弹框；未激活时才打开 App 内说明框；Root/Shizuku/ADB 均可点选并给出状态，
  选中项有 ✓/强调色；ADB 只复制命令；没有自动跳系统设置/桌面；
- 隐藏图标：alias 禁用并回读校验，桌面图标消失；常驻通知出现，点通知打开 App，
  通知内“显示图标”动作可恢复；隐藏/显示时停留在当前页面，不再跳桌面；
- 开关关闭：`disableSelf()` 后 `enabled_accessibility_services=null`、`accessibility_enabled=0`；
- 120 Hz：打开 App 后 `dumpsys display` 为 `mActiveModeId=3`、`renderFrameRate 120.00001`；
- 通知图标：重启后通知左侧头像、右侧大图标、状态栏小图标均为凪的风停图形；
  Nemu 为月亮；旧 F/插画缓存已清除；
- LSPosed 三态：管理器关闭 →“已在管理器中关闭”；已启用未重启 →“已启用，重启后生效”；
  重启注入 →“已激活，更新检查返回已最新”；三种状态均已实机验证。
  点击芯片在关闭态才弹管理器，其余状态直接切换不弹框；
- 激活判定：模块注入广播 + 本次开机标记；关闭判定：回到前台/刷新时 root 只读查询
  LSPosed `modules_state`。覆盖安装新 APK 后 LSPosed 可能自动关闭模块，App 会立即显示关闭。
- 不再提供暂停/冻结 OUC 的入口；测试后 OUC `suspended=false`。

## 8. 构建

```powershell
cd D:\win_root\nemu-update-guard
.\gradlew.bat :app:assembleDebug
```

依赖：`compileOnly` Xposed API 82（`app/libs/api-82.jar`）、Shizuku API 13.1.5。
LSPosed 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`。
