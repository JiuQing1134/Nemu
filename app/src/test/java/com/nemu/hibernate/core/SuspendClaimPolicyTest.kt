package com.nemu.hibernate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendClaimPolicyTest {
    @Test
    fun previouslySuspendedIsNotClaimed() {
        assertFalse(SuspendClaimPolicy.shouldClaim(alreadySuspended = true))
    }

    @Test
    fun visiblePackageIsClaimed() {
        assertTrue(SuspendClaimPolicy.shouldClaim(alreadySuspended = false))
    }

    @Test
    fun unknownStateIsClaimedConservatively() {
        assertTrue(SuspendClaimPolicy.shouldClaim(alreadySuspended = null))
    }

    @Test
    fun ownedSuspensionCanBeReapplied() {
        assertTrue(
            SuspendClaimPolicy.shouldClaim(
                alreadySuspended = true,
                ownedByNemu = true
            )
        )
    }

    @Test
    fun foreignSuspensionRemainsUnclaimable() {
        assertFalse(
            SuspendClaimPolicy.shouldClaim(
                alreadySuspended = true,
                ownedByNemu = false
            )
        )
    }
}
