package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun enablingAfterTheBootWindowDoesNotSchedule() {
        assertNull(
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 120
            )
        )
        assertNull(
            BootFreezeScheduler.remainingBootDelaySeconds(
                delaySeconds = 120,
                uptimeSeconds = 600
            )
        )
    }
}
