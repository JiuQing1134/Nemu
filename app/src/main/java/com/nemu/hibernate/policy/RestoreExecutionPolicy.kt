package com.nemu.hibernate.policy

/**
 * A preview must stay read-only, including the always-on residual cleanup
 * that normally runs after a user-initiated restore. Shutdown and boot
 * recovery use their own actual-run services and are not affected.
 */
object RestoreExecutionPolicy {
    fun runsResidualCleanup(dryRun: Boolean): Boolean = !dryRun

    fun residualLabel(dryRun: Boolean): String =
        if (dryRun) "安全残留：预览不执行" else "安全残留"
}
