package com.nemu.hibernate.detection

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.TargetCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionStrategyTest {
    private val numberIdentity =
        TargetCatalog.byPackage("com.hihonor.numberidentity")!!

    @Test
    fun legacyHideModeIsShownAsHide() {
        assertEquals(
            FreezeStrategy.HIDE,
            DetectionStrategy.resolve(
                target = numberIdentity,
                legacyNumberMode = "HIDE",
                storedRaw = null
            )
        )
    }

    @Test
    fun legacyDisableUserStillWins() {
        assertEquals(
            FreezeStrategy.DISABLE_USER,
            DetectionStrategy.resolve(
                target = numberIdentity,
                legacyNumberMode = "DISABLE_USER",
                storedRaw = "SUSPEND"
            )
        )
    }

    @Test
    fun offLegacyModeFallsBackToStoredStrategy() {
        assertEquals(
            FreezeStrategy.HIDE,
            DetectionStrategy.resolve(
                target = numberIdentity,
                legacyNumberMode = "OFF",
                storedRaw = "HIDE"
            )
        )
    }

    @Test
    fun invalidStoredStrategyFallsBackToTheDefault() {
        assertEquals(
            numberIdentity.defaultStrategy,
            DetectionStrategy.resolve(
                target = numberIdentity,
                legacyNumberMode = null,
                storedRaw = "not-a-strategy"
            )
        )
    }
}
