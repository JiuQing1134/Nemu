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
    fun freezeGroupsKeepSourceOrderSkipCloudAndEmptyGroups() {
        val groups = freezeGroups(
            listOf(
                freeze("core.one", FreezeSource.CORE),
                freeze("cloud.one", FreezeSource.CLOUD),
                freeze("ai.one", FreezeSource.AI),
                freeze("core.two", FreezeSource.CORE),
                freeze("catalog.one", FreezeSource.CATALOG)
            )
        )

        assertEquals(
            listOf("核心目标", "AI 目标", "统一目录目标"),
            groups.map { it.title }
        )
        assertEquals(listOf(2, 1, 1), groups.map { it.rows.size })
        assertEquals(
            listOf("core.one", "core.two"),
            groups.first().rows.map { it.packageName }
        )
        assertTrue(groups.none { it.source == FreezeSource.CLOUD })
        assertTrue(
            freezeGroups(
                listOf(freeze("cloud.only", FreezeSource.CLOUD))
            ).isEmpty()
        )
        assertTrue(freezeGroups(emptyList()).isEmpty())
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
        assertEquals("正常", overview.startup.label)
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
        assertEquals("未读", overview.system.label)
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
        assertEquals("读取失败", thermalServiceFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, thermalServiceFailed.tone)

        val thermalSectionFailed = systemSummary(
            ThermalState(message = "读取失败"),
            CotaState(CotaReadStatus.UNKNOWN)
        )
        assertEquals("读取失败", thermalSectionFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, thermalSectionFailed.tone)

        val cotaFailed = systemSummary(
            ThermalState(services = listOf(service(known = true))),
            CotaState(CotaReadStatus.ERROR)
        )
        assertEquals("读取失败", cotaFailed.label)
        assertEquals(DetectionSummaryTone.ERROR, cotaFailed.tone)

        val noRoot = systemSummary(
            ThermalState(),
            CotaState(CotaReadStatus.NO_ROOT)
        )
        assertEquals("无 root", noRoot.label)
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
        assertEquals("正常", startupSummary(ok).label)
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
        assertEquals("手动解冻，已跳过", skippedLine.value)
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
        assertEquals("正常", bootGuardLabel(enabled))

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
        assertEquals("守卫触发 · 上次未净关机", bootGuardLabel(tripped))

        val skipped = enabled.copy(bootCount = 2, skippedBootCount = 2)
        assertEquals("手动解冻，已跳过", bootGuardLabel(skipped))
    }

    @Test
    fun freezeSummaryLineIsOneCompactLine() {
        assertEquals(
            "已加入计划 · 已冻结",
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
            "已加入计划 · 未冻结",
            freezeSummaryLine(
                freeze(
                    "com.example.pending",
                    FreezeSource.AI,
                    policyApplied = false
                )
            )
        )
        assertEquals(
            "已加入计划 · 已停用",
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
            "已加入计划 · 已生效",
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
            "已加入计划 · 已冻结 · 仍有进程",
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
            listOf("开机计划", "上次关机", "上次开机", "守卫"),
            lines.map { it.label }
        )
        assertEquals(
            listOf(
                "已开启 · 延迟 120 秒",
                boot.describeLastShutdown(),
                boot.describeLastBoot(),
                "正常"
            ),
            lines.map { it.value }
        )
        assertTrue(lines.none { it.danger })
    }

    @Test
    fun bootSummaryLinesFlagReadFailureAndAbnormalStates() {
        val unread = bootSummaryLines(BootSafetyState())
        assertEquals("读取失败", unread.first { it.label == "开机计划" }.value)
        assertTrue(unread.first { it.label == "开机计划" }.danger)

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
        assertEquals("守卫触发 · 上次未净关机", tripped.value)
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
                "开机次数（Boot count）：7",
                "关机详情：关机前解冻了 3 项",
                "开机详情：开机后解冻了 2 项",
                "AI 目标：有启用项",
                "上次结果（自动/手动/解冻）：上次冻结成功",
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
        assertEquals("守卫触发 · 上次未净关机", guard.value)
        assertTrue(bootDetailLines(tripped).none { it.text.contains(guard.value) })
    }

    @Test
    fun bootDetailLinesSkipAbsentDetailsAndFlagFailedFreeze() {
        val bare = bootDetailLines(BootSafetyState())
        assertEquals(
            listOf(
                "开机次数（Boot count）：-1",
                "AI 目标：无启用项",
                "上次结果（自动/手动/解冻）：尚未执行"
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
            it.text.startsWith("上次结果")
        }
        assertTrue(failedLine.danger)

        // A stale snapshot is already covered by the read-failure warning.
        val stale = bootDetailLines(failed.copy(readOk = false)).first {
            it.text.startsWith("上次结果")
        }
        assertFalse(stale.danger)
    }
}
