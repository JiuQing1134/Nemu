package com.nemu.hibernate.policy

/**
 * Applied markers may be cleared only when every owned state was actually
 * restored. A null PackageManager readback is unknown, not evidence of
 * success; hidden/component ownership must keep the marker for a later retry.
 */
object AppliedMarkerCleanup {
    fun clearAfterShutdown(
        enabled: Boolean?,
        suspended: Boolean?,
        ownsHidden: Boolean,
        ownsComponents: Boolean
    ): Boolean = enabled == true &&
        suspended != true &&
        !ownsHidden &&
        !ownsComponents

    fun clearAfterSuspend(
        suspended: Boolean?,
        ownsHidden: Boolean,
        ownsComponents: Boolean
    ): Boolean = suspended == false && !ownsHidden && !ownsComponents
}
