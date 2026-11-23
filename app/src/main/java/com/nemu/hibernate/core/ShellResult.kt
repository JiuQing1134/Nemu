package com.nemu.hibernate.core

/**
 * Result of a privileged shell command.
 *
 * [indeterminate] means the wrapper was killed on timeout while the command
 * may still have reached the system (or may still land later). Callers must
 * keep ownership baselines/markers instead of rolling back on such a result.
 */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val indeterminate: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0
}
