# 文档索引

代码总览与审计请先读根目录 [`../PROJECT.md`](../PROJECT.md)。  
两个项目的本地地址、包名、版本与产物哈希见 [`../../PROJECTS.md`](../../PROJECTS.md)。

## 现状与设计

| 文档 | 内容 |
|---|---|
| `01-device-and-findings.md` | 机型、系统、HONOR 限制、实测发现 |
| `02-package-inventory.md` | 系统组件清单、风险分级、目标选择 |
| `03-design.md` | 总体架构、权限后端、冻结/解冻/UI 设计 |
| `05-ouc-crash-analysis.md` | 卸载 OUC 后崩溃的证据、假设与红线 |
| `07-boot-freeze-logging-ui-design.md` | 开机自动冻结、AI 深度冻结、日志、检测、Dashboard |
| `09-auto-unbrick-design.md` | 自动救砖（bootloop 自动禁用模块/解冻）设计（独立代理产出） |
| `10-nemu-redesign.md` | Nemu 重命名、冻结/解冻分页、多模式冻结与全组件目录（Phase 1–3 已实施，持续真机验收） |
| `12-device-test-log-2026-11-22.md` | 真机测试日志：冻结/解冻、disable-user、组件、安装、重启验收 |
| `13-custom-freeze-and-shutdown.md` | 自定义冻结任意应用、伪装卸载、关机 initrc/FIFO 强制解冻 |
| `14-update-guard.md` | 独立凪：LSPosed LSPosed 模式；无 LSPosed 时用无障碍覆盖层显示“已最新”；Root/Shizuku/ADB 限制 |
| `15-task-status-2026-11-22.md` | 本轮全部需求的完成/未完成清单与设备实测状态 |
| `16-logic-audit-2026-11-22.md` | 两个 App 的数据流、权限层、卡顿/图标/Toast 根因与验收顺序 |
| `audit-2026-11-22/AUDIT-RESULTS.md` | 六路功能/显示一致性审计、修复清单与保留项 |
| `audit-2026-11-22/` | 设备包/组件原始盘点、代码与 UI 审计材料 |

## 计划与验收

| 文档 | 内容 |
|---|---|
| `04-test-plan.md` | 测试编号、真机记录、未测项、回滚步骤 |
| `06-module-integration-plan.md` | 云控/反诈/COTA 三模块并行集成计划与共享文件规则 |
| `08-parallel-verification-plan.md` | Boot/AI、日志、检测、UI 的并行实现与验证计划 |
| `superpowers/plans/` | 历史实现计划（含绝对路径，作为审计材料保留，不参与构建） |

## 阅读建议

1. 先读 `PROJECT.md` 第 1、2、5、8、9 节；
2. 再看 `03-design.md` 与 `07-boot-freeze-logging-ui-design.md`；
3. 代码从 `app/src/main/java/com/nemu/hibernate/policy/` 和 `core/` 入手；
4. 真机结论只看 `04-test-plan.md` 的“通过/失败/未测”，未执行不得写成通过。
