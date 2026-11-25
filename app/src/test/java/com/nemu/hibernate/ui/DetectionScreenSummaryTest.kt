package com.nemu.hibernate.ui

import com.nemu.hibernate.detection.BootSafetyState
import com.nemu.hibernate.detection.CotaReadStatus
import com.nemu.hibernate.detection.CotaState
import com.nemu.hibernate.detection.DetectionSnapshot
import com.nemu.hibernate.detection.FreezeMode
import com.nemu.hibernate.detection.FreezeSource
import com.nemu.hibernate.detection.FreezeState
import com.nemu.hibernate.detection.ThermalServiceState
import com.nemu.hibernate.detection.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the detection overview, grouping helpers and the merged
 * boot-safety card rows. These read only data-layer models and never touch
 * Compose or Android classes.
 */
class DetectionScreenSummaryTest {

    private fun freeze(
        packageName: String,
        source: FreezeSource,
        policy: Boolean = true,
        policyApplied: Boolean = true,
        suspended: Boolean = false,
        mode: FreezeMode = FreezeMode.SUSPEND,
        known: Boolean = true,
        installed: Boolean = true,
        enabled: Boolean = true,
        componentDisabled: Boolean? = null,
        componentPartial: Boolean = false,
        restrictionApplied: Boolean = false,
        restrictionActual: Boolean? = null,
        processRunning: Boolean? = null
    ): FreezeState = FreezeState(
        packageName = packageName,
        label = packageName,
        source = source,
        mode = mode,
        policyEnabled = policy,
        installed = installed,
        actualKnown = known,
        enabled = enabled,
        suspended = suspended,
        processRunning = processRunning,
        componentDisabled = componentDisabled,
        componentPartial = componentPartial,
        restrictionApplied = restrictionApplied,
        restrictionActual = restrictionActual,
        policyApplied = policyApplied
    )

    private fun service(known: Boolean) = ThermalServiceState(
        packageName = "com.hihonor.powergenie",
        label = "PowerGenie",
        installed = true,
        actualKnown = known,
        enabled = true,
        suspended = false
    )

    @Test
    fun categoryLabelsMatchTheUnifiedControl() {
        assertEquals(
            listOf("应用冻结", "云控遥测", "系统服务", "启动安全"),
            DetectionCategory.entries.map { it.label }
        )
    }

    @Test
    fun overviewCountsUseSnapshotAndExcludeCloudFromFreezeCount() {
        val snapshot = DetectionSnapshot(
            freezeStates = listOf(
                freeze("core.ok", FreezeSource.CORE, suspended = true),
                freeze("core.bad", FreezeSource.CORE),
                freeze("cloud.bad", FreezeSource.CLOUD)
            ),
            thermal = ThermalState(services = listOf(service(known = true))),
            cota = CotaState(CotaReadStatus.OK),
            bootSafety = BootSafetyState(
                readOk = true,
                bootCount = 3,
                lastShutdownResult = "成功",
                lastBootResult = "安全解冻成功"
            )
        )

        val overview = snapshot.overviewSummary()

        assertEquals(1, overview.freezeCount)
        assertEquals(2, overview.freezeTotal)
        assertEquals(1, overview.freezeMismatchCount)
        assertEquals(0, overview.cloudCount)
        assertEquals(1, overview.cloudTotal)
        assertEquals(1, overview.cloudMismatchCount)
        assertEquals("1 项", overview.system.label)
        assertEquals(DetectionSummaryTone.PRIMARY, overview.system.tone)
        assertEquals("未见异常", overview.startup.label)
        assertEquals(DetectionSummaryTone.PRIMARY, overview.startup.tone)
    }

    @Test
    fun overviewIsEmptyWhenSnapshotHasNoRows() {
        val overview = DetectionSnapshot().overviewSummary()

        assertEquals(0, overview.freezeCount)
        assertEquals(0, overview.freezeTotal)
        assertEquals(0, overview.freezeMismatchCount)
        assertEquals(0, overview.cloudCount)
        assertEquals(0, overview.cloudTotal)
        assertEquals(0, overview.cloudMismatchCount)
        assertEquals("未读取", overview.system.label)
        assertEquals(DetectionSummaryTone.NEUTRAL, overview.system.tone)
        assertEquals("读取失败", overview.startup.label)
        assertEquals(DetectionSummaryTone.ERROR, overview.startup.tone)
    }

    @Test
    fun systemSummaryReportsThermalAndCotaReadFailures() {
        val ok = systemSummary(
            ThermalState(services = listOf(service(known = true))),
            CotaState(CotaReadStatus.OK)
        )
        assertEquals("1 项", ok.label)
        assertEquals(DetectionSummaryTone.PRIMARY, ok.tone)

        val thermalServiceFailed = systemSummary(
            ThermalState(services = listOf(service(known = false))),
            CotaState(CotaReadStatus.OK)
        )
        assertEquals("失败", thermalServiceFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, thermalServiceFailed.tone)

        val thermalSectionFailed = systemSummary(
            ThermalState(message = "读取失败"),
            CotaState(CotaReadStatus.UNKNOWN)
        )
        assertEquals("失败", thermalSectionFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, thermalSectionFailed.tone)

        val cotaFailed = systemSummary(
            ThermalState(services = listOf(service(known = true))),
            CotaState(CotaReadStatus.ERROR)
        )
        assertEquals("失败", cotaFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, cotaFailed.tone)

        val noRoot = systemSummary(
            ThermalState(),
            CotaState(CotaReadStatus.NO_ROOT)
        )
        assertEquals("无 Root", noRoot.label)
        assertEquals(DetectionSummaryTone.NEUTRAL, noRoot.tone)

        val cotaOnly = systemSummary(
            ThermalState(),
            CotaState(CotaReadStatus.OK)
        )
        assertEquals("已读取", cotaOnly.label)
        assertEquals(DetectionSummaryTone.PRIMARY, cotaOnly.tone)
    }

    @Test
    fun startupSummaryDistinguishesReadFailureOkAndAnomaly() {
        val unread = startupSummary(BootSafetyState())
        assertEquals("读取失败", unread.label)
        assertEquals(DetectionSummaryTone.ERROR, unread.tone)

        // Read failure wins over a stored failure string: the snapshot is
        // stale and must not be presented as a real anomaly value.
        val staleWithFailure = startupSummary(
            BootSafetyState(readOk = false, lastShutdownResult = "失败")
        )
        assertEquals("读取失败", staleWithFailure.label)
        assertEquals(DetectionSummaryTone.ERROR, staleWithFailure.tone)

        val ok = BootSafetyState(
            readOk = true,
            bootCount = 2,
            lastShutdownResult = "成功",
            lastBootResult = "安全解冻成功"
        )
        assertEquals("未见异常", startupSummary(ok).label)
        assertEquals(DetectionSummaryTone.PRIMARY, startupSummary(ok).tone)
        assertFalse(ok.shutdownAbnormal())
        // A never-run shutdown is not a failure.
        assertFalse(ok.copy(lastShutdownResult = "尚未执行").shutdownAbnormal())

        val shutdownFailed = startupSummary(ok.copy(lastShutdownResult = "失败"))
        assertEquals("异常", shutdownFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, shutdownFailed.tone)

        val shutdownInterrupted = startupSummary(
            ok.copy(lastShutdownResult = "未完成：关机前解冻被中断")
        )
        assertEquals("异常", shutdownInterrupted.label)

        val bootFailed = startupSummary(
            ok.copy(lastBootResult = "安全解冻失败：无可用权限")
        )
        assertEquals("异常", bootFailed.label)

        val guardTripped = BootSafetyState(
            readOk = true,
            bootCount = 2,
            lastBootFreezeAttemptBootCount = 1,
            lastBootFreezeAttemptTime = 1_000L
        )
        assertTrue(guardTripped.bootloopGuardTripped())
        assertEquals("异常", startupSummary(guardTripped).label)
    }

    @Test
    fun guardRowIsOnlyDangerousWhenTheBootloopGuardTripped() {
        val skipped = BootSafetyState(
            readOk = true,
            bootFreezeEnabled = true,
            bootCount = 2,
            skippedBootCount = 2
        )
        val skippedLine = bootSummaryLines(skipped)
            .first { it.label == "守卫" }
        assertEquals("本次开机已手动解冻，自动冻结已跳过", skippedLine.value)
        assertFalse(skippedLine.danger)

        val disabledSkip = skipped.copy(bootFreezeEnabled = false)
        val disabledLine = bootSummaryLines(disabledSkip)
            .first { it.label == "守卫" }
        assertEquals("未启用", disabledLine.value)
        assertFalse(disabledLine.danger)

        val tripped = BootSafetyState(
            readOk = true,
            bootFreezeEnabled = true,
            bootCount = 2,
            lastBootFreezeAttemptBootCount = 1,
            lastBootFreezeAttemptTime = 1_000L
        )
        val trippedLine = bootSummaryLines(tripped)
            .first { it.label == "守卫" }
        assertTrue(trippedLine.danger)
        assertTrue(trippedLine.value.contains("守卫触发"))

        // The overview says 异常 for a tripped guard even when the master
        // switch is now off; the row must explain it instead of saying 未启用.
        val disabledTripped = tripped.copy(bootFreezeEnabled = false)
        val disabledTrippedLine = bootSummaryLines(disabledTripped)
            .first { it.label == "守卫" }
        assertTrue(disabledTrippedLine.danger)
        assertTrue(disabledTrippedLine.value.contains("守卫触发"))
    }

    @Test
    fun skippedBootDetailDoesNotRepeatTheSummaryResult() {
        val boot = BootSafetyState(
            readOk = true,
            bootCount = 2,
            lastBootResult = "已跳过：本次开机已有操作",
            lastBootDetail = "本次开机已执行过手动解冻，跳过安全解冻"
        )

        assertTrue(
            bootDetailLines(boot).none { it.text.contains("开机详情") }
        )
    }

    @Test
    fun bootPlanLabelDescribesDelay() {
        assertEquals("已关闭", bootPlanLabel(BootSafetyState()))
        assertEquals(
            "已开启 · 延迟 120 秒",
            bootPlanLabel(
                BootSafetyState(
                    bootFreezeEnabled = true,
                    bootFreezeDelaySeconds = 120
                )
            )
        )
    }

    @Test
    fun bootGuardLabelStaysCompact() {
        assertEquals("读取失败", bootGuardLabel(BootSafetyState()))

        val enabled = BootSafetyState(
            readOk = true,
            bootFreezeEnabled = true,
            bootFreezeDelaySeconds = 120,
            bootCount = 1
        )
        assertEquals("未触发", bootGuardLabel(enabled))

        assertEquals(
            "未启用",
            bootGuardLabel(
                BootSafetyState(readOk = true, bootFreezeEnabled = false)
            )
        )

        val tripped = enabled.copy(
            bootCount = 2,
            lastBootFreezeAttemptBootCount = 1,
            lastBootFreezeAttemptTime = 1_000L
        )
        assertEquals("守卫触发 · 上次未正常关机", bootGuardLabel(tripped))

        val skipped = enabled.copy(bootCount = 2, skippedBootCount = 2)
        assertEquals("本次开机已手动解冻，自动冻结已跳过", bootGuardLabel(skipped))
    }

    @Test
    fun freezeSummaryLineIsOneCompactLine() {
        assertEquals(
            "已添加 · 已冻结",
            freezeSummaryLine(
                freeze(
                    "com.example.frozen",
                    FreezeSource.CORE,
                    suspended = true,
                    processRunning = false
                )
            )
        )
        assertEquals(
            "已添加 · 未冻结",
            freezeSummaryLine(
                freeze(
                    "com.example.pending",
                    FreezeSource.AI,
                    policyApplied = false
                )
            )
        )
        assertEquals(
            "已添加 · 已停用",
            freezeSummaryLine(
                freeze(
                    "com.example.disabled",
                    FreezeSource.CATALOG,
                    mode = FreezeMode.DISABLE,
                    enabled = false
                )
            )
        )
        assertEquals(
            "已添加 · 已隐藏",
            freezeSummaryLine(
                freeze(
                    "com.example.hidden",
                    FreezeSource.CORE,
                    mode = FreezeMode.HIDE,
                    installed = false
                )
            )
        )
        assertEquals(
            "仅观察",
            freezeSummaryLine(
                freeze(
                    "com.example.observed",
                    FreezeSource.CORE,
                    mode = FreezeMode.OBSERVE
                )
            )
        )
        assertEquals(
            "已添加 · 已冻结 · 仍有进程",
            freezeSummaryLine(
                freeze(
                    "com.example.running",
                    FreezeSource.CORE,
                    suspended = true,
                    processRunning = true
                )
            )
        )
    }

    @Test
    fun bootSummaryLinesKeepTheFourFactsInOnePlace() {
        val boot = BootSafetyState(
            readOk = true,
            bootCount = 2,
            lastShutdownResult = "成功",
            lastBootResult = "安全解冻成功",
            bootFreezeEnabled = true,
            bootFreezeDelaySeconds = 120
        )

        val lines = bootSummaryLines(boot)

        assertEquals(
            listOf("开机冻结", "上次关机", "上次开机", "守卫"),
            lines.map { it.label }
        )
        assertEquals(
            listOf(
                "已开启 · 延迟 120 秒",
                boot.describeLastShutdown(),
                boot.describeLastBoot(),
                "未触发"
            ),
            lines.map { it.value }
        )
        assertTrue(lines.none { it.danger })
    }

    @Test
    fun bootSummaryLinesFlagReadFailureAndAbnormalStates() {
        val unread = bootSummaryLines(BootSafetyState())
        assertEquals("读取失败", unread.first { it.label == "开机冻结" }.value)
        assertTrue(unread.first { it.label == "开机冻结" }.danger)

        val abnormal = bootSummaryLines(
            BootSafetyState(
                readOk = true,
                bootCount = 2,
                lastShutdownResult = "失败",
                lastBootResult = "安全解冻失败"
            )
        )
        assertTrue(abnormal.first { it.label == "上次关机" }.danger)
        assertTrue(abnormal.first { it.label == "上次开机" }.danger)

        val tripped = bootSummaryLines(
            BootSafetyState(
                readOk = true,
                bootCount = 2,
                bootFreezeEnabled = true,
                lastBootFreezeAttemptBootCount = 1,
                lastBootFreezeAttemptTime = 1_000L
            )
        ).first { it.label == "守卫" }
        assertEquals("守卫触发 · 上次未正常关机", tripped.value)
        assertTrue(tripped.danger)

        val disabled = bootSummaryLines(BootSafetyState(readOk = true))
            .first { it.label == "守卫" }
        assertEquals("未启用", disabled.value)
        assertFalse(disabled.danger)
    }

    @Test
    fun bootDetailLinesOnlyCarryFieldsTheSummaryLacks() {
        val boot = BootSafetyState(
            readOk = true,
            bootCount = 7,
            lastShutdownResult = "上次关机成功",
            lastShutdownDetail = "关机前解冻了 3 项",
            lastBootResult = "上次开机成功",
            lastBootDetail = "开机后解冻了 2 项",
            bootFreezeEnabled = true,
            bootFreezeDelaySeconds = 120,
            aiTargetsEnabledAny = true,
            lastBootFreezeResult = "上次冻结成功",
            lastBootFreezeDetail = "已完成 2 项"
        )

        val details = bootDetailLines(boot)
        val texts = details.map { it.text }

        assertEquals(
            listOf(
                "开机次数：7",
                "关机详情：关机前解冻了 3 项",
                "开机详情：开机后解冻了 2 项",
                "AI 应用：有启用项",
                "上次开机冻结结果：上次冻结成功",
                "冻结详情：已完成 2 项"
            ),
            texts
        )
        // Shutdown / boot / plan / guard values stay in the summary rows only.
        val summaryValues = bootSummaryLines(boot).map { it.value }
        assertTrue(
            texts.none { text ->
                summaryValues.any { value -> value.isNotBlank() && value in text }
            }
        )
        assertTrue(details.none { it.danger })
    }

    @Test
    fun bootDetailLinesDoNotRepeatTheGuardNote() {
        val tripped = BootSafetyState(
            readOk = true,
            bootCount = 2,
            bootFreezeEnabled = true,
            lastBootFreezeAttemptBootCount = 1,
            lastBootFreezeAttemptTime = 1_000L
        )
        val guard = bootSummaryLines(tripped).first { it.label == "守卫" }
        assertEquals("守卫触发 · 上次未正常关机", guard.value)
        assertTrue(bootDetailLines(tripped).none { it.text.contains(guard.value) })
    }

    @Test
    fun bootDetailLinesSkipAbsentDetailsAndFlagFailedFreeze() {
        val bare = bootDetailLines(BootSafetyState())
        assertEquals(
            listOf(
                "开机次数：未知",
                "AI 应用：无启用项",
                "上次开机冻结结果：尚未执行"
            ),
            bare.map { it.text }
        )
        assertTrue(bare.none { it.danger })

        val blankDetails = bootDetailLines(
            BootSafetyState(
                readOk = true,
                lastShutdownDetail = " ",
                lastBootDetail = "",
                lastBootFreezeDetail = "  "
            )
        ).map { it.text }
        assertTrue(blankDetails.none { it.startsWith("关机详情") })
        assertTrue(blankDetails.none { it.startsWith("开机详情") })
        assertTrue(blankDetails.none { it.startsWith("冻结详情") })

        val failed = BootSafetyState(
            readOk = true,
            lastBootFreezeResult = "安全解冻失败：无可用权限"
        )
        val failedLine = bootDetailLines(failed).first {
            it.text.startsWith("上次开机冻结结果")
        }
        assertTrue(failedLine.danger)

        // A stale snapshot is already covered by the read-failure warning.
        val stale = bootDetailLines(failed.copy(readOk = false)).first {
            it.text.startsWith("上次开机冻结结果")
        }
        assertFalse(stale.danger)
    }

    @Test
    fun bootSummaryLinesCarrySemanticToneAndHighlight() {
        val boot = BootSafetyState(
            readOk = true,
            bootCount = 82,
            lastShutdownResult = "成功",
            lastShutdownTime = 1_000L,
            lastBootResult = "已跳过：本次开机已有冻结/解冻操作",
            lastBootTime = 2_000L,
            bootFreezeEnabled = true,
            bootFreezeDelaySeconds = 120,
            lastBootFreezeBootCount = 82
        )

        val lines = bootSummaryLines(boot).associateBy { it.label }

        assertEquals(BootLineTone.PRIMARY, lines.getValue("开机冻结").tone)
        assertEquals("已开启", lines.getValue("开机冻结").highlight)
        assertEquals(BootLineTone.PRIMARY, lines.getValue("上次关机").tone)
        assertEquals("成功", lines.getValue("上次关机").highlight)
        assertEquals(BootLineTone.WARNING, lines.getValue("上次开机").tone)
        assertEquals("已跳过", lines.getValue("上次开机").highlight)
        // "本次开机已有冻结结果" is a normal note, not a success/failure.
        assertEquals(BootLineTone.NEUTRAL, lines.getValue("守卫").tone)
    }

    @Test
    fun trippedGuardUsesErrorToneAndHighlight() {
        val tripped = BootSafetyState(
            readOk = true,
            bootCount = 2,
            bootFreezeEnabled = true,
            lastBootFreezeAttemptBootCount = 1,
            lastBootFreezeAttemptTime = 1_000L
        )

        val guard = bootSummaryLines(tripped).first { it.label == "守卫" }

        assertEquals(BootLineTone.ERROR, guard.tone)
        assertEquals("守卫触发", guard.highlight)
    }

    @Test
    fun shutdownDetailWithSemicolonsBecomesAList() {
        val boot = BootSafetyState(
            readOk = true,
            lastShutdownDetail = "安全解冻成功；组件：成功，尝试 0，成功 0，失败 0；" +
                "隐藏：成功，尝试 0，成功 0，失败 0；" +
                "限制：成功，尝试 0，成功 0，失败 0"
        )

        val details = bootDetailLines(boot)
        val header = details.first { it.text == "关机详情" }
        assertEquals("关机详情", header.text)
        assertTrue(header.header)

        val bullets = details.filter { it.bullet }
        assertEquals(
            listOf(
                "安全解冻成功",
                "组件：成功 · 尝试 0 · 成功 0 · 失败 0",
                "隐藏：成功 · 尝试 0 · 成功 0 · 失败 0",
                "限制：成功 · 尝试 0 · 成功 0 · 失败 0"
            ),
            bullets.map { it.text }
        )
        assertTrue(bullets.all { it.tone == BootLineTone.PRIMARY })
        assertEquals("安全解冻成功", bullets[0].highlight)
        assertEquals("成功", bullets[1].highlight)
    }

    @Test
    fun failedShutdownDetailColorsOnlyTheSegmentResult() {
        val boot = BootSafetyState(
            readOk = true,
            lastShutdownDetail = "安全解冻有失败；组件：失败，尝试 1，成功 0，失败 1；" +
                "隐藏：成功，尝试 0，成功 0，失败 0"
        )

        val bullets = bootDetailLines(boot).filter { it.bullet }

        assertEquals(BootLineTone.ERROR, bullets[0].tone)
        assertEquals("安全解冻有失败", bullets[0].highlight)
        assertEquals(BootLineTone.ERROR, bullets[1].tone)
        assertEquals("失败", bullets[1].highlight)
        // The later "失败 0" is a count, not the segment result.
        assertEquals(BootLineTone.PRIMARY, bullets[2].tone)
        assertEquals("成功", bullets[2].highlight)
    }

    @Test
    fun manualBootFreezeSuccessUsesPrimaryTone() {
        val boot = BootSafetyState(
            readOk = true,
            lastBootFreezeResult = "手动冻结成功（2026-11-25 20:52:03）"
        )

        val line = bootDetailLines(boot).first {
            it.text.startsWith("上次开机冻结结果")
        }

        assertEquals(BootLineTone.PRIMARY, line.tone)
        assertEquals("手动冻结成功", line.highlight)
    }
}
