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

    suspend fun ensureScheduled(context: Context) {
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
            policy.isEnabled(it) && policy.strategy(it) != FreezeStrategy.OBSERVE
        }
        if (!hasActionableTarget) {
            runCatching { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
            return
        }

        val bootCount = BootCount.current(appContext)
        if (settings.lastBootFreezeBootCount == bootCount ||
            settings.lastBootFreezeAttemptBootCount == bootCount ||
            settings.bootFreezeSkippedBootCount == bootCount
        ) {
            return
        }

        if (hasWorkForBoot(workManager, bootCount)) {
            return
        }

        val delaySeconds = settings.bootFreezeDelaySeconds
            .coerceAtLeast(BootFreezeSettings.MIN_DELAY_SECONDS)
        // A user opening the app long after boot must not trigger a freeze
        // 120s later while the phone is in active use. The worker is only
        // meaningful inside the boot window.
        val uptimeSeconds = SystemClock.elapsedRealtime() / 1000L
        if (uptimeSeconds > delaySeconds + 120L) {
            ActionLog.info("已过本次开机冻结窗口；开关只对下次开机生效")
            return
        }
        val request = OneTimeWorkRequestBuilder<BootFreezeWorker>()
            .setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
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
        ActionLog.info("已安排开机冻结：${delaySeconds}s 后（boot=$bootCount）")
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
}

/**
 * Boot-count reader shared by the scheduler and worker.
 *
 * Primary source is `Settings.Global.BOOT_COUNT`. When that read fails, the
 * elapsedRealtime hour bucket is a coarse replacement (design docs/07 §5.2).
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
        return (SystemClock.elapsedRealtime() / 1000L / 3600L).toInt()
    }
}
