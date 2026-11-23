# 25 审计候选问题清单（2026-11-23）

> 工作文档：只记录候选与证据，**不代表已修复**。R1/R2/R3 结束后合并去重，再转成
> `docs/26-fix-plan-2026-11-23.md`。每条修复后在此标记状态与验证证据。

## A. R0 预审（COTA / detection）

### C1 — `manifest.tsv` 同名源文件覆盖备份（Important，旧问题）

- 位置：`cota/CotaPaths.kt:6,15,23-27`、`cota/CotaCommandBuilder.kt:44-46`、
  `cota/CotaController.kt:94-147`。
- `isSafeName("manifest.tsv")` 为 true；备份先 `cp -p` 到 `<dir>/manifest.tsv`，
  随后 `writeManifest` 用清单覆盖同一路径；回读校验拿清单和自身比较，仍显示“备份完成”。
- 后果：原文件在唯一备份中被销毁；之后 delete/restore 都因备份 hash 不匹配永久拒绝。
- 修复方向：清单改用 `.manifest.tsv`（前导点被 SAFE_NAME 拒绝）或备份时拒绝该名；
  同时采用临时文件 + `mv` 原子发布。

### C2 — restore 跟随目标侧符号链接（Minor，新暴露）

- 位置：`cota/CotaCommandBuilder.kt:72-79`；delete 的父目录替换竞态在 `:59-62`。
- 只检查备份源 `[ -f ] && [ ! -L ]`，没有检查目标父目录/叶子节点。
- 修复方向：逐段检查 `[ ! -L ]`，并用 `realpath -m` 验证目标在 `/data/cota/para/` 内；
  叶子先 unlink 再复制，避免 `cp` 跟随链接。

### C3 — 校验与破坏性命令分离的 TOCTOU（Minor，旧问题）

- 位置：`CotaController.kt:237-243,245-257,259-262`（delete）；
  `:319-336,338`（restore）。
- 校验和 `rm`/`cp` 是两次独立 root exec，期间 COTA 同步或其他 root 工具可改写文件。
- 修复方向：把校验与动作合并进同一个 shell（`cmp -s` 后立即 `rm`，或临时文件 + rename），
  或至少对目标 inode/hash 做同一条命令内复核。

### C4 — 特殊权限位导致整个列表失败（Minor，新）

- 位置：`CotaPaths.kt:31-32`、`CotaParsers.kt:91-94`、`CotaController.kt:42-44`。
- `stat -c %a` 对 setuid/setgid/sticky 输出 4 位且首位非 0；`isSafeMode` 拒绝，
  parseStat 丢行，随后 keys 不一致报“文件列表发生变化”。
- 修复方向：列表/解析允许 `^[0-7]{3,4}$`；只在 restore 时额外拒绝非 `0` 开头的 4 位模式
  （或明确保留并安全恢复特殊位）。

### C5 — 恢复范围大于对话框描述（Minor，新）

- 位置：`ui/CotaSection.kt:325-327`、`MainActivity.kt:967`、
  `cota/CotaController.kt:301-302`。
- 对话框说“覆盖同名文件”，实际 `restore(backupId)` 恢复备份内**所有**文件，
  包括当前不存在的文件（会重新创建）。
- 修复方向：对话框列出准确文件数与“将重建不存在的文件”，或把选择集传给
  `restore(backupId, names)`；恢复也应展示风险确认。

### C6 — manifest 非原子发布（Minor，新）

- 位置：`CotaCommandBuilder.kt:44-46`、`CotaController.kt:129-147,153-189`。
- 直接 `base64 -d > manifest.tsv`；写入被中断/ENOSPC 后截断到完整行边界时，
  manifest 仍可解析，备份目录会被列出并允许部分恢复。
- 修复方向：写 `.manifest.tmp` 后 `mv` 到最终名；失败时删除临时文件，最终 manifest
  不存在则 fail-closed。

### C7 — 反诈硬阻断消失，文档仍承诺（Minor，需产品决策）

- 位置：`core/DenyList.kt:1-10`、`core/FreezeStrategy.kt:49-66`、
  `core/TargetCatalog.kt:601-630`；文档 `docs/03-design.md:153,264-265`。
- 代码注释称保护包“不再 blocked”，securityserver/awareness 成为普通行，
  可整包 disable-user；文档仍说绝不整包冻结。
- 修复方向：二选一——恢复 DenyList/OBSERVE 强制策略，或更新文档并增加明确二次确认。
  需用户拍板。

## B. 本轮交互审计新发现

### C8 — 紧凑复选框缺少 checked 语义（Minor，旧实现沿用）

- 位置：`ui/Controls.kt` 的 `NemuSmallCheckbox`（由 `NemuScreens.SmallCheckbox`
  提升而来）；所有 COTA 文件/分组复选框。
- 使用 `Modifier.clickable(role = Role.Checkbox)`，没有 `toggleable`/stateDescription，
  uiautomator 显示 `checkable="false"`，TalkBack 无法报告已选/未选。
- 修复方向：改用 `Modifier.toggleable(value = checked, enabled, role = Role.Checkbox,
  onValueChange = onCheckedChange)`，并增加 contentDescription（分组名/文件名）。
- 已验证点击本身有效：点分组框后出现“已选 3”，再点清除。

## C. R1-4 死代码 / 语义漂移（已完成两遍）

### D1 — 号码识别 HIDE 在检测页缺失（P2，新回归）

- `detection/DetectionReader.kt:206-222` 的 `catalogStrategy()` 只处理
  `DISABLE_USER`/`SUSPEND`，没有把 legacy `antifraud_numberidentity_mode=HIDE` 映射为
  `FreezeStrategy.HIDE`；`TargetPolicy.strategy():106-110` 已映射。
- 后果：已 HIDE 的号码识别在检测页显示成 SUSPEND，并被标成“不一致”。
- 修法：让 DetectionReader 复用 `NumberIdentityMode.toStrategy()` 或抽公共 resolver。

### D2 — 产物文档过期（P2，旧问题）

- `PROJECTS.md:14-22` 指向不存在的 `app-debug.apk`/`app-release.apk`；实际是 split/universal。
- autostart zip 的字节/hash/版本与 `module.prop` 不符（实际 10,546 B、`28aca824…`、v1.9/10）。
  **FIXED（v1.10）**：34,135 B、`b0979b80…`、v1.10/11；同时删除了 `service.sh`
  里的 `am start`，模块不再在开机时自动打开 Nemu 页面。
- `docs/22:112-113`、`docs/23:86-87` 重复旧 hash。
- 修法：最终四包重编后统一重生成表格，并修正 autostart。

### D3 — 确认无调用/只测试使用的代码（P3，用户要求清理）

- `ui/DashboardScreen.kt:393,507` `NemuGlyph.UNLOCK` 分支永不可达。
- `policy/TargetPolicy.kt:300-308` `recordBackend/recordedBackend` + `StrategyOptions.backend`
  只写不读。
- `policy/TargetPolicy.kt:311-317` `setRestrictions()` 无生产调用。
- `core/CommandBuilder.kt:53-66` `appOpsBackground/standbyBucket` 只有测试用，与
  `RestrictionController` 重复。
- `policy/SafetyUnfreezer.kt:22-30` `unsuspendAll/unsuspendPackages` 只有测试用。
- `policy/FreezeStrategyPlan.kt:22-31,106-115` `describe/applyEnabled` 只有测试用。
- `privilege/PrivilegeOverview.kt:22,38,41-51` 四个 UI 字符串只构造不渲染。
- `policy/BootGuard.kt:8-25,54-67` `GuardHealth`/`health` 只有测试用。
- `detection/DetectionModels.kt:174,186,325-336` 诊断方法只有测试用；
  DetectionScreen 自己重算 mismatch。
- `core/Target.kt:17-22` + `TargetCatalog` 的 `description/consequences` 未渲染；
  `TargetCopy` 明确禁止渲染 consequences。需先确认 description 是否别处使用。
- `policy/BootFreezePolicy.kt:15-16` 两个输入从未读取。
- `ui/DashboardScreen.kt:114-115,104` `bootCount/resultBootCount/stopped` 只赋值不读。
- `ui/theme/ColorPalettes.kt:38-43,56` `next`/`flClashPage` 无调用。
- `cota/CotaPaths.kt:5` `DEFAULT_BACKUP_ROOT` 默认参数是公网存储，生产总覆盖；
  建议移除默认或改为私有。
- `data/Settings.kt:62-78` 关机结果/时间字段与 `BootFreezeSettings` 重复，生产未读写。

### D4 — 注释/文档语义漂移（P3）

- `core/Target.kt:17-22` KDoc 与 `TargetCopy` 相反；`policy/BootReceiver.kt:17-25`
  LOCKED_BOOT 注释与实际 return 相反；`SafetyUnfreezer.kt:8-12` 说 one shell，实际逐包。
- `DetectionReader.kt:406-410` 过期“main session 未落地”注释。
- `BootFreezeSettings.kt:10-13` “read-only”注释与写入方法矛盾。
- `docs/17:1127-1128`、`docs/20:275` 描述已删除的 grouped-restore planner。
- `docs/02:46-49` 反诈“never whole-package”已与代码不符（见 C7）。
- `docs/15:39` “每个目标专属后果”不成立。
- `docs/13:108`/`docs/12:301` 历史 restore script 描述已过期。
- `docs/README.md:4` 相对链接错误（应为 `../PROJECTS.md`）。

## E. R1-3 UI 真实性（已完成两遍）

- **U1 P1** `MainActivity.kt:1232`：默认预览模式下应用后显示“成功”，summary/ActionLog
  没有“预览”标记；应加前缀。
- **U2 P1** `MainActivity.kt:705-707` vs `BootFreezeWorker.kt:212-214`：`plannedCount`
  用 `strategy != OBSERVE`，开机用 `enabled && active`；限制后台目标显示“无计划”却会执行。
- **U3 P2** COTA 默认预览下备份被拒、删除按钮无可见原因；应在按钮旁说明 preview/先备份。
- **U4 P2** COTA 删除/恢复对话框在预览下仍写“会删除/覆盖”；应按 dryRun 分支或禁用。
- **U5 P2** COTA `Ok(emptyList())` 仍显示“尚未读取文件”；刷新成功会清空选择；
  应区分 loaded/empty，选择做交集。
- **U6 P2** “刷新全部状态”只刷新授权；改名或真的刷新 catalog/detection。
- **U7 P2** 无 Root 且无 Shizuku 时 SUSPEND 应用/批量仍可选；应使用 `privilege.canFreeze`。
- **U8 P2** 开机总开关关闭时仍显示“开机 X 秒后应用计划/计划 N 个”。
- **U9 P2** 预览开启开机计划时确认框缺少“执行：预览”一行。
- **U10 P2** 检测页“AI 目标：无启用项”只扫 `AiTargets.all`，漏掉 AI 分类 catalog 目标。
- **U11 P2** 紧凑复选框缺 checked/name 语义（已修：toggleable + contentDescription）。
- **U12 P2** COTA 折叠组的选择没有总计数，部分选择显示 unchecked，需两次点击清空。
- **U13 P2** COTA 删除对话框文件列表无滚动，组全选时确认按钮可能被挤出屏幕。
- **U14 P2** COTA 自动读取 latch 只在当前组合内有效，离开设置页再进会重新 root 扫描且丢错误。
- **U15 P2** `busy` 在 launch 内才置位，快速双击可启动两次备份/删除/应用（已修：同步 gate）。
- **U16 P2** `configChanges` 缺 `uiMode/fontScale/locale`，切深色/字体/语言会重建 Activity
  并取消正在执行的批量操作。
- **U17 P2** 解冻所选确认只显示数量，不列目标/风险；RED 目标不可复核。
- **U18 P2** `AllAppsScreen.loadAllApps` 未 runCatching，异常会永久停在“正在读取…”。
- **U19 P2** Applications/下载刷新无 busy/加载反馈。
- **U20 P2** 底栏 Tab、主题色块、风险复选框缺 selected/Role/label 语义。
- **U21 P2** 清除救援模式失败只写 ActionLog，UI 仍显示卡片。
- **U22 P2** 批量模式下 row 点击在 busy 时仍切换选择，复选框却禁用。

## F. R1-1 安全（已完成两遍）

- **S1 P2** `app/build.gradle.kts:38-46` release 用 debug keystore；需要私有 release keystore，
  否则同签名伪造/换钥后无法覆盖安装。**FIXED（2026-11-23，用户授权）**：生成私有
  keystore（`CN=Nemu`，SHA-256 `1f35e731…19a7`），release 改用它；debug 仍用 debug 签名。
- **S2 P2** `policy/BootGuard.kt:96-189` marker 命令直接拼 packageName/className/payload，
  当前入口有 Android 包名校验、不可利用；仍应统一 `ShellQuote.quote` + 包名/组件校验。
- **S3 P2** `MainActivity.kt:592-637` 启动即执行 root 探测/ps/dumpsys/COTA 全量 hash/写脚本/
  一次性 migration；其他应用可反复 startActivity 触发。应 gate 到首次交互/每 boot 限额，
  且 migration 不在非交互启动执行。
- 已确认：COTA 嵌套路径/ShellQuote/导出组件/protected broadcast/allowBackup=false 无新洞；
  B1/B3 已被本轮 F1/F4 修复覆盖。

## G. R1-2 生命周期（已完成两遍）

- **L1 P1** 卸载已冻结应用后，SafetyUnfreezer 把 Unknown package 当 resolved，但
  `clearAfterSuspend` 要求 suspended==false（缺失包返回 null），marker/baseline 永不清除；
  行从解冻页消失、restore-all 禁用、自定义目标拒绝删除、FGS 永久 armed。
  修法：缺失包视为 resolved ownership，清 applied/baseline；允许删除未安装目标；
  restore-all 在 `appliedTargets()` 非空时可用。
- **L2 P2** COMPONENT 策略因组件消失回退到默认策略后，组件所有权不再被 `buildCatalog`
  计算，行从解冻页消失；应无论当前策略都从所有权推导 `hasFrozenState`。
- **L3 P2** 开机自动冻结成功后没有重写 KernelSU shutdown 脚本（只有 UI 路径写），
  关机时执行旧脚本，suspend 可能跨重启残留。
- **L4 P2** UI“临时全部解冻/解冻所选”对外部暂停的包也 `force=true` unsuspend，
  不检查 Nemu 所有权；boot/shutdown 路径正确用 ownedSuspendPackageNames。
- **L5 P2** LOCKED_BOOT_COMPLETED 分支直接 return，与注释/manifest 的直接启动承诺不符。
- **L6 P2** boot identity 未知（BOOT_COUNT 与 boot_id 都不可用）时所有同 boot guard 被跳过，
  scheduler 可能重复入队/worker 重复执行；应 fail-closed。
- **L7 P1** 新 BootSafetyDecision：`frozenThisBoot` 要求 lastSucceeded，但 attempt 在命令前
  已持久化、结果后才写；BootWorker 在 apply 窗口可能判定 false 并 residual unfreeze，
  把刚冻结的目标解冻。应把“本 boot 已尝试”视为行动中/已行动，或加 in-flight 标记；
  补 `lastSucceeded=false + fresh attempt` 测试。
- **L8 P2** BootWorker 延迟到用户手动选择性解冻之后，会 residual unfreeze 用户选择保留的目标；
  应持久化手动操作时间并跳过。
- **L9 P2** uiMode/locale 重建 Activity 会取消批量 apply/restore（同 U16）。
- **L10 P2** 限制后台命令失败时仍保留 ownership 并显示“已限制”；应命令/读回失败回滚
  orig/owned，或 pending 状态。
- 已确认：durability 顺序、shutdown gate、重复广播、dry-run、组件恢复、COTA 校验路径
  在当前代码下无新洞；测试缺口集中在 TargetFreezeService/BootWorker/BootFreezeWorker
  只有源码形状契约测试，没有行为测试。

## H. R2-5 数据完整性

- **I1 P1** 安全清理与 in-flight apply 竞态：`clearSuspendAppliedMarkers` 在 apply 写完
  marker 但命令未返回时读到 suspended=false，清掉 marker；需要进程级互斥/代际。
- **I2 P1** 手动恢复与 BootFreezeWorker 不同步；skip 记录分 4 次 commit；需要原子 skip +
  worker 执行前复核。
- I3 P2 崩溃在 attempt 与 result 之间保留旧 succeeded=true，抑制 residual；
  attempt 应同时写 succeeded=false / in-flight。
- I4 P2 `strategy_opts_*` 多写者 RMW 丢字段；需要串行/分键/CAS。
- I5 P2 COTA manifest 非原子 + 截断前缀可解析（本轮已修：temp+mv、重复/负数校验）。
- I6 P2 COTA restore 逐文件 `cp` 无 staging；后续失败会留下混合状态。
- I7 P2 `restore_frozen.sh` 原地截断写（本轮已修：temp+mv）。
- I8 P2 BootSafetyDecision 用 wall clock；NTP 跳变可误判；应使用 boot count。
- I9 P2 hidden marker 清理可删并发 HIDE apply 写的 baseline；需要互斥/复核。
- I10–I13 P3 关机/开机结果显示延迟、HIDE 检测（F9）、导出 receiver、
  backend timeout 当确定失败。

## I. R2-1 攻击者模型

- **J1 P1** COTA restore 跟随目标父目录/叶子符号链接（嵌套路径扩大暴露）；
  delete 有父目录替换竞态。本轮已记录 F2/F3，待实现 guards/同命令复核。
- J2 P2 特殊权限位 backup 成功但 restore 抛 IAE；本轮已改为 controller 预检返回 Error。
- J3 P2 manifest 重复名/负 size/先 stat 后 cat 的 TOCTOU；本轮已加重复/负数拒绝与原子写。
- J4 P2 文件名含换行可伪造 `sha256sum` 行；本轮已把 listFiles 改为 `find -print0` + NUL 解析。
- J5 P2 `dumpsys package` 解析未绑定 Package 段，versionName 可注入伪造 User 0 行；
  应严格限定段/唯一 User 0。
- J6 P2 root 命令输出无上限，恶意大量 COTA 文件可 OOM；应加 256 KiB 上限。
- J7 P2 BootGuard 部分命令未 ShellQuote（本轮待修）。
- J8 P2 进程名伪造只影响检测显示；保留。
- 已确认：导出组件受 protected broadcast 保护、Shizuku provider 签名权限、
  allowBackup=false、无 deep link/file provider、备份路径应用私有。

## J. R2-2 粗心用户

- A4 P2 失败备份目录无 manifest 仍被 listBackups/lastBackupId 选中，阻塞 delete；
  只列 manifest 有效备份，或提供删除坏备份。
- A6 P2 大字体下固定高度（32dp 刷新、58dp 底栏、44dp 搜索）会裁剪；改 minHeight/换行。
- A8 P3 切 tab 丢批量选择（remember 非 saveable）；改为 rememberSaveable 或提示。
- A9 P3 策略开关 apply() 失败仍显示成功；关键写用 commit + 回滚/提示。
- B1 P2 不可见 ownership 阻塞恢复（同 L1）。
- B2 P2 选择性/全部恢复会 unsuspend 他人暂停的包（同 L4）。
- B3 P2 “解冻所选”实际取消整次开机自动冻结；文案或作用域需一致。
- B4 P2 清除救援失败无 UI 反馈（同 U21）。
- B5 P2 uiMode/locale 重建取消批量（同 U16）。
- B6/B7 P3 开机结果来源/manual vs auto、COTA 关闭文案误导。
- 已验证：COTA 自动读取 latch、分组选择、删除门禁、备份 hash、主题持久化、
  旋转/Back、360dp 主要布局。

## K. R2-4 回归声明核对

- **K1 P1** 卸载已冻结目标仍永久 ownership（同 L1）。
- K2 P2 UI restore-all 会 unsuspend 非 Nemu 所有权（同 L4）。
- K3 P2 自动冻结后未重写 shutdown script（本轮已修）。
- K4 P2 延迟 BootWorker 忽略手动恢复时间（同 L8）。
- K5 P2 限制命令失败仍显示“已限制”（同 L10）。
- K6 P2 `actualText()` 先返回“运行中”，掩盖 AppOps/standby ownership。
- K7 P2 检测页 HIDE 回归（同 D1/F9）。
- K8 P2 COMPONENT 策略回退后 ownership 不可见（同 L2）。
- K9 P2 升级用户 dry_run 缺失键变预览模式；需要 first_install/version 迁移。
- K10 P2 无后端 SUSPEND 仍可选（本轮已修：SelectionRules canFreeze）。
- K11 P3 特殊位 restore 抛异常（本轮已修：预检 Error）。
- K12 P3 重复组件 baseline 写结果被忽略，rollback 可能删已写 baseline。
- K13 P3 DEFAULT_BACKUP_ROOT 仍是公网路径（生产覆盖）。
- 旧声明核对：多数 VERIFIED；PARTIAL/BROKEN 集中在上述项。

## L. R0 UI 最终版（部分已修）

- L-U1 Important 分组部分选中时复选框标签与动作相反（本轮已修：恢复 checked 语义）。
- L-U2 Important 状态色塌缩：SUCCESS/INFO/THEME 都是 primary，红主题下成功/高风险同色；
  对比度不足。SUCCESS 保持用户要求的主配色，ERROR 改用更深的红/黑字提高可读性。
- L-U3 Minor 底栏选中标签低对比且无 Tab selected 语义（本轮已修：onSurface + selectable）。
- L-U4 Minor 检测深链被保存的搜索词遮住（本轮已修：focus 时忽略 query）。
- L-U5 Minor 改策略导致列表跳顶（本轮已修：拆分滚动 effect）。
- L-U6 Minor COTA 分组展开状态被 Lazy 回收（本轮已修：rememberSaveable map）。
- L-U7 Minor COTA 文案把“重新生成”写成必然（本轮已修：可能）。
- L-U8 Minor COTA 行只显示 basename 导致同名歧义（本轮已修：显示完整相对路径）。

## M. R1-5 端到端（已完成两遍）

- B1 P2 手动应用 rescue 状态未知被当 false，可能绕过救援模式；应 `rescueState != false`。
- B2 P2 开机冻结延迟从“启用时刻”而非 boot 开始；应 `delay - uptime`，≤0 跳过。
- B3 P2 “解冻所选并关闭计划”只对 previouslyOwned 关计划，与文案不符。
- B4 P2 全部/选择性恢复会 unsuspend 他人暂停（同 L4）。
- B5 P2 COMPONENT 所有权在策略回退后不可见（同 L2）。
- B6 P3 预览开机仍真实 residual unfreeze，Overview 文案说不会改手机；应说明安全清理例外。
- B7 P3 Detection 绕过 cotaEnabled 读取目录；应尊重模块开关或改文案。
- B8 P3 BOOT_COUNT 回落 boot_id 使上一 boot 干净关机规则失效；应独立 previous id 或禁用该规则。
- B9 P3 手动恢复更新结果但不更新时间戳。
- B10 P3 全量解冻成功后“上次结果”随空列表消失。
- B11 P3 检测 HIDE（同 F9）。
- B12 P3 COTA 刷新失败保留旧文件列表且 loaded=true；应标记 stale/清空。

## N. R2-3 平台语义（已完成两遍）

- A1 P2 manifest 的 USER_UNLOCKED 永不触发（registered-only）；LOCKED_BOOT 分支又直接 return，
  直接启动 fallback 结构上死路。应在 LOCKED_BOOT 注册 runtime USER_UNLOCKED。
- A2 P2 DEFAULT enabled state 被当 true；restore `pm enable` 会覆盖 manifest-disabled。
  应保存 raw 0–4，DEFAULT 用 `pm default-state`。
- A3 P2/P3 20dp 复选框、40dp 开关/分段、44dp 色块低于 48dp 触控；用
  `minimumInteractiveComponentSize()` 保持视觉小但触控 48dp。
- A4 P3 Android 16 statusBarColor/navigationBarColor 已 no-op；用 edge-to-edge/insets。
- A5 P3 standby 限制对 STANDBY_BUCKET_EXEMPTED 系统包可能永久“未生效”；设备验证或移除预设。
- B1 P2 安全解冻成功只看 pm stdout，没读回每个包 suspended==false；应读回并纳入 clean marker。
- B2 P2 recordBackend 只写不读；Shizuku/root 跨后端 unsuspend 可能失败；应使用记录后端。
- B3 P2 force-stop 的 stopped 标志恢复不了，却显示解冻成功；应提示“已启用但停止，首次启动恢复”
  或不在可恢复策略里 force-stop。
- B4 P2 关机 8s 预算无法中断阻塞 su；15+ 目标可能超时；应缩短 shutdown 超时/先 suspend/批量。
- B5 P3 Shizuku/无 KSU 时可能无任何 shutdown hook 却记“成功”；应验证 hook。
- B6 P3 probe 失败被当“包消失”，UI 丢失 applied 显示；应区分 unknown。
- B7 P3 慢启动时 scheduler 窗口可能静默跳过整次 boot freeze；应 boot 路径绕过窗口/记录跳过。
- B8 P3 特殊位 restore 抛异常（本轮已修：controller 预检 Error）。
- 已清除：pm suspend/disable/hide/appops/standby/force-stop 的权限与语义、
  BOOT/SHUTDOWN 接收、WorkManager 配置、Shizuku uid 分支、COTA shell 工具可用性。

## O. R6 最终审计（修复后）

### R6-3 生命周期

- D1 陈旧 in-flight 抑制 boot safety：FIXED（boot count 优先 + 回归测试；
  `BootSafetyDecisionTest` 新增 previous-boot case）。
- D2 Application.onCreate 在 direct boot 读 CE prefs 崩溃：FIXED（移到
  reloadAfterUnlock 的 runCatching，解锁后再 seed）。
- D3 runtime USER_UNLOCKED 无生命周期保证：FIXED（注册后若已解锁立即执行；注册包 runCatching）。
- D4 BootFreezeWorker residual 忽略手动 skip：FIXED（residual 前检查 skipped/attempt）。
- D5 shutdown/MainActivity 组件恢复不在锁内：FIXED（shutdown 整段 + 选中组件恢复都进
  FreezeOperationLock）。
- D6 shutdown script 共享 temp 并发：FIXED（ShutdownScript.write Mutex）。
- D7 restriction rollback 误删既有 ownership：FIXED（claimedHere 才回滚）。
- D8 boot_id fallback 破坏上一 boot 守卫：接受（记录遗留）。
- D9 recordBackend lost update：已由 OPTIONS_WRITE_LOCK 串行化；字段仍无读，记录 P3。

### R6-4 文档/死代码

- P2 preview 文案（Settings/Dashboard/README）已改为“手动不改；安全清理仍执行”。
- README shutdown 恢复 disable-user 的旧说法已改为“不自动逆转”。
- 反诈 docs/02、docs/03 剩余“不整包”行已改为默认 OBSERVE + 用户确认可整包。
- README 测试数/配色数/关键包“拒绝”/COTA 目录/autostart 版本已更新。
- 产物/hash 待最终四包重出后统一更新。
- P3 死代码仍列在 R6-4 报告；已删除 NemuGlyph.UNLOCK、ColorPalettes.next/flClashPage，
  其余接受为 test-only/metadata（GuardHealth、Target consequences 等）。

### R6-5 端到端

- F1 Shizuku-only 被 rescue 门禁阻断：FIXED（无 root 纯 SUSPEND 放行；marker 策略仍阻塞）。
- F2 stale in-flight：FIXED（同 R6-3 D1）。
- F3 mixed ownership 会 unsuspend 他人暂停：FIXED（restore 仅对 applied+SUSPEND 执行
  unsuspend，其余记 SUSPENDED_BY_OTHER）。
- F4 worker 结果不回 UI：FIXED（refreshBootFreezeState 重读 lastBoot/ShutdownResult）。
- F5 未安装 HIDE 误报“已隐藏”：FIXED（HIDE frozen 需 policyApplied）。
- F6 单目标应用无后端仍可点：FIXED（canFreeze 门禁 + 可见原因）。
- F7 预览 boot 文案：FIXED（两张卡按 dryRun 显示“只记录，不修改手机”）。
- F8/F9 其余 P3 接受或已记录。

### R6-2 UI 最终版

- Boot 总开关关闭时：Applications 计划确认、Overview hero、Boot 设置、Detection 延迟
  均已改文案/计数；单目标 Apply 增加 canFreeze 门禁与原因。
- AllApps 加载失败保留 `apps=null`，错误/重试卡可达。
- “临时全部解冻”改为“只处理 Nemu 所有权，他人暂停不接管”；救援清除、force-stop
  撤销文案按实际行为修正；securityserver 标签改为“高风险”。
- COTA：分组复选框 indeterminate、完整路径 maxLines=3、初始“正在读取”、
  恢复风险整行可点、删除列表滚动、预览禁用删除/恢复、loaded/empty 区分。
- NemuSmallCheckbox：toggleable + clearAndSetSemantics（checked/indeterminate/role/
  contentDescription/disabled），真机 uiautomator 已见 checkable + 标签；恢复确认
  风险行点击后确认按钮由 disabled 变 enabled（真机验证）。
- StatusChip：LOW_RISK 固定绿、ERROR 深红、SUCCESS 保持主配色；底栏 Tab
  selectable/Role.Tab；色板/分段控件 selectableGroup/RadioButton。
- 顶部新增“处理中…”chip；纯刷新 loading 文案仍为已知 P3。

## Q. R7 最终复核（2026-11-23）

### R7-2 生命周期

- L1 P1 BootFreezeWorker 的 `recordResult` 会写 `lastBootFreezeBootCount`，旧
  `BootSafetyDecision` 把它当“本 boot 已行动”，导致无后端/未完成启动/残留失败等
  未触碰目标的路径反而压掉 BootWorker 的残留清理。**FIXED**：决策只认
  `lastBootFreezeAttemptBootCount`/`lastAttemptTime`；BootFreezeWorker 的残留
  决策与清理合并到锁内 `residualUnfreezeIfNoBootAction`；移除同 boot 的锁外预检。
- L2 P2 AppOps/standby ownership 被 `ownershipPending` 误报“所有权待清理”。
  **FIXED**：抽出纯函数 `isOwnershipPending`（拥有 AppOps/standby 时不再 pending），
  新增 `CatalogModelsTest` 覆盖。
- L3 P2 限制命令 exit 0 但读回失败时回滚 ownership，可能把已生效的限制变成无主。
  **FIXED**：仅命令失败且未确认时回滚；exit 0 + 未确认保留 ownership/baseline，
  并二次读回；新增两条 `RestrictionControllerTest`。
- L4 P2 未知 boot 身份（-1）与 skip 默认哨兵相等。**FIXED**：
  `applyForBoot`/`residualUnfreezeIfNoBootAction` 均加 `bootCount >= 0` 门禁。
- 附：`lastBootFreezeAttemptInFlight` 在决策中已无读者，**已删除**（连同持久 key），
  避免写而不读的伪状态。
- 测试：`BootSafetyDecisionTest` 重写（同 boot attempt、result-only、未知 boot、
  时钟跳变）；`PackageSnapshotParserTest` 增加 `installed=false` → `isMissing()`。

### R7-3 UI

- U1 状态 chip 在部分 palette 的主色上对比度 4.45:1。**最终处理**：曾改为按亮度
  选纯黑/纯白，但用户明确反馈蓝色控件上的黑字更难看清；现统一使用 FlClash 设计的
  `onPrimary`（蓝底白字），`StatusChipContrastTest` 改为守护 64 组设计配色的
  ≥3:1 UI 组件对比度。
- U2 预览模式下开机计划 chip 仍像会执行。**FIXED**：FreezeScreen/卡片按
  `bootFreeze.enabled` 显示“总开关关闭”。
- U3 删除/恢复失败后 COTA 列表可能过期。**FIXED**：无论成功失败都重新 list；
  读取失败则清空列表。
- U4 AllApps“去应用页设置策略”可能落到恢复页。**FIXED**：Applications 模式
  提升到 DashboardScreen，深链/AllApps 均强制 manage(0)。
- U5 刷新无进度/失败反馈。**FIXED**：`catalogRefreshing` 状态 + 头部“刷新中…”；
  刷新异常写入“上次结果：刷新失败…”，旧列表保留。
- U6 检测行语义只有“展开/收起”。**FIXED**：`mergeDescendants` + 标签/状态/动作
  一并进入 contentDescription。
- U7 临时全部解冻文案未说明他人暂停不接管。**FIXED**（见 R6 部分）。

### R7-1 安全

- S1 P1 限制命令 exit 0 但读回“可读且未生效”时仍保留 ownership，UI 误报“已限制”。
  **FIXED**：读回改为三态（true/false/null）。可读且未生效 → 回滚本次 claim 并失败；
  两次都不可读才保留 ownership；新增 `readableUnrestrictedStateRollsBackOwnership`。
- S2 P2 停机/残留仍会 unsuspend 他人暂停（F3 只修了手动 restore）。**FIXED**：
  `ownedSuspendPackageNames` 改用与 restore 相同的 `isOwnedSuspension`
  （applied + storedStrategy==SUSPEND）；新增 `OwnedSuspensionTest`。
- S3 P2 预览 restore 仍会清理缺失包所有权/root marker。**FIXED**：缺失检查在
  `dryRun` 时跳过（restoreLocked / restoreOwnedComponents / restoreOwnedHidden）。
- S4 P2 COTA restore 目标若变成目录，`cp` 会复制进目录再 chown/chmod 目录。
  **FIXED**：命令增加 `[ ! -d target ]`，并在测试中断言其位于 `cp -p` 之前。
- S5 P2 shutdown 每目标 `dumpsys` 仍挤占 8s 预算。**FIXED**：shutdown 的
  `restoreOwnedComponents/restoreOwnedHidden` 传 `checkMissing=false`，缺失包留给
  下一次 boot residual；与 docs/26 的说法一致。
- S6 P2 LOCKED_BOOT 注册的 runtime USER_UNLOCKED 接收器无生命周期保证。
  **FIXED（兜底）**：LOCKED_BOOT 在 device-protected prefs 写入 pending 位；
  Application 冷启动/Activity 启动时 `ensureBootSafetyIfPending` 在已解锁时补发
  BootWorker，enqueue 成功后清位。
- 附带 P3：`clearMissingPackageOwnership` 现在仅在 root marker 清除成功后才删
  in-app ownership，避免留下无主 marker；`recordManualSkip(-1)` 在未知 boot 身份下
  仍不可见（记录为已知限制，真机 BOOT_COUNT 可用）。
- 复核回归 R5a P2：shutdown 跳过缺失包后，已卸载的 HIDE/COMPONENT ownership 会以
  “状态未知”失败并压掉 clean marker。**FIXED**：`checkMissing=false` 时对
  `isMissing()` 的 hidden 目标延后到 boot residual；组件读回 null 同样延后。
- 复核回归 R6a P2：pending 位在 WorkManager 异步持久化前就被同步清除，kill 窗口
  丢任务。**FIXED**：改为 enqueue Operation 成功回调再清；`BootWorker` 也在确认
  不是陈旧 boot 后清一次。

## P. 最终状态

- 四包已重出、hash/文档已同步、arm64 release 真机启动验证通过
  （`test-artifacts/final-20261123/179-final-overview.png`）。
- R7 后仍接受的风险见 docs/26「仍待处理/明确接受」。
