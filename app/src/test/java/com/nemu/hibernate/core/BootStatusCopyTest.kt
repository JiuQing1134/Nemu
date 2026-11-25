package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStatusCopyTest {
    @Test
    fun emptyResultIsNotExecuted() {
        assertEquals("尚未执行", BootStatusCopy.of(null).chip)
        assertEquals("尚未执行", BootStatusCopy.of("  ").chip)
        assertNull(BootStatusCopy.of(null).skipReason)
    }

    @Test
    fun legacyManualUnfreezeBecomesAShortSkipToken() {
        val selective = BootStatusCopy.of(
            "手动选择性解冻：本次开机不再自动冻结"
        )
        assertEquals("已跳过 · 手动解冻", selective.chip)
        assertEquals("手动解冻", selective.skipReason)
        assertEquals("本次开机不自动冻结", selective.detail)

        val all = BootStatusCopy.of("手动全部解冻：本次开机不再自动冻结")
        assertEquals("已跳过 · 全部解冻", all.chip)
        assertEquals("全部解冻", all.skipReason)
    }

    @Test
    fun skipReasonsMapToStableShortTokens() {
        val cases = mapOf(
            "已跳过：用户已在本 boot 手动解冻" to "手动解冻",
            "已跳过：上次未检测到干净关机" to "守卫触发",
            "已跳过：超过开机冻结窗口" to "错过时机",
            "已跳过：自动救砖模式" to "救援模式",
            "已跳过：无法确认救援状态" to "状态未知",
            "已跳过：已有冻结/解冻操作" to "已操作",
            "已跳过：用户已跳过本 boot 的开机自动冻结" to "用户跳过",
            "已跳过：开机自动冻结未开启" to "未开启"
        )
        cases.forEach { (raw, expected) ->
            val copy = BootStatusCopy.of(raw)
            assertEquals(raw, expected, copy.skipReason)
            assertEquals(raw, "已跳过 · $expected", copy.chip)
        }
    }

    @Test
    fun successFailurePreviewAndNoPlanAreShort() {
        assertEquals("开机冻结成功", BootStatusCopy.of("开机冻结成功").chip)
        assertEquals("手动冻结成功", BootStatusCopy.of("手动冻结成功").chip)
        assertEquals("策略切换成功", BootStatusCopy.of("策略切换成功").chip)
        assertEquals("失败", BootStatusCopy.of("开机冻结有失败").chip)
        assertEquals("预览 · 未改动", BootStatusCopy.of("预览：计划完成").chip)
        assertEquals("无计划", BootStatusCopy.of("没有启用任何目标").chip)
        assertEquals("部分成功", BootStatusCopy.of("部分成功").chip)
        assertEquals("尚未执行", BootStatusCopy.of("尚未执行").chip)
    }

    @Test
    fun unknownRawTextIsKeptAsDetailAndTheChipStaysShort() {
        val raw = "一段没有识别的旧结果：包含很长很长的说明文字"
        val copy = BootStatusCopy.of(raw)
        assertEquals("已记录", copy.chip)
        assertEquals(raw, copy.detail)
    }

    @Test
    fun everyChipFitsTheStatusBudget() {
        listOf(
            "手动选择性解冻：本次开机不再自动冻结",
            "手动全部解冻：本次开机不再自动冻结",
            "已跳过：用户已在本 boot 手动解冻",
            "已跳过：上次未检测到干净关机",
            "已跳过：超过开机冻结窗口",
            "已跳过：自动救砖模式",
            "已跳过：无法确认救援状态",
            "已跳过：已有冻结/解冻操作",
            "已跳过：用户已跳过本 boot 的开机自动冻结",
            "已跳过：开机自动冻结未开启",
            "开机冻结成功",
            "失败：开机冻结有失败",
            "预览：计划完成",
            "没有启用任何目标"
        ).forEach { raw ->
            val chip = BootStatusCopy.of(raw).chip
            assertTrue("chip too long: $chip", chip.length <= 10)
        }
    }
}
