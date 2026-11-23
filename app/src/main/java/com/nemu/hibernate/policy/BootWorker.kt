package com.nemu.hibernate.policy

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.BackendManager
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.delay

/**
 * Boot safety pass: unsuspend every known target and then stop. The old
 * "re-apply the freeze policy after boot" behaviour was removed deliberately —
 * targets stay unfrozen until the user taps Apply.
 */
class BootWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Re-arm the runtime shutdown receiver only when something is still
        // frozen. This may be refused when the worker runs as a background
        // job; SafetyService.start() swallows that and the worker still
        // performs the boot unfreeze.
        SafetyService.sync(applicationContext)
        BootFreezeScheduler.ensureScheduled(applicationContext)

        val currentBoot = BootCount.current(applicationContext)
        val scheduledBoot = inputData.getInt(KEY_SCHEDULED_BOOT_COUNT, -1)
        if (scheduledBoot >= 0 && scheduledBoot != currentBoot) {
            // WorkManager persists work across APK updates. A stale safety
            // pass must not unsuspend this boot's frozen targets after an
            // install or a later boot.
            ActionLog.warn(
                "开机安全解冻任务来自 boot $scheduledBoot，当前 $currentBoot，放弃"
            )
            return Result.success()
        }

        // This work is for the current boot; the device-protected crash
        // fallback (set by LOCKED_BOOT) has served its purpose.
        BootReceiver.clearPendingBootSafety(applicationContext)

        val settings = Settings(applicationContext)
        val bootFreezeSettings = BootFreezeSettings(applicationContext)
        if (bootFreezeSettings.lastShutdownResult ==
            BootFreezeSettings.SHUTDOWN_IN_PROGRESS
        ) {
            // The process was killed mid-shutdown; never leave a permanent
            // "进行中" sentinel in the UI.
            bootFreezeSettings.markShutdownFinished(
                "未完成：关机前解冻被中断"
            )
        }
        val manager = BackendManager(settings)

        var backend: PrivilegeBackend? = null
        var attempts = 0
        while (backend == null && attempts < 6) {
            backend = manager.get()
            if (backend == null) {
                delay(15_000)
                attempts++
            }
        }
        if (backend == null) {
            ActionLog.warn("开机安全解冻失败：无可用权限")
            settings.lastBootResult = "安全解冻失败：无可用权限"
            settings.lastBootTime = System.currentTimeMillis()
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }

        // WorkManager may deliver this safety pass many minutes after boot
        // (Doze, force-stop, app launch). The check must happen after the
        // backend wait: a manual/automatic freeze may land during the wait,
        // and unfreezing it afterwards would silently undo the user's action.
        val bootStartedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val service = TargetFreezeService(
            context = applicationContext,
            backend = backend,
            dryRun = false,
            markerBackend = manager.getRootForShutdown()
        )
        // The decision and residual pass must share the freeze lock: a manual
        // apply/restore may land between a pre-check and the cleanup.
        val result = service.residualUnfreezeIfNoBootAction(
            bootCount = currentBoot,
            bootSettings = bootFreezeSettings,
            bootStartedAtMillis = bootStartedAt
        )
        if (result == null) {
            settings.lastBootResult = "已跳过：本次开机已有冻结/解冻操作"
            settings.lastBootTime = System.currentTimeMillis()
            settings.lastBootDetail =
                "本次开机已执行过冻结或手动解冻，跳过安全解冻"
            ActionLog.info(
                "开机安全解冻已跳过：本次开机已有操作 " +
                    "attempt=${bootFreezeSettings.lastBootFreezeAttemptTime} " +
                    "skipped=${bootFreezeSettings.bootFreezeSkippedBootCount} " +
                    "bootStartedAt=$bootStartedAt"
            )
            return Result.success()
        }
        val ok = result.ok
        settings.lastBootDetail = result.summary()
        val resultText = if (ok) "安全解冻成功" else "安全解冻失败"
        ActionLog.info("开机$resultText")
        settings.lastBootResult = resultText
        settings.lastBootTime = System.currentTimeMillis()
        // residualUnfreeze clears applied markers for everything it restored;
        // this stops the safety service when nothing remains frozen.
        SafetyService.sync(applicationContext)
        // Keep the KernelSU shutdown script in sync after a background
        // cleanup, even when the user never opens the app this boot.
        manager.getRootForShutdown()?.let { markerRoot ->
            ShutdownScript.write(markerRoot, TargetPolicy(applicationContext))
        }
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_SCHEDULED_BOOT_COUNT = "scheduled_boot_count"
    }
}

/**
 * Pure decision helper for a late boot-safety pass. Only a freeze *attempt*
 * recorded for this boot suppresses the residual cleanup. `recordResult`
 * also writes `lastBootFreezeBootCount` for skips/failures that never touched
 * a target (no backend, boot not completed, residual failure), so that field
 * must not be treated as "the user already acted".
 */
internal object BootSafetyDecision {
    fun frozenThisBoot(
        lastAttemptTime: Long,
        lastAttemptBootCount: Int = -1,
        currentBootCount: Int = -1,
        bootStartedAtMillis: Long
    ): Boolean {
        // Boot counts survive clock jumps; wall-clock time is only a fallback
        // for ROMs that do not expose BOOT_COUNT.
        if (currentBootCount >= 0) {
            // Boot counts are immune to NTP/RTC jumps; do not let wall-clock
            // times override a different boot. -1 is "unknown", not a match.
            return lastAttemptBootCount >= 0 &&
                lastAttemptBootCount == currentBootCount
        }
        // Any attempt after boot start counts, even a failed/partial one:
        // an automatic residual pass must not undo the targets it froze.
        return lastAttemptTime > 0L && lastAttemptTime >= bootStartedAtMillis
    }
}
