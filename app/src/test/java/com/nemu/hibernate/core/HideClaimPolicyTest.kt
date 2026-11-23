package com.nemu.hibernate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HideClaimPolicyTest {
    @Test
    fun visiblePackageCanBeClaimed() {
        assertTrue(HideClaimPolicy.shouldClaim(hidden = false, ownedByNemu = false))
    }

    @Test
    fun unknownStateCanBeClaimedConservatively() {
        assertTrue(HideClaimPolicy.shouldClaim(hidden = null, ownedByNemu = false))
    }

    @Test
    fun foreignHiddenStateIsNotClaimed() {
        assertFalse(HideClaimPolicy.shouldClaim(hidden = true, ownedByNemu = false))
    }

    @Test
    fun ownedHiddenStateCanBeReapplied() {
        assertTrue(HideClaimPolicy.shouldClaim(hidden = true, ownedByNemu = true))
    }
}
