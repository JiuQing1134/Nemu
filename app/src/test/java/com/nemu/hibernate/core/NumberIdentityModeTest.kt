package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberIdentityModeTest {
    @Test
    fun hideRoundTripsInsteadOfBecomingOff() {
        assertEquals(
            NumberIdentityMode.HIDE,
            NumberIdentityMode.fromStrategy(FreezeStrategy.HIDE)
        )
        assertEquals(
            FreezeStrategy.HIDE,
            NumberIdentityMode.HIDE.toStrategy()
        )
    }

    @Test
    fun suspendAndDisableRoundTrip() {
        assertEquals(
            NumberIdentityMode.SUSPEND,
            NumberIdentityMode.fromStrategy(FreezeStrategy.SUSPEND)
        )
        assertEquals(
            NumberIdentityMode.DISABLE_USER,
            NumberIdentityMode.fromStrategy(FreezeStrategy.DISABLE_USER)
        )
    }

    @Test
    fun observeIsOffAndOffHasNoStrategy() {
        assertEquals(
            NumberIdentityMode.OFF,
            NumberIdentityMode.fromStrategy(FreezeStrategy.OBSERVE)
        )
        assertNull(NumberIdentityMode.OFF.toStrategy())
    }
}
