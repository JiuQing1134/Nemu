package com.nemu.hibernate.policy

import com.nemu.hibernate.core.FreezeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedSuspensionTest {
    @Test
    fun onlySuspendStrategyWithAppliedMarkerOwnsTheSuspension() {
        assertTrue(isOwnedSuspension(true, FreezeStrategy.SUSPEND))
        assertFalse(isOwnedSuspension(false, FreezeStrategy.SUSPEND))
        assertFalse(isOwnedSuspension(true, FreezeStrategy.COMPONENT))
        assertFalse(isOwnedSuspension(true, FreezeStrategy.DISABLE_USER))
        assertFalse(isOwnedSuspension(true, FreezeStrategy.HIDE))
        assertFalse(isOwnedSuspension(true, FreezeStrategy.OBSERVE))
    }
}
