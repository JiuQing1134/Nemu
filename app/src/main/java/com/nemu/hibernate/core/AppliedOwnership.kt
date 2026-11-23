package com.nemu.hibernate.core

/**
 * A target is still actionable after a crash/upgrade when *any* persisted
 * ownership record remains, not only the applied flag. Hidden ownership and
 * an original-enabled baseline are equivalent recovery evidence.
 */
object AppliedOwnership {
    fun isApplied(
        applied: Boolean = false,
        ownsComponents: Boolean = false,
        ownsAppOps: Boolean = false,
        ownsStandby: Boolean = false,
        ownsHidden: Boolean = false,
        hasOriginalEnabled: Boolean = false,
        legacyCloudApplied: Boolean = false
    ): Boolean = applied ||
        ownsComponents ||
        ownsAppOps ||
        ownsStandby ||
        ownsHidden ||
        hasOriginalEnabled ||
        legacyCloudApplied
}
