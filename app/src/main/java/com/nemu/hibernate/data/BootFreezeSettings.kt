package com.nemu.hibernate.data

import android.content.Context

/**
 * Boot-freeze / AI-freeze settings. Uses the same `nemu` SharedPreferences as
 * [Settings] but its own keys (design docs/07 §5.1), so the core settings
 * contract stays untouched.
 *
 * `last_shutdown_time` / `last_shutdown_result` are written here by the core
 * shutdown safety path (`markShutdownStarted/Finished`) for the bootloop
 * guard. When absent (older installs) the time reads as 0, which makes the
 * guard fail closed.
 */
class BootFreezeSettings(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Master switch, off by default. Even when on, nothing freezes until the
     * user enables individual targets: the scheduler refuses to enqueue work
     * with no actionable target and the worker re-filters with
     * TargetPolicy.isEnabled before running any command.
     */
    var bootFreezeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOOT_FREEZE_ENABLED, false)
        set(value) = prefs.edit()
            .putBoolean(KEY_BOOT_FREEZE_ENABLED, value)
            .apply()

    /** Delay after boot; always clamped to at least [MIN_DELAY_SECONDS]. */
    var bootFreezeDelaySeconds: Int
        get() = prefs.getInt(KEY_BOOT_FREEZE_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)
            .coerceAtLeast(MIN_DELAY_SECONDS)
        set(value) = prefs.edit()
            .putInt(
                KEY_BOOT_FREEZE_DELAY_SECONDS,
                value.coerceAtLeast(MIN_DELAY_SECONDS)
            )
            .apply()

    fun isAiTargetEnabled(packageName: String): Boolean =
        prefs.getBoolean(aiTargetKey(packageName), false)

    fun setAiTargetEnabled(packageName: String, enabled: Boolean) {
        prefs.edit()
            .putBoolean(aiTargetKey(packageName), enabled)
            .apply()
    }

    /** Boot count whose automatic-freeze decision has completed. */
    var lastBootFreezeBootCount: Int
        get() = prefs.getInt(KEY_LAST_FREEZE_BOOT_COUNT, -1)
        set(value) {
            prefs.edit().putInt(KEY_LAST_FREEZE_BOOT_COUNT, value).commit()
        }

    /** Boot count where freeze commands actually started. */
    var lastBootFreezeAttemptBootCount: Int
        get() = prefs.getInt(KEY_LAST_ATTEMPT_BOOT_COUNT, -1)
        set(value) {
            prefs.edit().putInt(KEY_LAST_ATTEMPT_BOOT_COUNT, value).commit()
        }

    /** Wall-clock millis written immediately before freeze commands run. */
    var lastBootFreezeAttemptTime: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_ATTEMPT_TIME, value).commit()
        }

    var lastBootFreezeTime: Long
        get() = prefs.getLong(KEY_LAST_FREEZE_TIME, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_FREEZE_TIME, value).commit()
        }

    var lastBootFreezeResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_RESULT, value).commit()
        }

    var lastBootFreezeDetail: String?
        get() = prefs.getString(KEY_LAST_DETAIL, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_DETAIL, value).commit()
        }

    var lastBootFreezeSucceeded: Boolean
        get() = prefs.getBoolean(KEY_LAST_SUCCEEDED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LAST_SUCCEEDED, value).commit()
        }

    /** Boot count the user manually skipped via "全部恢复". */
    var bootFreezeSkippedBootCount: Int
        get() = prefs.getInt(KEY_SKIPPED_BOOT_COUNT, -1)
        set(value) {
            prefs.edit().putInt(KEY_SKIPPED_BOOT_COUNT, value).commit()
        }

    /** Written by the core shutdown path together with `last_shutdown_result`. */
    val lastShutdownTime: Long
        get() = prefs.getLong(KEY_LAST_SHUTDOWN_TIME, 0L)

    val lastShutdownResult: String?
        get() = prefs.getString(KEY_LAST_SHUTDOWN_RESULT, null)

    /**
     * A shutdown counts as clean only when the safety unfreeze reported
     * success. Any failure/timeout/exception makes the bootloop guard fail
     * closed and skip the next automatic freeze.
     */
    val lastShutdownOk: Boolean
        get() = lastShutdownResult
            ?.let { it.startsWith("成功") || it.contains("安全解冻成功") } == true

    /**
     * Starts the shutdown safety pass. The sentinel is written in the same
     * transaction as the timestamp, so a process killed mid-unfreeze can never
     * leave yesterday's "成功" paired with a fresh timestamp.
     */
    fun markShutdownStarted(timestampMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_SHUTDOWN_RESULT, SHUTDOWN_IN_PROGRESS)
            .putLong(KEY_LAST_SHUTDOWN_TIME, timestampMillis)
            .commit()
    }

    fun markShutdownFinished(result: String) {
        prefs.edit()
            .putString(KEY_LAST_SHUTDOWN_RESULT, result)
            .commit()
    }

    /**
     * Durably records that freeze commands are about to run. Written before
     * applying so a crash/reboot mid-freeze is caught on the next boot.
     */
    fun recordAttempt(bootCount: Int, timestampMillis: Long): Boolean =
        prefs.edit()
            .putInt(KEY_LAST_ATTEMPT_BOOT_COUNT, bootCount)
            .putLong(KEY_LAST_ATTEMPT_TIME, timestampMillis)
            .commit()

    /**
     * One atomic record for a manual unfreeze that must suppress this boot's
     * automatic freeze. Separate commits could lose the skip on power loss and
     * let a late worker re-freeze the user's selection.
     */
    fun recordManualSkip(bootCount: Int, result: String): Boolean =
        prefs.edit()
            .putInt(KEY_SKIPPED_BOOT_COUNT, bootCount)
            .putInt(KEY_LAST_FREEZE_BOOT_COUNT, bootCount)
            .putString(KEY_LAST_RESULT, result)
            .putBoolean(KEY_LAST_SUCCEEDED, false)
            .putLong(KEY_LAST_FREEZE_TIME, System.currentTimeMillis())
            .commit()

    /**
     * Durably records the outcome for this boot. [lastBootFreezeBootCount] is
     * written here so the same boot never runs automatic freeze twice.
     */
    fun recordResult(
        bootCount: Int,
        result: String,
        detail: String?,
        succeeded: Boolean,
        timestampMillis: Long
    ) {
        prefs.edit()
            .putInt(KEY_LAST_FREEZE_BOOT_COUNT, bootCount)
            .putString(KEY_LAST_RESULT, result)
            .putString(KEY_LAST_DETAIL, detail)
            .putBoolean(KEY_LAST_SUCCEEDED, succeeded)
            .putLong(KEY_LAST_FREEZE_TIME, timestampMillis)
            .commit()
    }

    private fun aiTargetKey(packageName: String): String =
        "$KEY_AI_TARGET_PREFIX$packageName"

    companion object {
        const val PREFS_NAME = "nemu"
        const val MIN_DELAY_SECONDS = 60
        const val DEFAULT_DELAY_SECONDS = 120
        const val SHUTDOWN_IN_PROGRESS = "进行中"

        const val KEY_BOOT_FREEZE_ENABLED = "boot_freeze_enabled"
        const val KEY_BOOT_FREEZE_DELAY_SECONDS = "boot_freeze_delay_seconds"
        const val KEY_AI_TARGET_PREFIX = "ai_target_"
        const val KEY_LAST_FREEZE_BOOT_COUNT = "last_boot_freeze_boot_count"
        const val KEY_LAST_ATTEMPT_BOOT_COUNT =
            "last_boot_freeze_attempt_boot_count"
        const val KEY_LAST_ATTEMPT_TIME = "last_boot_freeze_attempt_time"
        const val KEY_LAST_FREEZE_TIME = "last_boot_freeze_time"
        const val KEY_LAST_RESULT = "last_boot_freeze_result"
        const val KEY_LAST_DETAIL = "last_boot_freeze_detail"
        const val KEY_LAST_SUCCEEDED = "last_boot_freeze_succeeded"
        const val KEY_SKIPPED_BOOT_COUNT = "boot_freeze_skipped_boot_count"
        const val KEY_LAST_SHUTDOWN_TIME = "last_shutdown_time"
        const val KEY_LAST_SHUTDOWN_RESULT = "last_shutdown_result"
    }
}
