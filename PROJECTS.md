# 两个项目的地址与产物

> 更新：2026-11-22  
> 设备：HONOR AAP-AN00，MagicOS 10.0.0.102（Android 16，SDK 36），当前槽位 `a`  
> 远程仓库：https://github.com/JiuQing1134/Nemu 与 https://github.com/JiuQing1134/Nagi（公开）。

## 1. Nemu（眠梦）

- **本地地址**：`D:\win_root\nemu`
- **GitHub**：https://github.com/JiuQing1134/Nemu
- **applicationId**：`com.nemu.dream`
- **源码命名空间**：`com.nemu.hibernate`
- **版本**：`1.3.3` / `versionCode 9`
- **APK**：`D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`
  - 33,375,281 字节
  - SHA-256：`811c36d6a59a6321613f43a35dc8eb0588a5b7ec4776a2d2b364e4a5af105961`
- **自启动模块**：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  - 10,043 字节
  - SHA-256：`e3320a28c9c1de004f782af6a94ee1258f5b402a45d17002be2d77cfa59dd89f`
  - 模块 ID：`nemu_boot_autostart`，v1.8；尚未安装到 FinaSU
- **文档**：
  - `D:\win_root\nemu\README.md`
  - `D:\win_root\nemu\PROJECT.md`
  - `D:\win_root\nemu\docs\README.md`
- **用途**：冻结/解冻系统更新、AI、云控/遥测、反诈、COTA 温控；不卸载系统应用。
- **图标**：白底黑色月亮。

## 2. 凪

- **本地地址**：`D:\win_root\nemu-update-guard`
- **GitHub**：https://github.com/JiuQing1134/Nagi
- **applicationId**：`com.nemu.windstop`
- **源码命名空间**：`com.nemu.updateguard`
- **版本**：`1.3.3` / `versionCode 9`
- **APK**：`D:\win_root\nemu-update-guard\app\build\outputs\apk\debug\app-debug.apk`
  - 2,653,436 字节
  - SHA-256：`d400b5165cf31760c613e69c555bb2719246e2c0c448e1b1ac3b2ef87ce7c7bb`
- **文档**：
  - `D:\win_root\nemu\docs\14-update-guard.md`
  - `D:\win_root\nemu\docs\15-task-status-2026-11-22.md`
- **用途**：让 OUC 更新检查显示“已是最新”，不冻结/不卸载 OUC，不修改系统版本号。
- **LSPosed**：作用域 `系统框架 (system)` + `软件更新 (com.hihonor.ouc)`；模块开关关闭/已启用待重启/已激活三态会实时显示。
- **图标**：白底黑色“三道风线 + 竖条”的风停图形。

## 3. 共用信息

- **参考项目**：Hail（雹）— https://github.com/aistra0528/Hail  
  仅参考 suspend 机制与公开文档，不依赖、不调用其 API/Intent/数据。
- **当前设备状态（2026-11-22）**：
  - Nemu：0 个冻结目标，OUC `suspended=false`，SafetyService 未运行。
  - 凪：LSPosed 已启用并激活；独立模式关闭；无障碍已关闭；桌面图标已恢复。
  - 两个 App 均请求 120 Hz（`mActiveModeId=3 / renderFrameRate 120.00001`）。
- **注意**：覆盖安装凪的新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。App 会立即显示“已在管理器中关闭”。
