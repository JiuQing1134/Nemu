# Nemu（眠梦）

HONOR MagicOS 系统组件“安全冻结”工具。

> **2026-11-22 完成彻底改名**：应用 applicationId `com.nemu.dream`（源码命名空间 `com.nemu.hibernate`），凪 applicationId `com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`）；模块 ID
> `nemu_boot_autostart`，根目录 `D:\win_root\nemu`；旧包名、旧模块和旧数据已清理。
> 冻结与解冻拆成独立页面，支持 SUSPEND / COMPONENT / DISABLE_USER / HIDE / OBSERVE
> 五种策略；HIDE 即“伪装卸载”（`pm hide`，仅 Root）。
>
> **参考项目**：
> - Hail（雹）— https://github.com/aistra0528/Hail  
>   仅参考其 suspend 冻结机制与公开文档，不依赖、不调用其 API/Intent/数据。
> - FlClash / prism_ui — https://github.com/chen08209/FlClash 、
>   https://github.com/chen08209/prism_ui  
>   仅参考配色、surface 与控件外观 token；Apache-2.0，Nemu 用 Compose 重新实现，
>   不包含 Flutter 运行时、不调用其代码。详见
>   [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
>
> **项目地址**：
> - Nemu：`D:\win_root\nemu` — https://github.com/JiuQing1134/Nemu
> - 凪：`D:\win_root\nemu-update-guard` — https://github.com/JiuQing1134/Nagi
>
> 完整地址、包名、版本与产物哈希：[`PROJECTS.md`](PROJECTS.md)。

> 代码地图、开发流程、代码树、已知问题和审计入口：**[PROJECT.md](PROJECT.md)**。  
> 文档索引：**[docs/README.md](docs/README.md)**。

## 支持的设备

- **已验证设备**：HONOR AAP-AN00
  - 系统：MagicOS 10.0.0.102（`10DLDLD102SP1C00E100`），Android 16 / SDK 36
  - 内核：`6.12.23-android16-5-gcd85666d7884-abogki452537391-4k`
  - 当前槽位：`a`；GPT 逻辑扇区 4096
  - Root：FinaSU / KernelSU（`me.yuki.fina` 或 KernelSU 管理器）
  - 实机：模块 v1.21 已通过 `ksud module install` 安装并暂存到
    `modules_update`，重启后生效；App release `1.3.3`（versionCode 9）
- **最低要求**：Android 11（minSdk 30）。完整功能需要 root（uid 0）：
  - 开机恢复模块、COTA 温控、`pm hide`（伪装卸载）只在 root 下工作；
  - Shizuku 只能作为普通冻结/解冻的 shell 后端，不能替代 root 的开机模块或 COTA；
  - 没有 root 时不要安装开机模块，也不要把“开机 early 解冻”当作可用能力。
- **其他 HONOR / MagicOS 设备**：理论可用，但 `pm` / `dumpsys` 输出、系统应用保护和
  组件枚举会随机型/版本变化，未做真机验证。请先用“暂停”，并在检测页核对实际状态。
- **不承诺**：非 Android 设备；OEM 锁死 `pm hide` / `disable-user` 的设备；没有 root
  却希望开机自动解冻的设备；强制断电/拔电池后的恢复。

## 行为说明

- 开机冻结在开机后按设定的延迟执行（默认 120 秒）；总开关在概览页的“系统状态”卡中。
- `adb reboot` / 强制重启不会通知 Nemu；Root 模块会尝试解冻，但“上次安全解冻”的结果不会更新。
  这些说明只保留在文档中，不在软件界面重复展示。

## 最近改动（2026-11-25，交互批次）

- **批量加入开机冻结**：冻结页选中应用后，底部操作栏新增“加计划”次操作；确认弹窗列出
  将新增/已在计划中的数量、高风险目标，并在总开关关闭时提供“开启开机自动冻结”。
  选择不再要求可冻结后端，无 Root / 未授权 Shizuku 也能先把应用加入计划。
- **选择不会被悄悄丢掉**：选择状态在切换冻结/解冻页后保留；执行批量操作时写入“上次选择”，
  清空后出现“上次选择 N”一键恢复。只有用户点操作按钮才保存，过滤/搜索导致的选择收缩不落盘。
- **解冻页保留开机冻结**：新增“当前冻结 / 开机冻结”两个范围；关机解冻后计划中的应用仍显示在
  “开机冻结”，不会只剩空列表。已计划但当前未冻结的应用可直接移出计划。
  “临时解冻所选（保留计划）”与“解冻所选并关闭计划”分开，避免下次开机智障式重选。
- **顶栏状态不再误导**：应用页顶栏改为“冻结 / 可解冻 N”，没有可解冻目标但存在计划时显示
  “计划 N”；计划计数统一按 `enabled && active`，总开关关闭时显示“计划·未启用”。
- **检测页统一分类**：原先分离的“冻结 / 云控遥测 / 系统 / 启动安全”入口合并为同一排四个
  总览块（也是唯一分类控件），点按切换分类；冻结和云控显示“已生效 / 总数”，冻结列表按
  核心 / AI / 统一目录分组，状态压缩成一行（计划、实际状态、残留进程）。
- **救援模式覆盖单目标操作**：自动救砖模式下批量冻结、加计划和每个卡片的“立即冻结/重新冻结”
  都会禁用并给出原因，不再只有横幅提示。
- **其他细节**：顶栏与检测页“刷新”用不可见内边距把触控高度补到 48dp；全选只作用于当前
  可见/可选应用；排序按钮触控区 36dp；“所有权待清理”等内部措辞改为“状态待确认”等用户语言。

### 审计后的 P1/P2 修复（同日）

- **拼音搜索**：全拼/首字母之外，补了多音词别名（音乐/银行/地图/长城/长沙/绿色/女士/旅行/
  重庆/厦门等），查询本身折叠声调、全角字母和 `+ & #` 等符号；搜索框提示与空结果文案
  明确写出“拼音/首字母”。
- **选择与计划**：冻结↔解冻分段切换用独立 `SaveableStateHolder`，未提交的勾选、搜索、筛选
  不再丢失；筛选/搜索只改变视图，不再裁剪已勾选应用，批量动作作用于全部已选（含当前
  筛选外）；“开机冻结”筛选移到第二枚 chip；计划视图的次操作变为“移出计划”，并显示数量
  和目标清单；重复点击当前解冻范围 chip 不再清空选择。
- **单卡计划开关**：保留单个应用的“开机冻结”开关，但改成 34×18dp 细开关并并入“策略”
  标题行，不再占一整行；批量加入/移出计划会立即翻转对应 `catalog.enabled`，卡片开关、
  计划筛选和计划计数同步更新，不必等异步重建。
- **选择控件合并**：顶部搜索行不再显示“已选 N”，只保留搜索框和全选；底部操作条的
  “已选 N ×”是唯一清空入口，原“清除”胶囊删除。全选只作用于当前可见范围（冻结/解冻页
  取消全选也只清可见项，不再 `selection = emptySet()` 误伤筛选外选择），× 清空全部，
  主按钮负责执行，三者职责不再重叠；解冻页卡片里重复的“已选 N”状态块也一并删除。
- **再次冻结上次选择**：独立记录“最后一次实际确认冻结”的集合，加计划不会覆盖它；
  没有勾选时显示“再次冻结 N 个”，点击恢复该集合并打开原确认弹窗（高风险仍会列出）。
  “上次选择”保留为“先恢复勾选再编辑”，两个入口职责分开。
- **空状态与反馈**：计划为空时提供“显示全部应用”；解冻页空状态提供“回到冻结页选择应用”；
  批量计划后再动单卡开关只撤掉“撤销”按钮，保留结果条，不再把整条反馈清掉。
- **快捷入口**：首页“计划 N ›”可直接打开冻结页的“开机冻结”筛选；全部应用页添加自定义目标后，
  “去冻结/解冻页设置策略”会带上刚添加的包名，自动切到“自定义”筛选并预选这些应用。
- **筛选外提示**：批量冻结/加计划/移出计划/解冻的确认弹窗会提示“其中 N 个不在当前筛选/搜索中”，
  避免用户以为只处理眼前可见项。
- **长按复制**：应用卡片的应用名/包名支持长按复制包名，Toast 确认；解冻页只读行同样可用。
- **全应用页**：“已选 N”现在清空全部选择（含被筛选隐藏的行），“全选”仍只清当前可见集合；
  两者不再共用一个回调。
- **撤销与结果**：计划批量操作可撤销，撤销前会跳过已改成“观察”或已离开目录的目标；单行
  计划开关/策略切换会使旧撤销失效。成功结果也保留非阻断备注（如已卸载、原本已停用），
  不再只藏在两行详情里；全部解冻的 residual 失败不再显示绿色成功。
- **布局与触控**：选择条改 `FlowRow`，窄屏/大字号自动换行而不裁掉主按钮；反馈条“详情/撤销”
  补到 44dp 并与关闭拉开间距；短屏自动收起反馈详情；页面加 `imePadding`，键盘不再盖住
  底部操作条；计数槽 76dp 改为最小宽度，三位数不再裁数字。
- **首次引导**：去掉与授权卡重复的按钮（Root 已就绪时不会再出现“授权 Shizuku”）；老用户
  升级时按安装/更新时间自动标记已看过，新用户仍会看到一次。
- **检测页**：启动安全读取失败时只显示一条错误，不再把 `-1/尚未执行/无启用项` 当事实；
  手动解冻跳过不再把“守卫”标红；关机/开机时间允许两行，详情不再省略；总览值允许两行。
- **验证**：533 JVM tests / 0 failures，lint 0 errors（89 warnings，均为既有 UseKtx/
  ApplySharedPref 等）。

### 2026-11-25 深度卸载（模块 v1.21）

- **新分段**：冻结/解冻页顶栏增加“深度卸载”。列表显示已安装、已备份、已卸载应用，支持搜索/全选。
- **备份后卸载**：先 `pm path` 取 base + split APK，复制到
  `/data/adb/nemu/deep_uninstall/apk/<包名>/<版本号-时间>/`，写入 TSV 清单并校验 SHA-256；
  同步复制一份到 `/storage/emulated/0/Download/Nemu/deep_uninstall/` 方便文件管理器/电脑
  转移，并把清单快照放在同目录；两处都成功后才执行 `pm uninstall -k --user 0`
  （`-k` 保留应用数据）。恢复仍以 `/data/adb` 为主；Download 副本是用户可见镜像。
- **立即恢复**：优先 `pm install-existing --user 0`，失败时用备份
  `pm install-multiple -r -d`；恢复后执行 `pm enable --user 0` 并校验包是否重新安装。
- **危险区**：启动器、SystemUI、设置、电话、权限控制器、安装器等标记为关键应用；
  确认弹窗必须勾选知晓项。Nemu 自身硬拒绝卸载。
- **开机自动恢复**：模块 v1.21 在 service 阶段恢复 manifest 中未安装的包，
  `boot_completed` + 90 秒后只重新卸载本次恢复的包；模块未安装时界面顶部提示
  “需模块 v1.20”，此时仍需手动恢复再重启。
- **分类与保留**：深度卸载沿用全部应用的分类胶囊（系统 / 用户 / Root 管理器 /
  Shizuku / Shizuku 应用 / LSPosed / LSP 插件）；“系统”用红色胶囊 + 白字，
  其余用蓝色胶囊 + 白字，全部应用和深度卸载共用同一套控件。行内不再重复显示
  “关键”（确认弹窗仍会单独警告）。已恢复的包标为“已恢复 · 保留备份”，
  开机时因为已安装而不会被自动重卸。备份不随恢复删除，manifest 条目和 APK
  目录都保留，供下一次深度卸载/恢复使用。包名等于应用名时不再重复显示包名。
- **全部应用标签精简**：已添加分组不再在每个卡片上重复“已添加”（分组标题已经说明）；
  未添加卡片只显示分类，不再显示“策略 暂停”这类尚未生效的状态——点击卡片展开后再选
  策略和开机计划。
- **折叠分组顺序**：全部应用、深度卸载、冻结管理统一为“未添加/未卸载/未冻结”在上，
  “已添加/已卸载/已冻结”在下；均使用可折叠分组头和总数。
- **冻结管理/解冻恢复卡片**：高风险紧贴应用名第一行；状态和策略放在名字下方同一行，
  都是蓝色胶囊 + 白字；删除“策略”前缀和折叠卡的“开机”标签；“已加入计划”统一改为
  “已添加”。自定义分类顶部的红字提示移到 README。
- **验证**：561 JVM tests / 0 failures，lint 0 errors。

### 2026-11-25 中文文案审计

- **统一术语**：冻结 / 暂停 / 停用 / 停用组件 / 隐藏 / 限制后台 / 限制待机 / 观察各只表示一种策略；
  批量动作统称“执行策略”，单卡按钮改“立即执行/重新执行”。
- **开机冻结**：统一“开机冻结”“开机冻结总开关”“N 秒后执行”；删除“开机冻结计划、自动冻结计划、
  冻结计划”等变体；顶栏“模式”改为“授权”。
- **解冻页**：“关闭计划”→“移出开机冻结”，“临时解冻”→“本次解冻”；预览不再说“命令、跳过标记、
  安全解冻”等内部词。
- **检测页**：总览计数改“已生效”；新增“他人暂停”“组件已停用”“组件部分停用”；启动安全用
  “未见异常/未触发”；开机次数未知不再显示 -1；`本 boot`、`未净关机`、`Boot count` 等已清理。
- **目标与温控**：策略名“组件”→“停用组件”；高风险按更新/推送/支付/温控/云控分流；COTA 错误不再
  直接显示退出码和英文 shell 输出；文件时间标为“修改时间”，大小列允许大字体不被截断。
- **文档**：[docs/27-copy-style-2026-11-25.md](docs/27-copy-style-2026-11-25.md) 记录术语表、状态词、
  句子模板、排版层级与后续结构优化。

### 2026-11-25 稳定性与开机修复

- **搜索返回键**：IME 可见时第一次返回只收起键盘并保留查询/搜索结果；键盘收起后再次返回才清搜索，
  避免“返回后结果没了但键盘还挡着列表”。冻结、解冻、全部应用三页统一。
- **开机冻结触发**：模块 v1.19 的 `BOOT_TRIGGER` 广播增加 `--include-stopped-packages`，
  并在 `boot_completed` 后重试 3 次；应用被系统后台清理/强制停止时不再静默丢掉触发，
  每次尝试写入 `boot_guard/guard.log`。此前只广播一次且不带该标志，是“每次开机都要手动进应用”
  的主要根因。
- **闪退防护**：`SafetyService` 前台服务启动被系统拒绝时只停止服务、不再拖垮进程；
  `BootReceiver` / `BootTriggerReceiver` 的启动路径全部 fail-soft；`BootFreezeScheduler`
  排程异常只记日志。新增 `filesDir/last-crash.txt` 保存最后一次未捕获异常，便于定位偶发闪退。
- **主构建**：533 JVM tests / 0 failures，lint 0 errors（89 warnings）。

## 最近改动（2026-11-25，模块 v1.21 / 深度卸载自动恢复）

- **深度卸载开机恢复**：模块 v1.21 的 `deep_restore.sh` 在 service 阶段等待
  PackageManager，按 `/data/adb/nemu/deep_uninstall/manifest.tsv` 恢复用户 0
  尚未安装的包：优先 `pm install-existing --user 0`，失败再用备份 APK
  `pm install-multiple -r -d --user 0`；恢复后 `pm enable --user 0`。恢复记录写入
  `restored_this_boot.tsv`（boot id + 包名 + 实际 versionCode）。
- **保留应用数据**：App 与模块的卸载命令都改为 `pm uninstall -k --user 0`；
  恢复后数据仍在。
- **稳定后重新卸载**：`sys.boot_completed=1` 后再等 90 秒（可在
  `/data/adb/nemu/deep_uninstall/config` 写 `delay_seconds=N`，30–600），
  只对本次 boot 恢复过的包执行 `pm uninstall -k --user 0`；开机时本来就在装的包
  不碰，版本已变化的包也跳过。若启动未完成、boot id 已变化或 boot guard 处于
  rescue，保留已恢复的应用。
- **ABX 最后救援**：`deep_rescue.sh` 在第二次未完成启动时、system_server 起来之前，
  只对 manifest 中的包修复 `package-restrictions` 四个副本的 `inst="false"`；
  主副本修复失败时不动 manifest，避免丢失备份记录。
- **last-good 基线**：`manifest.boot.tsv` 在本次启动保存，成功到达 `boot_completed`
  后才提升为 `manifest.last.tsv`；救援只回滚相对基线新增的包，原 manifest 备份到
  `manifest.tsv.rescue.<boot-id>`。
- **模块卸载恢复**：`uninstall.sh` 先同步执行 `deep_restore.sh --restore-only`，
  恢复所有未安装的 manifest 包，再移除 hook 和能力标记。
- **App 握手**：模块写 `auto_restore_ready=1.20`，App 接受 1.20 及更新版本；深度卸载页
  顶部显示“开机自动恢复，稳定后重新卸载”，确认弹窗同步改成“开机自动恢复”的文案；
  模块未安装时仍显示“需模块 v1.20”。
- **模块包**：`tools/nemu-boot-autostart/nemu-boot-autostart.zip`，
  37,144 字节，SHA-256
  `af5f5c50b34099a0d696f6d80622f54015abefd7b979957c54bf491f1764a924`；
  v1.27 / versionCode 28，新增 `deep_restore.sh` 与 `deep_rescue.sh`。

## 最近改动（2026-11-24，模块 v1.18）

- **开机恢复覆盖全部 Nemu 拥有的冻结态**：`disable-user`、`pm hide`（伪卸载）、
  `suspend`、组件停用、AppOps、standby。PackageManager 可用时先恢复，再让
  `BootFreezeWorker` 按策略重新冻结。
- **模块 v1.18**
  - 新增 `boot_guard_runner.sh`：service.d hook 走 480s（普通）/1200s（救援）预算，
    超时写 `guard.log`；
  - `planned_shutdown` / `clean_shutdown` 增加 `.prev` 槽，中间一次 boot 没跑 guard
    也不会丢掉清零；
  - 上一 boot 到达 `sys.boot_completed` 即视为成功，解决 `adb reboot` 逃过 2 秒
    `sys.powerctl` 轮询、把快速重启累计到 3 次误触发救援的问题；
  - `disable-user` 校验以 `pm list packages -d` 为准，兼容某些包 dumpsys 没有
    `enabled=` 字段；
  - 组件/隐藏只读 User 0；组件 marker 在 `pm disable` **之前**写入；截断 dumpsys
    fail-closed；AppOps 解析跳过 `Uid mode:` 行；
  - 自动 rescue 只处理 Nemu 已知目标和 `*_by_nemu` marker，不再全量扫描 foreign
    suspend；
  - 普通/early/late/rescue/clear 共用 `/data/adb/nemu/restore.lock`；卸载会停掉
    shutdown daemon 并清理 FIFO/restore 脚本。
- **App**
  - `ShutdownScript` 逐命令失败计数（`nemu_run` / `nemu_fail`），不再用最后一条命令的
    退出码冒充整体成功；
  - BootFreeze 门禁接受 root guard 的 planned/completed 记录（shell 秒 → App 毫秒）；
  - 检测页 `bootloopGuardTripped` 同步包含 planned/completed；
  - SUSPEND 在 root 可用时优先 root 身份，guard 兼容 `com.android.shell` 旧数据；
  - 清除救援模式使用 120s root 命令预算。
- **实机验证（2026-11-24）**：设备上 `base.apk` SHA-256 `b513e962…`；模块 v1.18 安装后
  两次快速重启，early guard exit=0、`attempts=1`、无 `rescue.flag`；491 JVM tests /
  0 failures，lint 0 errors。

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
- 当前版本：`versionCode 9` / `versionName 1.3.3`。2026-11-26 构建的 universal release：
  `app/build/outputs/apk/release/app-release.apk`，25,232,417 字节，SHA-256
  `0ae8393c983b611f209ca6f2f86a00072a4f4c41acf910e3f98dcb1f692efe28`；
  使用私有 keystore 签名（`CN=Nemu`，证书 SHA-256 `1f35e731…19a7`）。历史四包
  debug/release × arm64/x86_64 哈希见 `PROJECTS.md`。
- 2026-11-25 UI 批次：「功能」页改为**功能入口**（冻结管理 / 解冻恢复 / 开机冻结 /
  深度卸载），点击卡片进入独立页面，返回回到功能入口；普通控件字体整体下调
  一档（正文 14sp、辅助 12sp、标题 20sp），搜索框 38dp，卡片内边距收紧；
  恢复/计划按钮改为整行按钮换行，不在文字中间断行。
  功能入口状态标签为 12sp（标题 16sp），避免 14sp 在蓝底白字下和标题看起来一样大；
  检测页“更新于 …”并入“设备检测”标题下方，并删掉“总览”小标题。
- 2026-11-25 语义色层级：`StatusChip` 不再把 PRIMARY/SUCCESS/INFO/WARNING/THEME
  全部画成实心主色。实心主色只给生效状态和当前策略；“已添加/开机/分类”用浅色
  primaryContainer；“已跳过/部分生效/警告”用 secondaryContainer（蓝palette 的
  tertiary 是紫色，和主题不搭，已弃用）；不一致/失败仍用 error；未知/分类用
  inset 中性。全部应用的“系统/已添加/停用”因此分成三级。
  检测页应用摘要只给实际状态词上色；启动安全四行把首列“表头”也点上主题色，
  值只给结论词上色，行内用顶端对齐，长值换行留在值列。关机详情
  （安全解冻成功；组件；隐藏；限制）改为标题 + 逐段列表，计数用“·”分隔，
  不再挤成一段长文本。
- 同日稳定性修复：`SafetyService` 改用普通 `startService()` + 自行
  `startForeground()`；后台启动被系统拒绝时立即失败并由开机模块兜底，不再出现
  `ForegroundServiceDidNotStartInTimeException` 闪退。深度卸载成功后清除该应用的
  冻结所有权与开机冻结标记，`pm uninstall -k --user 0` 的应用不再出现在冻结/解冻
  列表或计数里。
- 同日精简：「功能」入口改为四行单行列表（图标 + 名称 + 一个状态 chip），
  去掉每张卡的两行说明；移除首次使用引导卡和设置里的“重新显示使用引导”；
  “授权”卡去掉重复说明与底部刷新按钮；解冻页删除两组重复的整行按钮、
  “先勾选要解冻的应用”这类占位文案和重复解释，操作统一由底部已选栏承担；
  深度卸载只保留一行重启警示，列表去掉版本行和“系统/未备份”噪声 chip。
  检测总览的“系统服务”值缩短为“N 项 / 失败 / 无 Root”，不再换行。
- 同日控件收紧：分类/计划筛选 chip 由 Material 32dp FilterChip 改为约 21dp
  自定义胶囊（11sp）；状态/分类 chip（“已应用”“高风险”“系统”等）统一为
  9sp、内边距 6×2dp；排序按钮 30dp；开关 52×34 / 40×26；底部导航 54dp。
  「功能」入口四张卡片放大为 40dp 图标、15sp 标题和 14×13dp 内边距；
  「全部应用」和冻结选择列表的复选框改为与图标顶边齐平，不再垂直居中。
- 同日交互统一：冻结页筛选 chip 直接显示数量（全部 / 开机冻结 / 各分类），
  原独立的“计划 N”chip 合并到“开机冻结 N”；“上次选择 / 重做上次”移入
  筛选行，使用同样的 11sp 胶囊，不再作为列表上方的大号游离文字按钮。
  「全部应用」改为与冻结管理一致的卡片：卡片内直接选择观察/暂停/冻结/
  停用/隐藏等策略，并使用与冻结管理相同的迷你开关控制“开机冻结”；
  批量添加时每个应用使用自己选定的策略（默认暂停）和开机冻结状态，
  添加后冻结页可直接执行。已添加目标显示“已添加”并自动置顶。
  “开机计划”统一更名为“开机冻结”。
  首页系统状态卡上移并加大内边距；“已生效/计划中”数字用主题色加粗，
  但字号与后面的标签保持一致；“授权/检查”改为同尺寸 13sp 胶囊按钮，
  顶栏“授权：Root/Shizuku”也同步为 13sp。检测页与顶栏刷新、按钮类控件
  使用更明显的缩放/变暗按压反馈。
- 同日文案：检测页“实际生效”改为“已生效”，删除“点按切换分类”和
  “只读快照 · 不执行系统操作”；设置执行页的注改为三条小字，明确普通
  冻结/停用/隐藏不卸载应用，只有深度卸载会卸载（系统应用也可以），
  且当前重启前需手动恢复。
- 同日布局收尾：功能入口状态文字 14sp（标题 16sp）；顶栏授权文字 11sp；
  「全部应用」的“开机冻结”开关放进卡片展开区；列表按开启主题色的
  「已添加 / 未添加」分组并可分别折叠；删除列表上方与“全部 N”重复的
  “共 N 个”。检测页删除“按来源分组 · 点卡片展开”副标题，应用按拼音
  首字母排序，并按「不一致 / 已生效 / 未生效」折叠分组；三组标题均使用主题色
  （不一致沿用错误色），未生效默认收起。
- 设置页：授权状态随页面进入/授权结果刷新；已授权时显示不可点击的“已授权”，
  未授权才显示“去授权”。注释改为无面板的正文，“注：”后留一格再接序号，
  ①/②/③ 换行后的文字与序号后的正文对齐。开机延迟/总开关位置与 `adb reboot` 的解冻行为
  只写在 README，不在软件内重复提示。
- 解冻页：“全部仅本次解冻”改为主题色胶囊，放在“当前冻结”筛选同一行、
  排序按钮左侧；全部应用/检测的折叠分组箭头改为 18dp 宽、2.6dp 圆头
  粗箭头，不再使用窄细的 `⌃/⌄` 字符。
- 统一间距（`ui/Spacing.kt`）：状态 chip 之间（含设置页 `Root` + `已授权`、
  冻结卡状态 + 策略、全部应用的分类 + 状态）一律 3dp；表头/标签到内容
  一律 8dp。表头保持首字左对齐（总开关与开机冻结从同一 x 起排），短标签
  后面的内容自然提前，不再用固定列宽把短标签推到右边。表头规则同时用于
  设置「开机与安全」、概览「上次结果/关机前/关机后」和检测「启动安全」。
- 冻结管理展开卡：删除“策略”小标题；功能行改为“功能：…”，包名行改为
  “包名：…”并移到功能行下面，两行同为 11sp，行高 13sp 且不再额外加行距
  （视觉间隔约为原来的三分之一）。只有已知功能的应用才显示功能行
  （`TargetCopy.knownFunctionLine`），用户添加的应用不再输出
  “功能：用户添加的应用”占位。开机冻结开关与包名同一行；应用名就是包名时
  不显示重复包名行，开关跟在功能行后面；两者都没有时开关单独右对齐。
  删除“观察：…”“不可用：…”“仅观察，不执行。”三段说明文字；真正的
  高风险警告仍保留（执行确认弹窗也照旧列出影响）。
- 解冻恢复卡片：包名行加“包名：”前缀；删除“重新启用应用；…”这类按策略
  复述的恢复说明，只保留状态行（计划：… / 非 Nemu 冻结，只读）。
- 深度卸载恢复修复（实机验证）：`pm install-existing` 对已被
  `pm uninstall -k --user 0` 完全移除的包抛 `NameNotFoundException`；
  回退命令 `pm install-multiple` 在本机 MagicOS 10 不存在
  （`Unknown command`）；改用 `pm install -r -d --user 0` 后仍被 SELinux
  拒绝——system_server 读不了 `/data/adb` 的 `adb_data_file`。现在先把备份
  APK 复制到 `/data/local/tmp/nemu_deep_install/<pkg>/` 并 `chmod 644`
  （`shell_data_file`），再执行 `pm install`，装完清理暂存目录；模块
  `deep_restore.sh` 用同一套流程。实机恢复 `com.hihonor.tips` 成功。
- 备份改用 `.apk.bak`（App 与模块一致）：主备份（`/data/adb`）和
  `Download/Nemu` 镜像都写成 `base.apk.bak` / `split_N.apk.bak`，避免被系统
  APK 扫描或清理误删；恢复时先复制到 `/data/local/tmp` 并把后缀改回 `.apk`
  再安装，装完删除暂存目录。旧的无后缀 `.apk` 备份仍可正常恢复。
- 修复恢复导致 system_server 热重启：`install-existing` 已返回成功、但
  `pm list packages` 还没刷新时，旧逻辑会立刻再从备份 `pm install`；
  MagicOS 10 的 PackageManager 在 `RemovePackageHelper.removePackageLI`
  对 null `AndroidPackage` 抛 NPE（`FATAL EXCEPTION IN SYSTEM PROCESS:
  PackageManager`）并热重启。现在 `install-existing` 成功后先确认代码是否
  真正存在（`pm path` 非空），存在即恢复成功；**永远不再回退安装备份**。
  只有 `install-existing` 明确失败（`doesn't exist` / `NameNotFoundException`）
  且 `pm list packages -u` 也查不到记录时，才允许从备份安装；瞬时服务错误
  先重试一次 install-existing。模块脚本同规则。
- HONOR delapp 预装实测（`com.hihonor.tips`）：`pm uninstall -k --user 0`
  后包记录仍保留（`codePath=/system/delapp/HnMagicManual`，`pm path` 为空、
  `primaryCpuAbi=null`）；`install-existing` 返回成功但代码不会立刻回来，
  **重启后系统重新扫描 delapp 才恢复**（实测重启后 `pm path` 恢复为
  `/system/delapp/HnMagicManual/HnMagicManual.apk`）。App 现在会提示
  “已登记恢复，重启后生效”，不会在无代码状态重装备份（那会触发上面的
  PackageManager NPE）。同时这类预装在重启时会被系统自己装回，模块看到
  “已安装”会跳过，因此“稳定后重新卸载”不覆盖它们；要让这类系统预装的
  深度卸载跨重启保持，需要额外的“注销/保留”标记（尚未实现）。
- 实机发现：部分 HONOR 预装应用（如 `com.hihonor.tips`）被
  `pm uninstall -k --user 0` 后，重启时系统会自己把出厂版本装回来
  （`firstInstallTime` 就是本次开机时间）；模块看到“已安装”会跳过，
  不会重新卸载。所以这类预装应用的深度卸载能否跨重启保持，取决于 OEM
  是否自动恢复；“稳定后重卸”只覆盖本次开机由模块恢复的包。
- 深度卸载页增加「全部 / 已卸载 N」筛选 chip（与其它列表同样的 8dp 间距）：
  选「已卸载」进入可恢复列表，勾选单个或全选后点「恢复所选」即可批量恢复；
  筛选态不再显示未卸载分组标题，避免折叠后看不到可恢复应用。
- 凪（`D:\win_root\nemu-update-guard`，applicationId `com.nemu.windstop`）：
  `versionCode 9` / `1.3.3`，`app\build\outputs\apk\debug\app-debug.apk`，
  2,653,436 字节，SHA-256 `d400b5165cf31760c613e69c555bb2719246e2c0c448e1b1ac3b2ef87ce7c7bb`。
- 自启动模块：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  （37,144 字节；SHA-256 `af5f5c50b34099a0d696f6d80622f54015abefd7b979957c54bf491f1764a924`；
  模块 v1.27 / versionCode 28，含 `deep_restore.sh`、`deep_rescue.sh` 与
  `boot_guard_runner.sh`）。v1.22 修复深度恢复在 MagicOS 10 上的两个失败点：
  本机 `pm` 没有 `install-multiple`（改用 `pm install -r -d --user 0`），且
  system_server 读不了 `/data/adb` 下的 `adb_data_file` 备份（先把 APK 暂存到
  `/data/local/tmp` 并 `chmod 644`）。v1.21 新增深度卸载 boot bridge：service 阶段等待
  PackageManager，按 `/data/adb/nemu/deep_uninstall/manifest.tsv` 恢复用户 0 未安装的包，
  恢复后 `pm enable`；`boot_completed` 后等 90 秒（config 可改），只对本次 boot 恢复过的包
  重新 `pm uninstall -k`，开机前已安装的包不碰。`deep_rescue.sh` 在第二次未完成启动时
  只对 manifest 包修复 ABX `package-restrictions` 的 `inst="false"`，并用
  `manifest.last.tsv` 回滚新增项；模块卸载前先 `--restore-only` 恢复。v1.19 的
  `BOOT_TRIGGER` 仍带 `--include-stopped-packages` 并重试 3 次，
  应用被系统后台清理/强制停止时仍能收到触发并排程开机冻结。v1.18 在 PackageManager
  可用的 early service 阶段恢复 Nemu 拥有的全部冻结态（disable-user、hide、suspend、
  组件、AppOps、standby），再重放 App 的恢复脚本；`boot_completed` 后复核。上一 boot
  到达 `boot_completed` 即视为成功，快速 `adb reboot` 不再累计到 3 次误触发救援。
  正常开机只处理 Nemu 自己的 ownership
  marker；安装/更新后需重启。
- UI：中性白/灰底 + 单色强调、M3E 大圆角、深色纯黑 OLED；桌面图标使用白底亮色几何图形：Nemu 为钴蓝 Tabler 雪花，凪为翠绿三角回环与中心圆点。
- 2026-11-22 第三轮：桌面/应用信息/通知大图标统一为白底黑色月亮/凪图形；通知显式设置大图标，不再回退系统缓存图标；请求 120 Hz（实测 `mActiveModeId=3 / renderFrameRate 120.00001`）；修复返回桌面图标闪回、隐藏图标后残留旧图标、模式芯片无响应、点击跳跃、无障碍自动开启、Nemu 点击 1 秒卡顿。系统通知左侧的小头像由 MagicOS 自行缓存，重启手机后刷新（代码不再提供任何插画资源）。
- 2026-11-22 第四轮：凪改名并改用风停图形；LSPosed 状态三态实时显示——管理器关闭 / 已启用待重启 / 已激活；激活用模块广播 + 本次开机标记，关闭用 root 只读查询 LSPosed 的 `modules_state`；覆盖安装后 LSPosed 若自动关掉模块，App 会立即显示“已在管理器中关闭”。

## 核心结论

1. **不要卸载/停用 `com.hihonor.ouc`**：用户报告的原始操作序列是“卸载系统更新 → 全量强制 dex2oat 编译 → 刷入类似 Magisk 的 root 模块 → 崩溃；重启能进桌面但马上卡住重启”。因此不能把崩溃单独归因于 OUC；Nemu 仍坚持“不卸载”，只用可逆操作。对现有日志的重新分析见 [05 OUC 崩溃分析](docs/05-ouc-crash-analysis.md)：当前 dropbox/tombstone 没有 system_server 或 OUC 崩溃证据，更多线索指向全量 dex2oat + root/zygisk 模块（LSPosed/dexoptanalyzer tombstone），原始事件日志可能已不存在；该结论不改变“不卸载”的产品规则。
2. `suspend` 是可逆的包策略，不保证进程退出；`force-stop` 是一次性请求，系统仍可能因绑定服务重新启动进程。检测页分别展示挂起、STOPPED 标记和实际进程状态，不把命令退出码当作“后台已彻底停止”。
3. 安全解冻始终开启：正常关机/重启前自动 `unsuspend` 全部 suspend 目标（含 AI 目标）；开机后由 `BootFreezeWorker` 在延迟（默认 120s）后自动重新冻结已启用目标。bootloop 守卫生效时，本次开机保持解冻并记录跳过原因。
4. 实机验证：OUC `suspended=true` → 正常 `svc power reboot` → `last_shutdown_result=成功` → 开机后 `suspended=false`，手机正常启动。`adb reboot` 不通知 App 的 `ACTION_SHUTDOWN`，但模块 v1.18 会在下次开机 early 按 ownership marker 恢复，并用 `boot_completed` 标记避免快速重启被误计为失败 boot。
5. `suspend` 不阻止后台运行（Hail README 与 dumpsys 均证实）；本机 OUC 在 `suspended=true` 时仍能收到 `BOOT_COMPLETED`。模块必须配合强停、AppOps/待机限制或 `disable-user` 的可逆例外。

## 云控 / 遥测（高级，默认关闭）

- 运营商 DM 三件套（`com.hihonor.regservice`、`com.hihonor.android.UEInfoCheck`、`com.hihonor.android.AutoRegSms`）和 `com.hihonor.numberidentity` 可使用 `pm disable-user --user 0`（绿/黄目标的可逆整包停用例外）；逐项确认、不卸载，只在记录“原本启用”时 `pm enable` 回滚。
- 遥测三件套（`com.hihonor.hiview`、`com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel`）在本机**不能 suspend**（平台返回 `state:false`），默认改用 **COMPONENT** 停用登记的 Service/Receiver/Provider；关机/开机按原始状态恢复组件，而不是只 `pm unsuspend`。
- 冻结/解冻统一在 Freeze/Unfreeze 页操作；所选解冻会关闭策略，“临时全部解冻”会保留策略并跳过本次开机冻结。执行方式在设置里明确选择“预览（手动操作不改手机；开机/关机安全清理仍执行）”或“实际执行”，不再用含义反直觉的 Dry run 开关。
- `com.hihonor.cipservice`、`com.hihonor.android.hnaps`、`com.hihonor.android.pushagent` 默认只观察；它们仍可切换暂停/停用/隐藏，但都属于高风险操作，确认框会写明后果（诊断、刷新率自适应或系统推送可能异常）。Nemu 不硬阻断任何已安装包，只警告并要求确认。
- 云控/遥测不再有单独的“恢复全部云控”按钮；使用 Unfreeze 页的“解冻所选/全部解冻”。
- 风险：停用运营商 DM 可能影响 VoLTE 配置、运营商 FOTA、自注册短信；组件停用遥测可能影响诊断日志。
- 测试：CT1–CT5、PF9/PF11/PF16（见 `docs/04-test-plan.md`、`docs/12-device-test-log-2026-11-22.md`）。

## 反诈（高级，默认关闭）

- 与更新/AI 列表分开管理，不混入 `Targets.all`。
- `com.hihonor.numberidentity`（号码识别）可选 `suspend` 或 `disable-user`；失去来电识别/骚扰与诈骗标记，可恢复。
- `com.hihonor.securityserver`（安全公共服务）与 `com.hihonor.awareness`（情景感知）默认只观察，可选“暂停”；**停用/隐藏不会被代码硬拒绝**，但会弹出高风险确认（支付保护/活体/反诈校验可能失灵）。高级控制里仍提供 `appops RUN_ANY_IN_BACKGROUND ignore` + `standby restricted`，可恢复为 `allow`/`active`。
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
- 上次冻结后没有“干净关机”（App 的成功记录，或 root guard 的 `planned_shutdown` /
  `boot_completed` 记录，且时间晚于冻结尝试）时，下一次开机 **跳过自动冻结**，记录
  `last_boot_freeze_result`，避免 bootloop。`boot_completed` 记录专门覆盖“快速
  `adb reboot` 来不及写 planned 标记”的情况。
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
- **跨工具 suspend 限制**：如果目标是被 Hail、`adb shell pm suspend` 或其他第三方工具暂停的，
  `pm unsuspend` 仍可能清除不了（`suspendingPackage` 不同），请用原工具或 adb 清除。
  Nemu 自己新施加的 suspend 在 root 可用时优先用 root 身份；guard 会依次尝试 root 和
  `com.android.shell` 两种身份，App 也会用原后端重试旧的 Shizuku 数据。App 的
  `last_shutdown_detail` / `last_boot_detail` 会记录后端、命令和输出。

## 外观与授权（v1.1）

- **图标**：白底亮色几何图形（Nemu=钴蓝 Tabler 雪花，凪=翠绿三角回环与中心圆点），自适应图标/单色图标/通知图标/App 内标记统一。
- **风格**：鲜明 / 氛围 / 柔和 / 单色四套（`VIVID` / `TINTED` / `SOFT` /
  `MONOCHROME`）；**配色**：蓝（默认）、品红、玫红、红、橙、黄、绿、石墨八套；
  每种配色的控件颜色都从该色系与白色之间推导；明暗跟随系统/浅色/深色。深色背景使用
  纯 `#000000`（OLED 省电），卡片为中性深灰，不使用褐色。配色/外观 token 参考
  FlClash / prism_ui，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- **授权卡**：显示当前授权模式（`Root` / `Shizuku（ADB 启动）` / `Shizuku（Root 启动）` / `未授权`），并分别列出 Root、Shizuku、ADB 调试的真实状态；“请求授权”会先尝试 Root，再请求 Shizuku 授权；提供“ADB 授权说明”（ADB 仅用于启动 Shizuku，Nemu 不能直接调用电脑 ADB）。
- **冻结/解冻按钮**：冻结页明确区分“勾选（只加入计划）”与“立即冻结”；每个目标有“立即冻结/重新冻结”，顶部有“立即冻结已勾选的 N 个应用”，执行前逐一列出“具体会发生什么”。解冻页用“解冻所选 N 个应用 / 全部解冻”。

## 自定义冻结与关机强制解冻（v1.2）

- 冻结页“添加任意应用（自定义冻结）”可搜索并冻结任意已安装应用；支持 暂停 / 停用 / 隐藏（伪装卸载）/ 只观察。桌面、设置、电话、输入法、权限控制器、Shizuku、Root 管理器等关键包会显示高风险警告并允许用户确认后执行（不硬拒绝）；请优先使用暂停。不要添加桌面、设置、电话、输入法、权限控制器、Shizuku、Root 管理器或 Nemu 自己，可能无法开机或无法恢复。
- 每次冻结/解冻后生成 `/data/adb/nemu/restore_frozen.sh`；模块 v1.18 通过 KernelSU `initrc` 的 `on shutdown`、root FIFO 守护进程/LSPosed 关机 hook、`sys.powerctl` 轮询三层尝试在正常关机/重启前解冻；即使 Nemu 进程没有运行也生效。下一次开机的 early 阶段还会按 ownership marker 恢复全部 Nemu-owned 状态（disable-user、hide、suspend、组件、AppOps、standby），所以 `adb reboot`/强制断电后的残留也有第二层兜底。强制断电/拔电池本身无法执行任何关机代码，这是唯一覆盖不了的场景。
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
- 自启动模块：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`，在 FinaSU 模块管理器中安装（或 `ksud module install`）。v1.21 会安装独立 `boot_guard.sh`、带超时的 `boot_guard_runner.sh`、`deep_restore.sh` 和 `deep_rescue.sh`：按 boot id 计数、early PM-ready 恢复全部 Nemu-owned 冻结态、`boot_completed` 后复核；连续 3 次未确认完成启动则写 rescue flag、禁用模块并在后续启动重试恢复。深度卸载 bridge 在 service 阶段恢复 manifest 中未安装的包，`boot_completed` + 90 秒后只重新卸载本次恢复的包；`deep_rescue.sh` 在第二次未完成启动时只对 manifest 包修复 ABX `package-restrictions` 并回滚新增项。v1.19 的 `BOOT_TRIGGER` 广播仍带 `--include-stopped-packages` 并重试 3 次，解决应用被后台清理后开机冻结不排程；安装/更新模块后重启一次。
- 如果 FinaSU 模块安装后仍无法自启动，请检查 FinaSU 日志；不要修改分区。

## 非目标

- 不做卸载；系统应用默认不做停用。云控模块的唯一例外是运营商 DM 三件套的 `disable-user`（可逆、逐项确认，见 `docs/03-design.md` §7.1）。
- 不修改 `build.prop`/系统版本号。
- 不依赖 Hail；不使用 Hail 的 API/Intent。
- 不默认处理反诈/温控/云控；这些作为单独的高级模块，需明确开关和风险确认。
- 不承诺 100% bootloop 防护：强制断电/拔电池无法执行任何用户态代码；`adb reboot`
  由下一次开机的 early 恢复兜底，但不能保证早于所有系统组件；系统崩溃本身不在恢复范围内。
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
- 参考项目：
  - Hail（雹）— https://github.com/aistra0528/Hail  
    仅参考 suspend 机制与公开文档，不依赖、不调用其 API/Intent/数据。
  - FlClash — https://github.com/chen08209/FlClash；prism_ui —
    https://github.com/chen08209/prism_ui  
    仅参考配色/surface/控件外观 token（Apache-2.0）；用 Compose 重新实现，
    不包含 Flutter 运行时，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
  - Material Color Utilities —
    https://github.com/material-foundation/material-color-utilities  
    仅用于生成静态 HCT 色表（Apache-2.0）。
  - KernelSU / FinaSU、Shizuku：作为 root 与 shell 权限运行时平台使用，
    不复制其代码；模块格式参考 KernelSU 模块规范。
- 支持的设备与限制：见上文 **[支持的设备](#支持的设备)**。
