package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Test

class CotaPathsTest {
    @Test
    fun absolutePathHandlesTheRootGroupAndNestedEntries() {
        assertEquals("/data/cota/para", CotaPaths.absolutePath(""))
        assertEquals(
            "/data/cota/para/thermal_config",
            CotaPaths.absolutePath("thermal_config")
        )
        assertEquals(
            "/data/cota/para/thermal_config/alpha/one.xml",
            CotaPaths.absolutePath("thermal_config/alpha/one.xml")
        )
        assertEquals(
            "/data/cota/para/a/b/c/d.conf",
            CotaPaths.absolutePath("a/b/c/d.conf")
        )
    }

    @Test
    fun absolutePathOrNullNeverThrowsForACopyGesture() {
        assertEquals("/data/cota/para", CotaPaths.absolutePathOrNull(""))
        assertEquals(
            "/data/cota/para/thermal_config/version.txt",
            CotaPaths.absolutePathOrNull("thermal_config/version.txt")
        )
        assertEquals(
            null,
            CotaPaths.absolutePathOrNull("thermal_config/../../escape")
        )
        assertEquals(null, CotaPaths.absolutePathOrNull("a/"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun absolutePathRejectsTraversal() {
        CotaPaths.absolutePath("thermal_config/../../escape")
    }
}
