package com.nemu.hibernate.ui

import com.nemu.hibernate.ui.theme.ThemeMode
import com.nemu.hibernate.ui.theme.ThemePalette

/**
 * 设置根页的五个入口。顺序即展示顺序，也是二级页返回后的稳定顺序；
 * 不持久化页面选择——从底部导航重新进入设置时总是回到根页。
 */
enum class SettingsEntryId {
    EXECUTION,
    BOOT,
    APPEARANCE,
    LOGS,
    ADVANCED
}

/** 根页一行：标题 + 一行摘要；摘要只描述当前状态，不放操作。 */
data class SettingsEntry(
    val id: SettingsEntryId,
    val title: String,
    val summary: String
)

/**
 * 把 [DashboardState] 压成设置根页的只读摘要。
 *
 * 纯函数，便于 JVM 测试；Compose 侧只负责点击后跳到对应二级页。
 */
fun settingsEntries(state: DashboardState): List<SettingsEntry> = listOf(
    SettingsEntry(
        id = SettingsEntryId.EXECUTION,
        title = "执行与权限",
        summary = if (state.dryRun) {
            "预览 · 手动不改；安全清理仍执行"
        } else {
            "实际执行 · 会修改手机"
        }
    ),
    SettingsEntry(
        id = SettingsEntryId.BOOT,
        title = "开机与恢复",
        summary = bootEntrySummary(state.bootFreeze)
    ),
    SettingsEntry(
        id = SettingsEntryId.APPEARANCE,
        title = "外观",
        summary = "${state.themePalette.label} · ${state.themeMode.label}"
    ),
    SettingsEntry(
        id = SettingsEntryId.LOGS,
        title = "日志",
        summary = if (state.logSettings.enabled) "已开启" else "已关闭"
    ),
    SettingsEntry(
        id = SettingsEntryId.ADVANCED,
        title = "高级",
        summary = "COTA 与保护规则"
    )
)

internal fun bootEntrySummary(boot: BootFreezeUiState): String = when {
    !boot.enabled -> "开机自动应用已关闭"
    boot.plannedCount <= 0 -> "无计划目标"
    boot.lastResult == null -> "计划 ${boot.plannedCount} 个 · 尚未执行"
    else -> "计划 ${boot.plannedCount} 个 · ${boot.lastResult}"
}

/** 二级页标题下的一行说明；只描述本页范围，不重复控件文案。 */
fun settingsPageSubtitle(id: SettingsEntryId): String = when (id) {
    SettingsEntryId.EXECUTION -> "实际执行与授权状态。"
    SettingsEntryId.BOOT -> "开机计划与上次安全解冻。"
    SettingsEntryId.APPEARANCE -> "风格、配色与明暗。"
    SettingsEntryId.LOGS -> "操作记录与启动日志。"
    SettingsEntryId.ADVANCED -> "COTA 云控参数；保护规则不可关闭。"
}
