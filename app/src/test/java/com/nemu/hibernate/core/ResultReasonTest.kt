package com.nemu.hibernate.core

import com.nemu.hibernate.policy.StrategyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultReasonTest {
    @Test
    fun everyReasonIsAShortChineseLine() {
        ResultReason.entries.forEach { reason ->
            assertTrue("$reason 缺少文案", reason.line.isNotBlank())
            assertTrue("$reason 文案过长：${reason.line}", reason.line.length <= 18)
            listOf("DRY-RUN", "OK", "FAIL", "PackageManager", "SUSPEND").forEach {
                assertFalse(
                    "$reason 文案泄露技术词 $it：${reason.line}",
                    reason.line.contains(it)
                )
            }
        }
    }

    @Test
    fun notePrefixesSubjectAndAppendsChineseDetail() {
        val note = ResultNote(
            reason = ResultReason.PRECHECK_FAILED,
            subject = "浏览器",
            detail = "风险不支持"
        )

        assertEquals("浏览器：预检未通过（风险不支持）", note.line())
    }

    @Test
    fun noteWithoutSubjectStartsWithReason() {
        val note = ResultNote(ResultReason.BACKEND_UNAVAILABLE)

        assertEquals("无可用权限", note.line())
    }

    @Test
    fun resultLineKeepsOutcomeAndCapsReasons() {
        val reasons = listOf(
            ResultReason.BASELINE_READ_FAILED.at("天气"),
            ResultReason.COMMAND_FAILED.at("浏览器"),
            ResultReason.MARKER_WRITE_FAILED.at("日历"),
            ResultReason.RESTORE_UNVERIFIED.at("时钟")
        )

        val line = ResultCopy.resultLine(
            ok = false,
            subject = "天气",
            reasons = reasons
        )

        assertTrue(line.startsWith("天气：失败（"))
        assertTrue(line.contains("无法读取原始状态，未执行"))
        assertTrue(line.endsWith("；…）"))
        assertFalse(line.contains("SUSPEND"))
    }

    @Test
    fun restrictionResultLineNamesTheQualifier() {
        val line = ResultCopy.resultLine(
            ok = false,
            subject = "天气",
            reasons = listOf(ResultReason.RESTRICTION_NOT_CONFIRMED.at("天气")),
            qualifier = "限制"
        )

        assertEquals("天气：限制失败（限制未生效）", line)
    }

    @Test
    fun runSummaryUsesCountsAndReadableReasons() {
        val summary = ResultCopy.runSummary(
            ok = false,
            attempted = 3,
            applied = 1,
            failed = 2,
            reasons = listOf(
                ResultReason.SUSPENDED_BY_OTHER.at("号码识别"),
                ResultReason.HIDE_NOT_CONFIRMED.at("天气")
            )
        )

        assertEquals(
            "有失败：尝试 3 个，成功 1，失败 2；" +
                "号码识别：操作前已被暂停，不接管；天气：无法确认隐藏状态",
            summary
        )
    }

    @Test
    fun strategySummaryHidesEnumNamesAndRawBackendOutput() {
        val result = StrategyResult(
            ok = false,
            packageName = "com.example.weather",
            strategy = FreezeStrategy.SUSPEND,
            commands = listOf("pm suspend com.example.weather"),
            reasons = listOf(ResultReason.PACKAGE_NOT_CONFIRMED.at("天气")),
            label = "天气"
        )

        assertEquals("天气：失败（系统未确认变更）", result.summary())
        assertFalse(result.summary().contains("SUSPEND"))
        assertFalse(result.summary().contains("pm "))
    }
}
