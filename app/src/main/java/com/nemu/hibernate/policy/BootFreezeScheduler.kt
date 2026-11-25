package com.nemu.hibernate.policy

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.BootFreezeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Schedules the delayed boot-freeze work (design docs/07 §5.4).
 *
 * One unique work item `nemu_boot_freeze` with
 * [ExistingWorkPolicy.REPLACE] and an initial delay of
 * `boot_freeze_delay_seconds` (minimum 60s). It is never enqueued when the
 * master switch is off, this boot was already attempted or skipped, or a
 * live/successful work for the same boot already exists.
 *
 * REPLACE (not KEEP): WorkManager's KEEP ignores the new request whenever any
 * work under the unique name is still ENQUEUED/RUNNING. A stale work from the
 * previous boot would therefore silently block the new boot's schedule. The
 * [hasWorkForBoot] check above already protects live current-boot work, so
 * REPLACE only cancels stale work.
 */
object BootFreezeScheduler {
    const val UNIQUE_WORK_NAME = "nemu_boot_freeze"

    /** States that mean "do not enqueue another work for this boot". */
    private val blockingStates = setOf(
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.RUNNING,
        WorkInfo.State.BLOCKED,
        WorkInfo.State.SUCCEEDED
    )

    /**
     * Serializes the check-then-enqueue sequence. Without a lock two callers
     * (boot trigger + UI) could both see "no work for this boot" and the
     * second REPLACE would cancel the request the first just enqueued.
     */
    private val scheduleMutex = Mutex()

    suspend fun ensureScheduled(context: Context) {
        // WorkManager/prefs can throw during direct boot or OEM background
        // restriction. Scheduling is best-effort: never let it take down the
        // app process from a fire-and-forget coroutine.
        runCatching {
            scheduleMutex.withLock { ensureScheduledLocked(context) }
        }.onFailure {
            ActionLog.warn(
                "开机冻结排程失败：" +
                    (it.message ?: it.javaClass.simpleName)
            )
        }
    }

    private suspend fun ensureScheduledLocked(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        val settings = BootFreezeSettings(appContext)

        if (!settings.bootFreezeEnabled) {
            runCatching { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
            return
        }

        // With no selected actionable target the worker would only wake up,
        // find nothing and exit. Do not schedule that pointless wakeup.
        val policy = TargetPolicy(appContext)
        val hasActionableTarget = TargetCatalog.all.any {
            policy.isEnabled(it) && policy.isActive(it)
        }
        if (!hasActionableTarget) {
            runCatching { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
            return
        }

        val bootCount = BootCount.current(appContext)
        if (bootCount >= 0 &&
            (settings.lastBootFreezeBootCount == bootCount ||
                settings.lastBootFreezeAttemptBootCount == bootCount ||
                settings.bootFreezeSkippedBootCount == bootCount ||
                settings.bootFreezeWindowMissedBootCount == bootCount)
        ) {
            return
        }

        if (bootCount >= 0 && hasWorkForBoot(workManager, bootCount)) {
            return
        }

        val delaySeconds = settings.bootFreezeDelaySeconds
            .coerceAtLeast(BootFreezeSettings.MIN_DELAY_SECONDS)
        // The deadline is anchored at the first moment Nemu observed this
        // boot (module boot trigger, boot worker, or app process start), not
        // at the moment the user flips the switch. A notification-only module
        // or a late first app start therefore still gets one predictable
        // freeze window instead of silently skipping the whole boot.
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000L
        var anchor = settings.bootFreezeAnchorUptimeSeconds
        if (bootCount >= 0) {
            if (settings.bootFreezeAnchorBootCount != bootCount) {
                if (uptimeSeconds >
                    delaySeconds + FIRST_OBSERVATION_LIMIT_SECONDS
                ) {
                    // Opening Nemu long after boot must not freeze apps
                    // during active use. The module's boot trigger normally
                    // anchors this within seconds of boot.
                    recordWindowMissed(settings, bootCount)
                    return
                }
                if (!settings.anchorBootWindow(bootCount, uptimeSeconds)) {
                    // A failed commit is not fatal: fall back to an
                    // unanchored boot-relative delay below.
                    ActionLog.warn("开机冻结锚点写入失败，改用开机相对时间")
                }
                anchor = uptimeSeconds
            }
        } else {
            // Without a boot count there is no durable same-boot anchor;
            // use the boot-relative uptime and let the worker's -1 guard
            // fail open.
            anchor = 0L
        }
        val remaining = remainingBootDelaySeconds(
            delaySeconds = delaySeconds.toLong(),
            uptimeSeconds = uptimeSeconds,
            anchorUptimeSeconds = anchor
        )
        if (remaining == null) {
            if (bootCount >= 0) recordWindowMissed(settings, bootCount)
            else ActionLog.info("已过本次开机冻结窗口；本次开机不再执行")
            return
        }
        if (remaining == LATE_GRACE_SECONDS) {
            ActionLog.info(
                "已过本次开机冻结窗口，${LATE_GRACE_SECONDS}s 后补偿执行"
            )
        }
        val request = OneTimeWorkRequestBuilder<BootFreezeWorker>()
            .setInitialDelay(remaining, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    BootFreezeWorker.KEY_SCHEDULED_BOOT_COUNT to bootCount
                )
            )
            .addTag(bootTag(bootCount))
            .build()

        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        ActionLog.info("已启用开机冻结：${delaySeconds}s 后（boot=$bootCount）")
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }.onSuccess {
            ActionLog.info("已取消开机冻结任务")
        }
    }

    /**
     * Checks existing work synchronously on the IO dispatcher. WorkInfo for a
     * different boot is stale (WorkManager persists one-time work across
     * reboots), so it never blocks a fresh schedule for the current boot.
     * The scheduling boot is encoded in a tag because WorkInfo no longer
     * exposes input data in WorkManager 2.11.
     */
    private suspend fun hasWorkForBoot(
        workManager: WorkManager,
        bootCount: Int
    ): Boolean {
        val existing = runCatching {
            withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            }
        }.getOrNull().orEmpty()

        val tag = bootTag(bootCount)
        return existing.any { info ->
            tag in info.tags && info.state in blockingStates
        }
    }

    private fun bootTag(bootCount: Int): String =
        "${UNIQUE_WORK_NAME}_boot_$bootCount"

    private fun recordWindowMissed(
        settings: BootFreezeSettings,
        bootCount: Int
    ) {
        if (bootCount < 0) return
        if (settings.bootFreezeWindowMissedBootCount == bootCount) return
        settings.bootFreezeWindowMissedBootCount = bootCount
        ActionLog.info("已过本次开机冻结窗口；本次开机不再执行")
    }

    /**
     * True when the worker is being delivered after the boot window has
     * already closed. The worker uses this to skip freezing during active use;
     * residual shutdown recovery still runs before this check.
     */
    fun bootWindowExpired(context: Context, bootCount: Int): Boolean {
        if (bootCount < 0) return false
        val settings = BootFreezeSettings(context.applicationContext)
        if (settings.bootFreezeAnchorBootCount != bootCount) return false
        val delay = settings.bootFreezeDelaySeconds
            .coerceAtLeast(BootFreezeSettings.MIN_DELAY_SECONDS)
        val uptime = SystemClock.elapsedRealtime() / 1000L
        return isWindowExpired(
            delaySeconds = delay.toLong(),
            uptimeSeconds = uptime,
            anchorUptimeSeconds = settings.bootFreezeAnchorUptimeSeconds
        )
    }

    internal fun isWindowExpired(
        delaySeconds: Long,
        uptimeSeconds: Long,
        anchorUptimeSeconds: Long
    ): Boolean {
        val elapsed = (uptimeSeconds - anchorUptimeSeconds).coerceAtLeast(0L)
        return elapsed > delaySeconds + LATE_COMPENSATION_WINDOW_SECONDS
    }

    /**
     * Delay measured from the boot-window anchor. Returns [LATE_GRACE_SECONDS]
     * when the worker is at most that late, so a missed window still gets one
     * bounded compensation run; null means the window is gone for this boot.
     */
    internal fun remainingBootDelaySeconds(
        delaySeconds: Long,
        uptimeSeconds: Long,
        anchorUptimeSeconds: Long = 0L
    ): Long? {
        val elapsed = (uptimeSeconds - anchorUptimeSeconds).coerceAtLeast(0L)
        val remaining = delaySeconds - elapsed
        return when {
            remaining > 0L -> remaining
            // OEM background policy can delay a non-expedited worker by a
            // few minutes; keep that recovery bounded but not brittle.
            elapsed - delaySeconds <= LATE_COMPENSATION_WINDOW_SECONDS ->
                LATE_GRACE_SECONDS
            else -> null
        }
    }

    /** First observation must happen within this long after boot. */
    private const val FIRST_OBSERVATION_LIMIT_SECONDS = 600L

    /** Compensation delay for a window that just passed. */
    internal const val LATE_GRACE_SECONDS = 15L

    /** How late a missed window may still trigger a compensation run. */
    internal const val LATE_COMPENSATION_WINDOW_SECONDS = 300L
}

/**
 * Boot-count reader shared by the scheduler and worker.
 *
 * Primary source is `Settings.Global.BOOT_COUNT`. When that read fails, the
 * first 8 hex digits of `/proc/sys/kernel/random/boot_id` identify the boot;
 * -1 means unknown and makes every same-boot guard fail open.
 */
internal object BootCount {
    fun current(context: Context): Int {
        val bootCount = runCatching {
            AndroidSettings.Global.getInt(
                context.contentResolver,
                AndroidSettings.Global.BOOT_COUNT,
                -1
            )
        }.getOrDefault(-1)
        if (bootCount >= 0) return bootCount

        // Do not log here: this getter is also read during Compose
        // composition, and an ActionLog write would trigger recomposition and
        // loop when BOOT_COUNT is unavailable.
        val bootId = runCatching {
            java.io.File("/proc/sys/kernel/random/boot_id")
                .readText()
                .trim()
                .replace("-", "")
        }.getOrNull()
        if (!bootId.isNullOrBlank()) {
            val value = bootId.take(8).toLongOrNull(16)
            if (value != null) {
                return (value and 0x7fffffffL).toInt()
            }
        }
        return -1
    }
}
