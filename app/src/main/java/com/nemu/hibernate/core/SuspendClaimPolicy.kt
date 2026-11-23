package com.nemu.hibernate.core

/**
 * A package that is already suspended may have been suspended by Hail, adb
 * shell or another tool. Nemu must not claim that state: unsuspending it later
 * would undo someone else's policy. Unknown reads are treated as not suspended
 * so a normal first apply still works.
 */
object SuspendClaimPolicy {
    fun shouldClaim(
        alreadySuspended: Boolean?,
        ownedByNemu: Boolean = false
    ): Boolean = alreadySuspended != true || ownedByNemu
}
