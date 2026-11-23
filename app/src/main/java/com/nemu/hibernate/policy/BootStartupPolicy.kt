package com.nemu.hibernate.policy

enum class BootStartupAction {
    RETRY,
    GIVE_UP
}

/**
 * Startup conditions are transient: Shizuku/root may still be coming up and
 * `sys.boot_completed` may not be set yet. Retry without writing the boot
 * attempt/result, so a temporary failure does not consume the only automatic
 * freeze slot for this boot.
 */
object BootStartupPolicy {
    const val MAX_TRANSIENT_ATTEMPTS = 3

    fun missingBackend(runAttemptCount: Int): BootStartupAction =
        if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS) {
            BootStartupAction.RETRY
        } else {
            BootStartupAction.GIVE_UP
        }

    fun bootNotCompleted(runAttemptCount: Int): BootStartupAction =
        if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS) {
            BootStartupAction.RETRY
        } else {
            BootStartupAction.GIVE_UP
        }

    fun rescueUnknown(runAttemptCount: Int): BootStartupAction =
        if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS) {
            BootStartupAction.RETRY
        } else {
            BootStartupAction.GIVE_UP
        }

    fun bootStateUnknown(runAttemptCount: Int): BootStartupAction =
        if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS) {
            BootStartupAction.RETRY
        } else {
            BootStartupAction.GIVE_UP
        }
}
