# 17 Nemu 重设计：极简文案、完整策略、组件级控制

> 状态：已实施，并经过 2026-11-22/23 真机冒烟与五轮 UI 反馈。日期 2026-11-22。
> 2026-11-23 全量审计补充：`CloudTargets`/`CloudAction`/`CloudState`、
> `AntiFraudTargets`/`AntiFraudController` 等第二套模型已从代码删除；本文
> §2–§8 中对它们的描述只保留为设计过程记录，当前实现以 `TargetCatalog` +
> `TargetPolicy` 为唯一入口。
> 目标版本：Nemu v2（1.3.3 / code 9 之后）。
> 证据口径：PF 编号来自 `docs/12-device-test-log-2026-11-22.md`（1.1/1.2 构建）；
> 当前 1.3.3 有 302 个 JVM 测试，并有多轮整机 UI 冒烟；未执行真实冻结的项仍标“未测”。
> 用户要求：① 文案一行一件事（`软件功能：…`；`暂停：…`；`停用：…`；`隐藏：…`）；
> ② 每个目标补齐完整策略；③ 可停用指定组件；④ 重新构思整体设计。
> 对抗验证：2026-11-22 两路审查（可行性 + 完整性）；4 个阻断项已修订，
> 见 §11。配套目标矩阵：`docs/18-target-matrix-2026-11-22.md`。

---

## 0. 结论

1. **策略模型**：保留现有 5 个 `FreezeStrategy` 值，新增 `StrategyOptions`
   （forceStop / components / appOps / standby / backend）。UI 派生 8 个预设：
   观察、暂停、冻结、组件、停用、隐藏（伪卸载）、限制后台、限制待机。
2. **暂停 ≠ 冻结**：暂停 = `pm suspend`；冻结 = `pm suspend` + `am force-stop`。
   旧 `SUSPEND` 默认 `forceStop=true`，升级后行为不变。
3. **组件级**：每个有组件的目标都有选择器（已选 n/m、逐项勾选、全选/清空）；
   执行只作用于所选；恢复按“所有权并集”，不按当前选择。
4. **文案**：卡片常驻 3 行（功能 / 状态 / 当前策略影响）；策略面板每行
   `策略：影响`；不可用项置灰 + 一行原因；删除长段落、重复解释和元提示。
5. **单一真相源**：`TargetCatalog` 是唯一策略注册表；`CloudTargets` /
   `AntiFraudTargets` 只做迁移与展示适配；同一包不再有两个控制入口。
6. **安全先行**：DenyList 硬门槛、HIDE 进入 guard、AppOps/standby 所有权、
   组件 owned union、暂停基线、后端记录。这些先于任何新按钮落地。

---

## 1. 现状诊断

### 1.1 文案冗余

| 问题 | 证据 |
|---|---|
| `这个应用是做什么的：` 前缀，每卡一次 | `ui/NemuScreens.kt:621-627` |
| 当前策略 `explain()` 在每卡重复 | `ui/NemuScreens.kt:672-676` |
| 确认弹窗再重复 `explain()` + `consequenceFor()` | `ui/NemuScreens.kt:343-361` |
| `点按钮后会先显示“具体会发生什么”` 每卡一次 | `ui/NemuScreens.kt:681-686` |
| 三步说明写四遍（页头、步骤卡、操作卡、每卡） | `NemuScreens.kt:131-134,148-152,215-219,681-686` |
| 概览把开机/关机解释多遍 | `DashboardScreen.kt:553-566,952-966,1044-1052` |
| 检测页每个分类一张长说明卡 | `ui/DetectionScreen.kt:113-118,131-137,146-155,161-166` |
| `暂停` 被写成“不能后台运行”，与平台事实不符 | `NemuScreens.kt:986-988`、`DashboardScreen.kt:541-543` |

平台事实：`pm suspend` 阻止前台交互/通知，**不停止后台 Service/Provider**；
`am force-stop` 只结束当前进程，系统绑定可重新拉起。详见 `docs/10:295-298`、
`docs/12` 的进程观察。

### 1.2 控件能力被隐藏或谎报

| 问题 | 证据 |
|---|---|
| 无 Root 时直接过滤掉组件/停用/隐藏 chip，用户只看到观察/暂停 | `NemuScreens.kt:647-654` |
| 不可用策略直接消失，没有一行原因 | `NemuScreens.kt:647-654` |
| 组件只显示静态文字“会停用 N 个组件”，没有勾选 | `NemuScreens.kt:664-671` |
| 执行时固定遍历 `target.components` 全部组件 | `FreezeStrategyController.kt:75-119` |
| planner 已有 `componentOverride`，但服务没有传 | `FreezeStrategyPlan.kt:42-45`、`TargetFreezeService.kt:215` |
| hiview 家族仍显示暂停，但本机平台拒绝 | `TargetCatalog.kt:366-457`、PF9 |
| securityserver/awareness 目录可暂停，反诈模块写“禁止整包” | `TargetCatalog.kt:679-718`、`AntiFraud.kt:71-90` |
| securitypluginbase 目录可停/隐，反诈写“仅观察”，且它在 DenyList | `TargetCatalog.kt:719-743`、`AntiFraud.kt:91-97`、`DenyList.kt:21` |
| cipservice 冻结页可停/隐，检测页写仅观察 | `TargetCatalog.kt:585-590`、`CloudTargets.kt:51-58` |
| DenyList 存在，但统一执行器 `TargetFreezeService` 不查 | `DenyList.kt:10-30`、`TargetFreezeService.kt` |

**用户感知“只有只观察模式”的两个主因**：

1. 无 Root 时 UI 把组件/停用/隐藏 chip 直接过滤掉（`NemuScreens.kt:647-654`）；
2. 不可用项被隐藏，用户看不到“为什么不可用”。

真正严格只观察的只有：`powergenie`（平台拒绝组件停用，PF14）、
`pushagent`（DenyList，通知通道）、`securitypluginbase`（DenyList + 反诈
“仅观察”）、`cipservice`（角色未确认，待证据）。见 §4.2。

### 1.3 同一包多入口与状态口径不一致

- 同一包同时出现在冻结页、解冻页、设置→高级控制（反诈）、检测页。
- 已冻结数量有三套口径：`applied`、`hasFrozenState`、`freezeStates`；
  云控目标又从 `freezeStates` 排除（`DetectionReader.kt:81-84`）。
- 冻结页 Checkbox 直接写持久 `enabled`，不是“本次选择”；
  解冻页 Checkbox 是局部选择。两页同名控件语义不同（`NemuScreens.kt:113-119,734`）。
- 同一“计划”同时服务手动执行和开机冻结，无法只改其中一套（`BootFreezeWorker.kt:157-160`）。

### 1.4 安全恢复缺口

| 缺口 | 证据 |
|---|---|
| HIDE 没有 boot guard 恢复（脚本无 `pm unhide`） | `tools/nemu-boot-autostart/boot_guard.sh` |
| AppOps/standby 无原始值、无所有权，恢复无条件 `allow/active` | `AntiFraudPolicy.kt:54-73,103-135` |
| DISABLE_USER 关机语义分裂：App 不恢复，模块脚本 `pm enable` | `ShutdownSafety.kt`、`ShutdownScript.kt:80-83` |
| 暂停没有“执行前已 suspended”基线，可能误认领 | `TargetFreezeService.kt:164` |
| 组件恢复只遍历 `target.components`，OTA 删除组件会成孤儿 | `TargetFreezeService.kt:610-699` |
| 未记录执行后端，关机可能换后端导致 unsuspend 失败 | `BackendManager.kt:40-49` |
| 关机恢复整体 8 秒预算，逐组件逐命令执行 | `ShutdownSafety.kt:24-26` |

---

## 2. 设计原则

1. **一行一件事**：功能一行、策略影响一行、不可用原因一行。没有长段落。
2. **一个目标一个控制面**：策略、组件、执行、恢复都在同一张目标卡完成；
   检测页只读并深链回目标卡。
3. **能力透明**：可做、不可做、为什么不可做，全部可见；不可用项置灰，
   不消失、不假装能点。
4. **默认安全，专家可选**：红色包/DenyList 默认禁止；专家模式只解锁
   非 DenyList、非平台拒绝、非真实卸载的危险项，绝不解锁 DenyList。
5. **选择 ≠ 执行**：勾选只改计划；执行前 3 行确认；执行后显示实际状态。

---

## 3. 策略模型

### 3.1 最终术语

`冻结` 在 UI 中是**具体策略**（暂停 + 结束进程），不再是所有动作的总称。
页面/按钮用“应用”，避免与策略“冻结”同名。

| 用户词 | 内部表示 | apply 命令 | 用户可见效果 | 恢复 | 权限 |
|---|---|---|---|---|---|
| 观察 | `OBSERVE` | 无 | 不改动，只显示状态 | 无需 | 任意 |
| 暂停 | `SUSPEND` + `forceStop=false` | `pm suspend --user 0 pkg` | 不能打开或显示通知；后台服务仍可能被唤起 | `pm unsuspend`，仅 Nemu 所有 | Root 实测；Shizuku 未实测 |
| 冻结 | `SUSPEND` + `forceStop=true` | `pm suspend` + `am force-stop` | 暂停并结束当前进程；系统可能重新拉起服务 | `pm unsuspend` | 同上 |
| 组件 | `COMPONENT` + 选定组件 | 所选 `pm disable --user 0 pkg/cls`；可选 `am force-stop` | 只停用选中的组件；应用本体仍可打开 | 按原状态 `pm enable` / `pm default-state`，仅 owned | Root |
| 停用 | `DISABLE_USER` | `pm disable-user --user 0 pkg` | 整包禁用，入口消失；设置显示“已停用”；数据保留 | `pm enable`，仅 owned 且原 enabled=true | Root |
| 隐藏（伪卸载） | `HIDE` | `pm hide --user 0 pkg` | 对系统不可见，像已卸载；APK/数据保留 | `pm unhide`，仅 owned | Root |
| 限制后台 | `OBSERVE` + `appOps=true` | `appops set … RUN_ANY_IN_BACKGROUND ignore` | 前台可打开；后台运行被拒绝 | 原始 mode，仅 owned | Root（首版） |
| 限制待机 | `OBSERVE` + `standby=true` | `am set-standby-bucket … restricted` | 后台任务/网络被严格限制 | 原始桶，仅 owned | Root（首版） |

**命名规则**：

- UI 动作统一用“解冻”；文档中的“恢复”与“解冻”同义（指按所有权取消暂停/
  重新启用/解除隐藏/恢复组件）。状态词用“已冻结/已暂停/已停用/已隐藏”。
- `停用` 只指 `disable-user`；`隐藏（伪卸载）` 只指 `pm hide`。
  删除“停用（伪装卸载）”“隐藏（彻底伪装）”两种旧说法。
- 组件级只能“停用组件”，不能写“暂停组件”：Android 没有按组件 suspend 的命令。
- `冻结` 是暂停 + 结束进程，不是新的系统 API；UI 可以把它作为独立预设。

### 3.2 为什么保留五值枚举

新增 `FreezeStrategy.PAUSE`/`FREEZE` 枚举值会带来三个高风险：

1. 旧版本 `FreezeStrategy.valueOf()` 失败会静默回退 `defaultStrategy`
   （`data/Settings.kt:78-83`），可能从“暂停”跳到“组件/观察”。
2. `SafetyTargets`、`ShutdownScript`、`DetectionReader` 都按五值写 `when`；
   漏一个分支就可能在关机时不解冻。
3. 枚举组合无法表达“组件子集 + forceStop + AppOps + standby”。

采用：**五值存盘 + `StrategyOptions` JSON + UI 派生 Preset**。

```kotlin
enum class StrategyPreset {
    OBSERVE, PAUSE, FREEZE, COMPONENT,
    DISABLE_USER, HIDE,
    RESTRICT_BACKGROUND, RESTRICT_STANDBY
}

data class StrategyOptions(
    val schema: Int = 1,
    val forceStop: Boolean = true,          // SUSPEND / COMPONENT
    val components: Set<String>? = null,    // null = 未配置；COMPONENT 运行时 fail-closed
    val appOps: Boolean = false,
    val standby: Boolean = false,
    val backend: String? = null             // root / shizuku
)
```

存储（唯一来源）：

- `strategy_<pkg>`：仍存 `SUSPEND|COMPONENT|DISABLE_USER|HIDE|OBSERVE`。
- `strategy_opts_<pkg>`：版本化 JSON，字段
  `schema/forceStop/components/appOps/standby/backend`。
  **不再新增** `strategy_components_*` / `strategy_backend_*`；旧键迁移后删除。
- 缺失 options：旧 `SUSPEND` = `forceStop=true`；旧 `COMPONENT` 在迁移时
  物化为当时 `target.components` 的显式集合。
- 解析失败/版本未知：告警 + 回退保守默认；若策略是 `COMPONENT`，
  `components` 视为空并 **fail-closed**（不执行、不写 applied、要求重选）。
- 修改选项必须在任何命令之前 `commit()` 同步落盘，与
  `strategy_applied_<pkg>` 的耐久性契约一致。

派生：

```kotlin
fun presetOf(strategy: FreezeStrategy, o: StrategyOptions): StrategyPreset = when {
    strategy == SUSPEND && o.forceStop -> FREEZE
    strategy == SUSPEND -> PAUSE
    strategy == COMPONENT -> COMPONENT
    strategy == DISABLE_USER -> DISABLE_USER
    strategy == HIDE -> HIDE
    strategy == OBSERVE && o.appOps -> RESTRICT_BACKGROUND
    strategy == OBSERVE && o.standby -> RESTRICT_STANDBY
    else -> OBSERVE
}
```

限制后台/待机可与任一主策略叠加；UI 显示主策略 + “限制后台/待机”徽标。

### 3.3 迁移映射

| 旧状态 | 迁移后 | 行为 |
|---|---|---|
| `strategy_<pkg>=SUSPEND`，无 opts | `SUSPEND` + `forceStop=true` | 不变（仍 suspend + force-stop） |
| `strategy_<pkg>=COMPONENT`，无 opts | `COMPONENT` + `components=当时 target.components 显式集合` + `forceStop=true` | 不变（仍全部登记组件） |
| `strategy_<pkg>=DISABLE_USER/HIDE/OBSERVE` | 原值 + 默认 opts | 不变 |
| 旧云控 `cloud_target_<pkg>` | 同步为 `strategy_<pkg>` + `enabled` | 一次性迁移 |
| 旧 hiview 云控 `SUSPEND` | `COMPONENT`，提示“本机暂停被拒绝，已转组件模式” | 有意变化 |
| 旧 cipservice `OBSERVE_ONLY` | `OBSERVE`，补证据后再开放 | 有意变化 |
| 旧 `antifraud_numberidentity_mode` | canonical `strategy_<pkg>`；旧键只读一版 | 不变 |
| 旧 securityserver/awareness `restrict=true` | `OBSERVE` + `appOps=true` + `standby=true` | 命令相同；恢复改为所有权门控 |
| 旧 AI `ai_target_<pkg>=true` | 无 `strategy_` 时写 `SUSPEND` + `forceStop=true` + `enabled=true` | 不变 |

迁移入口：`TargetPolicy.normalizePolicy()`，在 `NemuApplication` 启动时执行一次，
用 `migration_policy_v2=1` 防重复。

**能力收回顺序**：先 `legacyRestoreOwnedStates()` 恢复旧版已应用的
DISABLE_USER/HIDE/COMPONENT，再执行 `normalizePolicy()` 收回不支持的能力；
`restore` 永远按所有权集，不看当前 `supportedStrategies`。否则升级后会留下
无法恢复的停用/隐藏状态。

### 3.4 组件级选择

必须分开三个集合：

| 集合 | 来源 | 用途 |
|---|---|---|
| `target.components` | `TargetCatalog` 登记 | UI 可选项；不自动成为所有权 |
| `StrategyOptions.components` | `strategy_opts_<pkg>`；null=未配置/fail-closed | 本次 apply 对哪些组件下命令 |
| `ownedComponents` | `strategy_component_original_*` + `strategy_component_owned_*` | 恢复哪些；与当前选择解耦 |

规则：

- 恢复永远用 `target.components ∪ ownedComponents`，不能用当前选择。
- 选择集为空：保存拒绝，执行 fail-closed，UI 显示“至少选择 1 个组件”。
- 选择集里出现目录已删除的类名：apply 忽略并记录；restore 仍按 owned 恢复。
- OTA 新增组件：不自动加入已有选择；UI 显示“新增组件未选”。
- 新建/首次选择组件：UI 默认全选当前目录组件；应用前把显式集合写入
  `strategy_opts.components`，不把 null 写盘。
- 取消勾选一个已由 Nemu 停用的组件：先恢复 + 清基线和 marker，再保存新选择；
  恢复失败则保留旧选择和基线。
- `TargetComponent.risk` 当前从未赋值（默认 YELLOW）；补 RED/YELLOW 前 UI 统一
  显示“未分级”，不得假装有风险分级。
- 非 `COMPONENT` 策略下组件行只读，不显示“会停用 N 个组件”。
- 已生效 `COMPONENT` 时禁止编辑选择；先解冻或“解冻后重应用”。
- 组件选择器增加“同时结束进程”（forceStop）开关。
- 他人已停用的组件不可接管；只有 owned 组件提供“恢复此组件”。
- COMPONENT/DISABLE/HIDE 都要求可写 Root marker backend；组件停用前新增
  `beforeComponentDisable` 回调，写入失败在命令前中止。

执行/恢复链路要改的函数：

| 文件:函数 | 改动 |
|---|---|
| `data/Settings.kt:strategy` | 新增 `strategyOptions` / `setStrategyOptions` |
| `data/Settings.kt:componentOriginalState` | 复用；新增选择集键 |
| `policy/TargetPolicy.kt` | 新增 `options`、`preset`、`effectiveComponents`、`ownedComponents`、`setPreset` |
| `policy/FreezeStrategyPlan.kt:apply` | 增加 `options`；`forceStop` 从 options 读 |
| `policy/FreezeStrategyController.kt:apply` | 增加 `componentsOverride`；只遍历子集 |
| `policy/FreezeStrategyController.kt:restore` | 改为 owned union；去掉空 components 早退 |
| `policy/TargetFreezeService.kt:apply` | 解析 options/选择集，传给 controller |
| `policy/TargetFreezeService.kt:restoreOwnedComponents` | owned union + 孤儿合成 |
| `policy/ShutdownScript.kt` | owned union；补 AppOps/standby/隐藏恢复 |
| `ui/CatalogModels.kt` | 增加 selected/owned 计数与逐组件状态 |
| `MainActivity.kt:buildCatalog` | 用选择集读组件状态 |
| `ui/NemuScreens.kt:FreezeTargetCard` | 组件选择器；空选择禁用 |
| `detection/DetectionReader.kt` | 同一选择集；输出计数 |

### 3.5 能力与原因模型

不要让 UI 自己拼 `filter { validate == null }`。新增一个统一模型：

```kotlin
enum class Evidence { DEVICE_VERIFIED, CODE_ONLY, PLANNED, PLATFORM_REJECTED, DENY_LIST, NOT_APPLICABLE }

data class PresetCapability(
    val available: Boolean,
    val requiresRoot: Boolean,
    val evidence: Evidence,
    val reason: String? = null
)
```

`TargetPresetPolicy.capability(target, preset)` 默认从
`risk + supportedStrategies + components + allowAppOps/allowStandby + DenyList`
推导；`presetOverrides` 只写例外（powergenie、pushagent、hiview、
securityserver、awareness、securitypluginbase、cipservice）。

UI 三态：

- 可用（实测）：正常 chip。
- 可用（未测）：chip + “未测”角标；确认弹窗多一行“本机未验证，可能无效”。
- 不可用：chip 置灰 + 一行原因，**不得消失**。

**DenyList 口径（D-05）**：

- 整包 `disable-user` / `hide`：默认绝对禁止；唯一显式例外是
  `numberidentity`（PF12 已验证停用往返）。
- 整包 `suspend` / `freeze`：默认禁止；只允许注册表显式例外且有 PF 证据：
  `aipluginengine`、`assistant`、`magicvoice`、`brain`（PF16）。
- `powergenie`、`pushagent`：无例外。
- 删除“专家模式解锁 DenyList”；专家模式只解锁非 DenyList、非平台拒绝的危险项。
- `isAllowed` 必须调用 `validate`（或共用同一 gate）；`setStrategy`、检测、
  执行全部走它，避免保存一个执行层会拒绝的策略。

**预置状态**：执行前已 suspended/disabled/hidden/appops-restricted 的目标，
显示“已停用·非本应用 / 他人隐藏 / 已被他人限制”，不自动认领；停用/限制只提供
“接管”二次确认，结果写“已存在，未接管”。

**guardHealth**：模块未安装、版本不含 `pm unhide`/appops/standby、或最近未运行时，
UI 显示“救援未就绪”；AppOps/standby 置灰；HIDE 保留 PF20/21 但标注
“救援依赖模块”。

### 3.6 权限门槛

| 能力 | Root uid 0 | Shizuku ADB uid 2000 | Shizuku Root uid 0 |
|---|---|---|---|
| `pm suspend` / `unsuspend` | 实测 PF2/3/13/16 | 代码可达，未实测 | 未单独验证 |
| `am force-stop` | 统一路径使用 | 代码可达，未实测 | 未验证 |
| `pm disable` 组件 | 实测 PF11/14/16 | 否（Android 12+ 限制） | 未验证 |
| `pm disable-user` | 实测 PF12 | 否 | 未验证 |
| `pm hide` / `unhide` | 实测 PF20/21 | 否（需 MANAGE_USERS） | 未验证 |
| 写 `/data/adb` guard marker | 是 | 否 | 否 |

UI 在无 Root 时仍显示组件/停用/隐藏 chip，置灰并写“需要 Root”；不得直接过滤。

### 3.7 安全恢复矩阵

| 预设 | 手动解冻 | 正常关机 | 模块 `restore_frozen.sh` | 开机残留 | bootloop rescue |
|---|---|---|---|---|---|
| 暂停/冻结 | 是 | 是 | 是 | 是 | 是 |
| 组件（子集） | 是（owned union） | 是 | 是（owned union） | 是 | 是（marker 天然支持子集） |
| 停用 | 是 | 设计上不恢复，保留策略 | **改为不恢复**（删除无条件 `pm enable`） | 仅策略关闭时清孤儿 | 是 |
| 隐藏 | 是 | 是 | 是 | 是 | **当前缺失，必须补 `pm unhide`** |
| 限制后台 | 是 | 仅清孤儿 | 仅清孤儿 | 仅清孤儿 | 是（新增 marker） |
| 限制待机 | 是 | 仅清孤儿 | 仅清孤儿 | 仅清孤儿 | 是（新增 marker） |

持久型状态（停用/AppOps/standby）不应每次关机无条件撤销，否则策略会变成
“仅本次开机有效”。主动解冻、策略关闭、bootloop 救援才恢复。这是对
“安全路径覆盖所有策略”的解释：**覆盖 = 有所有权、有恢复路径、guard 可救援**，
不是每次关机都无条件撤销。需用户确认（D-06）。

- HIDE 恢复以 `ownsHidden + hidden_by_nemu marker` 为条件，不依赖 `applied`。
- 按 `strategy_opts.backend` 分组恢复，每组一次脚本；8 秒预算作为可测试验收。
- **预览不阻断安全恢复**：开机残留/关机恢复始终真实执行；预览只约束用户主动 apply。

### 3.8 技术边界（必须诚实显示）

1. `pm suspend` 只能整包，不能按组件；组件级只有 `pm disable`。
2. `pm suspend` 不停止后台 Service/Provider；`force-stop` 非持久。
3. `pm hide` / `pm disable-user` / `pm disable` 需要 Root（Shizuku ADB 不行）。
4. powergenie 的组件 `pm disable` 被平台拒绝（PF14：`new state: default`）。
5. hiview 家族整包 `pm suspend` 被平台拒绝（PF9：`new suspended state: false`）。
6. 原生/HAL 进程（thermal HAL、perfservice、hiview native、vendor iaware service）
   不在 `pm` 冻结模型内，只能观察。
7. **不提供真实卸载**；`伪卸载` 只指 `pm hide`。
8. 整包暂停/冻结/停用/隐藏对所有已安装目标开放，包括红色包和 DenyList；
   只显示高风险提醒与确认框，不再硬性禁止。平台拒绝、未登记组件、缺 Root
   仍是真实限制。
9. 不提供真实卸载；`伪卸载` 只指 `pm hide`。

---

## 4. 目标能力矩阵（摘要）

完整 41 目标 × 8 策略表见 `docs/18-target-matrix-2026-11-22.md`。

### 4.1 分类统计

- 41 个内置目标；64 个登记组件（powergenie 3 个平台拒绝，61 个可进选择器）。
- 21 个目标有组件；20 个可用组件选择器；powergenie 只展示、不可操作。
- 五策略全开（含组件）：11 个；其中 hiview 家族暂停/冻结被平台拒绝，实际可用 4 项
  （组件/停用/隐藏/观察）。
- 严格只观察：`powergenie`、`pushagent`、`securitypluginbase`（DenyList +
  反诈口径）、`cipservice`（角色未确认，待证据）。
- 后台/待机限制型：`securityserver`、`awareness`。
- 仅组件型：`systemserver`、`systemmanager`、`iaware`、`chr`。

### 4.2 保护与只观察清单

| 目标 | 原因 | 证据 |
|---|---|---|
| `com.hihonor.powergenie` | 平台拒绝组件停用；整包冻结破坏温控/充电 | PF14 |
| `com.hihonor.android.pushagent` | DenyList；系统推送通道 | `DenyList.kt:25` |
| `com.hihonor.securitypluginbase` | DenyList + 反诈“仅观察” | `DenyList.kt:21`、`AntiFraud.kt:91-97` |
| `com.hihonor.cipservice` | 角色未确认；默认仅观察，补 `dumpsys`/bind 证据后再开放 | `CloudTargets.kt:51-58` |

不是严格只观察、只是默认 OBSERVE 的目标：`upgradeguide`、`visionengine`、
`lens`、`magichome`、`hiboard`、`detectrepair`、`assetsync`、
`assetsyncservice`、`systemserver`、`systemmanager`、`securityserver`、
`awareness`、`hnaps`、`msdp`。它们必须显示可点击的策略 chip。

### 4.3 能力收回收敛

| 目标 | 当前目录 | 设计后 | 原因 |
|---|---|---|---|
| `securityserver` | 观/暂 | 观 + 限制后台/待机 | 反诈模块禁止整包 |
| `awareness` | 观/暂 | 观 + 限制后台/待机 | 同上 |
| `securitypluginbase` | 观/暂/停/隐 | 仅观（D-05：DenyList 无例外） | DenyList + 反诈仅观察 |
| `cipservice` | 观/暂/停/隐 | 仅观；补 `dumpsys`/bind 证据后再开放 | 角色未确认 |
| `hiview` 三件套 | 暂/组/停/隐/观 | 组/停/隐/观；暂停/冻结置灰 | PF9 平台拒绝 |
| `powergenie` | 仅观，但登记 3 组件 | 仅观；组件只展示不可操作 | PF14 平台拒绝 |

### 4.4 待补组件（需 `dumpsys package` + 绑定测试）

- 0 组件但可补：`yoyoappsug`、`yoyocards`、`mageruntime`、`magictranslate`、
  `magicavatar`、`magichome`、DM 三件套、`assetsync`、`assetsyncservice`、
  `cipservice`、反诈四包、`hnaps`、`msdp`。
- 已有候选未登记：OUC `CotaService`/`PullUpProcessReceiver`、
  aipluginengine `DataUpdateReceiver`/`BrainFenceNotifyService`、
  assistant `AntiProcessDeathService`、hiview `HiViewSysReceiver`。
- 不得凭 dumpsys 存在就登记；必须做绑定测试并写证据。

---

## 5. 文案体系

### 5.1 硬规则

| 类型 | 模板 | 上限 |
|---|---|---|
| 软件功能 | `软件功能：{功能}` | ≤24 汉字，一行，无句号 |
| 策略影响 | `{策略}：{影响}` | ≤28 汉字，一行 |
| 不可用 | `{策略}：不可用（{原因}）` | 总 ≤24 汉字；原因 ≤10 |
| 状态 | 白名单词 | ≤12 汉字 |
| 按钮 | `{动词}{对象}` | ≤10 汉字 |
| 确认弹窗 | `执行：…` / `影响：…` / `恢复：…` | 3 行，每行 ≤24 |
| 结果 | `{成功/失败}：{N} 项；{原因}` | ≤20 汉字 |

计数：汉字与全角符号按 1 个词元、拉丁/数字/空格按 0.5 个词元计（显示宽度）；
另设总字符硬上限（功能 32、影响 36、不可用 28）。L1/L2 禁止句号；
超过词元上限或 >2 句视为长解释。

**禁用词/句式**：这个应用是做什么的、为什么只能观察、选择冻结方式、
点按钮后会先显示、具体会发生什么、最温和、彻底伪装、伪装卸载、更彻底、
挂起、DRY-RUN、SUSPEND/DISABLE_USER/HIDE/COMPONENT/OBSERVE、force-stop、
detectionContent、SharedPreferences、数据不丢、该应用。

### 5.2 通用策略行

| 策略 | 文案 |
|---|---|
| 观察 | 观察：不改动，只显示状态 |
| 暂停 | 暂停：不能打开或显示通知；后台服务仍可能被唤起 |
| 冻结 | 冻结：暂停并结束当前进程；系统可能重新拉起服务 |
| 组件 | 组件：只停用选中的组件；应用本身仍可打开 |
| 停用 | 停用：整包禁用，入口消失；数据保留 |
| 隐藏 | 隐藏：伪装成未安装；APK 和数据保留 |
| 限制后台 | 限制后台：可以打开；后台运行被系统拒绝 |
| 限制待机 | 限制待机：后台任务和网络被严格限制 |

### 5.3 状态白名单

`运行中、未冻结、已勾选、待执行、已暂停、已暂停·进程存活、已冻结、
组件停用、组件启用、已停用、已隐藏、他人隐藏、隐藏待确认、部分生效、
不一致、状态未知、读取失败、未安装、仅观察、已恢复`

删除 `CatalogModels.kt:47-73` 的长状态句。

### 5.4 示例

目标卡（折叠态）：

```text
[icon] 软件更新                         黄 · 已冻结
软件功能：系统更新检查、下载与安装
冻结：暂停并结束当前进程；系统可能重新拉起服务
[更改策略]  [解冻]
```

策略面板：

```text
策略 · 软件更新
( ) 观察   不改动，只显示状态
( ) 暂停   不能打开或显示通知；后台服务仍可能被唤起
(•) 冻结   暂停并结束当前进程；系统可能重新拉起服务
( ) 组件   只停用选中的组件；应用本身仍可打开
( ) 停用   整包禁用，入口消失；数据保留
( ) 隐藏   伪装成未安装；APK 和数据保留
[应用]
```

组件选择器：

```text
选择组件 · 软件更新
已选 3/8 · 只停用勾选组件
[✓] DownloadReceiver        下载/安装广播
[✓] CotaReceiver            COTA 广播
[ ] FirmwareInstallReceiver 固件安装广播
...
[全选] [清空] [确定]
```

确认弹窗：

```text
冻结「软件更新」？
执行：暂停并结束当前进程
影响：系统更新检查与下载停止；已装系统不受影响
恢复：解冻后恢复检查与下载
[取消] [确认冻结]
```

应用页 · 已生效筛选：

```text
已生效 1 · 已选 1
软件更新  已冻结
恢复：取消暂停，应用可重新启动
[解冻所选 1 个]
```

检测页：

```text
检测 · 只读
[冻结 1] [云控 6] [系统 4] [启动 1]
软件更新  com.hihonor.ouc    已冻结
策略：冻结   实际：已暂停，进程未存活
[去应用页查看]
```

### 5.5 自动化文案测试

| ID | 断言 |
|---|---|
| T01 | 41 个内置目标 `functionLine` 非空；自定义模板全覆盖 |
| T02 | 功能行 ≤24 汉字、无句号、无换行、不以“这个应用”开头 |
| T03 | 影响行 ≤28 汉字，正则 `^(观察|暂停|冻结|组件|停用|隐藏|限制后台|限制待机)：` |
| T04 | 不可用行正则 `^(观察|暂停|冻结|组件|停用|隐藏|限制后台|限制待机)：不可用（[^）]{1,12}）$` |
| T05 | 每个目标 × 8 个预设（含限制后台/待机）都要么可用，要么有 unavailableReason，禁止静默缺失 |
| T06 | 状态词白名单，≤12 汉字，禁止英文枚举/DRY-RUN |
| T07 | 弹窗 ≤3 行，前缀 ∈ {执行,影响,恢复,计划} |
| T08 | 禁用词命中数为 0 |
| T09 | `strategy.name` / `previous.name` 不直接进 UI |
| T10 | `description` 不被 Composable 渲染；`explain()`/`genericConsequence()`/`plainActualText()` 无调用点 |
| T11 | 标“已验证”必须映射 PF 编号，否则只能标“未测” |
| T12 | “伪卸载”只与“隐藏”同句；禁止伪装卸载/彻底伪装/挂起/最温和/更彻底 |

### 5.6 结果与全屏文案

- 新增 `ResultReason` 枚举：基线读取失败、原本已停用、隐藏读回失败、
  AppOps/standby 复核失败、marker 写入失败、guard 未就绪、预检失败、
  部分成功、平台拒绝、DenyList 拒绝。服务只返回枚举 + 参数，UI 用中文一行表。
- 英文枚举、命令、`still suspended` 等只进日志；T08/T09 扩展到结果字符串。
- 建 `CopyMap` 覆盖目标卡以外：设置/日志/救援/开机/安全解冻/ADB/COTA 的
  `SectionCard` 副标题、确认弹窗、空状态、结果；逐项标“保留/改写/删除”。
- 屏幕级测试：每屏无长段落（>28 词元或 >2 句的连续文本）；确认弹窗 ≤3 行。

---

## 6. UI/UX

### 6.1 信息架构：4 页，分阶段落地

推荐终态：

```text
概览   只读状态 + 全局开关
应用   一行一包：策略、组件、计划、实际状态、执行/恢复
检测   只读镜像 + 深链回应用页
设置   执行模式、权限、开机与恢复、日志、外观、高级
```

**为什么合并冻结/解冻**：两页渲染同一批目标，`FreezeTargetCard` 与解冻行是
两套逻辑，文案、状态和计数必然分叉；41 个目标下用户还要记住“冻结去哪页、
恢复去哪页”。合并后“已生效”只是一个筛选，恢复是行内动作。

**分阶段**：

1. **v1.4.0（阶段 0–2）**：保留 5 页，先做安全底座、文案、策略矩阵。
2. **v1.5.0（阶段 3）**：组件子集；仍在 5 页。
3. **v2.0.0（阶段 4）**：合并为 4 页。若 5 页是硬约束，第 5 页放“计划”，
   不要保留冻结/解冻双页。

### 6.2 三个集合与“生效”的唯一定义

| 集合 | 持久 | UI 位置 | 作用 |
|---|---|---|---|
| 策略 | 是 | 目标展开后的策略列表 | 决定执行什么 |
| 开机计划 | 是 | 目标展开后的计划开关 + 概览总开关 | 决定下次开机自动执行哪些 |
| 本次选择 | 否（仅页面会话） | 批量模式的复选框 | 只决定本次批量对象 |

- 手动执行不要求“已加入计划”；计划只影响开机自动执行。
- 复选框只在批量模式出现，不写设置、不下发命令。
- “生效”只指：Nemu 有所有权 + PackageManager/root 读回确认 + 不是执行前
  就已存在的他人状态。概览、应用筛选、检测页共用这一份计算。
- 全局只显示两个数字：**生效 N · 计划 M**。

### 6.3 目标卡（折叠态）

```text
┌────────────────────────────────────────────┐
│ [图标]  软件更新                     中风险 │
│         com.hihonor.ouc                     │
│ 软件功能：系统更新检查、下载与安装            │
│ [已暂停]  策略：暂停        [计划]      [›] │
└────────────────────────────────────────────┘
```

- 图标 42dp；名称单行；包名 11sp 中部省略；功能行单行（>22 汉字在文案阶段压缩）。
- 状态 chip 是**实际状态**，不是策略；白名单见 §5.3。
- `策略：暂停` 是已选策略；`[计划]` 仅在开机计划为真时显示。
- 异常：`[不一致]` 加警告描边；`[已停用·非本应用]` / `[他人隐藏]` 不给恢复；
  `[读取失败]` 行尾给重试。
- 手风琴：同一时间只展开一张卡，避免策略影响行在屏幕上重复多份。
- 已生效且策略一致时只显示一个联合状态 chip（如 `已冻结`），不再同时显示
  `策略：冻结`；未生效才显示 `策略：…`。影响行只在展开态出现一次，避免
  “已暂停 + 策略：暂停 + 暂停：…” 三重重复。

### 6.4 展开态：策略列表

```text
策略 · 软件更新
────────────────────────────────────────────
( ) 观察   不改动，只显示状态
( ) 暂停   不能打开或显示通知；后台服务仍可能被唤起
(•) 冻结   暂停并结束当前进程；系统可能重新拉起服务
( ) 组件   只停用选中的组件；应用本身仍可打开
( ) 停用   整包禁用，入口消失；数据保留
( ) 隐藏   伪装成未安装；APK 和数据保留
────────────────────────────────────────────
附加限制  [ ] 限制后台    [ ] 限制待机
组件      [已选 2/8 · 修改]
计划      [ ] 加入开机自动应用
────────────────────────────────────────────
[恢复]                              [执行]
```

规则：

1. **所有策略始终可见**：可用、不可用、未测三种状态。不可用项保留在列表里，
   置灰 + 一行原因；禁止再 `filter` 隐藏。
2. 每行 = 单选 + 名称 + 一行影响；用户不用逐个点开比较。这直接对应用户要求的
   `暂停：影响…` / `停用：…` / `隐藏：…`。
3. **已生效时**：除当前策略外其余行禁用，原因“已生效，先恢复”；底部只保留
   `[恢复]`。不再用一条 toast 阻止。
4. **纯观察**（无附加限制）：不显示执行按钮，一行“仅观察，不下发命令”；
   计划开关禁用。观察 + 限制后台/待机是可执行预设，显示执行/计划。
5. **未测**：行可点，右上角“未测”角标；确认弹窗加一行“本机未验证，可能无效”。
6. **不可用原因**：DenyList / 红色包 / 需要 Root / 无登记组件 / 平台拒绝 /
   恢复路径未就绪；原因来自 `PresetCapability`，不在 UI 拼字符串。
7. 组件行展开选择器；空选择时 `执行` 禁用。
8. 危险策略（停用/隐藏/红色包整包冻结）需要二次确认。
9. 限制后台/待机是独立可执行预设：卡片显示“观察 + 限制后台”，有执行、
   计划、确认、恢复；不能因主策略是 OBSERVE 而禁用。
10. 组件行只在策略=组件时可编辑；其他策略显示“仅组件策略生效”，不显示
    “会停用 N 个组件”。

> 紧凑变体：6 个策略用 FlowRow chips，选中项下方只显示一行影响。适合屏幕
> 高度紧张时；默认仍用上面的完整列表，因为它满足“每个策略都有影响一行”。

### 6.5 组件选择器

```text
选择组件 · HiView 遥测采集
已选 2/4 · 只停用勾选组件
[全选] [清空]
────────────────────────────────────────────
[✓] NcDftService            未分级 · 启用
    诊断服务
[✓] LogCollectService       未分级 · 启用
    日志采集服务
[ ] CloudConfigUpdate…      未分级 · 启用
    云端配置更新接收器
[ ] DiagKitService          未分级 · 已停用（本应用）
    诊断服务 · 残留，不在当前选择
    [恢复此组件]
────────────────────────────────────────────
取消勾选会在保存前恢复该组件
至少选择 1 个组件              [取消] [确定]
```

- 每行：复选框、组件短名、角色（≤14 汉字）、风险文字、实际状态。
- 实际状态：启用 / 已停用（本应用）/ 已停用（他人）/ 未知。
- 已停用但不在当前选择：标“残留”，提供“恢复此组件”。
- 全选/清空只改选择，不执行；空选择“确定”禁用。
- 组件风险当前全部默认 YELLOW；补 RED/YELLOW 前统一显示“未分级”，不假装有分级。
- 只允许“停用选定组件”，禁止“暂停组件”字样。
- 只有 owned 组件显示“恢复此组件”；他人停用显示“非本应用状态，不接管”。
- 增加“同时结束进程”（forceStop）开关。
- 已生效 COMPONENT 时禁止编辑选择；先解冻或“解冻后重应用”。
- 非 COMPONENT 策略下组件行只读。

### 6.6 批量模式与结果

批量是显式模式：点顶部“批量”或长按卡片进入；普通浏览没有复选框。

```text
┌ 应用 · 已选 2 ───────────────────── [完成] ┐
│ [✓] 软件更新        [已暂停]               │
│ [ ] HiView          [组件 2/4]             │
│ [ ] 荣耀推送        [仅观察]               │
├────────────────────────────────────────────┤
│ 1 项不可执行：仅观察                       │
│ [恢复 2]                        [执行 1]   │
└────────────────────────────────────────────┘
```

- 不可执行/不可恢复的行仍显示，复选框禁用 + 一行原因；不能静默排除。
- 底部两个按钮分别计数；退出批量清空选择。
- 批量确认按策略分组，危险组置顶，相同影响只写一次：

```text
执行 3 项？
危险（1） 系统应用更新器 · 停用
普通（2） 软件更新 · 暂停；YOYO 建议 · 暂停
跳过（1） 荣耀推送 · 仅观察（DenyList 保护）
[展开逐项] [取消] [确认执行 3]
```

- 成功：不弹模态，短提示 + 行状态刷新。
- 部分成功/失败：结果弹层按目标列出成功/失败/跳过 + 一行原因，
  提供“重试失败项”“查看日志”。

### 6.7 恢复流程

入口：应用页“已生效”筛选的行内 `[恢复]`、批量 `[恢复 N]`、概览“恢复全部生效项”。

```text
恢复：暂停「软件更新」？
影响：取消暂停，应用可重新启动
计划：同时关闭（下次开机不再执行）  [开关]
[取消] [确认恢复]
```

- “同时关闭开机计划”默认开；关闭即“临时恢复”。
- 组件目标追加一行“组件：2 个恢复原状态；整包取消暂停”。
- 恢复永远按所有权集，不按当前组件选择集；否则取消勾选的组件会成孤儿。
- 没有自有所有权时不给恢复按钮：“不是本应用设置，Nemu 不接管”。

### 6.8 检测页

```text
检测 · 只读快照 14:32                    [刷新]
[生效 3] [遥测与云控 6] [系统 4] [启动安全 1]
────────────────────────────────────────────
软件更新  com.hihonor.ouc
策略：暂停    实际：已暂停·进程存活    [不一致]
组件：2/8 已停用 · 残留 1
说明：系统可保留已暂停进程，不代表恢复
[在应用页查看]
```

- 页头只说一次“只读”；各分类不再写长说明卡。
- 分类计数来自统一数据源；云控目标不再被排除到第二套 `cloudStates`。
- 行式：目标 / 策略 / 实际 / 进程 / 组件计数；点行展开详情。
- 差异行给“在应用页查看”深链，只导航、不执行。
- COTA 只显示一行摘要，不重复整个文件列表。

### 6.9 设置页

```text
设置
[Root · 实际执行]
执行与权限        [›]
开机与恢复        [›]
外观              [›]
日志              [›]
高级              [›]  COTA、专家模式、DenyList 清单、关于
```

- 设置主页是列表，不是 FilterChip 分段导航。
- “执行与权限”：预览/实际切换（切到实际需确认）、后端、授权状态、能力说明。
- “开机与恢复”：计划总数、上次关机/开机/guard 结果、救援状态；总开关只在概览。
- 反诈/号码识别/后台限制全部并入目标行；设置不再有第二套包级控件。
- COTA 是独立文件级工具，留在高级；检测页只显示摘要。
- 首次安装默认预览（D-03）；现有用户保留原设置。
- **现有控件追踪**：ADB 启动 Shizuku/开发者选项、荣耀“应用启动管理”深链、
  救援模式清除/重启用模块、自定义目标移除、日志抓取/隐私确认、COTA 删除/恢复
  必须在新 IA 保留；建“现有控件 → 新页面/新控件 → 保留/合并/删除”追踪表，
  验收逐项覆盖。

### 6.10 交互状态机与预检

```text
浏览 -> 点卡片 -> 展开（手风琴）
  -> 点策略（只写设置，不执行）
  -> 可选：组件（只写选择）/ 计划（只写计划）
  -> 点执行 -> 预检 -> 确认 -> 执行中 -> 强制刷新 -> 成功/结果弹层
  -> 点恢复 -> 预检 -> 确认 -> 恢复中 -> 强制刷新
批量 -> 选择 -> 执行 N / 恢复 N -> 分组确认 -> 结果
```

硬规则：

1. 勾选 ≠ 执行；改策略 ≠ 执行；改计划 ≠ 执行。
2. 执行前必须确认；危险项必须勾选了解声明。
3. 执行后强制刷新受影响目标（忽略 TTL），失败也刷新。
4. 失败保留所有权，不清基线；结果弹层可重试失败项。
5. 同一时间只允许一个写操作（全局 busy）。
6. 预览模式只约束用户主动 apply：不下发用户命令、不写 applied/ownership；
   安全残留/关机恢复始终真实执行（D-04）。
7. 读取失败时禁止新执行（无法建立基线）；恢复允许但弹层注明“按恢复记录执行”。

预检：

| 检查 | 失败时 |
|---|---|
| 后端可用 | 按钮禁用“需要 Root 或 Shizuku” |
| 能力可用 | 行已置灰；服务层再校验 |
| DenyList | 拒绝“DenyList 保护” |
| 救援模式 | 拒绝“救援模式：执行已禁用” |
| 实际状态可读 | 执行禁用“读取失败，无法建立恢复基线” |
| 组件选择非空 | “至少选择 1 个组件” |
| HIDE/AppOps guard 就绪 | “恢复路径未就绪” |

### 6.11 空态、权限与异常

| 场景 | 显示 |
|---|---|
| 无 Root/Shizuku | 顶栏“未授权”；执行/恢复禁用；授权 CTA |
| 仅 Shizuku ADB | 暂停/冻结可用；组件/停用/隐藏置灰“需要 Root” |
| 平台拒绝 | `暂停：不可用（本机平台拒绝）` |
| DenyList | `高风险，可能影响系统/安全功能`（仍可执行） |
| 红色包整包 | `高风险，可能影响系统/安全功能`（仍可执行） |
| 无登记组件 | `组件：不可用（无登记组件）` |
| 组件空选 | `至少选择 1 个组件` |
| 读取失败 | `读取失败 [重试]`；不显示“未冻结” |
| 他人停用/隐藏 | `已停用·非本应用` / `他人隐藏`；不给恢复 |
| 预置已停用 | 停用行不可用或只提供“接管”二次确认；结果写“已存在，未接管” |
| 已被他人限制 | `已被他人限制`；AppOps/standby 只提供“接管”确认 |
| 救援模式 | 全局红条；执行禁用，仅恢复/查看 |
| 无目标/筛选为空 | `没有匹配的目标` + 清除筛选 |
| 未测策略 | “未测”角标 + 确认弹层警告 |

### 6.12 手机宽度、无障碍、深色

- 基线 360dp，最低 320dp；左右 16dp，卡片内 12dp。
- 策略列表纵向排列，不用横向滚动；分类 chips 可横滚但要有渐隐/数量。
- 点击区域 ≥48dp；整行可点；颜色不是唯一信息（图标 + 文字）。
- 包名/组件名过长中间省略；详情页完整显示。
- 字体缩放 1.3/2.0 下自动换行，不固定高度。
- 深色：纯黑背景 + 中性深灰卡片；禁用原因文字对比度 ≥4.5:1。
- TalkBack 覆盖执行、恢复、组件、批量四条流程。
- 验证矩阵：320/360/412dp × 1.0/1.3/2.0 字体 × 浅/深色 × 空/无 Root/预览/读取失败/救援。

---

## 7. 架构与代码改造

### 7.1 单一真相源

- `TargetCatalog`：包、label、risk、category、组件白名单、defaultStrategy、
  capabilities、functionLine、impact、unavailableReason、evidence 只在这里定义。
- `Targets` / `AiTargets`：只保留历史迁移常量；UI/执行/检测不再从它们推导能力。
- `CloudTargets` / `AntiFraudTargets`：降级为迁移映射；`action`/`applyActions`
  标 `@Deprecated`，不再决定 UI。
- `CloudController` / `AiFreezeController` / `FreezeController`：删除或停止接线；
  统一走 `TargetFreezeService` + `FreezeStrategyController`。
- 反诈/COTA 旧独立入口：反诈包级动作并入目标卡；COTA 保留文件级高级工具。
- 新增 `RestrictionController`：AppOps/standby 的 apply/restore/verify，与 5 值
  freeze 控制器分离；`TargetFreezeService` 按 preset 分派。
- `Target.defaultEnabled`/`Target.suspend`/`Target.clearCache` 是死字段或未统一
  执行，清理或标迁移用；`TargetComponent.risk` 补 RED/YELLOW。
- `CatalogTargetUiState`/`FreezeState` 增加 `preset/options/restrictionApplied`；
  `buildCatalog` 从 `TargetPolicy.options` 生成。
- 新 AppOps/standby 所有权落地前，先退役 `AntiFraudController`/
  `AntiFraudSection` 的包级恢复；迁移记录“原始值未知”并提供一次性恢复。

### 7.2 执行链路

```text
UI Preset
  -> TargetPolicy.setPreset(target, preset, options)   // 同步 commit
  -> TargetFreezeService.apply(targets)
       -> TargetPresetPolicy.capability(...)           // 服务层再校验
       -> FreezeStrategyPolicy.validate(...)           // 风险/DenyList/组件
       -> FreezeStrategyPlanner.apply(target, options) // 命令
       -> FreezeStrategyController.apply(...)          // 基线/执行/复核/所有权
```

任何 UI 置灰都不能代替服务层校验。

- `TargetPolicy.isActive` 判定目标是否参与执行/计划：
  `strategy != OBSERVE || appOps || standby`。批量、服务、`BootFreezeWorker`、
  `SafetyService` 都用它，不再只判断 `strategy != OBSERVE`。
- `FreezeStrategyPolicy.validate` 增加 `effectiveComponents` 参数（或服务层
  `requireSelectionNonEmpty`）；空选绝不写 applied。
- `isAllowed` 调 `validate`；`setStrategy`、检测、执行统一走它。
- COMPONENT/DISABLE/HIDE 执行前检查可写 Root marker backend；组件停用前
  `beforeComponentDisable`，失败在命令前中止。

### 7.3 恢复链路

- `restore`：按 owned union 生成命令；不按当前选择。
- `restoreOwnedComponents`：遍历 `target.components ∪ ownedComponents`；
  目录删除的组件合成 `TargetComponent(className, label=className)`。
- `restoreOwnedHidden`：已有；补 guard marker。
- AppOps/standby：只恢复 owned 项，原始值未知时 fail-closed。
- 取消勾选差异恢复：`previousOwned - newSelection` 先恢复，再保存新选择。
- HIDE：`ownsHidden + hidden_by_nemu marker` 是唯一所有权条件，不依赖 `applied`。
- 所有权消费方统一扩展：`TargetPolicy.isApplied`、`CatalogTargetUiState.hasFrozenState`、
  `SafetyService.sync`、`ShutdownScript`、`SafetyTargets` 都纳入 AppOps/standby/
  组件/HIDE 所有权；否则限制型目标不会启动安全服务、不会进关机脚本、不会显示生效。
- 按 `strategy_opts.backend` 分组恢复；每组一次脚本；8 秒预算作为测试。
- 任何恢复（单目标/批量/全部）都取消当前 boot 的 `BootFreezeScheduler` 并写
  skip；弹窗写“本次开机不再自动执行”。

### 7.4 检测

- `DetectionReader` 统一读 `strategy_<pkg>` + `strategy_opts_<pkg>`；
  云控包不再走第二套 `CloudAction`。
- 新增 `FreezeMode.RESTRICT` 表示“仅限制后台/待机”。
- 组件状态输出 `selectedTotal / selectedDisabledCount / ownedUnselectedDisabledCount`。
- 检测页行可“去应用页查看”，不直接执行。
- `FreezeState` 增加 `preset`（PAUSE/FREEZE/RESTRICT）；检测页区分“暂停”与
  “冻结”；限制型行显示“观察 + 限制后台/待机”，不显示成纯观察。

### 7.5 安全与 guard

- HIDE：`pm hide` 前写 `hidden_by_nemu` marker；`boot_guard.sh` 新增
  `unhide_owned_targets()`，service 与 rescue 两轮调用。
- AppOps/standby：新增原始值 + owned + guard marker；rescue 恢复。
- DISABLE_USER：统一“关机不恢复、rescue 恢复”；删除模块脚本无条件 `pm enable`。
- 暂停：新增 `strategy_original_suspended_<pkg>`；执行前已 suspended 不认领。
- 后端：记录在 `strategy_opts.backend`；关机按后端分组，优先同后端。
- 8 秒预算：合并同后端恢复命令为一次脚本，再逐项复核；写成可测试验收。
- `guardHealth()`：模块目录/版本/脚本能力/最近运行；未就绪时 AppOps/standby
  置灰，HIDE 标注“救援依赖模块”。
- 旧反诈 `planRestore` 不再无条件 `allow/active`；先退役旧路径，再启用新
  AppOps/standby 所有权。

---

## 8. 实施计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| 0 安全底座与恢复门槛 | DenyList gate；`legacyRestoreOwnedStates()` 先于能力收回；暂停基线；后端记录；HIDE `hidden_by_nemu` marker；AppOps/standby 所有权；`guardHealth`；`isActive`；服务层统一校验 | 单测通过；不新增命令；PF 场景命令不变；已应用旧状态可恢复 |
| 1 文案与假按钮 | `Target.functionLine`；回填 10 个空描述；删除长文案；`PresetCapability`；置灰 + 原因；CopyMap/ResultReason | 41 目标功能行非空；无禁用词；不可用项有原因 |
| 2 暂停/冻结 | `StrategyOptions`；planner/controller/service 接 options；UI 8 预设；检测映射 | 旧数据命令逐字节一致；暂停只发 suspend；冻结发两条；阶段 0 安全项已就绪 |
| 3 组件子集 | 选择键；`effectiveComponents`；override；owned union；取消勾选差异恢复；UI 选择器 | 只停所选；空选拒绝；取消勾选先恢复；OTA 孤儿可恢复 |
| 4 限制/检测/IA | `RestrictionController`；`FreezeMode.RESTRICT`；UI/检测 preset/options；去重云控行；页面合并；恢复取消开机排程 | 限制在手动/开机/关机/guard 都执行；同一包只有一行 |
| 5 真机验收 | PAUSE vs FREEZE、组件子集、HIDE rescue、AppOps/standby、后端归属、guardHealth、预置状态 | 每条有命令输出与恢复记录；未测不写已验证 |

**组件发现不在本版**：0 组件目标的 `dumpsys` 枚举 + 绑定测试 + risk/role 登记
列入 v2.x；本版这些目标只做整包/观察，组件 chip 显示“未发现/未登记”，
不得凭包名猜组件。

### 8.1 必须补的单测

**计划层**：`legacySuspendWithoutOptionsStillForceStops`、
`legacyComponentWithoutOptionsUsesAllRegistered`、
`suspendPlanWithoutForceStopEmitsOnlyPmSuspend`、
`suspendPlanWithForceStopEmitsBothCommands`、`componentPlanUsesSelectedSubset`、
`componentPlanRejectsEmptySelection`、`componentPlanIgnoresUnknownSelectedClass`、
`disableUserPlanHasNoForceStop`、`hidePlanHasNoForceStop`、
`appOpsPlanEmitsIgnoreOnly`、`standbyPlanEmitsRestrictedOnly`。

**控制层**：`suspendApplyDoesNotClaimPreExistingSuspension`、
`suspendRestoreOnlyUnsuspendsOwnedSuspension`、
`componentSelectionOwnsOnlyCheckedComponents`、
`deselectedOwnedComponentIsRestoredBeforeSave`、
`componentPartialFailureKeepsOwnershipForDisabledOnly`、
`hideApplyWithoutDumpsysReaderDoesNotTrustStdout`、
`hidePreHiddenPackageIsNotClaimed`、`disableUserUnknownBaselineFailsClosed`、
`appOpsBaselineCapturedBeforeRestrict`、
`appOpsVerificationFailureKeepsOwnership`、
`standbyVerificationFailureKeepsOwnership`。

**服务/持久化层**：`optionsJsonMalformedFallsBackToConservativeDefault`、
`optionsWrittenSynchronouslyBeforeCommands`、
`componentSelectionPersistedSynchronously`、
`restoreOwnedComponentsUsesOwnedUnionNotSelection`、
`restoreOwnedComponentsHandlesCatalogRemovedComponent`、
`restoreOwnedComponentsHandlesEmptyCatalogList`、
`applyWritesAppliedMarkerBeforeAnyCommand`、
`failedHideRollsBackAndClearsBaseline`、
`failedDisableUserKeepsMarkerOnlyWhenStillDisabled`、
`deselectRestoreFailureKeepsOldSelectionAndBaseline`。

**安全层**：`safetyTargetsIncludesEverySuspendPreset`、
`safetyTargetsIncludesRestrictionOwners`、
`shutdownScriptCoversEveryOwnedPreset`、
`shutdownSafetyAndModuleScriptAgreeOnDisableUser`、
`bootWorkerResidualRestoresAllOwnedComponentsIncludingUnselected`、
`bootFreezeWorkerAppliesRestrictionOnlyTargets`、
`bootGuardScriptUnhidesOwnedHidden`、
`bootGuardScriptRestoresOwnedAppOpsAfterBootCompleted`、
`bootGuardScriptKeepsMarkerWhenRestoreUnverified`。

**检测/后端**：`pauseAndFreezeMapToDistinctStatus`、
`componentSubsetReportsSelectedAndResidualCounts`、
`cloudPackagesUseCatalogRowsWithoutDuplicate`、
`restrictionOnlyRowsUseRestrictMode`、
`shizukuAdbCannotHideOrDisableUser`、`recordedBackendPreferredAtShutdown`、
`disableUserRequiresRootMarkerBackend`。

**验证补充（对应 §11）**：`denyListGateSharedByIsAllowedAndValidate`、
`legacyAppliedDisableRestoredBeforeCapabilityRemoval`、
`restrictionTargetsApplyInManualBootShutdownAndGuard`、
`restoreCancelsBootFreezeSchedulerAndWritesSkip`、
`guardHealthMissingDisablesRestrictionAndWarnsHide`、
`preExistingDisableNotAdopted`、`componentMarkerWriteFailureAbortsBeforeCommand`、
`shizukuRootWithoutMarkerCannotDisableComponent`、
`hiddenOwnedButAppliedLostIsRestoredOnBoot`、`copyNoLongParagraphPerScreen`；
Compose UI 测试覆盖执行/恢复/组件/批量/空态/无 Root/预览。

---

## 9. 验收标准（对应用户四条）

1. **文案**：卡片无“这个应用是做什么的”；每卡常驻 ≤3 行非交互文本；
   策略面板每行 `策略：影响`；T01–T12 全绿。
2. **完整策略**：41 个目标每个都有能力矩阵；不可用项置灰 + 一行原因；
   powergenie/pushagent 不再出现可点假按钮；hiview 暂停置灰。
3. **组件级**：20 个可用组件目标都可勾选具体组件（powergenie 3 个组件平台
   拒绝，只展示不可操作）；执行只作用于所选；恢复按 owned union；空选拒绝；
   取消勾选先恢复；非 owned 组件不接管。
4. **整体设计**：`TargetCatalog` 单一真相源；同一包只有一个控制入口；
   冻结/解冻/检测/设置职责单一；安全路径覆盖所有新策略。

---

## 10. 决策台账

| 编号 | 决策 | 结论 | 状态 |
|---|---|---|---|
| D-01 | 暂停/冻结拆分 | 拆分：暂停 = 仅 `pm suspend`；冻结 = suspend + force-stop；页面动作叫“应用” | 已定（本文件） |
| D-02 | 4 Tab vs 5 Tab | 终态 4 Tab；v1.4–1.5 保留 5 Tab；5 Tab 时第 5 页放“计划”，不保留冻结/解冻双页 | 推荐，分阶段 |
| D-03 | 首次安装默认 | 默认预览；现有用户保留原设置 | 推荐 |
| D-04 | 预览与安全恢复 | 预览只约束用户主动 apply；安全残留/关机恢复始终真实执行 | 已定（安全优先） |
| D-05 | DenyList 口径 | 默认拒绝 + 注册表证据例外；整包 `disable-user`/`hide` 仅 numberidentity（PF12）例外；整包暂停/冻结仅 AI 四包（PF16）例外；删除“专家模式解锁 DenyList” | 推荐，需用户确认 |
| D-06 | 持久策略关机语义 | 停用/AppOps/standby 不随正常关机撤销；隐藏按现有 `restoreOwnedHidden` 在关机时解除；关机只清孤儿；guard rescue 恢复全部 owned | 推荐，需用户确认 |
| D-07 | 专家模式 | 只解锁技术上可行但风险高的项（非 DenyList、非平台拒绝、非真实卸载）；默认关 | 推荐 |
| D-08 | 自定义应用组件枚举 | 本次不做；只允许目录登记组件；v2.x 再评估 `PackageManager` 枚举 | 推荐 |
| D-09 | OUC 停用/隐藏 | 不在本版支持集；需单独实测 + 用户确认 | 待定 |
| D-10 | cipservice | 默认仅观察（角色未确认）；补 `dumpsys`/bind 证据后再开放 | 推荐 |
| D-11 | 限制后台/待机首版 | 所有权 + guard + 真机验证就绪后开放；否则置灰 | 推荐 |

---

## 11. 对抗验证结论（2026-11-22）

验证结论：方向正确（五值枚举 + `StrategyOptions` + 派生 Preset、组件 owned
union、不可用项保留并给原因），但初稿不能直接实现。两路审查共 **4 个安全级
阻断**，另有迁移、恢复、文案、UI 与验收口径的主要缺口；以下修订已并入本文件。
实现前先完成 §8 阶段 0。

### 11.1 阻断项与修订

| # | 问题 | 修订 |
|---|---|---|
| B-1 | “限制后台/待机”用 `OBSERVE + appOps/standby` 表示，会被四处 `strategy != OBSERVE` 短路：批量选择、执行服务、控制器、开机冻结都不会下发命令 | 新增 `TargetPolicy.isActive = strategy != OBSERVE \|\| appOps \|\| standby`；新增独立 `RestrictionController` 执行 AppOps/standby；手动、开机、关机、guard 四条路径都走它；补端到端测试 |
| B-2 | 选项解析失败回退 `components=null`，而 null 定义为“全部组件”，一次数据损坏会把 2 个组件扩成全量 | 迁移时把旧 COMPONENT 物化为当时组件全集；运行时 `null`/解析失败/版本未知对 COMPONENT **fail-closed**：不执行、不写 applied、要求重新选择 |
| B-3 | 阶段 0 收回 securityserver/awareness/securitypluginbase/cipservice 能力，但旧版已应用的 DISABLE/HIDE/COMPONENT 可能在升级后无法恢复 | 能力收回前先执行一次性 `legacyRestoreOwnedStates()`；`restore` 按所有权集，不按当前 `supportedStrategies`；补迁移测试 |
| B-4 | DenyList 例外清单与“绝对保护”口径冲突；`isAllowed` 可能保存/显示执行层会拒绝的策略 | 明确两级口径（D-05）；`isAllowed` 与 `validate` 共用同一 gate；所有 setStrategy/检测/执行路径走它；删除“专家模式解锁 DenyList” |

### 11.2 主要修订（已并入正文）

1. **单一持久化**：`strategy_opts_<pkg>` JSON 是唯一来源，含
   `schema/forceStop/components/appOps/standby/backend`；删除
   `strategy_components_*`、`strategy_backend_*`、`Target.componentSelection`
   等重复键。
2. **组件空选**：`validate`/planner 接收 `effectiveComponents`；服务层加
   `requireSelectionNonEmpty`；空选绝不写 applied。
3. **所有权消费方**：`isApplied`、`hasFrozenState`、`SafetyService.sync`、
   `ShutdownScript`、`SafetyTargets` 都纳入 AppOps/standby/组件/HIDE 所有权。
4. **HIDE 恢复**：以 `ownsHidden + hidden_by_nemu marker` 为条件，不依赖
   `applied`（docs/12 有 applied 丢失记录）。
5. **组件恢复**：选择器只对 owned 组件给“恢复”；他人停用显示“不接管”；
   非 COMPONENT 策略下组件行只读。
6. **Root marker**：COMPONENT/DISABLE_USER/HIDE 都要求可写 Root marker
   backend；组件停用前新增 `beforeComponentDisable` 回调，写入失败在命令前中止。
7. **后端与关机预算**：按 `strategy_opts.backend` 分组恢复，每组一次脚本；
   8 秒预算写成可测试验收；无法保证时以模块脚本为主路径。
8. **预览**：安全残留/关机恢复不受预览影响，始终真实执行；预览只约束用户
   主动 apply。
9. **旧反诈退役**：新 AppOps/standby 所有权落地前，先下线
   `AntiFraudController`/`AntiFraudSection` 的包级恢复；迁移记录“原始值未知”
   并提供一次性恢复。
10. **UI/检测模型**：`CatalogTargetUiState` 与 `FreezeState` 增加
    `preset/options/restrictionApplied`；PAUSE/FREEZE/RESTRICT 可区分。
11. **阶段顺序**：暂停基线、后端记录、HIDE guard、AppOps 所有权全部前移到
    阶段 0/1；阶段 2 不开放未就绪策略。
12. **恢复与开机排程**：任何恢复都取消当前 boot 的 `BootFreezeScheduler` 并写
    skip；弹窗写明“本次开机不再自动执行”。
13. **guardHealth**：检查模块目录、版本、脚本是否含 `pm unhide`/appops/standby、
    最近运行时间；未就绪时依赖策略置灰并给原因。HIDE 保留 PF20/21 能力，
    但标注“救援依赖模块”。
14. **文案单一源**：8 预设；T03/T04 包含“冻结/限制后台/限制待机”；
    状态白名单加入“已冻结”；长度按显示宽度/码点计数，不只看汉字。
15. **结果文案**：新增 `ResultReason` 枚举 + 中文一行表；英文枚举/命令只进日志。
16. **卡片去重**：已生效且一致时只显示一个联合状态 chip；未生效显示
    “策略：…”; 影响行只在展开态出现一次。
17. **文案覆盖**：设置/日志/救援/开机/ADB/COTA 建 CopyMap，逐项保留/改写/删除；
    屏幕级测试断言无长段落。
18. **组件存储/交互**：单键 `strategy_opts.components`；非 COMPONENT 只读；
    已生效时禁止编辑或强制恢复后重应用；补“同时结束进程”开关。
19. **0 组件目标**：明确“未发现组件，不代表不存在”，不声称“已确认无组件”；
    组件发现（dumpsys + 绑定测试）列入 v2.x 或明确不支持；组件风险补
    RED/YELLOW 后再分级。
20. **限制型 UI**：定义“观察 + 限制后台”卡片、执行、计划、确认、恢复文案；
    限制型目标必须有执行入口，不能因 `OBSERVE` 被短路。
21. **预置已停用/已限制**：显示“已停用·非本应用”/“已被他人限制”；停用 chip
    只提供“接管”二次确认或不可用；结果区分“已存在，未接管”。
22. **控件覆盖**：ADB/开发者选项、荣耀启动管理深链、救援清除、自定义移除、
    日志抓取/隐私确认、COTA 删除/恢复都在新 IA 保留。
23. **验收数字**：21 个有组件目标、20 个可用（powergenie 平台拒绝）；
    未测组件目标按 15 个列，不写 12。

### 11.3 实现前检查清单

- [x] `StrategyOptions` + 版本化 codec + `StrategyPreset` 派生（2026-11-22）。
- [x] `isActive` + `effectiveComponents` + `RestrictionController`；手动/开机/关机/
  rescue 接线；AppOps/standby 原始值、所有权与守卫 marker。
- [x] `strategy_opts_<pkg>` canonical codec + 迁移物化组件 + 解析失败 fail-closed。
- [x] DenyList 两级口径 + `isAllowed`/`validate` 同 gate + 目录 AI/numberidentity 例外。
- [x] 暂停前已被他人 suspended 不认领（`SuspendClaimPolicy` + `TargetFreezeService`）。
- [x] 恢复改用 `storedStrategy` + `RestoreStrategySelector`，能力收回后仍能撤销旧状态。
- [x] 一次性 `policyMigrationDone` 迁移：能力收回前恢复 `storedStrategy != strategy`
  的旧状态；失败下次启动重试。
- [x] HIDE `hidden_by_nemu` marker + `boot_guard.sh` 的 `pm unhide`；恢复不再依赖
  `applied`。脚本只做语法检查，未上机。
- [x] COMPONENT/DISABLE/HIDE 的 Root marker backend 前置检查。
- [x] `guardHealth` 解析器与脚本能力探测；UI 置灰待阶段 1。
- [x] 检测模型：`FreezeMode.RESTRICT`、`restrictionApplied`、`preset`；
  PAUSE/FREEZE 状态区分。UI 渲染待阶段 1。
- [x] 恢复取消开机排程 + skip 标记（现有 `onCatalogRestore` / `RestoreAll` 已有）。
- [x] 组件空选拒绝 + owned-only 恢复 + 选择器 UI；取消勾选先恢复再保存；
  非 COMPONENT 不显示选择器；组件状态/残留/未分级可读。
- [x] 记录 `strategy_opts.backend`（apply 时写入）。
- [x] 后端分组规划 + 8 秒预算常量：`RestoreBatchPlan`（按记录的后端分组、
  每组一条 `; ` 脚本）与 `RestoreBatchPlanTest`；真机接线与计时列入设备验证
  （一次脚本的退出码会混合单项失败，未在无设备证据时强改安全路径）。
- [x] 预览不阻断安全恢复：全部解冻使用 `safetyService`（`dryRun=false`）执行
  残留清理；`PreviewSafetyContractTest` 固化，安全解冻本身无 dryRun 参数。
- [x] 旧反诈 UI 的 securityserver/awareness 动作已路由到
  `TargetFreezeService`/`RestrictionController`；numberidentity 仍走旧控制器。
- [x] `TargetCopy`：41 目标功能行、策略影响、恢复行、不可用原因；
  目标卡/确认弹窗/解冻行使用短文案；不可用 chip 置灰 + 原因。
  `TargetCopyTest` 覆盖长度、禁用句式、平台拒绝/DenyList/红色包/no-root。
- [x] 阶段 2：暂停/冻结预设；`forceStop` 从 `StrategyOptions` 贯穿
  planner/controller/service；PAUSE 只发 `pm suspend`，FREEZE 再发
  `am force-stop`；卡片显示 6 个主预设（securityserver/awareness 额外有
  限制后台/待机）；检测状态区分暂停/冻结。
- [x] 阶段 3：组件选择器；选择写入 `strategy_opts.components`；
  `FreezeStrategyController` 只停用所选；取消勾选先恢复到原始状态再保存；
  空选拒绝；组件状态显示本应用/他人、残留、未分级。
- [x] 阶段 4（第一刀）：底部导航合并为 4 页（概览/应用/检测/设置）；
  “应用”页内用“管理/恢复”分段复用现有列表；旧反诈卡片移除，
  securityserver/awareness/numberidentity 全部由目标卡处理；高级控制只剩 COTA。
- [x] 计划/本次选择分离：卡片复选框只改本次选择（会话）；新增“开机计划”
  Switch 写持久 `enabled`；手动应用不要求已加入计划；`SelectionRules`
  保证未计划目标也能本次应用。
- [x] 显式批量模式：普通浏览无复选框；点“批量选择”才显示；批量栏提供
  全选/清除/完成/应用已选；应用后自动退出批量并清空选择。
- [x] 检测深链：检测行“在应用页查看”切到应用页并定位包名；
  应用页显示“已定位：…”和“显示全部”；离开应用页自动清除定位。
- [x] ResultReason 中文表：服务返回 `ResultReason + ResultNote`，UI 只渲染
  一行中文；命令、枚举名和原始输出只进 ActionLog；覆盖冻结/恢复/组件/隐藏/
  限制/云控/开机与关机详情/预检。
- [x] 全屏 CopyMap：`docs/19-copymap-2026-11-22.md` 覆盖设置/日志/救援/开机/
  安全解冻/ADB/COTA；确认弹窗压到 ≤3 行；T01–T12 由 `CopyContractTest` 断言。
- [x] 组件 owned-only 选择器 UI + 非 COMPONENT 只读（阶段 3）。
- [x] 验收数字修正（21/20、15 个未测组件目标）。

---

## 12. 阶段 0 实施记录（2026-11-22）

**已落地**：

- `StrategyOptions`/`StrategyPreset`/版本化 codec；`isActive`、`effectiveComponents`、
  `StoredOptions` 的 Missing/Invalid 语义。
- DenyList 硬 gate 与 `denyExceptions`；`isAllowed` 与 `validate` 同源。
- `TargetPolicy.storedStrategy`、`options`、`isApplied` 扩展（AppOps/standby 所有权）。
- `SuspendClaimPolicy`：已被他人 suspend 不认领。
- `RestrictionController`：AppOps/standby 原始值、所有权、复核、恢复；接入
  `TargetFreezeService.apply/restore`、`BootFreezeWorker` 计划、关机孤儿清理、
  `boot_guard.sh` rescue。
- HIDE `hidden_by_nemu` marker + `boot_guard.sh` 的 `pm unhide`；`restoreOwnedHidden`
  不再依赖 `applied`。
- COMPONENT/DISABLE/HIDE 的 Root marker backend 前置检查。
- `GuardHealth`/`GuardHealthParser`：检查 guard 脚本是否具备 unhide/AppOps/standby。
- 一次性 `policyMigrationDone` 迁移：能力收回前恢复 `storedStrategy != strategy`。
- 检测模型：`FreezeMode.RESTRICT`、`restrictionApplied`、`preset`；PAUSE/FREEZE 状态区分。
- 后端记录 `strategy_opts.backend`；恢复取消开机排程沿用现有路径。
- 阶段 1：`TargetCopy` 单一文案源；目标卡改为 `软件功能：…`；
  不可用策略置灰 + 一行原因；删除 `explain()`/`genericConsequence()`/
  `plainActualText()` 与“这个应用是做什么的”前缀；确认弹窗改 3 行；
  概览/检测/反诈/COTA 的长段落压缩。
- 旧反诈 UI 的 securityserver/awareness 开关现在写入新的 AppOps/standby 所有权
  并走统一执行器；numberidentity 保留旧控制器。
- 阶段 2：`TargetPolicy.setPreset`；UI 6 个主预设 + securityserver/awareness
  的限制后台/待机；PAUSE/FREEZE 通过 `forceStop` 区分并贯穿执行器；
  `FreezeStrategyPlanTest` 覆盖两条命令面；检测状态区分已暂停/已冻结。
- 阶段 3：组件选择器（全选/清空/逐项勾选/已选 n/m）；选择持久化到
  `strategy_opts.components`；`FreezeStrategyController` 只处理所选；
  取消勾选先 `restoreOwnedComponents(only=…)` 再保存；空选拒绝；
  组件行显示本应用/他人停用、残留、未分级；非 COMPONENT 不显示选择器。
- 阶段 4（第一刀）：`DashboardTab` 4 页；`ApplicationsScreen` 提供
  “管理/恢复”分段；旧 `AntiFraudSection` 从设置移除，四个安全目标由
  目标卡预设处理；FreezeScreen 文案改为“选择/策略/应用”。
- 计划/本次选择分离：复选框绑定会话 `selection`；目标卡新增持久
  “开机计划” Switch；`onCatalogApply` 不再要求 `policy.isEnabled`；
  批次应用后清空本次选择；`SelectionRulesTest` 覆盖未计划目标本次应用。
- 显式批量模式：普通浏览隐藏复选框；进入批量后才出现选择/全选/清除/
  完成/应用已选；应用后自动退出并清空选择。
- 检测深链：`DetectionScreen` 行内“在应用页查看”设置 `focusPackage`；
  `DashboardScreen` 自动切到应用页；应用页只显示该包并提供“显示全部”；
  离开应用页清除定位。
- 设置二级页：根页改为五个入口（执行与权限/开机与恢复/外观/日志/高级）的
  行式列表 + `›`；二级页有 `‹ 返回` 并接管系统返回键；离开设置页即回到根页。
  执行页独占预览/实际切换，关闭预览改为确认弹窗；开机页只读复用计划、安全
  结果与救援卡（总开关仍在概览）；高级页保留 COTA 与固定保护规则说明；
  `SettingsEntriesTest` 覆盖页序、摘要与每页说明。
- 结果原因：新增 `ResultReason`（约 40 个语义）、`ResultNote` 与 `ResultCopy`；
  `StrategyResult`/`RestrictionResult`/`TargetRunResult` 改为存储 reasons 并导出
  中文 notes；UI 不再出现 OK/FAIL、策略枚举、DRY-RUN、PackageManager 或原始
  shell；覆盖冻结/解冻/组件/隐藏/限制/云控/开机详情/关机详情/预检，原始输出
  留在 ActionLog。
- 全屏文案与安全守卫：`docs/19-copymap-2026-11-22.md` 逐项标保留/改写/删除；
  确认弹窗统一走 `TargetCopy.confirmLines`（≤3 行）；HIDE 状态词压到 ≤12 字；
  0 组件改为“未发现组件”，不再声称已确认无组件；全部解冻在预览模式下也通过
  `safetyService` 执行真实残留清理；`CopyContractTest` T01–T12、
  `PreviewSafetyContractTest`、`TargetCopyTest` 固化；`RestoreBatchPlan`
  提供按后端分组与 8 秒预算常量。

**验证**：

- JVM 测试 **302 个 / 36 个测试类 / 0 失败**。
- `boot_guard.sh` 通过 `sh -n` 语法检查；**未上机**。
- release APK 24,408,741 字节，SHA-256
  `DE3F55E10F69AD63897F7D1AE576A75BFC37AF834C871F8808C652C39EEF6B72`
  （非 debuggable；本地测试用 debug keystore 签名，发布前替换私有签名）。
- debug APK 34,862,151 字节，SHA-256
  `B05880E2F6F9DF4B53CBA65A5823136E8E9B5B82D1AB1765B9045E4DA67C0473`。
- 真机冒烟（2026-11-22）：见 `docs/20-device-test-2026-11-22.md`；启动、
  四页、设置五个二级页、检测深链、日志开关、开机安全解冻均通过；修复两处
  文案/状态显示问题后已重装验证；未执行真实冻结/停用/隐藏。
- 真机反馈第 2 轮：删除标题下“睡眠管理·boot”，取消 1-2-3 步骤卡；
  授权卡改为 Root/Shizuku 两个显式按钮（已授权 toast，未授权弹管理器窗口）；
  删除开发者选项/ADB 帮助入口；自定义应用选择器显示图标；DenyList 与红色包
  不再硬性禁止隐藏/停用，改为高风险提醒；后续进一步取消
  `supportedStrategies` 对整包策略的拦截，只有平台拒绝、组件登记、Root
  缺失仍是真实限制。
- 真机反馈第 3 轮（布局简化）：概览删除重复“现在的状态”卡，状态并入
  首卡；应用页目标卡改为默认收起、一次只展开一张；工具栏压缩为
  `批量选择 / 刷新 / 添加`；分类 chip 改用 `FlowRow` 并隐藏 0 项分类；
  检测页摘要改 chip、行内“在应用页查看”移入展开区；授权页去重，
  “已授权”按钮改为“检查”；COTA 卡统一为 `SectionCard`；
  “恢复”页无冻结目标时只显示空状态与刷新。
- 真机反馈第 4 轮：应用页增加名称/包名搜索；分类与策略 chip 改为单行
  横向滑动；授权卡删除 ADB 行；开机开关移回首卡；总开关/计划/授权/
  日志/安全解冻等状态改用 `StatusChip`；删除旧反诈面板与 Dashboard 死
  槽位；回前台只刷新 Shizuku 状态；相同时间的“上次尝试/处理”合并。
- 第 5 轮：新增底部“全部应用”标签（5 项导航），列出全部 374 个已安装
  包（用户 60、系统 314），支持搜索、分类、多选和批量加入；系统/关键
  应用持久化 RED，恢复关键应用确认后才可加入；Nemu 自身不可选；新增
  目标不再自动加入开机计划。
- 模块 zip v1.9：10,546 字节，SHA-256
  `28ACA824ED187F34809715C8F6351BA529EE5BFCBDF5B99CAC5243695A23FF10`。
- APK 已安装；KSU 模块已安装但尚未重启激活；未执行真实冻结/停用/隐藏。

**未完成 / 留到后续阶段**：

- 后端分组恢复的**实际接线与 8 秒真机计时**：规划与常量已测；一次脚本会混合
  单项退出码，需设备证据后再改安全路径。
- 目标行限制 UI：现由“限制后台/限制待机”预设承载；是否再加独立开关
  待真机验证后决定。
- 0 组件目标的组件发现（dumpsys + 绑定测试，v2.x）；当前只说“未发现组件”。

**真机待验证**：AppOps/standby 往返、HIDE rescue、guardHealth、迁移恢复、
boot_guard.sh v1.9 的 unhide/AppOps/standby 分支。

---

## 附录

- `docs/18-target-matrix-2026-11-22.md`：41 目标 × 8 策略完整矩阵、
  组件清单、证据与未测清单。
- `docs/19-copymap-2026-11-22.md`：设置/日志/救援/开机/安全解冻/ADB/COTA
  与结果文案的逐项 CopyMap。
- `docs/20-device-test-2026-11-22.md`：真机冒烟、发现的问题与修复、未做项。
- `docs/10-nemu-redesign.md`：历史设计与真机记录。
- `docs/12-device-test-log-2026-11-22.md`：PF1–PF27 证据。
