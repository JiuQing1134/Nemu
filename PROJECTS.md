# 两个项目的地址与产物

> 更新：2026-11-24（模块 v1.18 / App 重新构建）  
> **已验证设备**：HONOR AAP-AN00，MagicOS 10.0.0.102（Android 16，SDK 36），
> 内核 `6.12.23-android16-5-gcd85666d7884-abogki452537391-4k`，当前槽位 `a`。  
> 远程仓库：https://github.com/JiuQing1134/Nemu 与 https://github.com/JiuQing1134/Nagi（公开）。

## 1. Nemu（眠梦）

- **本地地址**：`D:\win_root\nemu`
- **GitHub**：https://github.com/JiuQing1134/Nemu
- **applicationId**：`com.nemu.dream`
- **源码命名空间**：`com.nemu.hibernate`
- **版本**：`1.3.3` / `versionCode 9`
- **当前 universal release**（2026-11-24，含模块 v1.18 配套 App 修复）：
  `app/build/outputs/apk/release/app-release.apk` — 24,806,433 字节 — SHA-256
  `b513e96269b665e77ad6de52edac45be63a311519219963f272fa702afb21fa3`
- **历史四包**（`dist/`，debug/release × arm64-v8a/x86_64）：
  - `Nemu-1.3.3-arm64-v8a-debug.apk` — 34,756,230 字节 — `436565d4ac55a2a7303e2e5b4b7e35ab4da25e4ce72846c9e0bb90a2e336fa81`
  - `Nemu-1.3.3-x86_64-debug.apk` — 34,756,891 字节 — `9b6267e7e1a887dd9a4a39ed0bd7c464ee769bd9b071655242987f12d754b56f`
  - `Nemu-1.3.3-arm64-v8a-release.apk` — 24,543,012 字节 — `b1c28e57363f1dcf0864412bffa8bb8314ce9c5ccab63be702cf2f3cc4774539`
  - `Nemu-1.3.3-x86_64-release.apk` — 24,543,673 字节 — `bf11363f726afd24f95421974a8bb0cb399f9fd1799738e7abb1034bcc6babb2`
  - ABI 已校验：arm64 只含 `lib/arm64-v8a`，x86_64 只含 `lib/x86_64`；release 已用私有
    keystore 签名（`CN=Nemu`，证书 SHA-256 `1f35e73171516428dc80cf8e5645efe10f41bde510a4e1cb705409adcebf19a7`），
    备份在 `D:\win_root\nemu-signing-backup\`。
- **自启动模块**：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  - 26,295 字节
  - SHA-256：`9ce22b583919f8a672e550452ac483976401e5ed8f8cc99c9ea58d445c01557c`
  - 模块 ID：`nemu_boot_autostart`，v1.18 / versionCode 19，11 个条目（含
    `boot_guard_runner.sh`）
  - early PM-ready 阶段恢复全部 Nemu-owned 冻结态；`boot_completed` 后复核；
    快速 `adb reboot` 由 boot_completed 标记防止误触发救援；安装/更新后需重启
- **文档**：
  - `D:\win_root\nemu\README.md`
  - `D:\win_root\nemu\PROJECT.md`
  - `D:\win_root\nemu\docs\README.md`
- **用途**：冻结/解冻系统更新、AI、云控/遥测、反诈、COTA 温控；不卸载系统应用。
- **外观**：主题配色/外观移植（参考 FlClash / prism_ui）；默认蓝色鲜明，深色纯黑；详见 `docs/22-theme-port-2026-11-23.md`。
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

- **参考项目**：
  - Hail（雹）— https://github.com/aistra0528/Hail  
    仅参考 suspend 机制与公开文档，不依赖、不调用其 API/Intent/数据。
  - FlClash / prism_ui — https://github.com/chen08209/FlClash 、
    https://github.com/chen08209/prism_ui  
    仅参考配色/surface/控件外观 token（Apache-2.0），Compose 重新实现。
  - Material Color Utilities —
    https://github.com/material-foundation/material-color-utilities  
    生成静态 HCT 色表（Apache-2.0）。
  - KernelSU / FinaSU、Shizuku：运行时平台，不复制代码。
  - 完整第三方声明：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- **当前设备状态（2026-11-22）**：
  - Nemu：0 个冻结目标，OUC `suspended=false`，SafetyService 未运行。
  - 凪：LSPosed 已启用并激活；独立模式关闭；无障碍已关闭；桌面图标已恢复。
  - 两个 App 均请求 120 Hz（`mActiveModeId=3 / renderFrameRate 120.00001`）。
- **注意**：覆盖安装凪的新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。App 会立即显示“已在管理器中关闭”。
