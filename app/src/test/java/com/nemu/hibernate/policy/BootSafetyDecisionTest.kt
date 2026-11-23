package com.nemu.hibernate.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootSafetyDecisionTest {
    private val bootStartedAt = 1_000_000L

    @Test
    fun noFreezeThisBootMeansRunResidualUnfreeze() {
        assertFalse(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt - 60_000,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun freezeAttemptAfterBootSkipsUnfreeze() {
        assertTrue(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt + 300_000,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun sameBootAttemptSuppressesCleanupEvenWhenWallClockIsSuspect() {
        assertTrue(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt - 86_400_000,
                lastAttemptBootCount = 10,
                currentBootCount = 10,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    /**
     * A recorded result without a real attempt (no backend, boot not
     * completed, residual failure) must not suppress the boot-safety pass.
     * The decision therefore reads only the attempt marker.
     */
    @Test
    fun freezeResultWithoutAttemptDoesNotSuppressCleanup() {
        assertFalse(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = 0,
                lastAttemptBootCount = -1,
                currentBootCount = 10,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun unknownBootCountUsesWallClockFallback() {
        assertTrue(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt + 300_000,
                lastAttemptBootCount = -1,
                currentBootCount = -1,
                bootStartedAtMillis = bootStartedAt
            )
        )
        assertFalse(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt - 60_000,
                lastAttemptBootCount = -1,
                currentBootCount = -1,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun clockJumpsDoNotOverrideMismatchedBootCounts() {
        assertFalse(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt + 300_000,
                lastAttemptBootCount = 10,
                currentBootCount = 11,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun unknownAttemptCountNeverMatchesKnownBoot() {
        assertFalse(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt + 300_000,
                lastAttemptBootCount = -1,
                currentBootCount = 11,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }
}
