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
