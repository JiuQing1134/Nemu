package com.nemu.hibernate.ui

import com.nemu.hibernate.ui.theme.ThemeMode
import com.nemu.hibernate.ui.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEntriesTest {
    private fun state(
        dryRun: Boolean = true,
        boot: BootFreezeUiState = BootFreezeUiState(),
        logsEnabled: Boolean = false,
        palette: ThemePalette = ThemePalette.BLUE,
        mode: ThemeMode = ThemeMode.SYSTEM
    ) = DashboardState(
        dryRun = dryRun,
        bootFreeze = boot,
        cota = CotaUiState(),
        logSettings = LogUiState(enabled = logsEnabled),
        themePalette = palette,
        themeMode = mode
    )

    @Test
    fun entriesFollowFixedSectionOrder() {
        val ids = settingsEntries(state()).map { it.id }

        assertEquals(SettingsEntryId.entries.toList(), ids)
    }

    @Test
    fun dryRunEntrySaysPreviewOnly() {
        val execution = settingsEntries(state(dryRun = true))
            .first { it.id == SettingsEntryId.EXECUTION }

        assertEquals("执行与权限", execution.title)
        assertEquals("预览 · 手动不改；安全清理仍执行", execution.summary)
    }

    @Test
    fun actualRunEntryWarnsAboutModification() {
        val execution = settingsEntries(state(dryRun = false))
            .first { it.id == SettingsEntryId.EXECUTION }

        assertEquals("实际执行 · 会修改手机", execution.summary)
    }

    @Test
    fun bootEntryWithoutPlanSaysNothingWillRun() {
        val boot = settingsEntries(
            state(boot = BootFreezeUiState(enabled = true))
        ).first { it.id == SettingsEntryId.BOOT }

        assertEquals("开机与安全", boot.title)
        assertEquals("无计划目标", boot.summary)
    }

    @Test
    fun bootEntryReportsPlanCountAndLastResult() {
        val boot = settingsEntries(
            state(
                boot = BootFreezeUiState(
                    enabled = true,
                    plannedCount = 3,
                    lastResult = "成功",
                    lastSucceeded = true
                )
            )
        ).first { it.id == SettingsEntryId.BOOT }

        assertEquals("计划 3 个 · 成功", boot.summary)
    }

    @Test
    fun bootEntrySaysNotRunBeforeFirstAttempt() {
        val boot = settingsEntries(
            state(
                boot = BootFreezeUiState(
                    enabled = true,
                    plannedCount = 2,
                    lastResult = null
                )
            )
        ).first { it.id == SettingsEntryId.BOOT }

        assertEquals("计划 2 个 · 尚未执行", boot.summary)
    }

    @Test
    fun appearanceEntryShowsPaletteAndMode() {
        val appearance = settingsEntries(
            state(palette = ThemePalette.BLUE, mode = ThemeMode.DARK)
        ).first { it.id == SettingsEntryId.APPEARANCE }

        assertEquals("外观", appearance.title)
        assertEquals("蓝色 · 深色", appearance.summary)
    }

    @Test
    fun logsEntryFollowsSwitch() {
        val off = settingsEntries(state(logsEnabled = false))
            .first { it.id == SettingsEntryId.LOGS }
        val on = settingsEntries(state(logsEnabled = true))
            .first { it.id == SettingsEntryId.LOGS }

        assertEquals("已关闭", off.summary)
        assertEquals("已开启", on.summary)
    }

    @Test
    fun everyEntryHasPageSubtitle() {
        SettingsEntryId.entries.forEach { id ->
            assertTrue(
                "$id 缺少二级页说明",
                settingsPageSubtitle(id).isNotBlank()
            )
        }
    }

    @Test
    fun rootListSummariesStayShort() {
        listOf(
            "部分成功",
            "手动选择性解冻：本次开机不再自动冻结",
            "开机冻结成功"
        ).forEach { lastResult ->
            val entries = settingsEntries(
                state(
                    boot = BootFreezeUiState(
                        enabled = true,
                        plannedCount = 12,
                        lastResult = lastResult
                    ),
                    palette = ThemePalette.GRAPHITE,
                    mode = ThemeMode.SYSTEM
                )
            )

            entries.forEach { entry ->
                assertTrue(
                    "${entry.title} 的摘要过长：${entry.summary}",
                    entry.summary.length <= 20
                )
            }
        }
    }

    @Test
    fun legacyManualSkipIsSummarizedAsAShortSkipToken() {
        val entries = settingsEntries(
            state(
                boot = BootFreezeUiState(
                    enabled = true,
                    plannedCount = 12,
                    lastResult = "手动选择性解冻：本次开机不再自动冻结"
                ),
                palette = ThemePalette.GRAPHITE,
                mode = ThemeMode.SYSTEM
            )
        )

        val boot = entries.first { it.id == SettingsEntryId.BOOT }
        assertTrue(boot.summary.contains("已跳过 · 手动解冻"))
    }
}
