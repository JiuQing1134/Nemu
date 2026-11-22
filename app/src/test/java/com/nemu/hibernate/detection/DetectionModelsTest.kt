package com.nemu.hibernate.detection

import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaFileKind
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests: no Android classes are loaded by DetectionModels, so these
 * mapping helpers can be verified without Robolectric or a device.
 */
class DetectionModelsTest {

    private fun freeze(
        policy: Boolean,
        installed: Boolean = true,
        known: Boolean = true,
        enabled: Boolean = true,
        suspended: Boolean = false,
        stopped: Boolean = false,
        processRunning: Boolean? = null,
        mode: FreezeMode = FreezeMode.SUSPEND
    ): FreezeState = FreezeState(
        packageName = "com.example.app",
        label = "示例应用",
        source = FreezeSource.CORE,
        mode = mode,
        policyEnabled = policy,
        installed = installed,
        actualKnown = known,
        enabled = enabled,
        suspended = suspended,
        stopped = stopped,
        processRunning = processRunning,
        risk = RiskLevel.YELLOW
    )

    private fun cloud(
        action: CloudAction,
        policy: Boolean,
        installed: Boolean = true,
        known: Boolean = true,
        enabled: Boolean = true,
        suspended: Boolean = false,
        stopped: Boolean = false,
        processRunning: Boolean? = null,
        protected: Boolean = false,
        applied: Boolean = true
    ): CloudState = CloudState(
        packageName = "com.example.cloud",
        label = "示例云控",
        action = action,
        policyEnabled = policy,
        policyApplied = applied,
        installed = installed,
        actualKnown = known,
        enabled = enabled,
        suspended = suspended,
        stopped = stopped,
        processRunning = processRunning,
        note = "说明",
        protected = protected
    )

    // ---- FreezeState ----

    @Test
    fun suspendPolicyOnButNotSuspended_isMismatch() {
        val state = freeze(policy = true, suspended = false)

        assertTrue(state.mismatch())
        assertEquals("不一致", state.statusLabel())
        assertEquals(
            "策略：已纳入；实际：未暂停，已启用，停止标记未设置，进程未知",
            state.describe()
        )
    }

    @Test
    fun suspendPolicyOnAndSuspended_matches() {
        val state = freeze(policy = true, suspended = true)

        assertFalse(state.mismatch())
        assertFalse(state.partiallyApplied())
        assertEquals("已暂停 · 进程未知", state.statusLabel())
        assertEquals("已暂停，已启用，停止标记未设置，进程未知", state.describeActual())
    }

    @Test
    fun suspendedPackageWithLiveProcessIsReportedAsPartial() {
        val state = freeze(
            policy = true,
            suspended = true,
            stopped = true,
            processRunning = true
        )

        assertFalse(state.mismatch())
        assertTrue(state.partiallyApplied())
        assertEquals("已暂停 · 进程存活", state.statusLabel())
        assertEquals("已暂停，已启用，停止标记已设置，进程存活", state.describeActual())
    }

    @Test
    fun suspendWithForceStopConfirmed_isFullyFrozen() {
        val state = freeze(policy = true, suspended = true, stopped = true)

        assertFalse(state.mismatch())
        assertFalse(state.partiallyApplied())
        assertEquals("已暂停 · 进程未知", state.statusLabel())
        assertEquals("已暂停，已启用，停止标记已设置，进程未知", state.describeActual())
    }

    @Test
    fun suspendedPackageWithNoLiveProcessIsFullyFrozen() {
        val state = freeze(
            policy = true,
            suspended = true,
            processRunning = false
        )

        assertFalse(state.partiallyApplied())
        assertEquals("已冻结", state.statusLabel())
        assertEquals("已暂停，已启用，停止标记未设置，无目标进程", state.describeActual())
    }

    @Test
    fun suspendPolicyOffButActuallySuspended_isMismatch() {
        val state = freeze(policy = false, suspended = true)

        assertTrue(state.mismatch())
        assertEquals("不一致", state.statusLabel())
        assertEquals("未纳入", state.describePolicy())
        assertEquals("已暂停，已启用，停止标记未设置，进程未知", state.describeActual())
    }

    @Test
    fun suspendPolicyOffAndNotSuspended_matches() {
        val state = freeze(policy = false, suspended = false)

        assertFalse(state.mismatch())
        assertEquals("未冻结", state.statusLabel())
    }

    @Test
    fun disableModePolicyOnAndDisabled_matches() {
        val state = freeze(
            policy = true,
            enabled = false,
            mode = FreezeMode.DISABLE
        )

        assertFalse(state.mismatch())
        assertEquals("已停用", state.statusLabel())
        assertEquals("策略：已纳入；实际：已停用", state.describe())
    }

    @Test
    fun disableModePolicyOnButEnabled_isMismatch() {
        val state = freeze(
            policy = true,
            enabled = true,
            mode = FreezeMode.DISABLE
        )

        assertTrue(state.mismatch())
        assertEquals("不一致", state.statusLabel())
        assertEquals("策略：已纳入；实际：已启用", state.describe())
    }

    @Test
    fun disableModePolicyOffAndEnabled_isNotFrozen() {
        val state = freeze(
            policy = false,
            enabled = true,
            mode = FreezeMode.DISABLE
        )

        assertFalse(state.mismatch())
        assertEquals("仍启用", state.statusLabel())
    }

    @Test
    fun observeModeNeverMismatches() {
        val state = freeze(
            policy = true,
            suspended = false,
            mode = FreezeMode.OBSERVE
        )

        assertFalse(state.mismatch())
        assertEquals("仅观察", state.statusLabel())
        assertEquals("仅观察", state.describePolicy())
        assertFalse(state.actuallyFrozen)
    }

    @Test
    fun missingPackageIsNotReportedAsMismatch() {
        val state = freeze(policy = true, installed = false, known = false)

        assertFalse(state.mismatch())
        assertEquals("未安装", state.statusLabel())
        assertEquals("未安装", state.describeActual())
    }

    @Test
    fun unknownActualIsReportedAsReadFailure() {
        val state = freeze(policy = true, known = false)

        assertFalse(state.mismatch())
        assertEquals("读取失败", state.statusLabel())
        assertEquals("读取失败", state.describeActual())
    }

    @Test
    fun stoppedPackageIsDescribed() {
        val state = freeze(policy = false, stopped = true)

        assertEquals("未暂停，已启用，停止标记已设置，进程未知", state.describeActual())
    }

    @Test
    fun freezeSourceAndRiskAreCarried() {
        val state = freeze(policy = false)

        assertEquals(FreezeSource.CORE, state.source)
        assertEquals(RiskLevel.YELLOW, state.risk)
    }

    // ---- CloudState ----

    @Test
    fun cloudSuspendMatchesWhenPolicyAndSuspended() {
        val state = cloud(
            action = CloudAction.SUSPEND,
            policy = true,
            suspended = true
        )

        assertFalse(state.mismatch())
        assertEquals("已暂停 · 进程未知", state.statusLabel())
        assertEquals(
            "策略：已纳入；实际：已暂停，已启用，停止标记未设置，进程未知",
            state.describe()
        )
    }

    @Test
    fun cloudSuspendPolicyOnButNotSuspended_isMismatch() {
        val state = cloud(
            action = CloudAction.SUSPEND,
            policy = true,
            suspended = false
        )

        assertTrue(state.mismatch())
        assertEquals("不一致", state.statusLabel())
    }

    @Test
    fun cloudPolicyConfiguredButNotApplied_isPendingNotMismatch() {
        val state = cloud(
            action = CloudAction.SUSPEND,
            policy = true,
            suspended = false,
            applied = false
        )

        assertFalse(state.mismatch())
        assertEquals("未应用", state.statusLabel())
        assertTrue(state.describePolicy().contains("未应用"))
    }

    @Test
    fun cloudDisableMatchesWhenPolicyAndDisabled() {
        val state = cloud(
            action = CloudAction.DISABLE_USER,
            policy = true,
            enabled = false
        )

        assertFalse(state.mismatch())
        assertEquals("已停用", state.statusLabel())
        assertEquals("策略：已纳入；实际：已停用", state.describe())
    }

    @Test
    fun cloudDisablePolicyOnButEnabled_isMismatch() {
        val state = cloud(
            action = CloudAction.DISABLE_USER,
            policy = true,
            enabled = true
        )

        assertTrue(state.mismatch())
        assertEquals("不一致", state.statusLabel())
    }

    @Test
    fun cloudObserveOnlyNeverMismatches() {
        val state = cloud(
            action = CloudAction.OBSERVE_ONLY,
            policy = true,
            suspended = false
        )

        assertFalse(state.mismatch())
        assertEquals("仅观察", state.statusLabel())
        assertEquals("仅观察", state.describePolicy())
    }

    @Test
    fun protectedCloudPackageIsMarkedForbidden() {
        val state = cloud(
            action = CloudAction.OBSERVE_ONLY,
            policy = false,
            protected = true
        )

        assertEquals("禁止操作", state.statusLabel())
        assertEquals("禁止操作", state.describePolicy())
    }

    @Test
    fun cloudMissingAndUnknownAreExplicit() {
        val missing = cloud(
            action = CloudAction.SUSPEND,
            policy = true,
            installed = false,
            known = false
        )
        val unknown = cloud(
            action = CloudAction.SUSPEND,
            policy = true,
            known = false
        )

        assertEquals("未安装", missing.statusLabel())
        assertEquals("读取失败", unknown.statusLabel())
        assertFalse(missing.mismatch())
        assertFalse(unknown.mismatch())
    }

    @Test
    fun cloudAsFreezeStateMapsSourceAndMode() {
        val suspend = cloud(action = CloudAction.SUSPEND, policy = true).asFreezeState()
        val disable = cloud(
            action = CloudAction.DISABLE_USER,
            policy = false
        ).asFreezeState()
        val observe = cloud(
            action = CloudAction.OBSERVE_ONLY,
            policy = false
        ).asFreezeState()

        assertEquals(FreezeSource.CLOUD, suspend.source)
        assertEquals(FreezeMode.SUSPEND, suspend.mode)
        assertEquals(FreezeMode.DISABLE, disable.mode)
        assertEquals(FreezeMode.OBSERVE, observe.mode)
        assertEquals("com.example.cloud", suspend.packageName)
    }

    // ---- CotaState ----

    @Test
    fun cotaOkWithNoFilesIsExplicitlyEmpty() {
        assertEquals("未检测到文件", CotaState(CotaReadStatus.OK).describe())
    }

    @Test
    fun cotaOkDescribesFileCount() {
        val file = CotaFile(
            name = "thermal.conf",
            sizeBytes = 2048,
            sha256 = "abc",
            mode = "644",
            uid = 0,
            gid = 0,
            kind = CotaFileKind.THERMAL_CONFIG
        )

        val state = CotaState(CotaReadStatus.OK, files = listOf(file))

        assertEquals("检测到 1 个文件", state.describe())
        assertEquals(1, state.files.size)
        assertTrue(state.hasFiles)
    }

    @Test
    fun cotaNoRootIsExplicit() {
        assertEquals("无 root，未读取", CotaState(CotaReadStatus.NO_ROOT).describe())
        assertFalse(CotaState(CotaReadStatus.NO_ROOT).hasFiles)
    }

    @Test
    fun cotaErrorIncludesMessage() {
        val state = CotaState(
            CotaReadStatus.ERROR,
            message = "目录不存在：/data/cota/para"
        )

        assertEquals("读取失败：目录不存在：/data/cota/para", state.describe())
        assertFalse(state.hasFiles)
    }

    @Test
    fun cotaUnknownIsNotSuccess() {
        val state = CotaState()

        assertEquals(CotaReadStatus.UNKNOWN, state.status)
        assertEquals("未检测", state.describe())
        assertFalse(state.hasFiles)
    }

    // ---- ThermalState ----

    @Test
    fun thermalServiceStatesAreDescribed() {
        val enabled = ThermalServiceState(
            packageName = "com.hihonor.powergenie",
            label = "PowerGenie",
            installed = true,
            actualKnown = true,
            enabled = true,
            suspended = false
        )
        val disabled = enabled.copy(
            packageName = "com.hihonor.iaware",
            label = "iAware",
            enabled = false,
            suspended = true
        )
        val missing = enabled.copy(
            packageName = "com.example.missing",
            label = "缺失",
            installed = false,
            actualKnown = false
        )

        assertEquals("已启用", enabled.describe())
        assertEquals("已停用，已暂停", disabled.describe())
        assertEquals("未安装", missing.describe())
        assertTrue(enabled.installedAndEnabled())
        assertFalse(disabled.installedAndEnabled())
    }

    @Test
    fun thermalStateCountsInstalledAndEnabledServices() {
        val a = ThermalServiceState(
            packageName = "a",
            label = "A",
            installed = true,
            actualKnown = true,
            enabled = true,
            suspended = false
        )
        val b = a.copy(packageName = "b", label = "B", enabled = false)
        val state = ThermalState(services = listOf(a, b))

        assertEquals("已安装 2/2；已启用 1/2", state.describe())
        assertEquals("只读；不提供停止/冻结操作", state.readOnlyNote)
    }

    @Test
    fun emptyThermalStateReportsDetectionGap() {
        assertEquals("未检测", ThermalState().describe())
        assertEquals("读取失败", ThermalState(message = "读取失败").describe())
    }

    // ---- BootSafetyState ----

    @Test
    fun cleanShutdownDetectionUsesResultPrefix() {
        assertTrue(
            BootSafetyState(lastShutdownResult = "成功（1700000000000）")
                .lastShutdownClean()
        )
        assertTrue(
            BootSafetyState(lastShutdownResult = "安全解冻成功")
                .lastShutdownClean()
        )
        assertFalse(
            BootSafetyState(lastShutdownResult = "失败（1700000000000）")
                .lastShutdownClean()
        )
        assertFalse(BootSafetyState().lastShutdownClean())
    }

    @Test
    fun bootloopGuardTripsWhenPreviousAttemptHadNoCleanShutdownAfterIt() {
        val state = BootSafetyState(
            bootFreezeEnabled = true,
            bootCount = 10,
            lastBootFreezeAttemptBootCount = 9,
            lastBootFreezeAttemptTime = 5_000,
            lastShutdownResult = "成功（4000）",
            lastShutdownTime = 4_000
        )

        assertTrue(state.bootloopGuardTripped())
        assertTrue(state.describeGuard().startsWith("守卫触发"))
    }

    @Test
    fun bootloopGuardClearsWithCleanShutdownAfterAttempt() {
        val state = BootSafetyState(
            bootCount = 10,
            lastBootFreezeAttemptBootCount = 9,
            lastBootFreezeAttemptTime = 5_000,
            lastShutdownResult = "成功（6000）",
            lastShutdownTime = 6_000
        )

        assertFalse(state.bootloopGuardTripped())
    }

    @Test
    fun bootloopGuardIgnoresOldAttempts() {
        val state = BootSafetyState(
            bootCount = 10,
            lastBootFreezeAttemptBootCount = 5,
            lastBootFreezeAttemptTime = 5_000
        )

        assertFalse(state.bootloopGuardTripped())
    }

    @Test
    fun skippedThisBootIsDetected() {
        val state = BootSafetyState(
            bootFreezeEnabled = true,
            bootCount = 10,
            skippedBootCount = 10
        )

        assertTrue(state.skippedThisBoot())
        assertTrue(state.describeGuard().contains("手动恢复"))
    }

    @Test
    fun timestampedResultsAppendFormattedTime() {
        val state = BootSafetyState(
            lastBootFreezeResult = "成功",
            lastBootFreezeTime = 1_700_000_000_000L
        )

        assertEquals(
            "成功（2023-11-14 22:13:20）",
            state.describeLastBootFreeze(
                ZoneId.of("UTC")
            )
        )
        assertEquals("尚未执行", BootSafetyState().describeLastBootFreeze())
    }

    @Test
    fun timestampFormattingIsStable() {
        assertEquals(
            "2023-11-14 22:13:20",
            formatDetectionTimestamp(1_700_000_000_000L, ZoneId.of("UTC"))
        )
    }

    @Test
    fun aiSourceUsesTheSamePolicyVersusActualRules() {
        val applied = freeze(
            policy = true,
            suspended = true,
            stopped = true
        ).copy(source = FreezeSource.AI)
        val missing = freeze(policy = true, suspended = false)
            .copy(source = FreezeSource.AI)

        assertFalse(applied.mismatch())
        assertEquals("已暂停 · 进程未知", applied.statusLabel())
        assertTrue(missing.mismatch())
        assertEquals("不一致", missing.statusLabel())
    }

    // ---- DetectionSnapshot ----

    @Test
    fun snapshotCountsMismatchesAndSummarizes() {
        val matching = freeze(policy = true, suspended = true)
        val mismatching = freeze(policy = true, suspended = false)
            .copy(packageName = "com.example.mismatch")
        val cloudMismatch = cloud(
            action = CloudAction.SUSPEND,
            policy = true,
            suspended = false
        )
        val snapshot = DetectionSnapshot(
            freezeStates = listOf(matching, mismatching),
            cloudStates = listOf(cloudMismatch)
        )

        assertEquals(1, snapshot.freezeMismatches.size)
        assertEquals(1, snapshot.cloudMismatches.size)
        assertEquals(1, snapshot.mismatchCount)
        assertTrue(snapshot.describeSummary().contains("不一致 1 项"))
        assertTrue(snapshot.describeSummary().contains("COTA：未检测"))
    }

    @Test
    fun snapshotDefaultsAreExplicitlyUnknown() {
        val snapshot = DetectionSnapshot()

        assertEquals(CotaReadStatus.UNKNOWN, snapshot.cota.status)
        assertTrue(snapshot.thermal.services.isEmpty())
        assertEquals(-1, snapshot.bootSafety.bootCount)
        assertTrue(snapshot.notes.isEmpty())
    }
}
