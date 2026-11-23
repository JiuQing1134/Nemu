package com.nemu.hibernate.core

/**
 * Like suspension, a hidden package must not be claimed when another tool
 * hid it. Nemu may re-apply its own already-hidden state so a user can press
 * Apply twice without a false "hidden by someone else" failure.
 */
object HideClaimPolicy {
    fun shouldClaim(
        hidden: Boolean?,
        ownedByNemu: Boolean
    ): Boolean = hidden != true || ownedByNemu
}
