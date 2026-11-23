# 两个项目的地址与产物

> 更新：2026-11-23（审计修复后重新构建）  
> 设备：HONOR AAP-AN00，MagicOS 10.0.0.102（Android 16，SDK 36），当前槽位 `a`  
> 远程仓库：https://github.com/JiuQing1134/Nemu 与 https://github.com/JiuQing1134/Nagi（公开）。

## 1. Nemu（眠梦）

- **本地地址**：`D:\win_root\nemu`
- **GitHub**：https://github.com/JiuQing1134/Nemu
- **applicationId**：`com.nemu.dream`
- **源码命名空间**：`com.nemu.hibernate`
- **版本**：`1.3.3` / `versionCode 9`
- **最终四包**（`dist/`，debug/release × arm64-v8a/x86_64）：
  - `Nemu-1.3.3-arm64-v8a-debug.apk` — 34,700,954 字节 — `111cdb3eb168791cf5d9f04fca9d83f0382815a9943d026cb424fa70e604c79c`
  - `Nemu-1.3.3-x86_64-debug.apk` — 34,701,615 字节 — `423920dd87b941ed73082bd397deda4d67b304967cd50020f1ec319532129047`
  - `Nemu-1.3.3-arm64-v8a-release.apk` — 24,510,244 字节 — `426a23f8d43cec2bb080d5c2196d2eddc9cffecb85f12a29a1d8cf4027973e51`
  - `Nemu-1.3.3-x86_64-release.apk` — 24,510,905 字节 — `0390ecfb10503a9d5340c4f9ac9977dc70a40199288a1644e1b2c40ded274d2d`
  - ABI 已校验：arm64 只含 `lib/arm64-v8a`，x86_64 只含 `lib/x86_64`；release 已用私有
    keystore 签名（`CN=Nemu`，证书 SHA-256 `1f35e73171516428dc80cf8e5645efe10f41bde510a4e1cb705409adcebf19a7`），
    备份在 `D:\win_root\nemu-signing-backup\`。
- **自启动模块**：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  - 33,830 字节
  - SHA-256：`b3c28507a7f38c1fd4463d420c15de8cb27077fb927b246d5586a419ce325a83`
  - 模块 ID：`nemu_boot_autostart`，v1.10/versionCode 11；v1.10 删除 `am start`
    开机拉起 UI，已安装旧模块需在 FinaSU/KernelSU 更新或卸载后重启
- **文档**：
  - `D:\win_root\nemu\README.md`
  - `D:\win_root\nemu\PROJECT.md`
  - `D:\win_root\nemu\docs\README.md`
- **用途**：冻结/解冻系统更新、AI、云控/遥测、反诈、COTA 温控；不卸载系统应用。
- **外观**：FlClash 配色/外观移植；默认蓝色鲜明，深色纯黑；详见 `docs/22-flclash-theme-2026-11-23.md`。
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
