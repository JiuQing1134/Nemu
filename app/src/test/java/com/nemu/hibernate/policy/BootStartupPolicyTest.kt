package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class BootStartupPolicyTest {
    @Test
    fun missingBackendRetriesBeforeGivingUp() {
        assertEquals(
            BootStartupAction.RETRY,
            BootStartupPolicy.missingBackend(runAttemptCount = 0)
        )
        assertEquals(
            BootStartupAction.RETRY,
            BootStartupPolicy.missingBackend(runAttemptCount = 2)
        )
        assertEquals(
            BootStartupAction.GIVE_UP,
            BootStartupPolicy.missingBackend(runAttemptCount = 3)
        )
    }

    @Test
    fun bootNotCompletedAlsoRetriesBeforeGivingUp() {
        assertEquals(
            BootStartupAction.RETRY,
            BootStartupPolicy.bootNotCompleted(runAttemptCount = 0)
        )
        assertEquals(
            BootStartupAction.GIVE_UP,
            BootStartupPolicy.bootNotCompleted(runAttemptCount = 3)
        )
    }

    @Test
    fun unknownBootStateRetriesBeforeGivingUp() {
        assertEquals(
            BootStartupAction.RETRY,
            BootStartupPolicy.bootStateUnknown(runAttemptCount = 0)
        )
        assertEquals(
            BootStartupAction.GIVE_UP,
            BootStartupPolicy.bootStateUnknown(runAttemptCount = 3)
        )
    }

    @Test
    fun unknownRescueStateRetriesBeforeGivingUp() {
        assertEquals(
            BootStartupAction.RETRY,
            BootStartupPolicy.rescueUnknown(runAttemptCount = 0)
        )
        assertEquals(
            BootStartupAction.GIVE_UP,
            BootStartupPolicy.rescueUnknown(runAttemptCount = 3)
        )
    }
}
