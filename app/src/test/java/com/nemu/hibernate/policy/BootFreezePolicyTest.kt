package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootFreezePolicyTest {
    private fun input(
        bootCount: Int = 10,
        bootFreezeEnabled: Boolean = true,
        lastSkippedBootCount: Int = -1,
        lastFreezeBootCount: Int = -1,
        lastAttemptBootCount: Int = -1,
        lastAttemptTime: Long = 0L,
        lastFreezeSucceeded: Boolean = false,
        lastFreezeTime: Long = 0L,
        lastShutdownTime: Long = 0L,
        lastShutdownOk: Boolean = false
    ): BootFreezeInput = BootFreezeInput(
        bootFreezeEnabled = bootFreezeEnabled,
        bootCount = bootCount,
        lastSkippedBootCount = lastSkippedBootCount,
        lastFreezeBootCount = lastFreezeBootCount,
        lastAttemptBootCount = lastAttemptBootCount,
        lastAttemptTime = lastAttemptTime,
        lastFreezeSucceeded = lastFreezeSucceeded,
        lastFreezeTime = lastFreezeTime,
        lastShutdownTime = lastShutdownTime,
        lastShutdownOk = lastShutdownOk
    )

    @Test
    fun workerUsesTheSharedShutdownGate() {
        val source = com.nemu.hibernate.TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/BootFreezeWorker.kt"
        )

        assertTrue(
            "worker 必须使用统一的秒→毫秒 planned 判据",
            source.contains("BootFreezePolicy.shutdownGate")
        )
    }

    @Test
    fun first_boot_runs() {
        val decision = BootFreezePolicy.evaluate(input())

        assertTrue(decision.shouldRun)
    }

    @Test
    fun disabled_master_switch_skips() {
        val decision = BootFreezePolicy.evaluate(
            input(bootFreezeEnabled = false)
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("未开启"))
    }

    @Test
    fun previous_success_with_clean_shutdown_runs() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 11,
                lastFreezeBootCount = 10,
                lastAttemptBootCount = 10,
                lastAttemptTime = 1_000L,
                lastFreezeSucceeded = true,
                lastFreezeTime = 2_000L,
                lastShutdownOk = true,
                lastShutdownTime = 3_000L
            )
        )

        assertTrue(decision.shouldRun)
    }

    @Test
    fun plannedShutdownAfterAttemptUnlocksTheGate() {
        val gate = BootFreezePolicy.shutdownGate(
            plannedShutdownMs = 1_720_000_000_000L,
            appShutdownOk = false,
            appShutdownTimeMs = 0L,
            lastAttemptTimeMs = 1_700_000_000_000L
        )

        assertTrue(gate.clean)
        assertEquals(1_720_000_000_000L, gate.time)
    }

    @Test
    fun plannedShutdownBeforeAttemptDoesNotUnlockTheGate() {
        val gate = BootFreezePolicy.shutdownGate(
            plannedShutdownMs = 1_600_000_000_000L,
            appShutdownOk = false,
            appShutdownTimeMs = 0L,
            lastAttemptTimeMs = 1_700_000_000_000L
        )

        assertFalse(gate.clean)
    }

    @Test
    fun appCleanShutdownStillWinsWhenPlannedIsStale() {
        val gate = BootFreezePolicy.shutdownGate(
            plannedShutdownMs = 1_600_000_000_000L,
            appShutdownOk = true,
            appShutdownTimeMs = 1_800_000_000_000L,
            lastAttemptTimeMs = 1_700_000_000_000L
        )

        assertTrue(gate.clean)
        assertEquals(1_800_000_000_000L, gate.time)
    }

    @Test
    fun previous_success_without_clean_shutdown_skips() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 11,
                lastFreezeBootCount = 10,
                lastAttemptBootCount = 10,
                lastAttemptTime = 1_000L,
                lastFreezeSucceeded = true,
                lastFreezeTime = 2_000L,
                lastShutdownOk = false,
                lastShutdownTime = 0L
            )
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("干净关机"))
    }

    @Test
    fun previous_success_with_stale_clean_shutdown_skips() {
        // The last clean shutdown happened before the freeze attempt, not after
        // it, so it cannot prove that the freeze did not cause the reboot.
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 11,
                lastFreezeBootCount = 10,
                lastAttemptBootCount = 10,
                lastAttemptTime = 5_000L,
                lastFreezeSucceeded = true,
                lastFreezeTime = 6_000L,
                lastShutdownOk = true,
                lastShutdownTime = 4_000L
            )
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("干净关机"))
    }

    @Test
    fun previous_failed_attempt_without_clean_shutdown_skips() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 11,
                lastFreezeBootCount = 10,
                lastAttemptBootCount = 10,
                lastAttemptTime = 1_000L,
                lastFreezeSucceeded = false,
                lastFreezeTime = 2_000L
            )
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("干净关机"))
    }

    @Test
    fun failed_attempt_without_attempt_record_allows_retry() {
        // Backend unavailable / no targets: the worker never wrote an attempt
        // record, so the next boot may try again.
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 11,
                lastFreezeBootCount = 10,
                lastFreezeSucceeded = false
            )
        )

        assertTrue(decision.shouldRun)
    }

    @Test
    fun user_skipped_boot_is_skipped() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 10,
                lastSkippedBootCount = 10
            )
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("跳过"))
    }

    @Test
    fun same_boot_repeat_is_skipped() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 10,
                lastFreezeBootCount = 10
            )
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("已处理") || decision.reason.contains("重复"))
    }

    @Test
    fun same_boot_attempt_is_skipped() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 10,
                lastAttemptBootCount = 10,
                lastAttemptTime = 1_000L
            )
        )

        assertFalse(decision.shouldRun)
        assertTrue(decision.reason.contains("已尝试") || decision.reason.contains("重复"))
    }

    @Test
    fun attempt_from_two_boots_ago_does_not_block_forever() {
        // The previous boot was skipped by the guard; this boot may retry even
        // though the older attempt itself never saw a clean shutdown.
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 12,
                lastFreezeBootCount = 11,
                lastAttemptBootCount = 10,
                lastAttemptTime = 1_000L,
                lastFreezeSucceeded = true,
                lastFreezeTime = 2_000L,
                lastShutdownOk = false,
                lastShutdownTime = 0L
            )
        )

        assertTrue(decision.shouldRun)
    }

    @Test
    fun clean_shutdown_after_attempt_two_boots_ago_runs() {
        val decision = BootFreezePolicy.evaluate(
            input(
                bootCount = 12,
                lastFreezeBootCount = 11,
                lastAttemptBootCount = 10,
                lastAttemptTime = 1_000L,
                lastFreezeSucceeded = true,
                lastFreezeTime = 2_000L,
                lastShutdownOk = true,
                lastShutdownTime = 3_000L
            )
        )

        assertTrue(decision.shouldRun)
    }
}
