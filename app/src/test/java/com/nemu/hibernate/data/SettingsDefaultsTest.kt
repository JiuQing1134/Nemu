package com.nemu.hibernate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDefaultsTest {
    @Test
    fun freshInstallStartsInPreviewMode() {
        assertTrue(Settings.DEFAULT_DRY_RUN)
        assertEquals(
            true,
            Settings.dryRunForUpgrade(
                hasDryRunKey = false,
                hasOtherKeys = false
            )
        )
        assertEquals(
            false,
            Settings.dryRunForUpgrade(
                hasDryRunKey = false,
                hasOtherKeys = true
            )
        )
        assertEquals(
            null,
            Settings.dryRunForUpgrade(
                hasDryRunKey = true,
                hasOtherKeys = true
            )
        )
    }
}
