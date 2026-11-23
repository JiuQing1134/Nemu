package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootFreezeSchedulerWindowTest {
    @Test
    fun delayIsMeasuredFromBootNotFromEnabling() {
        assertEquals(
            90L,
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 30
            )
        )
    }

    @Test
    fun windowAnchoredAtFirstObservationSurvivesLateUnlock() {
        // First observed 5 minutes after boot (e.g. user unlocked late).
        // The window is re-anchored instead of being skipped forever.
        assertEquals(
            120L,
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 300,
                anchorUptimeSeconds = 300
            )
        )
        assertEquals(
            60L,
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 360,
                anchorUptimeSeconds = 300
            )
        )
    }

    @Test
    fun justMissedWindowGetsOneBoundedCompensation() {
        assertEquals(
            BootFreezeScheduler.LATE_GRACE_SECONDS,
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 130,
                anchorUptimeSeconds = 0
            )
        )
        assertEquals(
            BootFreezeScheduler.LATE_GRACE_SECONDS,
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 400,
                anchorUptimeSeconds = 20
            )
        )
    }

    @Test
    fun workerSkipsAfterTheCompensationWindow() {
        assertFalse(
            BootFreezeScheduler.isWindowExpired(
                delaySeconds = 120,
                uptimeSeconds = 400,
                anchorUptimeSeconds = 20
            )
        )
        assertTrue(
            BootFreezeScheduler.isWindowExpired(
                delaySeconds = 120,
                uptimeSeconds = 500,
                anchorUptimeSeconds = 20
            )
        )
    }

    @Test
    fun longMissedWindowDoesNotFreezeDuringActiveUse() {
        assertNull(
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 600,
                anchorUptimeSeconds = 0
            )
        )
        assertNull(
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 3600,
                anchorUptimeSeconds = 20
            )
        )
    }
}
