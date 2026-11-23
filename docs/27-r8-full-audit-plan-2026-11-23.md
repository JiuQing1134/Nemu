# R8 全量联合审计计划（2026-11-23）

> 触发原因：用户要求重新审计**所有代码**（不是只看改动），确认功能真实生效、点击有反馈、
> 无逻辑错误/虚假控件/死代码/潜在 bug；并要求多 Agent、多轮、交叉复核、写修复文档、
> 按文档修改后再做最终全量审计。此前 COTA 温控列表问题说明「改过的代码」也不能免检。

## 0. 当前基线

- 应用：Nemu 1.3.3 / versionCode 9，`com.nemu.dream`，源码 `d:\win_root\nemu`
- 设备：HONOR AAP-AN00，MagicOS 10.0.0.102，已安装 arm64 release
- 构建：`gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease -PnemuAbiSplits=true`
- 当前基线：416 JVM 测试 / 0 失败，lint 0 errors，四包已出；release 私有签名
  `CN=Nemu`；模块 `nemu_boot_autostart` v1.10 已安装并验证开机不拉起 UI
- 审计阶段默认只读；修复后才允许构建/安装；刷机/重启仍由用户执行

## 1. 范围（全部代码，不含生成物）

1. `app/src/main/java/com/nemu/hibernate/**` 全部 Kotlin（约 150 文件）
2. `app/src/test/java/**` 全部测试（69 类）
3. `app/src/main/AndroidManifest.xml`、`res/**` 中被代码引用的资源
4. `app/build.gradle.kts`、根 `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`
5. `tools/nemu-boot-autostart/**`（sh/module.prop/zip 内容）
6. `tools/*.py` 等辅助脚本
7. README / PROJECTS / docs 中的行为承诺，逐条对照代码
8. 生成物（`dist/`、`app/build/`、`test-artifacts/`）不审计，只做产物/hash 校验

## 2. 审计问题清单（每个 Agent 都要回答）

- **功能是否真实生效**：命令是否执行、状态是否读回、失败是否有可见反馈
- **点击是否有真实效果**：每个 clickable/Button/Switch/Checkbox 是否有回调、是否改变状态
- **是否存在虚假/欺骗控件**：看起来能操作、实际空实现/永远禁用/无状态变化
- **逻辑问题**：分支不可达、条件写反、竞态、事务/顺序、单位/边界、off-by-one
- **死代码**：未调用函数/类/常量、只写不读字段、重复实现、遗留兼容层
- **潜在 bug**：异常吞掉、空读当成功、失败后状态不一致、跨进程/直启/重启
- **文案与行为一致性**：屏幕承诺 vs 实际代码行为
- **测试真实性**：断言是否真能失败、是否只测源码字符串、关键行为是否无测试

## 3. 多 Agent 矩阵（交叉阅卷）

### A. 全量两遍审计（complete-code，至少 2 个独立 Agent）

| Agent | Pass 1 | Pass 2 | 输出 |
|---|---|---|---|
| A1 | 全仓库静态逻辑/安全/死代码 | 逐条追踪功能是否真实生效 + 测试是否有效 | 问题行 |
| A2 | 全仓库用户旅程/点击/文案承诺 | 数据流/生命周期/错误路径/重启后状态 | 问题行 |

要求：先 `git ls-files` 枚举全部源码，再逐文件审阅；禁止只抽样、禁止只审「最近改过」的文件。
每个 Agent 必须在同一轮内完成两个不同 prompt 的完整 pass，并在输出中标注 pass 编号。

### B. 分域双人交叉（8 个 Agent，每个域 2 个不同 prompt）

| 域 | Agent B1 视角 | Agent B2 视角 |
|---|---|---|
| 冻结/恢复/限制 | 状态机与所有权 | 命令执行/读回/失败恢复 |
| 开机/关机/生命周期 | 时序与竞态 | 持久化与异常重启 |
| COTA | 路径/命令注入/TOCTOU | 备份/删除/恢复 UI 与真实结果 |
| UI/导航/控件 | 点击是否有真实效果 | 布局/语义/返回/文案一致性 |
| 核心目录/Settings/检测 | 目标目录与策略推导 | 数据持久化与迁移 |
| 权限/后端/解析器 | ShellQuote/权限/解析 | 失败 soft/hard 与 API 语义 |
| 模块脚本/构建 | shell 安全/边界 | 安装/开机/卸载/版本一致性 |
| 测试/文档承诺 | 测试是否能捕获回归 | 文档与代码不一致 |

每个分域 Agent 也要做两遍：第一遍静态 + 第二遍调用链/行为验证，使用不同检查清单。

### C. 汇总与复核

1. 主会话合并去重，按严重度写 `docs/28-r8-audit-findings-2026-11-23.md`。
2. 启动**验证审计**（3 个 Agent：安全/生命周期/UX），给出每条 CONFIRMED/REFUTED/UNCERTAIN
   与代码证据；只保留 CONFIRMED/证据充分的。
3. 写 `docs/29-r8-fix-plan-2026-11-23.md`：每条包含文件、函数、具体代码改法、控件布局、
   文案、测试、验收证据。
4. 严格按文档修改；每改一组跑相关单测；全部改完跑全量 test/lint/四包。
5. 最终全量审计：2 个全新 Agent 再完整读一遍全部代码 + 回归专项，检查新问题/漏修。
6. 仍有问题则再修一轮；无 P0/P1 后出包、装机冒烟、清理过程文件、更新文档/hash/记忆。

## 4. 交付物

- `docs/27-r8-full-audit-plan-2026-11-23.md`（本文件）
- `docs/28-r8-audit-findings-2026-11-23.md`（全部候选 + 复核结论）
- `docs/29-r8-fix-plan-2026-11-23.md`（详细修复文档 + 实施记录）
- 修复后的四包、测试/lint 结果、真机冒烟截图
- 清理后的仓库（无临时 log/tmp/中间产物）

## 5. 验收标准

- 所有 P0/P1/P2 要么修复并有测试/真机证据，要么在文档中明确「接受 + 原因」
- 不存在「看起来能点但无效果」的控件；每个可见操作有状态或反馈
- `testDebugUnitTest` 0 失败、`lintDebug` 0 errors、四包 ABI/hash 正确
- 模块 v1.10 开机不拉起 UI；应用冷启动、返回栈、检测、全部应用分类真机可用
- 工作区无遗留过程文件；文档/hash 与产物一致

## 6. 执行状态

- [x] 计划文档
- [ ] A 全量两遍审计（2 Agent）
- [ ] B 分域交叉审计（8 Agent）
- [ ] 汇总/去重 findings
- [ ] 验证审计（3 Agent）
- [ ] 修复文档
- [ ] 实施 + 回归
- [ ] 最终全量审计（2 Agent）
- [ ] 出包/装机/清理/文档
