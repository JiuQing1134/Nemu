package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreStrategySelectorTest {
    @Test
    fun ownedHiddenWinsOverStoredStrategy() {
        assertEquals(
            FreezeStrategy.HIDE,
            RestoreStrategySelector.select(
                stored = FreezeStrategy.SUSPEND,
                ownsHidden = true,
                ownsDisable = true,
                disabledNow = false
            )
        )
    }

    @Test
    fun storedDisableUserSurvivesWithoutDisableOwnership() {
        assertEquals(
            FreezeStrategy.DISABLE_USER,
            RestoreStrategySelector.select(
                stored = FreezeStrategy.DISABLE_USER,
                ownsHidden = false,
                ownsDisable = false,
                disabledNow = true
            )
        )
    }

    @Test
    fun actualDisabledWithOwnedDisableSelectsDisable() {
        assertEquals(
            FreezeStrategy.DISABLE_USER,
            RestoreStrategySelector.select(
                stored = FreezeStrategy.OBSERVE,
                ownsHidden = false,
                ownsDisable = true,
                disabledNow = true
            )
        )
    }

    @Test
    fun storedSuspendSelectedWhenNotDisabled() {
        assertEquals(
            FreezeStrategy.SUSPEND,
            RestoreStrategySelector.select(
                stored = FreezeStrategy.SUSPEND,
                ownsHidden = false,
                ownsDisable = true,
                disabledNow = false
            )
        )
    }
}
