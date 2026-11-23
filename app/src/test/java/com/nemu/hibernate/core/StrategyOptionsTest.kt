package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StrategyOptionsTest {
    @Test
    fun defaultOptionsDeriveFreezeForLegacySuspend() {
        assertEquals(
            StrategyPreset.FREEZE,
            StrategyOptions().presetOf(FreezeStrategy.SUSPEND)
        )
    }

    @Test
    fun forceStopFalseDerivesPause() {
        assertEquals(
            StrategyPreset.PAUSE,
            StrategyOptions(forceStop = false).presetOf(FreezeStrategy.SUSPEND)
        )
    }

    @Test
    fun appOpsDerivesRestrictBackground() {
        assertEquals(
            StrategyPreset.RESTRICT_BACKGROUND,
            StrategyOptions(appOps = true).presetOf(FreezeStrategy.OBSERVE)
        )
    }

    @Test
    fun standbyDerivesRestrictStandby() {
        assertEquals(
            StrategyPreset.RESTRICT_STANDBY,
            StrategyOptions(standby = true).presetOf(FreezeStrategy.OBSERVE)
        )
    }

    @Test
    fun codecRoundTripsAllFields() {
        val options = StrategyOptions(
            schema = 1,
            forceStop = false,
            components = setOf(
                "com.example/com.example.A",
                "com.example/com.example.B"
            ),
            appOps = true,
            standby = true,
            backend = "root"
        )

        assertEquals(options, StrategyOptionsCodec.decode(StrategyOptionsCodec.encode(options)))
    }

    @Test
    fun codecPreservesNullComponents() {
        val decoded = StrategyOptionsCodec.decode(
            StrategyOptionsCodec.encode(StrategyOptions(components = null))
        )

        assertNotNull(decoded)
        assertNull(decoded!!.components)
    }

    @Test
    fun codecPreservesEmptyComponents() {
        val decoded = StrategyOptionsCodec.decode(
            StrategyOptionsCodec.encode(StrategyOptions(components = emptySet()))
        )

        assertNotNull(decoded)
        assertEquals(emptySet<String>(), decoded!!.components)
    }

    @Test
    fun codecRejectsMalformedBoolean() {
        assertNull(StrategyOptionsCodec.decode("v=1|forceStop=maybe"))
    }

    @Test
    fun codecRejectsUnsupportedSchema() {
        assertNull(StrategyOptionsCodec.decode("v=99|forceStop=true"))
    }

    @Test
    fun codecRejectsMissingVersion() {
        assertNull(StrategyOptionsCodec.decode("forceStop=true"))
    }
}
