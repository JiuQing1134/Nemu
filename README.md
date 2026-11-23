# Nemu（眠梦）

HONOR MagicOS 系统组件“安全冻结”工具。

> **2026-11-22 完成彻底改名**：应用 applicationId `com.nemu.dream`（源码命名空间 `com.nemu.hibernate`），凪 applicationId `com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`）；模块 ID
> `nemu_boot_autostart`，根目录 `D:\win_root\nemu`；旧包名、旧模块和旧数据已清理。
> 冻结与解冻拆成独立页面，支持 SUSPEND / COMPONENT / DISABLE_USER / HIDE / OBSERVE
> 五种策略；HIDE 即“伪装卸载”（`pm hide`，仅 Root）。
>
> **参考项目**：Hail（雹）— https://github.com/aistra0528/Hail  
> 仅参考其 suspend 冻结机制与公开文档，不依赖、不调用其 API/Intent/数据。
>
> **项目地址**：
> - Nemu：`D:\win_root\nemu` — https://github.com/JiuQing1134/Nemu
> - 凪：`D:\win_root\nemu-update-guard` — https://github.com/JiuQing1134/Nagi
>
> 完整地址、包名、版本与产物哈希：[`PROJECTS.md`](PROJECTS.md)。

> 代码地图、开发流程、代码树、已知问题和审计入口：**[PROJECT.md](PROJECT.md)**。  
> 文档索引：**[docs/README.md](docs/README.md)**。

## 状态

- 2026-11-21：核心 + 安全解冻已完成实机验收；**FinaSU root 后端已授权并实测**（`last_shutdown_detail` 显示 `Root`，关机解冻成功）。
- 2026-11-22：**Nemu 重设计完成**：5 Tab、冻结/解冻分页、统一目录（41 个包 /
  64 个组件，组件全部在设备 `dumpsys` 核对）、SUSPEND/COMPONENT/DISABLE_USER/HIDE/OBSERVE、
  所有权恢复、检测页策略感知、guard v1.5。全量单测 416 个（0 失败）。
- 2026-11-22 第二轮（v1.1）：新增 **HIDE（伪装卸载，`pm hide/unhide`，仅 Root）**、
  每个目标的“具体会发生什么”确认弹窗、冻结页直白按钮、Material3 / M3 Expressive / MIUIX
  四套风格 + 八套配色（深色纯黑 OLED）、Root/Shizuku 授权模式卡、
  无冻结时 SafetyService 不常驻；修复 WorkManager 迟到解冻覆盖用户冻结的 P0。
  真机验证见 `docs/12-device-test-log-2026-11-22.md`。
- 2026-11-22 真机验证：OUC dry-run/实际冻结/选择性解冻/重新冻结；dry-run 全部与
  所选解冻；numberidentity disable-user 完整往返；systemappsupdater 冻结/恢复；
  hiview 三件套组件模式；iaware 组件模式；重装 APK 不触发解冻；正常重启后
  ShutdownSafety 恢复 10/10 组件，guard attempts=1，120 秒后 18 个目标自动冻结成功。
  powergenie 组件被 HONOR 保护（`pm disable` 返回 default），已改为 OBSERVE only。
  完整记录见 `docs/12-device-test-log-2026-11-22.md`；历史 190 测试/19 类见 `PROJECT.md`。
- 设备：HONOR AAP-AN00，MagicOS 10.0.0.102（Android 16，SDK 36）。
- 首要目标：系统更新（`com.hihonor.ouc`）。
- 已实现但默认关闭：AI 叶子组件（App 建议、YOYO 建议卡片集、智能提醒、荣耀智慧空间基础服务）、AI 深度冻结（AI 基础服务/YOYO/语音/运行时）、云控/遥测、反诈、COTA 温控。
- 实机安装/模块写入由用户在明确授权后执行；助手不碰 ABL/分区/`fastboot flash`。
- 当前版本：`versionCode 9` / `versionName 1.3.3`。最终四包在 `dist/`：
  arm64-v8a debug 34,756,230 字节 `436565d4…fa81`；x86_64 debug 34,756,891 字节
  `9b6267e7…b56f`；arm64-v8a release 24,543,012 字节 `b1c28e57…4539`；
  x86_64 release 24,543,673 字节 `bf11363f…abb2`。完整 SHA-256 见 `PROJECTS.md`。
  release 已使用私有 keystore 签名（`CN=Nemu`，证书 SHA-256
  `1f35e731…19a7`）；debug 包仍为 debug 签名。
- 凪（`D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`）：
  `versionCode 9` / `1.3.3`，`app\build\outputs\apk\debug\app-debug.apk`，
  2,653,436 字节，SHA-256 `d400b5165cf31760c613e69c555bb2719246e2c0c448e1b1ac3b2ef87ce7c7bb`。
- 自启动模块：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  （34,135 字节；SHA-256 `b0979b800d33fd2fc6960e7a5bf00105838f4cf4ed3ba976040b321242ae15f9`；
  FinaSU 模块 v1.10 / versionCode 11；v1.10 起 **不再在开机时自动打开 Nemu 页面**，
  开机恢复交给应用自己的 BootReceiver/WorkManager。已安装旧模块的设备需在 FinaSU/
  KernelSU 中用新 zip 更新（或直接卸载模块）后重启才生效）。
- UI：中性白/灰底 + 单色强调、M3E 大圆角、深色纯黑 OLED；桌面图标使用白底亮色几何图形：Nemu 为钴蓝 Tabler 雪花，凪为翠绿三角回环与中心圆点。
- 2026-11-22 第三轮：桌面/应用信息/通知大图标统一为白底黑色月亮/凪图形；通知显式设置大图标，不再回退系统缓存图标；请求 120 Hz（实测 `mActiveModeId=3 / renderFrameRate 120.00001`）；修复返回桌面图标闪回、隐藏图标后残留旧图标、模式芯片无响应、点击跳跃、无障碍自动开启、Nemu 点击 1 秒卡顿。系统通知左侧的小头像由 MagicOS 自行缓存，重启手机后刷新（代码不再提供任何插画资源）。
- 2026-11-22 第四轮：凪改名并改用风停图形；LSPosed 状态三态实时显示——管理器关闭 / 已启用待重启 / 已激活；激活用模块广播 + 本次开机标记，关闭用 root 只读查询 LSPosed 的 `modules_state`；覆盖安装后 LSPosed 若自动关掉模块，App 会立即显示“已在管理器中关闭”。

## 核心结论

1. **不要卸载/停用 `com.hihonor.ouc`**：用户报告的原始操作序列是“卸载系统更新 → 全量强制 dex2oat 编译 → 刷入类似 Magisk 的 root 模块 → 崩溃；重启能进桌面但马上卡住重启”。因此不能把崩溃单独归因于 OUC；Nemu 仍坚持“不卸载”，只用可逆操作。对现有日志的重新分析见 [05 OUC 崩溃分析](docs/05-ouc-crash-analysis.md)：当前 dropbox/tombstone 没有 system_server 或 OUC 崩溃证据，更多线索指向全量 dex2oat + root/zygisk 模块（LSPosed/dexoptanalyzer tombstone），原始事件日志可能已不存在；该结论不改变“不卸载”的产品规则。
2. `suspend` 是可逆的包策略，不保证进程退出；`force-stop` 是一次性请求，系统仍可能因绑定服务重新启动进程。检测页分别展示挂起、STOPPED 标记和实际进程状态，不把命令退出码当作“后台已彻底停止”。
3. 安全解冻始终开启：正常关机/重启前自动 `unsuspend` 全部 suspend 目标（含 AI 目标）；开机后由 `BootFreezeWorker` 在延迟（默认 120s）后自动重新冻结已启用目标。bootloop 守卫生效时，本次开机保持解冻并记录跳过原因。
4. 实机验证：OUC `suspended=true` → 正常 `svc power reboot` → `last_shutdown_result=成功` → 开机后 `suspended=false`，手机正常启动。`adb reboot` 走 init，不会触发安全解冻。
5. `suspend` 不阻止后台运行（Hail README 与 dumpsys 均证实）；本机 OUC 在 `suspended=true` 时仍能收到 `BOOT_COMPLETED`。模块必须配合强停、AppOps/待机限制或 `disable-user` 的可逆例外。

## 云控 / 遥测（高级，默认关闭）

- 运营商 DM 三件套（`com.hihonor.regservice`、`com.hihonor.android.UEInfoCheck`、`com.hihonor.android.AutoRegSms`）和 `com.hihonor.numberidentity` 可使用 `pm disable-user --user 0`（绿/黄目标的可逆整包停用例外）；逐项确认、不卸载，只在记录“原本启用”时 `pm enable` 回滚。
- 遥测三件套（`com.hihonor.hiview`、`com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel`）在本机**不能 suspend**（平台返回 `state:false`），默认改用 **COMPONENT** 停用登记的 Service/Receiver/Provider；关机/开机按原始状态恢复组件，而不是只 `pm unsuspend`。
- 冻结/解冻统一在 Freeze/Unfreeze 页操作；所选解冻会关闭策略，“临时全部解冻”会保留策略并跳过本次开机冻结。执行方式在设置里明确选择“预览（手动操作不改手机；开机/关机安全清理仍执行）”或“实际执行”，不再用含义反直觉的 Dry run 开关。
- `com.hihonor.cipservice`、`com.hihonor.android.hnaps` 仅展示状态；`com.hihonor.android.pushagent` 在禁止清单中，不提供操作。
- 云控/遥测不再有单独的“恢复全部云控”按钮；使用 Unfreeze 页的“解冻所选/全部解冻”。
- 风险：停用运营商 DM 可能影响 VoLTE 配置、运营商 FOTA、自注册短信；组件停用遥测可能影响诊断日志。
- 测试：CT1–CT5、PF9/PF11/PF16（见 `docs/04-test-plan.md`、`docs/12-device-test-log-2026-11-22.md`）。

## 反诈（高级，默认关闭）

- 与更新/AI 列表分开管理，不混入 `Targets.all`。
- `com.hihonor.numberidentity`（号码识别）可选 `suspend` 或 `disable-user`；失去来电识别/骚扰与诈骗标记，可恢复。
- `com.hihonor.securityserver`（安全公共服务）与 `com.hihonor.awareness`（情景感知）**禁止停用/隐藏**；默认只观察，可选“暂停”（会弹出具体后果：支付保护/活体/反诈校验可能失灵）。高级控制里仍提供 `appops RUN_ANY_IN_BACKGROUND ignore` + `standby restricted`，可恢复为 `allow`/`active`。
- `com.hihonor.securitypluginbase` 仅观察。
- `numberidentity` 纳入关机/开机安全解冻（`pm unsuspend` 幂等；`disable-user` 不会被 `pm unsuspend` 自动恢复）。
- 测试：`AF1–AF6`（见 `docs/04-test-plan.md`）。

## 温控 / COTA（root-only，默认关闭）

- 递归只读 `/data/cota/para` 下最多 4 层的厂商云控参数（真机为 `thermal_config/thermal_config_cloud.xml` 等子目录文件），按子系统分组，显示相对路径、大小、SHA-256、mode/uid/gid。
- **备份优先**：必须先备份并回读校验 `manifest.tsv`，且当前文件 hash 与备份一致，才允许删除；删除只使用显式路径的 `rm -f`，没有 `rm -rf`；恢复后再次校验 SHA-256。
- 只接受 `RootPrivilegeBackend`（uid 0）；Shizuku 是 shell 权限，不实现该接口，不存在 Shizuku 回退。
- **不触碰** `com.hihonor.powergenie`、`com.hihonor.iaware`、vendor HAL、内核 thermal zone。
- 风险：删除温控/功耗参数可能改变降频与充电策略，导致发热、充电变慢或系统不稳定；不保证提升性能；系统可能重新下发参数；错误文件或没有备份可能导致无法恢复。
- 测试：`COTA1–COTA6`（见 `docs/04-test-plan.md`）；先做合成文件测试，真实参数删除是可选项。

## 开机自动冻结（可选，默认关）

- 开机稳定后（默认 120s，可配置，最低 60s）由 `BootFreezeWorker` 自动冻结已启用的核心/AI 目标；FinaSU `service.sh` 或打开 Nemu 都会调度。
- **开机恢复优先于冻结**：Worker 在评估守卫/执行冻结之前，先用 root 解冻全部安全目标，清掉强制断电或崩溃遗留的 suspend；清理失败则跳过本次冻结。因此守卫跳过新冻结时，残留 suspend 仍会先被清除。
- 上次冻结后没有“干净关机”（`last_shutdown_result` 成功且时间晚于冻结尝试）时，下一次开机 **跳过自动冻结**，记录 `last_boot_freeze_result`，避免 bootloop。
- “全部恢复”/关闭开关会取消本 boot 的自动冻结并标记跳过。
- AI 深度冻结默认关闭；每个 AI 目标单独开关，红色目标有高风险提示；`mageruntime` 单目标已实测可冻结/重启解冻。`powergenie` 仅观察、永不执行；`iaware` 默认只读，仅在用户启用并选择“组件”策略时于开机冻结中停用两个云配置组件（PF14 实测）。

## 持久日志（默认关闭）

- UI “设置 → 日志”打开；关闭时不写文件。
- 文件：`/sdcard/Android/data/com.nemu.dream/files/logs/nemu-YYYYMMDD.log`（最多 7 个、单文件 2 MB）。
- 记录启动（`[startup]`）、Boot、关机安全解冻、冻结/恢复命令和错误。
- “抓取启动 logcat”会用 root/Shizuku 执行 `logcat -b all -d -t 3000`，写入 `startup-*.txt`。

## 检测（只读）

- Dashboard “检测”页显示核心/AI/云控目标的策略 vs 实际 suspend/启用状态、COTA 文件、powergenie/iaware 状态、启动/安全结果。
- 只读；不提供停止/删除/暂停按钮。

## 安全解冻（始终开启，但只在需要时驻留）

- `SafetyService` **只在至少一个目标处于冻结状态时启动**；没有冻结目标时不会常驻、不会保留前台通知，也不会有周期性唤醒。冻结/解冻后 App 会自动 `sync`：有冻结则启动，全部恢复则停止。
- 服务运行时接收 `ACTION_SHUTDOWN`/`ACTION_REBOOT`，在关机窗口内同步执行一次 `pm unsuspend --user 0 <Nemu 拥有的 suspend 目标>`（最多 8s），并恢复 Nemu 拥有的组件/隐藏状态；`disable-user` 不自动逆转，只有显式解冻会 `pm enable`。
- 开机后 `BootFreezeWorker` 在延迟（默认 120s）后自动重新冻结已启用目标；它成功冻结后才会启动安全服务。WorkManager 若在开机很久后才送达解冻任务，会检查“本次开机是否已经执行过冻结”，是则跳过，避免把用户刚冻结的应用解冻。
- 安全集合由 `SafetyTargets` 统一维护：核心目标 + 全部 AI 目标 + hiview 三件套 + `numberidentity`。运营商 DM 三件套是 `disable-user`，安全路径**不**执行 `pm enable`，它们保持停用直到显式“恢复全部云控”。
- 开机后 `BootFreezeWorker` 会在延迟后自动重新冻结已启用目标；bootloop 守卫跳过时保持解冻。
- 正常电源菜单重启 / `svc power reboot` 会触发；`adb reboot`、强制断电、系统崩溃无法在关机瞬间执行任何代码，所以跳过关机解冻。
- **强制断电/崩溃的兜底在下次开机**：FinaSU `service.sh` 会在等待 `sys.boot_completed` 之前就循环执行 `pm unsuspend`（避免“因残留 suspend 导致 boot_completed 永远不到”的死锁），启动完成后再补一次；`BootFreezeWorker` 也会在冻结前先清除残留。两层都失败时才会带着 suspend 启动，核心 OUC 单独 suspend 仍可开机，红色 AI 目标风险最高。
- **本机实测 HONOR 会拦截从“已停止”状态拉起应用的第三方开机广播**：logcat 出现
  `HwMtmBroadcastResourceManager ... com.nemu.dream : don't meet cpuload`。
  因此 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` 不能可靠启动 `SafetyService`。
  但如果应用已被 FinaSU 脚本或手动打开，后续 `USER_UNLOCKED` 仍可入队 `BootWorker`
  （boot=34 实测 `last_boot_result=安全解冻成功`）。
  最稳妥做法：安装 FinaSU 自启动模块，或在
  “设置 → 应用 → 应用启动管理”把 Nemu 设为**手动管理**并允许**自启动/关联启动/后台活动**；
  否则下一次关机的安全解冻可能不会运行。App 顶部已提供“打开「应用启动管理」允许自启动”按钮。
- 开机安全解冻是 best-effort 第二层；主路径是关机安全解冻。
- 关机时 Shizuku/root 必须可用，且安全服务必须在运行（打开 App 或由系统允许自启动后）。
- **跨工具 suspend 限制**：如果目标是被 Hail、`adb shell pm suspend` 或其他工具暂停的，Nemu 的 `pm unsuspend` 可能清除不了（`suspendingPackage` 不同）；请用原工具或 adb 清除。Nemu 自己施加的 suspend 会由同一后端清除。App 的 `last_shutdown_detail` / `last_boot_detail` 会记录后端、命令和输出，便于排查。

## 外观与授权（v1.1）

- **图标**：白底亮色几何图形（Nemu=钴蓝 Tabler 雪花，凪=翠绿三角回环与中心圆点），自适应图标/单色图标/通知图标/App 内标记统一。
- **风格**：Material 3 / M3 Expressive / MIUIX 三套可切换；**配色**：松林绿（默认）、冰川蓝、暖琥珀、极光紫、石墨灰；每种配色的控件颜色都从该色系与白色之间推导；明暗跟随系统/浅色/深色。深色背景使用纯 `#000000`（OLED 省电），卡片为中性深灰，不使用褐色。
- **授权卡**：显示当前授权模式（`Root` / `Shizuku（ADB 启动）` / `Shizuku（Root 启动）` / `未授权`），并分别列出 Root、Shizuku、ADB 调试的真实状态；“请求授权”会先尝试 Root，再请求 Shizuku 授权；提供“ADB 授权说明”（ADB 仅用于启动 Shizuku，Nemu 不能直接调用电脑 ADB）。
- **冻结/解冻按钮**：冻结页明确区分“勾选（只加入计划）”与“立即冻结”；每个目标有“立即冻结/重新冻结”，顶部有“立即冻结已勾选的 N 个应用”，执行前逐一列出“具体会发生什么”。解冻页用“解冻所选 N 个应用 / 全部解冻”。

## 自定义冻结与关机强制解冻（v1.2）

- 冻结页“添加任意应用（自定义冻结）”可搜索并冻结任意已安装应用；支持 暂停 / 停用 / 隐藏（伪装卸载）/ 只观察。桌面、设置、电话、输入法、权限控制器、Shizuku、Root 管理器等关键包会显示高风险警告并允许用户确认后执行（不硬拒绝）；请优先使用暂停。
- 每次冻结/解冻后生成 `/data/adb/nemu/restore_frozen.sh`；模块 v1.8 通过 KernelSU `initrc` 的 `on shutdown`、root FIFO 守护进程/LSPosed 关机 hook、`sys.powerctl` 轮询三层，在**任何正常关机/重启**前执行解冻，即使 Nemu 进程没有运行。强制断电/拔电池无法执行任何代码，这是唯一覆盖不了的场景。
- 下次开机后 `BootFreezeWorker` 按策略重新冻结（含自定义目标）。
- 详见 [docs/13-custom-freeze-and-shutdown.md](docs/13-custom-freeze-and-shutdown.md)。

## 凪（独立 App）

- `D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`），显示名 **凪**（日语风格中文名，不含日文假名）；
  版本 1.3.3 / code 9；APK 2,653,436 字节，SHA-256
  `d400b5165cf31760c613e69c555bb2719246e2c0c448e1b1ac3b2ef87ce7c7bb`。
- **LSPosed 模式**：hook `com.hihonor.ouc` 的
  `check.model.result.a.d()` 返回 false，让系统更新始终认为已是最新；不修改系统版本号。
  作用域 `android` + `com.hihonor.ouc`，启用后常开；模块注入后会给 App 发激活广播，App 记录本次开机并实时刷新状态。
- **无 LSPosed 独立模式**：无障碍覆盖层把更新页替换为“当前系统已是最新”。
  Root/Shizuku/ADB 只负责自动开启这个覆盖层，**不会暂停、停用或冻结 OUC**。
  这不是注入 OUC 代码；ADB/Shizuku 的权限模型不允许向其他 App 注入 Java 代码。
- 四种模式以 2×2 芯片显示：LSPosed 三态（关闭/已启用待重启/已激活），只有关闭时点芯片才打开管理器；Root / Shizuku 直接开启独立模式；ADB 复制命令。选中项有 ✓ 和强调色。
- 隐藏桌面图标会禁用启动器 alias；隐藏后发布常驻通知，点通知可打开凪，通知内可“显示图标”。
- 没有 LSPosed 时也能看到“已是最新”的结果页（覆盖层）。
- 详见 [docs/14-update-guard.md](docs/14-update-guard.md)。

## 文档

- [PROJECT.md](PROJECT.md) — 项目总览、代码树、审计指南
- [docs/README.md](docs/README.md) — 文档索引
- [01 设备与实测发现](docs/01-device-and-findings.md)
- [02 包清单与风险分级](docs/02-package-inventory.md)
- [03 设计](docs/03-design.md)
- [04 测试与回滚计划](docs/04-test-plan.md)
- [05 OUC 卸载崩溃分析](docs/05-ouc-crash-analysis.md)
- [06 模块并行开发与集成计划](docs/06-module-integration-plan.md)
- [07 开机冻结/日志/检测/UI 设计](docs/07-boot-freeze-logging-ui-design.md)
- [08 并行验证计划](docs/08-parallel-verification-plan.md)
- [09 自动救砖设计](docs/09-auto-unbrick-design.md)
- [10 Nemu 重设计计划](docs/10-nemu-redesign.md)
- [12 真机测试日志](docs/12-device-test-log-2026-11-22.md)
- [13 自定义冻结与关机强制解冻](docs/13-custom-freeze-and-shutdown.md)
- [14 凪](docs/14-update-guard.md)
- [15 任务清单与完成状态](docs/15-task-status-2026-11-22.md)
- [16 逻辑审计](docs/16-logic-audit-2026-11-22.md)

## 构建

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`。

安装（由用户执行）：

```powershell
adb install -r D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk
```

## Root / FinaSU 自启动

- 本机 root 方案是 **FinaSU**（包名 `me.yuki.fina`，基于 KernelSU）。Nemu 的 `RootBackend` 会依次尝试 `su`、`/system/bin/su`、`/data/adb/ksu/bin/su`；请在 FinaSU 管理器中给 Nemu 授权 root（建议“始终允许”）。
- 有 root 时 Nemu 自动优先 root 后端；关机安全解冻也优先 root，以匹配 Nemu 自己施加 suspend 的 `suspendingPackage`。
- 自启动模块：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`，在 FinaSU 模块管理器中安装（或 `ksud module install`）。v1.8 会安装独立 `boot_guard.sh` 到 `/data/adb/nemu/boot_guard/`：按 boot id 计数、逐包解冻、按原始状态恢复 Nemu 所有的组件与 disable-user 标记；连续 3 次未确认成功则写 rescue flag、禁用模块并在 boot_completed 后重试恢复。
- 如果 FinaSU 模块安装后仍无法自启动，请检查 FinaSU 日志；不要修改分区。

## 非目标

- 不做卸载；系统应用默认不做停用。云控模块的唯一例外是运营商 DM 三件套的 `disable-user`（可逆、逐项确认，见 `docs/03-design.md` §7.1）。
- 不修改 `build.prop`/系统版本号。
- 不依赖 Hail；不使用 Hail 的 API/Intent。
- 不默认处理反诈/温控/云控；这些作为单独的高级模块，需明确开关和风险确认。
- 不承诺 100% bootloop 防护：强制断电、`adb reboot` 或系统崩溃可能跳过安全解冻。
- COTA 不承诺提升性能；只提供备份/删除/恢复云控参数的能力。

## 命名与地址

- 项目一：Nemu（眠梦）
  - GitHub：https://github.com/JiuQing1134/Nemu
  - applicationId：`com.nemu.dream`（源码命名空间 `com.nemu.hibernate`）
  - 本地地址：`D:\win_root\nemu`
- 项目二：凪
  - GitHub：https://github.com/JiuQing1134/Nagi
  - applicationId：`com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`）
  - 本地地址：`D:\win_root\nemu-update-guard`
- 地址与产物汇总：`D:\win_root\PROJECTS.md`
- 参考项目：Hail（雹）https://github.com/aistra0528/Hail
