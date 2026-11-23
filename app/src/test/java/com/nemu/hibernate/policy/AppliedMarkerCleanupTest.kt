package com.nemu.hibernate.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliedMarkerCleanupTest {
    @Test
    fun unknownEnabledStateDoesNotClearApplied() {
        assertFalse(
            AppliedMarkerCleanup.clearAfterShutdown(
                enabled = null,
                suspended = false,
                ownsHidden = false,
                ownsComponents = false
            )
        )
    }

    @Test
    fun stillSuspendedTargetDoesNotClearApplied() {
        assertFalse(
            AppliedMarkerCleanup.clearAfterShutdown(
                enabled = true,
                suspended = true,
                ownsHidden = false,
                ownsComponents = false
            )
        )
    }

    @Test
    fun hiddenOrComponentOwnershipKeepsApplied() {
        assertFalse(
            AppliedMarkerCleanup.clearAfterShutdown(
                enabled = true,
                suspended = false,
                ownsHidden = true,
                ownsComponents = false
            )
        )
        assertFalse(
            AppliedMarkerCleanup.clearAfterShutdown(
                enabled = true,
                suspended = false,
                ownsHidden = false,
                ownsComponents = true
            )
        )
    }

    @Test
    fun verifiedEnabledAndUnsuspendedTargetCanClearApplied() {
        assertTrue(
            AppliedMarkerCleanup.clearAfterShutdown(
                enabled = true,
                suspended = false,
                ownsHidden = false,
                ownsComponents = false
            )
        )
    }

    @Test
    fun suspendMarkerClearsOnlyAfterVerifiedUnsuspend() {
        assertFalse(
            AppliedMarkerCleanup.clearAfterSuspend(
                suspended = null,
                ownsHidden = false,
                ownsComponents = false
            )
        )
        assertFalse(
            AppliedMarkerCleanup.clearAfterSuspend(
                suspended = true,
                ownsHidden = false,
                ownsComponents = false
            )
        )
        assertTrue(
            AppliedMarkerCleanup.clearAfterSuspend(
                suspended = false,
                ownsHidden = false,
                ownsComponents = false
            )
        )
    }
}
