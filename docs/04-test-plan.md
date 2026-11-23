# 04 测试与回滚计划

> 历史记录：本文件写于 2026-11-21，部分模块/测试类（CloudController、
> AntiFraudController、旧自定义选择器等）已在后续重构中删除。当前回归状态
> 以 `docs/20-device-test-2026-11-22.md` 与 `docs/21-audit-2026-11-23.md`
> 第六轮为准。

> 原则：一次只改一个包；每次改完必须重启验证；先 OUC，后 AI 叶子包；红色包不测整包冻结。
> 助手只生成命令/脚本，写入与重启由用户执行。

## 1. 前置条件

- [ ] 手机电量 > 50%，已备份重要数据。
- [ ] 确认 `com.hihonor.ouc` 已安装（若之前卸载，先 `pm install-existing --user 0 com.hihonor.ouc` 或恢复系统版本）。
- [ ] 记录当前状态：`dumpsys package com.hihonor.ouc | grep 'User 0:'`。
- [ ] 准备回滚命令（见第 5 节），保存在电脑和手机各一份。
- [ ] 尽量在备用机/非主力机上先做 T1；如果只能用主力机，先做完整数据备份。
- [ ] 确认 ADB 可用；注意：**如果 suspend 导致 bootloop，ADB 可能起不来**，fastboot 不能改 /data。

## 2. 测试用例

### T1（最高优先级）：OUC suspend 跨重启

**目的**：验证当前已 `suspended=true` 的 OUC 在重启后不会导致系统崩溃。

步骤：

1. 只读确认：
   ```powershell
   adb shell dumpsys package com.hihonor.ouc | Select-String 'User 0:'
   ```
   期望：`suspended=true`、`enabled=1`。
2. 用户手动重启手机（正常重启，不是强制断电）。
3. 观察：
   - 是否正常开机到桌面；
   - 设置 → 系统和更新 → 软件更新 是否闪退/卡住；
   - SystemUI 是否反复重启；
   - 通知栏是否出现“系统更新”相关崩溃。
4. 开机后只读确认：
   ```powershell
   adb shell dumpsys package com.hihonor.ouc | Select-String 'User 0:'
   ```
   期望：仍 `suspended=true`。

**通过标准**：正常开机；除“软件更新”入口外，设置/桌面/SystemUI 正常。

**失败处理**：立即执行第 5 节回滚；记录崩溃表现与 logcat（若能连上 ADB）。

### T2：force-stop + 清缓存

**目的**：验证 suspend 之外的“真停 + 清缓存”。

步骤（用户执行）：

```powershell
adb shell am force-stop com.hihonor.ouc
adb shell pm clear --cache-only com.hihonor.ouc
```

若 adb shell 无权限，改用 Shizuku/Hail 的终端，或 KSU 管理器里的 root 终端执行同样命令。

观察：

- `dumpsys package com.hihonor.ouc` 中 `stopped=` 是否变化；
- `ps -A | grep ouc` 是否还有进程；
- 一段时间后是否被系统重新拉起。

**通过标准**：命令返回成功；无系统异常；OUC 不再频繁运行。

### T3：AI 绿色叶子包逐个测试

顺序（一次一个，每次重启）：

1. `com.honor.yoyoappsug`（App 建议）
2. `com.honor.yoyocards`（YOYO 建议卡片集）
3. `com.hihonor.suggestion`（智能提醒）
4. `com.hihonor.sceneservice`（荣耀智慧空间基础服务，黄，需更谨慎）

每个包：

```powershell
adb shell pm suspend --user 0 <包名>
# 重启，观察桌面/负一屏/设置/通知
# 回滚：
adb shell pm unsuspend --user 0 <包名>
```

**通过标准**：重启正常；无持续崩溃；可接受失去对应建议/卡片功能。

**失败处理**：`pm unsuspend`；若无效，用 Hail 的同一模式解冻或 `pm enable`。

### T4：开机安全解冻（best-effort）

**目的**：验证 `BOOT_COMPLETED` → `BootWorker` 的第二层解冻。

步骤：

1. 打开 Nemu，确认目标已冻结，且 `SafetyService` 在运行。
2. 用正常重启（`svc power reboot` 或电源菜单）重启。
3. 开机后等待 90s，检查 `Settings.lastBootResult` / UI“上次开机安全解冻”。

**通过标准**：`BootWorker` 执行并写入“安全解冻成功/失败”。

**实机结果（2026-11-21）**：未通过/受 OEM 限制。HONOR 未把 `BOOT_COMPLETED` 送达 Nemu，`last_boot_result` 未写入；主路径关机安全解冻已通过，因此不阻塞验收。

### T5：关机安全解冻（始终开启）

**目的**：验证正常关机/重启前自动解冻全部目标。

步骤：

1. 打开 Nemu，启动 `SafetyService`（通知“Nemu 安全解冻待命”）。
2. 用 Nemu 冻结至少一个目标（先绿色包，再 OUC），确认 `suspended=true`。
3. 正常重启：`adb shell svc power reboot` 或电源菜单 → 重启。**不要用 `adb reboot`**（它走 init，不发 `ACTION_SHUTDOWN`）。
4. 开机后检查 `Settings.lastShutdownResult`、目标 `suspended=false`、`sys.boot_completed=1`。

**通过标准**：`last_shutdown_result=成功`；目标全部 unsuspend；手机正常启动；不自动重新冻结。

**实机结果（2026-11-21）**：通过。绿色包与 OUC 各测一次：

- `com.honor.yoyoappsug` `suspended=true` → `svc power reboot` → `last_shutdown_result=成功`，开机后 `suspended=false`。
- `com.hihonor.ouc` `suspended=true` → `svc power reboot` → `last_shutdown_result=成功`，开机后 `suspended=false`，`boot_completed=1`。
- `adb reboot` 不触发 `ACTION_SHUTDOWN`，目标会保持 suspend；不要用它验证本功能。

### T6：禁止清单（代码级检查）

**目的**：确认受保护包无法被冻结。

步骤：

1. 运行 `.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.TargetsTest"`：`TargetsTest.no_target_is_protected` 断言 `Targets.all` 不包含 `DenyList.protectedPackages` 中的任何条目。
2. 代码级确认：`FreezeController.apply` 对受保护包记录“拒绝冻结受保护包”并跳过，不执行任何后端命令。

**通过标准**：上述单元测试通过，且拒绝路径存在于生产代码中。不期待 UI 选择器：受保护包不会出现在目标列表中。

### T7：Restore all

**目的**：验证一键恢复。

步骤：

1. 至少 suspend 一个绿色包。
2. 点击 Restore all。
3. 期望：所有目标 `unsuspend`；日志记录；系统正常。

### T8：Shizuku 后端（自动选择）

步骤：

1. 启动 Shizuku（Shizuku Store/无线调试），确认 `shizuku_server` 进程存在，并在 Shizuku 中授权 Nemu。
2. 确保 Root 不可用（未加载/未授权）。
3. 打开 App，期望状态显示“Shizuku 可用”。
4. 关闭 Dry run（否则只会写 DRY-RUN 日志）；只勾选一个绿色目标，点“立即应用”，日志应显示 `OK(Shizuku)`；再点“全部恢复”，日志应显示 `OK(Shizuku)` 的 unsuspend。
5. 重启后确认 Shizuku server 是否自动拉起（thedjchi fork）；若没有，Worker 应重试并提示。

说明：手动后端选择 UI 推迟到后续增量；当前后端由 `BackendManager` 自动选择（Root → Shizuku）。

### T9：Root 后端（自动选择）

步骤：

1. 确认 KSU 已加载（`/proc/modules` 有 `kernelsu`），在 KSU 管理器里给 Nemu 授权 root。
2. 打开 App，期望顶栏/概览显示当前授权模式（本轮为 `Root`）。
3. 关闭 Dry run；只勾选 OUC 目标，点“立即应用”（会依次执行 suspend、force-stop、clear cache），日志应显示三条 `OK(Root)`；再点“全部恢复”，日志应显示 `OK(Root)` 的 unsuspend。
4. 重启后确认 root 是否可用（GhostLock late-load 需手动执行）；Worker 的重试行为是否符合预期。

说明：手动后端选择 UI 推迟到后续增量；Root 可用时自动选择 Root（顶栏只显示模式名）。

### T10：Bootloop guard

**状态**：不适用。设计已改为“开机后保持解冻，不自动重新冻结”；不再存在自动重新冻结导致的循环。主保护是 T5 关机安全解冻。

## 2.1 云控 / 遥测模块测试（CT1–CT5，待用户执行）

### CT1：运营商 DM 三件套 disable-user + 回滚（逐包，用户执行）

**目的**：验证 `pm disable-user --user 0` 的可逆停用、重启持久性与 `pm enable` 回滚；一次只测一个包。

**前置（用户执行，只读）**：

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
```

记录基线：`pm list packages -d` 不应包含目标；`User 0:` 行记录 `enabled=` 的当前值。

**步骤（以 `com.hihonor.regservice` 为例；另外两个包重复同样步骤）**：

1. 打开 Nemu，保持 Dry run ON。在“云控 / 遥测”区打开 `系统消息（实际：中国移动终端管理 DM）` 开关，确认弹出停用警告。
2. 点“立即应用云控”。期望日志只出现：
   `DRY-RUN: pm disable-user --user 0 'com.hihonor.regservice'`
   且 `pm list packages -d` 无变化。
3. 关闭 Dry run，再点“立即应用云控”。期望日志出现：
   `OK(Root) 或 OK(Shizuku): pm disable-user --user 0 'com.hihonor.regservice'`
4. 只读验证：

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
adb shell "ps -A | grep -i regservice"
```

期望：`pm list packages -d` 包含 `package:com.hihonor.regservice`；`User 0:` 行显示 `enabled=3`（`COMPONENT_ENABLED_STATE_DISABLED_USER`）；进程无输出。App 内“状态”应显示“已停用”（点“刷新状态”）。
5. 正常重启（电源菜单或 `adb shell svc power reboot`，不要用 `adb reboot`）。开机后重复第 4 步：目标应仍为 `enabled=3`，`pm list packages -d` 仍包含它。
6. 回滚：关闭 Dry run，点“恢复全部云控”。期望日志出现：
   `OK(Root) 或 OK(Shizuku): pm enable --user 0 'com.hihonor.regservice'`
7. 只读验证：

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
```

期望：不再出现在 `pm list packages -d`；`User 0:` 行显示 `enabled=0`；App 状态显示“已启用”。

**通过标准**：Dry run 不写入；实际执行后 `enabled=3` 且重启保持；`pm enable` 后 `enabled=0`；无系统异常。

**失败处理**：立即执行第 5 节回滚命令；若 adb 不可用，用 Shizuku 终端或 KSU root 终端执行同样的 `pm enable`。

### CT2：遥测三项 suspend + 安全解冻（用户执行）

**目的**：验证 `pm suspend` + `am force-stop`，以及 `SafetyTargets` 在正常重启前对遥测 suspend 的安全解冻。

**步骤（以 `com.hihonor.hiview` 为例）**：

1. 打开 Nemu（启动 `SafetyService`），保持 Dry run ON，打开 `hiview（遥测采集）` 开关（不应弹停用警告），点“立即应用云控”。期望日志：
   `DRY-RUN: pm suspend --user 0 'com.hihonor.hiview'`
   `DRY-RUN: am force-stop 'com.hihonor.hiview'`
2. 关闭 Dry run，点“立即应用云控”。期望两条 `OK(Root)` 或 `OK(Shizuku)` 日志。
3. 只读验证：

```powershell
adb shell dumpsys package com.hihonor.hiview | Select-String 'User 0:'
adb shell "ps -A | grep -i hiview"
```

期望：`suspended=true`；进程通常无输出（suspend 不阻止后台，进程可能被系统重新拉起，进程存在不作为失败）。
4. 正常重启（`adb shell svc power reboot` 或电源菜单）。开机后验证：

```powershell
adb shell dumpsys package com.hihonor.hiview | Select-String 'User 0:'
```

期望：`suspended=false`（`SafetyTargets` 已将其纳入关机/开机安全解冻）。若使用 `adb reboot`，本项不成立；不要用 `adb reboot` 验收。
5. 重新应用 suspend，然后点“恢复全部云控”，验证 `suspended=false`。
6. 对 `com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel` 各重复一次（可合并执行一次“立即应用云控”，但验证时逐包检查）。

**通过标准**：apply 后 `suspended=true`；正常重启后 `suspended=false`；restore 后 `suspended=false`；手机正常启动。

### CT3：仅观察与状态回读（用户执行）

1. `com.hihonor.cipservice` 行显示“操作：仅观察”，没有开关；状态由 `PackageManager` 回读显示。
2. Dry run OFF，点“立即应用云控”和“恢复全部云控”，日志中不应出现任何 `cipservice` 命令。
3. 对三件套执行 CT1 的 disable 后，点“刷新状态”，三件套行应显示“已停用”；回滚后显示“已启用”。

**通过标准**：`cipservice` 无操作路径；状态与实际 `dumpsys`/`pm list packages -d` 一致。

### CT4：逐项确认与守卫（用户执行 + 代码级）

1. 在云控区打开 `系统消息（实际：中国移动终端管理 DM）` 开关：必须弹出停用确认框；点“取消”后开关保持关闭。
2. 再次打开并点“确认停用”：开关变为开启。
3. 打开 `hiview（遥测采集）`：不应弹确认框。
4. 代码级：

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.CloudControllerTest"
```

期望：14 个测试通过，其中覆盖未确认拒绝、非白名单 disable-user 拒绝、DenyList 拒绝。

### CT5：无卸载与调用面检查（代码级）

```powershell
cd D:\win_root\nemu
Select-String -Path app\src\**\*.kt -Pattern 'pm uninstall' -SimpleMatch
Select-String -Path app\src\main\java\com\nemu\hibernate\policy\CloudController.kt -Pattern 'CommandBuilder.disableUser'
```

期望：第一条无匹配；第二条在 `CloudController.kt` 出现一次（受守卫调用）。`core/CommandBuilder.kt` 中是 `fun disableUser` 定义，不含 `CommandBuilder.disableUser` 字样；其他模块的 disable-user 调用点不属于云控检查范围（反诈模块另见 AF 节）。

**通过标准**：不存在 `pm uninstall`；云控模块中 `pm disable-user` 只有一个生产调用点且受 `CloudTargets.disableUserAllowed` 与 `isConfirmed` 保护。

## 2.2 反诈模块测试（AF1–AF6，待用户执行）

### AF1 号码识别 `suspend`

基线：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

步骤：

1. 打开 Nemu，反诈区块选择“号码识别 → 暂停”，关闭 Dry run，点“应用反诈设置”，在确认对话框点“确认执行”。
2. 只读验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

期望：`suspended=true`、`enabled=1`。

3. 用一个未知/骚扰号码来电，观察是否还显示号码识别/诈骗标记；记录“失去”或“仍显示”。
4. 点“恢复反诈设置”并确认，再次验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

期望：`suspended=false`、`enabled=1`。

通过标准：apply 的 `pm enable` + `pm suspend` 与 restore 的 `pm unsuspend` + `pm enable` 均成功；状态与期望一致；手机无异常重启。

### AF2 号码识别 `disable-user`

基线同 AF1。步骤：

1. 反诈区块选择“号码识别 → 停用（更强）”，关闭 Dry run，点“应用反诈设置”。
2. 确认对话框必须出现“我确认停用号码识别，并接受失去来电识别/诈骗标记”复选框；未勾选时“确认执行”不可用。
3. 勾选并执行，验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
adb shell pm list packages -d | Select-String 'com.hihonor.numberidentity'
```

期望：`enabled=0`；`pm list packages -d` 包含该包。不同构建可能显示 `enabled=0` 或 `enabled=3`（disabled-user）；以 `pm list packages -d` 包含该包为准。

4. 点“恢复反诈设置”，验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
adb shell pm list packages -d | Select-String 'com.hihonor.numberidentity'
```

期望：`enabled=1`；`pm list packages -d` 不再包含该包。

如果系统拒绝 `pm disable-user`，记录原始输出；AF1 的 `suspend` 是降级路径。

### AF3 安全公共服务后台/待机限制

基线：

```powershell
adb shell appops get --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.securityserver
```

步骤：

1. 反诈区块打开“安全公共服务”限制开关，关闭 Dry run，应用并确认。
2. 验证：

```powershell
adb shell appops get --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.securityserver
```

期望：AppOps 输出 `RUN_ANY_IN_BACKGROUND: ignore`；bucket 为 `restricted`（不同构建可能打印数值 `45`）。

如果 `am set-standby-bucket` 对 uid=system 包返回错误或被忽略，记录原始输出，标记“standby 平台受限”，AppOps 仍是主限制。

3. 观察电话/短信/系统管家是否正常；记录反诈提示是否减少（这是预期副作用，不是故障）。
4. 恢复并验证：AppOps `allow`；bucket `active`（或数值 `10`）。

### AF4 情景感知后台/待机限制

基线：

```powershell
adb shell appops get --user 0 com.hihonor.awareness RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.awareness
```

步骤与 AF3 相同，包名换成 `com.hihonor.awareness`。额外观察：相机人脸解锁/活体检测是否可用（如果设备使用）。记录副作用；不把 `securityserver` 的 libdexkit 开机崩溃归因于本模块。

### AF5 重启与安全解冻

1. 号码识别选“暂停”并应用；确认 `suspended=true`。
2. 正常重启（`adb shell svc power reboot` 或电源菜单），不要用 `adb reboot`。
3. 开机后验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

期望：`suspended=false`（关机安全解冻已包含 numberidentity）；Nemu 中反诈设置仍显示“暂停”，但不会自动重新应用。

4. 号码识别选“停用”并应用；正常重启；开机后验证 `enabled=0`（安全解冻不会 `pm enable`）。然后恢复。

5. 可选但推荐：应用 `suspend` 后，把号码识别切回“不处理”并应用（不点“恢复反诈设置”），再正常重启；开机后验证 `suspended=false`。这证明安全解冻不依赖保存的开关状态。

### AF6 系统更新后状态检查（观察项）

系统更新后重新执行 AF1/AF3/AF4 的基线命令，记录号码识别是否被重新启用、AppOps/standby 是否被重置。Nemu 的 `antifraud_*` 设置会保留，但不代表系统实际状态；需要时重新应用。

## 2.3 温控 / COTA 模块测试（COTA1–COTA6，待用户执行）

> 2026-11-23 实机：COTA1/COTA2 已通过（AAP-AN00 102：递归读到 55 个文件，温控组 3 项；`thermal_config/thermal_config_cloud.xml` 备份 452,048 字节，manifest 保留相对路径并校验 SHA-256）。COTA3–COTA6 待执行。

### COTA1：COTA 只读清单（root）

**目的**：核对 App 展示的文件名、大小、mode/uid/gid、SHA-256 与设备实际内容一致。

1. 在 KSU 管理器中给 Nemu 授权 Root；打开 Nemu → 设置 → 高级；模块已启用时进入页面会自动读取，也可点“刷新列表”。
2. 在手机 root 终端执行（只读）：

   ```sh
   find /data/cota/para -mindepth 1 -maxdepth 4 -type f -print
   find /data/cota/para -mindepth 1 -maxdepth 4 -type f \
     -exec stat -c '%n|%s|%a|%u|%g' {} \;
   find /data/cota/para -mindepth 1 -maxdepth 4 -type f \
     -exec sha256sum {} \;
   ls -laZ /data/cota/para
   ```

3. 逐项对比 App 显示与终端输出。App 以相对路径（如 `thermal_config/thermal_config_cloud.xml`）为标识，并按子系统分组。

**通过标准**：每个普通文件的相对路径、大小、SHA-256、mode、uid、gid 一致；目录和符号链接不出现在可操作列表中；若 App 日志显示“跳过 N 个无法安全处理的条目”，用 root 终端对比 `find` 输出确认这些条目的名称，并记录数量与原因（App 出于安全考虑只记录数量，不回显不安全文件名）。

### COTA2：COTA 备份

**目的**：验证备份目录、清单和 hash 校验。

1. 在 App 中勾选温控组（或需要的文件），点“备份选中”，记录时间戳 `<ts>` 和 App 显示的备份路径。
2. 在手机 root 终端执行（路径是 App 私有目录）：

   ```sh
   find /data/user/0/com.nemu.dream/files/cota-backup/<ts> -type f -print
   cat /data/user/0/com.nemu.dream/files/cota-backup/<ts>/manifest.tsv
   find /data/user/0/com.nemu.dream/files/cota-backup/<ts> -type f \
     -exec sha256sum {} \;
   ```

**通过标准**：备份目录包含所有选中文件（保留相对子目录）和 `manifest.tsv`；清单首行为 `#nemu-cota-backup-v1`；备份文件 hash 与 COTA1 一致；App 显示“备份完成”。

### COTA3：COTA 合成文件备份/删除/恢复（先做，避开真实参数）

**目的**：在不碰真实云控参数的情况下验证删除与恢复路径。

1. 在手机 root 终端创建一个合成文件（放在子目录，验证相对路径）：

   ```sh
   mkdir -p /data/cota/para/nemu_test_dir
   printf 'nemu-test\n' > /data/cota/para/nemu_test_dir/nemu_test_param
   chmod 644 /data/cota/para/nemu_test_dir/nemu_test_param
   ```

2. 在 App 中刷新，展开 `nemu_test_dir` 组，只勾选 `nemu_test_param`，备份，然后删除（勾选风险确认）。
3. 在 root 终端验证删除：

   ```sh
   test ! -e /data/cota/para/nemu_test_dir/nemu_test_param && echo GONE
   ```

4. 在 App 中从该备份恢复，再验证（目标子目录应被自动重建）：

   ```sh
   sha256sum /data/cota/para/nemu_test_dir/nemu_test_param
   cat /data/cota/para/nemu_test_dir/nemu_test_param
   stat -c '%a|%u|%g' /data/cota/para/nemu_test_dir/nemu_test_param
   ```

**通过标准**：删除后文件不存在；恢复后内容为 `nemu-test`，hash 与清单一致，mode/uid/gid 与备份前一致。清理：用户确认后删除该合成文件与空目录。

### COTA4：真实参数删除（可选，高风险）

**前置**：COTA2 全量备份完成；手机电量 50%–80%；先做 COTA3。

1. 记录基线（PC，只读）：

   ```powershell
   adb shell dumpsys thermalservice
   adb shell dumpsys battery | Select-String 'temperature|status|level|health'
   adb shell dumpsys cpuinfo
   ```

2. 只删除一个子系统组；AAP-AN00 102 上可测试温控组 `thermal_config`（3 个文件）。不要同时删除多个组。删除前确认该组已在 COTA2 备份中。
3. 删除后 10 分钟内每 2 分钟记录一次 `dumpsys battery` 温度与 `dumpsys thermalservice` 状态；观察是否异常发热、卡顿、充电异常。注意：系统可能重新下发，且本模块不改内核/HAL 温控，效果不保证。
4. 正常重启（电源菜单或 `svc power reboot`，不要用 `adb reboot`），确认：

   ```powershell
   adb shell getprop sys.boot_completed
   adb shell dumpsys thermalservice
   ```

5. 检查是否被重新下发（root 终端）：

   ```sh
   find /data/cota/para/thermal_config -maxdepth 1 -type f -print
   find /data/cota/para/thermal_config -maxdepth 1 -type f -exec sha256sum {} \;
   ```

6. 从 COTA2 备份恢复温控组，确认 hash 与 COTA1 一致。

**通过标准**：正常开机；无持续热紧急状态；可恢复且 hash 一致。失败处理见“回滚”。

### COTA5：Root-only 守卫

**目的**：确认 Shizuku 或无 Root 时模块不可操作。

1. 重启设备但不加载 KSU late-load root；启动 Shizuku 并授权 Nemu。
2. 打开 Nemu，启用 COTA 模块。

**通过标准**：显示“当前没有 Root，模块不可用”；刷新/备份/删除/恢复按钮均不可用；App 日志中没有 COTA 命令。也可在 KSU 管理器中临时撤销 Nemu 的 root 授权复测。

### COTA6：系统重新下发观察（信息性）

删除真实参数后保持联网，等待 30 分钟并重启，重新执行 COTA1 的只读命令。记录文件是否重新出现、hash 是否变化。此项不设通过/失败，仅记录“系统可能重新下发”的实际行为。

## 2.4 Root / FinaSU 自启动（BOOT-1，待用户执行）

1. 打开 FinaSU（`me.yuki.fina`），给 Nemu（`com.nemu.hibernate`）授权 root，建议选择“始终允许”。
2. 在 FinaSU 模块管理器中安装 `D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`；或把模块里的 `service.sh` 放到 `/data/adb/service.d/99nemu.sh`（权限 755）。
3. 正常重启；等待约 30 秒，执行：

   ```powershell
   adb shell dumpsys activity services com.nemu.hibernate | Select-String 'SafetyService|isForeground'
   adb shell "run-as com.nemu.hibernate cat shared_prefs/nemu.xml" | Select-String 'last_boot_result|last_boot_detail'
   ```

   预期：`SafetyService` 已前台运行；`last_boot_result` 为“安全解冻成功”或有明确失败原因。
4. 若未自动启动：检查 FinaSU 模块日志、`/data/adb/service.d/99nemu.sh` 是否存在且可执行；不要修改分区或 vendor。
5. 自启动通过后，回到 Nemu 界面把 OUC 纳入策略并“立即应用”（确认 OUC 是 Nemu/root 施加的 suspend），再用正常电源菜单/`svc power reboot` 重启；开机后验证 OUC `suspended=false`。不要用 `adb shell pm suspend` 模拟后再要求 Nemu 解冻（跨工具 suspend 不会被清除）。

## 2.5 开机冻结 / 日志 / 检测 / UI（BF/LOG/DET/UI，已实现，真机视觉待验收）

> 设计见 `docs/07-boot-freeze-logging-ui-design.md`。以下项目在代码合并后执行；未执行写“未测”。

- **BF1 root 手动冻结/恢复**：FinaSU 授权 Nemu；在 UI 选择 OUC + 一个绿色 AI 包，点“立即冻结”；`dumpsys package` 显示 `suspended=true`；“全部恢复”后为 `false`。
- **BF2 root 关机安全解冻**：Nemu 自己 suspend OUC → 正常电源菜单或 `svc power reboot` → 开机后 `suspended=false`，`last_shutdown_detail` 后端为 `Root`。**2026-11-21 已实测 Root 解冻成功；仍需在最终 APK 复测。**
- **BF3 开机自动冻结**：重启后打开 Nemu（或由 FinaSU 模块启动），等待 `boot_freeze_delay_seconds`（默认 120s）；已启用核心目标应 `suspended=true`；未启用目标保持 `false`；`last_boot_freeze_result` 有记录。
- **BF4 bootloop 守卫**：BF3 后不执行正常关机，直接强制重启/`adb reboot`；下一次 boot 不应自动冻结，`last_boot_freeze_result` 记录“未干净关机，已跳过”；用 Nemu/`adb shell pm unsuspend` 恢复后，再正常关机/重启，守卫应允许下一次自动冻结。
- **BF5 AI 深度冻结**：逐个开启 AI 目标（一次一个）；确认框出现；冻结后观察桌面/YOYO/相机/语音；正常重启后应自动解冻（`SafetyTargets` 覆盖 AI）；出现异常立即全部恢复并关闭该目标。
- **LOG1 日志关闭**：`logging_enabled=false` 时启动/冻结/恢复不新增日志文件。
- **LOG2 日志开启**：打开日志开关，重启/冻结/解冻；`logs/nemu-*.log` 应包含 startup、boot、safety、freeze 事件；关闭开关后不再新增行。
- **LOG3 启动 logcat**：日志页“抓取启动日志”生成 `logs/startup-*.txt`；root 可用时包含系统启动日志，失败时显示错误但不崩溃。
- **DET1 冻结检测**：核心目标与 AI 目标（`FreezeSource.AI`，策略 = AI 总开关 && 单项开关）
  分别显示策略/实际状态；策略开启但实际未 suspend 时显示 warning；与 `dumpsys package` 对照。
- **DET2 云控/遥测检测**：显示 CloudTargets/DM/hiview 状态；`pushagent` 标记禁止操作；无写操作。
- **DET3 COTA/温控检测**：root 下显示 COTA 文件清单/哈希；无 root 显示“无 root”；powergenie/iaware 只显示状态、无停止按钮。
- **UI1 布局**：底部导航 5 个目的地（概览/冻结/解冻/检测/设置）；卡片/状态 chip/等宽日志；在 1272x2800 屏幕上无横向滚动、主要按钮不被遮挡。

## 3. 验收标准

- [x] T1 通过（OUC suspend + 正常重启安全解冻；未单独测试无安全解冻的裸跨重启）。
- [x] T2 通过（force-stop + clear cache 路径已执行；缓存大小未单独测量）。
- [x] T3 通过（绿色 AI 包 suspend/restore + 重启安全解冻）。
- [x] T5 通过（关机安全解冻：绿色包 + OUC）。
- [x] T6/T7 通过（禁止清单 + 一键恢复）。
- [x] T8 通过（Shizuku 后端实测）。
- [x] T9 Root 后端通过（FinaSU 授权；`last_shutdown_detail`/`last_boot_freeze_detail` 后端为 `Root`）。
- [ ] T4 开机安全解冻：`BootWorker` 可在开机后延迟解冻，但 `SafetyService` 仍不能由 HONOR 开机广播自动启动；需 FinaSU 模块或打开 App。
- [x] 日志可在 App 内查看（包/后端/结果；时间显示与导出推迟到 P4）。
- [x] 代码中不存在 `pm uninstall`。核心版本无 `pm disable-user` 调用路径；云控模块的 `pm disable-user` 仅存在于 `CloudController`（三件套白名单 + 逐项确认 + `pm enable` 回滚），反诈模块仅对 `numberidentity` 生成 `pm disable-user`（用户选择 + 恢复）。
- [ ] CT1–CT5 云控/遥测真机测试（待用户执行）。
- [ ] AF1–AF6 反诈真机测试（待用户执行）。
- [ ] COTA1–COTA6 温控/COTA 真机测试（待用户执行；COTA4 为可选项）。
- [ ] 三个高级模块默认关闭；`securityserver` / `awareness` 无整包暂停/停用/强停路径。
- [x] BF3 核心开机自动冻结（root，延迟 60/120s）通过。
- [x] BF5 AI `mageruntime` 单目标开机冻结 + 正常重启解冻通过。
- [x] BF4 bootloop 守卫在强制重启后跳过下一次自动冻结；设备级通过。
- [x] LOG2 持久日志包含启动/Boot/冻结/安全解冻；LOG1/LOG3 由单元测试/UI 待验收。
- [x] UI1/DET1–DET3 视觉与检测页已解锁验收（PF 系列；5 Tab）。
- [x] FinaSU 自启动模块 v1.5 安装后，重启自动拉起/自动冻结的验收（PF16，boot 38→39）。
- [ ] 强制断电/`adb reboot` 后 FinaSU 脚本“先解冻再等 boot_completed”的真机验收（RECOVERY-1）。
- [x] `BootFreezeWorker` 在策略评估/冻结前先清除全部安全目标残留 suspend；清理失败则跳过冻结（代码实现，248 单测通过；PF16 正常重启已验证）。
- [x] 安全解冻集合包含核心目标 + 全部 AI 目标 + hiview 三件套 + `numberidentity`（`SafetyTargets` 单元测试与 root 实测 26 目标）；运营商 DM 可被安全路径 `pm unsuspend`，但不会被 `pm enable`。

## 4. 风险登记

| 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|
| suspend 导致 bootloop | 高（可能需恢复出厂） | 低（OUC 是更新客户端） | 先 T1；备用机；备份；记录回滚命令 |
| ADB 在 bootloop 时不可用 | 高 | 中 | 不把 suspend 当作“无风险”；先用非关键包验证 |
| force-stop 被系统绑定拉起 | 低 | 高 | 配合 suspend + 网络/后台限制；接受后台仍可能运行 |
| Shizuku 重启后不可用 | 中 | 高 | Worker 重试；提示用户；优先 root |
| root 仅 late-load 可用 | 中 | 高 | Worker 重试；不要假设开机早期有 root |
| 系统更新后反诈/云控开关被重置 | 中 | 中 | 文档提示；定期检查；App 状态回读 |
| 冻结基础服务导致系统 App 崩溃 | 高 | 中 | 硬性禁止清单；红色包不可选 |
| 停用运营商 DM 影响 VoLTE/FOTA/自注册短信 | 中 | 中 | 逐项确认；一次只停一个；随时 `pm enable` 回滚并重启 |
| 反诈能力下降（号码识别/后台限制） | 高 | 高（应用后） | 默认关闭；确认对话框；逐项恢复；文档说明 |
| `securityserver` / `awareness` 的 AppOps/standby 无效或被系统重置 | 中 | 中 | 只记录失败，不降级为整包操作；系统更新后重新检查 |
| COTA 删除温控参数导致发热/充电/降频异常 | 高 | 中 | 备份优先；先做合成文件测试；一次只删一个真实文件；恢复校验 SHA-256 |
| COTA 备份目录被 SELinux/外部存储限制 | 中 | 中 | 在 COTA1/COTA2 先验证；失败则停止，不进入删除 |
| 系统重新下发 COTA 参数 | 中 | 高 | 文档说明；COTA6 记录；不承诺永久性能变化 |

## 5. 回滚命令

```powershell
# 解冻（suspend）
adb shell pm unsuspend --user 0 com.hihonor.ouc

# 如果是被 disable（核心模块不主动使用）
adb shell pm enable --user 0 com.hihonor.ouc

# 如果是被 uninstall --user 0（本项目不主动使用）
adb shell pm install-existing --user 0 com.hihonor.ouc

# 云控：运营商 DM 三件套（Nemu 使用 disable-user）
adb shell pm enable --user 0 com.hihonor.regservice
adb shell pm enable --user 0 com.hihonor.android.UEInfoCheck
adb shell pm enable --user 0 com.hihonor.android.AutoRegSms

# 云控：遥测三件套（Nemu 使用 suspend）
adb shell pm unsuspend --user 0 com.hihonor.hiview
adb shell pm unsuspend --user 0 com.hihonor.hiviewpush
adb shell pm unsuspend --user 0 com.hihonor.hiviewtunnel

# 反诈：号码识别
adb shell pm unsuspend --user 0 com.hihonor.numberidentity
adb shell pm enable --user 0 com.hihonor.numberidentity
adb shell appops set --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND allow
adb shell appops set --user 0 com.hihonor.awareness RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket --user 0 com.hihonor.securityserver active
adb shell am set-standby-bucket --user 0 com.hihonor.awareness active

# 查状态
adb shell dumpsys package com.hihonor.ouc | Select-String 'User 0:'
```

如果 adb shell 被拒：

- 用 Shizuku 授权的终端 App 执行同样的 `pm` 命令；
- 或用 KSU 管理器/MT Manager 的 root 终端执行；
- 不要通过其他冻结工具代执行本项目未授权的操作。

COTA 的回滚在 App 内“从备份恢复”；如果 App 不可用，用 root 终端按备份目录的 `manifest.tsv` 手动 `cp -p`，并用 `sha256sum` 校验。不要删除备份目录。

## 6. 测试记录模板

| 日期 | 包 | 操作 | 后端 | 结果 | 重启后 | 备注 |
|---|---|---|---|---|---|---|
| 2026-11-21 | com.hihonor.ouc | suspend（现状） | 未知 | 待测 | 待测 | 用户此前卸载导致崩溃 |

## 7. 实测记录（2026-11-21）

> 本节更新于 2026-11-21 实机验收。已在 AAP-AN00 / MagicOS 10.0.0.102 上安装 Nemu debug APK；
> 只使用 Nemu 自己的 suspend/unsuspend 操作其目标，未手动 disable/uninstall 其他系统组件，未修改分区。
> `adb reboot` 与 `svc power reboot` 的区别见 §7.3。

### 7.1 构建与代码级检查

| 日期 | 检查项 | 结果 | 证据 / 备注 |
|---|---|---|---|
| 2026-11-22 | C1 单元测试（Nemu 最终） | 通过 | `.\gradlew.bat :app:testDebugUnitTest`：248 个测试，0 失败 / 0 错误 / 0 跳过，29 个测试类；报告位于 `app/build/test-results/testDebugUnitTest/` |
| 2026-11-22 | C2 Debug APK 构建（Nemu 最终） | 通过 | `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`；产物 `app\build\outputs\apk\debug\app-debug.apk`，34,450,913 字节，SHA-256 `535bcc6d2c0e5b47fb178cf499a43f7ef43da1d0b2a4e828811fc076270ac5ba` |
| 2026-11-22 | C3 APK 标识 | 通过 | `package=com.nemu.hibernate`，versionCode=3，versionName=1.1.0-nemu，minSdk 30 / targetSdk 36 |
| 2026-11-21 | C4 无 `pm uninstall` | 通过 | `Select-String -Path app\src\**\*.kt -Pattern 'pm uninstall' -SimpleMatch` 无匹配 |
| 2026-11-21 | C5 `pm disable-user` 调用面 | 通过（代码级） | 核心 `FreezeController` 无 `disable-user`；生产调用点只有 `CloudController`（三件套白名单 + `isConfirmed`）与 `AntiFraudPolicy`（仅 `numberidentity`）。`securityserver`/`awareness` 只有 AppOps/standby 命令 |
| 2026-11-21 | C6 禁止清单在后端路径生效 | 通过（代码级） | `FreezeController.apply` 先跳过未启用目标，再对启用目标调用 `DenyList.isProtected`，命中则记录“拒绝冻结受保护包”并跳过；`MainScreen` 对受保护包禁用开关；`TargetsTest.no_target_is_protected` 通过 |
| 2026-11-21 | C7 Dry run 默认开启 | 通过（代码级） | `Settings.dryRun` 默认 `true`；`FreezeController.executeAll` 在 dry-run 下只写 `DRY-RUN:` 日志、不调用后端；`SafetyUnfreezer` 明确忽略 dryRun，关机/开机安全解冻始终执行 |
| 2026-11-21 | C8 无 Hail 依赖/API/Intent | 通过 | `Select-String -Path app\src\**\*.kt,build.gradle.kts,settings.gradle.kts,gradle.properties -Pattern 'hail' -SimpleMatch` 无匹配；依赖清单无 Hail |
| 2026-11-21 | C9 命令面 | 通过（代码级） | 核心：`pm suspend` / `am force-stop` / `pm clear --cache-only` / `pm unsuspend`；云控：`pm disable-user`（仅三件套）、`pm enable`、`pm suspend`、`am force-stop`；反诈：`pm suspend`/`pm disable-user`（仅 numberidentity）、`pm enable`/`pm unsuspend`、`appops`、`standby`；COTA：root-only `find/stat/sha256sum/cp/rm -f/chown/chmod` |
| 2026-11-21 | C10 目标与默认值 | 通过 | `Targets.all` 首位为 `com.hihonor.ouc`，另有 `com.honor.yoyoappsug`、`com.honor.yoyocards`、`com.hihonor.suggestion`、`com.hihonor.sceneservice`；全部 `defaultEnabled=false` |
| 2026-11-22 | C11 统一安全解冻集合 | 通过（单元测试） | `SafetyTargetsTest`：核心/AI/hiview/numberidentity/运营商 DM 均包含（26 个）；`cipservice` 等只观察/组件目标不包含；无重复。`SafetyUnfreezerTest` 覆盖逐包 unsuspend |
| 2026-11-21 | C12 云控守卫 | 通过（单元测试） | `CloudControllerTest` 21 项：未确认拒绝、非白名单 `disable-user` 拒绝、DenyList 拒绝、dry-run、restoreAll；`CloudTargetsTest` 锁定三件套/遥测三项/仅观察 |
| 2026-11-21 | C13 反诈守卫 | 通过（单元测试） | `AntiFraudPolicyTest` 11 项 + `AntiFraudControllerTest` 6 项：`securityserver`/`awareness` 无整包暂停/停用，只有 AppOps/standby；`numberidentity` 可恢复 |
| 2026-11-21 | C14 COTA 安全不变量 | 通过（单元测试） | `CotaControllerTest` 14 项：无备份拒绝删除、hash 不一致拒绝删除、备份副本损坏时拒绝删除/恢复、备份/清单校验、恢复后 SHA-256 校验、无 Shizuku 回退；代码中无 `rm -rf`，COTA 包不引用 powergenie/iaware/vendor/thermal HAL |

### 7.2 实机测试项

| 日期 | 测试 ID | 结果 | 备注 |
|---|---|---|---|
| 2026-11-21 | T1 OUC suspend + 正常重启安全解冻 | 通过（带安全解冻） | 安装 APK；OUC `suspended=true` → `svc power reboot` → `last_shutdown_result=成功` → 开机后 `suspended=false`；`boot_completed=1`；未单独测试无安全解冻的裸跨重启 |
| 2026-11-21 | T2 force-stop + `pm clear --cache-only` | 通过（部分） | Nemu apply 后 OUC `suspended=true`、`stopped=true`；命令执行成功；缓存大小未单独测量 |
| 2026-11-21 | T3 AI 叶子包 | 通过 | `com.honor.yoyoappsug` suspend=true → `svc power reboot` → suspended=false；手机正常启动 |
| 2026-11-21 | T4 开机安全解冻 | 失败（OEM 限制） | 多次正常重启后 `last_boot_result` 未写入；HONOR 未送 `BOOT_COMPLETED`；不阻塞，主路径 T5 通过 |
| 2026-11-21 | T5 关机安全解冻（绿色包 + OUC） | 通过 | 两次独立测试：yoyoappsug / OUC 均 `suspended=true` → `svc power reboot` → `last_shutdown_result=成功` → 开机后 `suspended=false`，`boot_completed=1` |
| 2026-11-21 | T6 禁止清单 | 通过（代码级） | C6 + `TargetsTest.no_target_is_protected` |
| 2026-11-21 | T7 Restore all | 通过 | 使用“全部恢复”将 OUC + yoyoappsug unsuspend；`dumpsys` 均 `suspended=false` |
| 2026-11-21 | T8 Shizuku 后端 | 通过 | App 自动选 Shizuku；授权后 apply/suspend/unsuspend 成功（`dumpsys` 验证） |
| 2026-11-21 | T9 Root 后端 | 通过 | FinaSU 授权后，`last_shutdown_detail` / `last_boot_freeze_detail` 均为 `Root`；关机安全解冻与开机冻结实机执行 |
| 2026-11-21 | T10 Bootloop guard | 通过 | boot=33 冻结后 `adb reboot`，boot=34 自动跳过并记录原因（BF4） |
| 2026-11-21 | INSTALL-1 新 APK 安装 + 关机安全解冻 | 通过 | `adb install -r` 覆盖安装成功；OUC `suspended=true` → `svc power reboot` → `last_shutdown_result=成功` → 开机后 OUC `suspended=false` |
| 2026-11-21 | AUTO-START-1 HONOR 开机广播 | 失败（OEM 限制） | logcat：`HwMtmBroadcastResourceManager ... com.nemu.hibernate : don't meet cpuload`；`SafetyService` 未自动启动；需打开 Nemu 或在“应用启动管理”允许自启动 |
| 2026-11-21 | SAFETY-2 跨工具 suspend + 安全解冻 | 失败（跨工具限制） | 用 `adb shell pm suspend` 模拟，`suspendingPackage=com.android.shell`；Nemu 的 Shizuku `pm unsuspend` 输出 `new suspended state: true`，无法清除另一个包的 suspend；需用 adb/原工具清除。Nemu 自己施加的 suspend 不受此限制 |
| 2026-11-21 | BOOT-1 FinaSU 模块自启动 | 通过 | 模块 v1.1 安装后重启（boot=35），19:59:47 自动拉起 Nemu（pid=9935），`SafetyService` 前台运行；`last_boot_result=安全解冻成功`。模块路径 `tools\nemu-boot-autostart\nemu-boot-autostart.zip` |
| 2026-11-21 | AUTO-RESCUE-1 自动救砖（代码/stub） | 部分通过（真机未验证） | `boot_guard.sh` 用 stub 测试：连续 3 次 boot 后写 rescue flag、模块 `disable`、动态 unsuspend 所有 suspended 包、enable DM/numberidentity；normal service 不会 enable DM。真机 v1.3 因用户暂停手机操作，未验证 auto-start/3 次救援 |
| 2026-11-21 | BF6 120s + 全量 AI 开机冻结（最终 APK） | 通过 | boot=35 于 19:59:47 调度，20:01:49 执行（约 122s）；`last_boot_freeze_detail = 后端=Root | 核心=5（成功） | AI=6（成功）`；全部核心与 6 个 AI 目标 `suspended=true`；随后系统可正常启动 |
| 2026-11-21 | ROOT-1 root 后端关机安全解冻 | 通过 | FinaSU 授权后，`last_shutdown_detail` 显示 `Root | exit=0 | pm unsuspend ...`，所有目标 `new suspended state: false`；这是现有 APK 的实测 |
| 2026-11-21 | BF3 核心开机自动冻结 | 通过 | 新 APK：boot=31，60/120s 后 OUC `suspended=true`；`last_boot_freeze_detail = Root | 核心=1（成功）| AI=0`；随后正常重启 `suspended=false` |
| 2026-11-21 | BF5 AI 开机冻结（mageruntime） | 通过（单目标） | 开启 `ai_deep_freeze_enabled` + `ai_target_com.hihonor.mageruntime`，boot=32，60s 后 `mageruntime suspended=true`；`AI=1（成功）`；正常关机重启后 `suspended=false`，`last_shutdown_detail` 的 Root 批量解冻包含 AI 目标 |
| 2026-11-21 | LOG2 持久日志启动/Boot/冻结/解冻 | 通过 | 打开 `logging_enabled` 后 `nemu-20261121.log` 包含 `[startup] pid=... boot=...`、`安全解冻成功（Root）`、`开机冻结成功`、`关机安全解冻：成功` |
| 2026-11-21 | BF4 bootloop 守卫（设备级） | 通过 | boot=33 核心冻结成功后用 `adb reboot` 强制重启（无安全解冻）；boot=34 时 `last_boot_freeze_result = 已跳过：上次冻结尝试后未检测到干净关机`，OUC 保持未冻结；符合 fail-closed 设计 |
| 2026-11-21 | UI1/DET1–DET3 真机 UI | 未测 | 手机锁屏，无法通过 adb 解锁；需用户解锁后检查新布局/检测页 |
| 2026-11-21 | AUDIT-1 只读设备审计 | 通过 | 独立只读代理核对 APK 哈希、SafetyService 前台、boot=34 守卫、prefs、WorkManager、持久日志；未发现 Nemu FATAL/ANR；发现 120s 延迟与 AI 开机路径尚未在新 APK 上复测，UI 未验收 |
| 2026-11-21 | REVIEW-1 独立代码/UI/检测审查 | 通过（已修复） | 4 个独立只读代理：检测无 Important；UI 指出开机结果语义/手动冻结假成功/日志循环，已修复；Boot 指出 KEEP 过期工作阻塞、手动冻结绕过守卫、安全解冻假成功，已修复（REPLACE、recordAttempt、输出 `state: true` 判失败、恢复先标记跳过并覆盖 hiview/numberidentity）；175 单元测试通过。UI 视觉与真机新路径仍待复测 |
| 2026-11-21 | REVIEW-2 检测“不一致”专项审计 | 通过（已修复，待装新 APK） | 只读审计确认核心 5+AI 6 的 suspend 均成功；用户看到的多数不一致来自：①启动时 19:59:47 的旧快照（120s 冻结前 11 条核心/AI + 6 条云控=17 条）；②云控 6 项策略已勾选但从未点“立即应用云控”；③云控行在冻结区/云控区重复渲染。另发现 5/6 AI 包 suspend=true 但进程被 Honor Service/Provider 重新拉起（stopped=false），旧 UI 仍显示“已冻结”。已修复：云控 `cloud_applied_<pkg>` 与“未应用”语义、freezeStates 去重、检测页进入/应用后自动刷新、`stopped` 纳入“已暂停（未强停）”警告、AI 卡片提示 suspend 不等于后台停止。另发现/修复：安装 APK 触发 `MY_PACKAGE_REPLACED` 时旧 BootReceiver 会执行 BootWorker 解冻全部 15 目标，导致“更新后冻结失效”；现在只重启 SafetyService，不做开机解冻。178 单测通过；新 APK 待安装复测 |

### 7.3 已知限制与后续

- **安全解冻的主路径是正常关机/重启**（电源菜单或 `svc power reboot`）。`adb reboot` 走 init，不发送 `ACTION_SHUTDOWN`；是否残留 suspend 取决于开机时 `BootWorker`/FinaSU 脚本是否运行（boot=34 中被 `BootWorker` 解冻）。强制断电/系统崩溃同样可能跳过安全解冻。
- **HONOR 会拦截第三方开机广播拉起已停止的应用**：2026-11-21 新 APK 实测 logcat 出现
  `HwMtmBroadcastResourceManager ... com.nemu.hibernate : don't meet cpuload`。
  但如果应用已被 FinaSU 脚本/手动打开，后续 `USER_UNLOCKED` 仍可入队 `BootWorker`
  （boot=34 实测 `last_boot_result=安全解冻成功`）。
  开机安全解冻 `BootWorker` 可能延迟成功，但不能替代关机前的安全解冻。
- **避免下一次关机无保护**：重启后必须打开一次 Nemu，或在“设置 → 应用 → 应用启动管理”把 Nemu 设为手动管理，允许自启动/关联启动/后台活动；否则关机安全解冻不会运行。App 顶部有“打开「应用启动管理」允许自启动”按钮。
- **跨工具 suspend 的清除限制**：2026-11-21 用 `adb shell pm suspend` 模拟时，`suspendingPackage=com.android.shell`；Nemu 的 Shizuku `pm unsuspend` 输出 `new suspended state: true`，无法清除另一个包的 suspend。Nemu 自己通过 Shizuku 施加的 suspend 由同一包清除（早期 T5 已验证）；跨工具 suspend 需用原工具或 adb 清除。`last_shutdown_detail` / `last_boot_detail` 记录后端、命令和输出。
- 安全服务必须在运行；关机时 Shizuku/root 必须可用；前台服务被系统杀死时安全解冻会失败。
- 构建已知环境告警：AGP 对 compile SDK 37.2 的未测试版本提示、NDK strip 告警、Gradle deprecation 告警；均非本次功能引入。
- Shizuku 后端的 `newProcess`、流 getter 与 `destroy` 都是 binder 调用，不在 15s 超时范围内；Shizuku server 卡死时执行仍可能阻塞。已知限制。
- 本表记录的是设备实测；未执行的项目必须写“未测”，不得写“通过”。

### 7.4 模块实测记录（2026-11-21，待用户执行）

| 日期 | 测试 ID | 结果 | 备注 |
|---|---|---|---|
| 2026-11-21 | CT1 运营商 DM disable-user + 回滚 | 未测 | 需先 dry-run；一次只测一个包 |
| 2026-11-21 | CT2 遥测三项 suspend + 安全解冻 | 未测 | 正常电源菜单/`svc power reboot` |
| 2026-11-21 | CT3 仅观察与状态回读 | 未测 | `cipservice` 不提供操作 |
| 2026-11-21 | CT4 逐项确认与守卫 | 未测 | 未确认/非白名单应拒绝 |
| 2026-11-21 | CT5 无卸载与调用面检查 | 代码级通过 | 见 C4/C5/C9 |
| 2026-11-21 | AF1 号码识别 suspend | 未测 | 会失去号码识别/标记 |
| 2026-11-21 | AF2 号码识别 disable-user | 未测 | 平台可能拒绝；suspend 是降级路径 |
| 2026-11-21 | AF3 securityserver AppOps/standby | 未测 | 只允许后台/待机限制 |
| 2026-11-21 | AF4 awareness AppOps/standby | 未测 | 注意人脸活体/深伪副作用 |
| 2026-11-21 | AF5 重启与安全解冻 | 未测 | suspended numberidentity 应变回 false |
| 2026-11-21 | AF6 系统更新后状态检查 | 未测 | 信息性 |
| 2026-11-21 | COTA1 COTA 只读清单 | 未测 | 需 Root（KSU late-load） |
| 2026-11-21 | COTA2 COTA 备份 | 未测 | 记录备份时间戳 |
| 2026-11-21 | COTA3 合成文件删除/恢复 | 未测 | 先做此项，不碰真实参数 |
| 2026-11-21 | COTA4 真实参数删除 | 未测 | 可选；高风险 |
| 2026-11-21 | COTA5 Root-only 守卫 | 未测 | 无 Root + Shizuku 应拒绝 |
| 2026-11-21 | COTA6 系统重新下发 | 未测 | 信息性 |
| 2026-11-22 | PF1 Freeze 页 Dry-run 应用 OUC | 通过 | 显示“成功：尝试 1”，未写 applied，suspended 不变 |
| 2026-11-22 | PF2 Freeze 页实际应用 OUC | 通过 | suspended=true，target_=true，applied=true，卡片显示“已应用” |
| 2026-11-22 | PF3 Unfreeze 页解冻所选 OUC | 通过 | suspended=false，策略关闭，applied 清除 |
| 2026-11-22 | PF4 重新启用并应用 OUC | 通过 | 恢复 suspended=true，applied=true |
| 2026-11-22 | PF5 Dry-run 全部解冻不改变状态 | 通过（修复后） | 首轮发现 P0：residualUnfreeze 忽略 dry-run；修复后 OUC 保持 suspended=true |
| 2026-11-22 | PF6 目录组件对照设备 | 通过 | 64 个组件全部存在于 `dumpsys package`，0 缺失 |
| 2026-11-22 | PF7 组件/disable-user 实际冻结/恢复 | 部分通过 | 组件模式已在 hiview 三件套实测；disable-user 三项原状态=false，保持原状 |
| 2026-11-22 | PF8 组件模式 Shizuku 拒绝 | 代码级 | 无 Root 时 UI 不提供组件/整包停用，服务 fail-closed |
| 2026-11-22 | PF9 hiview suspend 可用性 | 平台拒绝 | `pm suspend` 返回 `new suspended state: false`；默认策略改为 COMPONENT |
| 2026-11-22 | PF10 安装 APK 不触发解冻 | 通过 | 重装后 OUC/核心/AI 仍 suspended=true；BootWorker 校验 boot count + 更新时取消旧 work |
| 2026-11-22 | PF11 hiview 三件套组件模式 | 通过 | Root 下 10 个组件全部 disabled，所有权/原始状态已写入；默认策略改 COMPONENT |
| 2026-11-22 | PF12 numberidentity disable-user 往返 | 通过 | enabled 3→1→3；mode DISABLE_USER→OFF→DISABLE_USER；applied/original 同步 |
| 2026-11-22 | PF13 systemappsupdater 冻结/恢复 | 通过 | suspended false→true→false；策略/applied 同步 |
| 2026-11-22 | PF14 温控组件模式 | 部分通过 | iaware 组件可停用/恢复；powergenie 组件被平台保护（返回 default），改为 OBSERVE only |
| 2026-11-22 | PF15 Dry-run 解冻所选 | 通过 | 结果带 DRY-RUN；OUC suspended/策略/applied 均不变；关闭后 dry_run=false |
| 2026-11-22 | PF16 正常重启 / guard v1.5 / 自动冻结 | 通过 | boot 38→39；关机 components 10/10；guard attempts=1 rescue=no；120s 后 18 目标开机冻结成功 |
| 2026-11-22 | PF17 图标/三风格/五配色/OLED 纯黑 | 通过 | 桌面实拍雪人图标；M3/M3E/MIUIX 与五套配色即时切换；深色背景 `#000000` |
| 2026-11-22 | PF18 授权模式卡 | 通过 | 顶栏/概览只显示当前模式 `Root`；Root/Shizuku/ADB 三行状态；请求授权与 ADB 说明 |
| 2026-11-22 | PF19 冻结按钮与后果弹窗 | 通过 | “立即冻结已勾选的 18 个应用”；OUC 弹窗明确“系统更新会被暂停” |
| 2026-11-22 | PF20 HIDE 实际隐藏 | 通过 | upgradeguide `hidden=true`；baseline/owned/applied 写入；UI 显示已隐藏 |
| 2026-11-22 | PF21 HIDE 解冻 | 通过 | `hidden=false`；三类所有权标记清除；目标策略关闭 |
| 2026-11-22 | PF22 低功耗服务生命周期 | 通过 | 全部解冻后 SafetyService 消失；重新冻结后恢复 |
| 2026-11-22 | PF23 迟到 BootWorker | 修复并验证 | 发现 25 分钟后 WorkManager 解冻 15 目标；BootSafetyDecision 跳过本 boot 已冻结；3 单测 |
| 2026-11-22 | PF24 模块 v1.6 | 源码级 | KNOWN_SUSPEND 增加 7 个包；zip 8,276 字节；设备仍 v1.5，待用户安装 |
| 2026-11-22 | PF25 securityserver/awareness 可选暂停 | 代码级 | v1.1 目录新增 SUSPEND（默认 OBSERVE），确认弹窗警告支付保护/活体/反诈校验风险；未实际冻结 |
| 2026-11-22 | PF26 颜色/文字/直觉性修正 | 通过 | 解冻页风险/状态 chip 间距 6dp；调色板改为单种子色生成（默认松林绿）；深色纯黑；Dry run 改为“仅预览/实际执行”两个选项；`Boot count`/`STOPPED`/`force-stop` 等改为中文解释 |
| 2026-11-22 | PF27 自定义冻结任意应用 | 通过 | 阿里巴巴 suspended true→false、applied 往返；AirLink 被平台拒绝并如实报错；测试后移除 |
| 2026-11-22 | PF28 图标 | 通过 | 最终改为白底黑色几何图形（月亮/风止），桌面/通知/单色/顶栏一致 |
| 2026-11-22 | PF29 更新守卫 | 部分通过 | Root/无障碍已启用；LSPosed 模块已启用但需重启，LSPosed 模式 未重启验收；无 LSPosed 仅安装会提示更新 |
