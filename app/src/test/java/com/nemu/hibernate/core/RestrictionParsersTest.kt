package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestrictionParsersTest {
    @Test
    fun parsesAllowedAppOpsMode() {
        assertEquals(
            "allow",
            RestrictionParsers.parseAppOpsMode("RUN_ANY_IN_BACKGROUND: allow")
        )
    }

    @Test
    fun parsesIgnoredAppOpsMode() {
        assertEquals(
            "ignore",
            RestrictionParsers.parseAppOpsMode(
                "RUN_ANY_IN_BACKGROUND: ignore\n"
            )
        )
    }

    @Test
    fun skipsUidModeAndParsesThePackageMode() {
        val output = """
            Uid mode: RUN_ANY_IN_BACKGROUND: ignore
            RUN_ANY_IN_BACKGROUND: allow; time=+1h2m3s ago
        """.trimIndent()

        assertEquals("allow", RestrictionParsers.parseAppOpsMode(output))
    }

    @Test
    fun uidOnlyOutputIsNotAPackageMode() {
        assertNull(
            RestrictionParsers.parseAppOpsMode(
                "Uid mode: RUN_ANY_IN_BACKGROUND: ignore"
            )
        )
    }

    @Test
    fun appOpsSuffixAndModePrefixAreStripped() {
        assertEquals(
            "ignore",
            RestrictionParsers.parseAppOpsMode(
                "RUN_ANY_IN_BACKGROUND: mode=ignore (running)"
            )
        )
        assertEquals(
            "allow",
            RestrictionParsers.parseAppOpsMode(
                "RUN_ANY_IN_BACKGROUND: allow; rejectTime=+1s ago"
            )
        )
    }

    @Test
    fun unknownAppOpsOutputIsNull() {
        assertNull(RestrictionParsers.parseAppOpsMode("No operations."))
        assertNull(RestrictionParsers.parseAppOpsMode(""))
    }

    @Test
    fun parsesStandbyBucket() {
        assertEquals(10, RestrictionParsers.parseStandbyBucket("10"))
        assertEquals(
            45,
            RestrictionParsers.parseStandbyBucket("45 (restricted)")
        )
    }

    @Test
    fun unknownStandbyOutputIsNull() {
        assertNull(RestrictionParsers.parseStandbyBucket("Error: package not found"))
        assertNull(RestrictionParsers.parseStandbyBucket(""))
    }
}
