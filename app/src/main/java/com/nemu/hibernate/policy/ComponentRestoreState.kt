package com.nemu.hibernate.policy

import com.nemu.hibernate.core.ShellResult

/**
 * A component is restored only when PackageManager reports default (0) or
 * enabled (1). A null readback is unknown, not success: treating it as
 * restored would delete the only exact-state baseline.
 */
object ComponentRestoreState {
    fun isRestored(state: Int?): Boolean = state == 0 || state == 1

    fun isMissingComponent(result: ShellResult): Boolean =
        // Some OEM pm builds exit non-zero while still printing the explicit
        // unknown-component marker; that is a resolved (missing) component,
        // not a transient command failure.
        result.output.contains("Unknown component", ignoreCase = true)
}
