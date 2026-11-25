# systemapp_nuker 审计（2026-11-25）

- 仓库：https://github.com/chisewaguri/systemapp_nuker
- 审计对象：HEAD `0b4c09c`（2026-10-31），模块 v2.2.6
- 克隆位置：`D:\win_root\audit\systemapp_nuker`（只读；未执行仓库内任何脚本）
- 结论：它**不是** Nemu 这种“APK 备份 → 开机恢复 → 稳定后重新卸载”的模型。
  主机制是 **overlayfs whiteout 持久隐藏**，默认模式再叠加一次
  `pm uninstall -k --user 0`。系统分区原 APK 从未删除。

## 1. 卸载机制

- **whiteout**：`module/nuke.sh:72-78`
  ```sh
  busybox mknod "$target" c 0 0
  busybox setfattr -n trusted.overlay.whiteout -v y "$target"
  ```
  whiteout 放在模块的 upperdir（`system/`、`product/` 等）；开机时由管理器
  magic mount 或 `module/mountify.sh` 挂载（`module/post-fs-data.sh:161-188`）。
- **默认 `whiteout_uninstall`**：除了 whiteout，还对包执行
  `pm uninstall -k --user 0`（`module/nuke.sh:167-175`）。`-k` 保留用户数据。
- **`uninstall_only`**：不创建 whiteout，只执行 `pm uninstall --user 0`
  （`module/nuke.sh:408-419`）。
- 所有 `pm uninstall` 都带 `--user 0`；whiteout 是挂载层隐藏，在挂载可见的
  命名空间内对所有用户生效。

## 2. 重启后的启动流程

1. 用户在 WebUI 选择应用，点 Apply。WebUI 通过 `nsenter` 调
   `nuke.sh`（`webui/src/lib/Cli.ts:17-39`）。
2. `nuke.sh` 做快照、补全 APK 路径、在 `modules_update` 里创建 whiteout，
   安装 dummy zip；dummy 的 `customize.sh` 以 `DUMMYZIP=true` 回调
   `nuke.sh` 重建 whiteout（`module/dummy_zip/customize.sh:8-12`）。
3. 重启进入 `post-fs-data.sh`：boot 计数 +1，挂载 whiteout
   （`module/post-fs-data.sh:11-28, 161-188`）。
4. `service.sh`：等待 `sys.boot_completed=1`，复制当前清单为 `.applied`；
   对“上次 `.old` 有、本次没有”的应用执行 `pm install-existing` + `pm enable`
   （`module/service.sh:113-138`）；对清单中路径为空或位于 `/data/app` 的条目
   再执行一次 `pm uninstall --user 0`（`module/service.sh:149-160`）。
5. 恢复生效点：
   - whiteout 的移除在**下一次开机的挂载阶段**生效，所以恢复后要重启；
   - `pm install-existing` / `pm enable` 在恢复操作当时和 boot_completed 后各试一次；
   - 没有 init.rc、没有 daemon、没有 Android app/broadcast 组件。

## 3. 恢复后会不会自动再次卸载？

- **不会。** 它没有 Nemu 的“开机先恢复、稳定后再卸”循环。
- 用户从 Restore 页移除应用后，包从清单消失；之后开机不会再隐藏/卸载它。
- 要再次隐藏，必须手动在 Home 页重新选中、Apply，然后重启。
- 仍在清单里的应用则每次开机保持隐藏；`service.sh` 在 boot_completed 后
  只对“路径为空/在 `/data/app`”的条目补一次 `pm uninstall --user 0`
  （`module/service.sh:94-97, 149-160`），不是对所有包延迟重卸。
- 因此在模块外手动 `pm install-existing` 一个仍在清单里的应用，下次开机
  仍会被 whiteout 隐藏；pathless/`/data` 条目还会被再次卸载。

## 4. 备份、清单与安全护栏

- **没有 APK 备份**。恢复依赖系统分区原 APK + `pm install-existing`。
  WebUI 的 Backup & Restore 是清单的 `.bak`，不是 APK
  （`webui/src/lib/Cli.ts:55-85`）。
- 清单：`/data/adb/system_app_nuker/nuke_list.txt`，空格分隔
  `<包名> <APK 路径> <标签>`，路径可为空；另有 `.old`、`.boot`、`.applied`、
  `.last` 快照（`module/nuke.sh:12`、`module/service.sh:103-104, 169-190`）。
- split APK：`pm path | head -n1` 只取第一行；若路径在
  `/system/app/<pkg>/` 或 `/system/priv-app/<pkg>/`，隐藏整个包目录；
  共享目录只隐藏单个 APK（`module/nuke.sh:118-130`）。
- 系统应用更新：若 `pm path` 指向 `/data/app`，先
  `pm uninstall-system-updates`，再取 factory APK（`module/nuke.sh:346-354`）。
  这会删除用户的系统应用更新。
- 安全护栏：bootloop 计数/回滚/禁用（`module/post-fs-data.sh:94-153`）；
  ABX `package-restrictions` 的最后救援（`module/post-fs-data.sh:30-66, 128-129`）；
  模块卸载时 `pm install-existing` + `pm enable`（`module/uninstall.sh:7-24`）；
  原子写、路径校验、无 keep-list。

## 5. 与 Nemu v1.20 的对比

| 维度 | systemapp_nuker | Nemu v1.20 |
|---|---|---|
| 主机制 | overlay whiteout + 默认 `pm uninstall -k --user 0` | APK 备份 + `pm uninstall -k --user 0`（v1.21 已改） |
| 恢复来源 | 系统分区原 APK | `/data/adb/nemu/deep_uninstall/apk` 备份 |
| 开机恢复 | 不主动恢复；移除清单 + 重启后不再隐藏 | 每次开机恢复 manifest 中未安装的包 |
| 再次卸载 | 不自动；留清单的包保持隐藏 | boot_completed + 90s 后只重卸本次恢复的包 |
| 用户手动恢复 | 不会被再次卸载（已从清单移除） | 开机时已安装的包不碰，不会被再次卸载 |
| Split APK | 只取第一条 `pm path` | 备份所有 `pm path` 行，base 优先 |
| 系统更新 | 先删系统应用更新 | 保留当前版本备份 |
| 数据 | `-k` 保留数据 | v1.21 已改为 `-k` 保留数据 |

## 6. 可借鉴 / 不要照搬

### 可借鉴

1. **`pm uninstall -k --user 0` 保留用户数据**：v1.21 已实施（App 与模块
   都使用 `-k`）；后续可再提供“彻底清除数据”选项。
2. **恢复后补 `pm enable --user 0`**：Nemu 的恢复路径只有
   `install-existing`/`install-multiple`，若包在卸载前被 disable，恢复后仍可能是禁用。
3. **ABX `package-restrictions` 最后救援**：连续失败阈值后，只对 Nemu 自己
   deep-uninstall manifest 中的包修复 `inst="false"`；需先在 MagicOS 10 实机
   确认 `abx2xml`/`xml2abx` 存在及 fs-verity 行为。
4. **“最后好 boot”基线 + 只回滚新增项**：给 manifest 增加 `.last`/`.boot` 快照。
5. **模块卸载时恢复 deep-uninstalled 包**：Nemu 的 `uninstall.sh` 目前只移除
   hook 和 `auto_restore_ready`，不恢复包；可在移除 bridge 前调用一次恢复。

### 不要照搬

1. 不要把 whiteout/overlay 当主机制：依赖挂载；KSU late-load/硬重启会丢。
2. 不要用 `pm path | head -n1` 的单路径模型；Nemu 的全 split 备份更稳。
3. 不要照搬 `pm uninstall-system-updates` 强制回 factory；会破坏“恢复原版本”。
4. 不要 boot_completed 后无差别重卸；Nemu 的“已安装不碰 + 版本复查”要保留。
5. 不要用风险标签替代 critical 硬阻断；Nemu 的 critical 名单更强。
6. 不要解析 `/data/system/package_cache` 内部格式；OTA 可能清掉。
7. 不要全局文件系统隐藏；Nemu 只处理 user 0。
8. 不要在 boot_completed 后立刻卸载；90 秒稳定窗口更适合 HONOR 启动后的服务拉起。

## 7. README 自述与代码一致性

README 说“用 overlay whiteout 隐藏，APK 留在系统分区，恢复只需重启”
（`README.md:30`），与代码一致；但默认模式还会立刻
`pm uninstall -k --user 0`，README 没有强调这一点。README 说“两次开机失败
就禁用并删 whiteout”，代码实际会先尝试回滚“最后好清单”中的新增项
（`module/post-fs-data.sh:94-119`），再决定禁用（`121-153`），与 README 的
简化描述略有出入。
