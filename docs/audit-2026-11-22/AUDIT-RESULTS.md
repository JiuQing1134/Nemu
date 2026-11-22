# Nemu 功能/显示一致性审计结果（2026-11-22）

六路独立审计（Freeze UI、Unfreeze、Boot/救砖、策略目录、检测/显示、测试/安全），
逐项对照源码与实机。所有高/中风险发现已修复；低风险项列出状态。

## 已修复（高/中）

| 域 | 问题 | 修复 |
|---|---|---|
| Unfreeze | 已应用 COMPONENT 策略但实际处于 suspend 的目标，解冻只恢复组件，不 unsuspend | `restore` 先按实际 `suspended` 逐包 `pm unsuspend` 并复核，再按策略恢复 |
| Unfreeze | 解冻所选调用全局 `restoreOwnedComponents()`，会恢复未选目标的组件 | 改为按选中目标过滤；全局版本仅用于全部解冻/关机/开机残留 |
| Unfreeze | 组件已停用但缺少原始状态时返回成功并清除 applied，隐藏实际冻结 | 无基线且仍有组件停用 → 失败并保留 applied；仅在确认无停用组件时清除 |
| Unfreeze | SUSPEND/COMPONENT 恢复只信 shell 退出码 | 恢复后读实际 suspend / 组件 enabled state，未确认则保留所有权并报失败 |
| Unfreeze | 页面不显示恢复结果/权限错误；Dry-run 对话框无提示 | Unfreeze 页显示上次操作结果；失败保留选择；对话框标注 DRY-RUN |
| Unfreeze | numberidentity 仅勾选策略就显示“已应用”，恢复后又被重标 applied | `isApplied` 不再看 anti-fraud mode；取消勾选只保留策略，不写 applied；恢复后可清除 |
| Freeze | 重复应用已冻结的 COMPONENT 目标报“没有可停用的组件” | 全部组件已停用且仍有基线 → 幂等成功 |
| Freeze | 策略切换只拦 applied/禁用，实际 suspend 或组件停用可绕过 | 实际 suspended/disabled/组件停用也要求先解冻 |
| Freeze | 组件命令返回成功但组件未停用仍写所有权/applied | 执行后逐组件读 enabled state，未停用则报失败并撤销该组件基线 |
| Boot | guard 救援只在 service 阶段跑一次，pm 未就绪则永久失败 | 早期 pass + 等待 boot_completed + 第二次 pass；外层 timeout 240s |
| Boot | guard 只 enable 固定 DM 列表，App 可写其它 DISABLE_USER 标记 | guard 扫描 `disabled_by_nemu/` 标记逐个恢复；未知包视为已解决 |
| Boot | 单个已移除包会让整条 `pm unsuspend` 中止 | SafetyUnfreezer 改为逐包执行并复核；Unknown target package 视为已解决 |
| Boot | 组件标记只存类名，guard 用 default-state，无法还原原本 enabled=1 | 标记写入 `component|originalState`，guard 对 1 用 `pm enable`，0 用 default-state |
| Boot | 组件/包被 OTA 删除后标记永久阻塞 clear | 识别 Unknown component/package 并清理标记 |
| Boot | count_attempt 可被两个 guard 实例并发重复计数 | boot_id 作用域 mkdir 锁 |
| Boot | rescue.flag 分支在残留恢复前就返回 | 调整为先做 dryRun=false 残留恢复，再检查 rescue |
| Boot | 关机恢复不清理 applied 标记 | 关机成功后清理 suspend/已恢复组件的 applied |
| Boot | 旧 hook 路径/CRLF 被 installer 拒绝覆盖 | 识别 2 行 managed hook（任意 boot_guard 路径/CRLF）后替换 |
| Policy | 旧 CloudSection 固定 action 与新目录策略冲突，checkbox 还是死控件 | 从高级设置移除旧 CloudSection，云控统一在冻结页；检测页保留只读云状态 |
| Policy | `CloudSettings.setEnabled` 清 applied，重新启用会丢所有权 | `TargetPolicy` 双向保留 applied；旧路径恢复后清理统一标记 |
| Policy | Cloud/AntiFraud 原始状态用 apply() 非 commit() | 改为 commit()，防止命令前崩溃丢基线 |
| Policy | hiview 三件套平台拒绝 `pm suspend`（返回 state:false） | 默认策略改 COMPONENT；检测页改用策略感知目录行 |
| Policy | powergenie 组件被 HONOR 保护，`pm disable` 返回 `new state: default` | 目录改为 OBSERVE only，不提供无法生效的组件按钮；iaware 组件仍可用 |
| 显示 | Detection core/AI 硬编码 SUSPEND | 统一由目录 strategy 生成行，组件模式读实际组件状态 |
| 显示 | pushagent 在检测页重复 | 云控专区与目录行去重 |
| 显示 | 清单缺少 16 个目录包的 `<queries>`，PackageManager 会隐藏 | 补齐全部目录包 |
| 安装 | 重装 APK 时旧 BootWorker 重跑导致全量解冻 | `MY_PACKAGE_REPLACED` 取消旧 work；BootWorker 校验 boot count |

## 已修复（低）

- Freeze 页救援入口文案改为“概览”页。
- 概览页进入时刷新开机冻结结果。
- 安全解冻通知不再声称会启用 disable-user。
- 用户可见字符串 Nemu → Nemu。
- Unfreeze 页提示反诈 AppOps/待机限制需在高级设置恢复。

## 保留/未做

- 旧 `onApply/onRestore/onToggleTarget/onRestoreTargets` 回调仍声明但无 UI 调用；
  作为历史路径保留，未再接入任何按钮（避免大范围删除风险）。
- `BootFreezePolicy` 的 `lastFreezeSucceeded/lastFreezeTime` 字段仍未被策略读取
  （历史字段，测试覆盖了实际使用的字段）。
- AppOps/待机限制不在 Unfreeze 页统一恢复，仍由反诈高级控制负责。
- 旧 CloudSection 文件保留在源码中但不再渲染，供历史审计参考。

## 验证

- 225 个 JVM 测试，0 失败（25 个测试类）。
- 设备：HONOR AAP-AN00 / boot 38；OUC 与核心/AI 目标 suspended=true；
  hiview 三件套 10 个组件 disabled；DM 三件套与 numberidentity enabled=3；
  Dry run=false；安装 APK 后核心目标保持 suspended。
- 组件名：64 个目录组件逐一与设备 `dumpsys package` 对照，0 缺失。
