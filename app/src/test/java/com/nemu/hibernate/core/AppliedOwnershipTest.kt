package com.nemu.hibernate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliedOwnershipTest {
    @Test
    fun anyOwnedStateCountsAsApplied() {
        assertTrue(AppliedOwnership.isApplied(applied = true))
        assertTrue(AppliedOwnership.isApplied(ownsComponents = true))
        assertTrue(AppliedOwnership.isApplied(ownsAppOps = true))
        assertTrue(AppliedOwnership.isApplied(ownsStandby = true))
        assertTrue(AppliedOwnership.isApplied(ownsHidden = true))
        assertTrue(AppliedOwnership.isApplied(hasOriginalEnabled = true))
        assertTrue(AppliedOwnership.isApplied(legacyCloudApplied = true))
    }

    @Test
    fun noOwnershipIsNotApplied() {
        assertFalse(AppliedOwnership.isApplied())
    }
}
