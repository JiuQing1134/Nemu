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
                lastFreezeTime = bootStartedAt - 59_000,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun freezeAttemptAfterBootSkipsUnfreeze() {
        assertTrue(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = bootStartedAt + 300_000,
                lastFreezeTime = 0,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }

    @Test
    fun successfulFreezeAfterBootSkipsUnfreezeEvenWithoutAttempt() {
        assertTrue(
            BootSafetyDecision.frozenThisBoot(
                lastAttemptTime = 0,
                lastFreezeTime = bootStartedAt + 300_000,
                bootStartedAtMillis = bootStartedAt
            )
        )
    }
}
