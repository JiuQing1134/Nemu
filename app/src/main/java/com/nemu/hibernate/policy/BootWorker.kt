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

        val settings = Settings(applicationContext)
        val bootFreezeSettings = BootFreezeSettings(applicationContext)
        // WorkManager may deliver this safety pass many minutes after boot
        // (Doze, force-stop, app launch). If the user already froze something
        // during this boot, unfreezing now would silently undo their action.
        // Only a freeze attempt that happened after boot counts.
        val bootStartedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val frozenThisBoot = BootSafetyDecision.frozenThisBoot(
            lastAttemptTime = bootFreezeSettings.lastBootFreezeAttemptTime,
            lastFreezeTime = bootFreezeSettings.lastBootFreezeTime,
            lastSucceeded = bootFreezeSettings.lastBootFreezeSucceeded,
            bootStartedAtMillis = bootStartedAt
        )
        if (frozenThisBoot) {
            settings.lastBootResult = "已跳过：本次开机已经执行过冻结"
            settings.lastBootTime = System.currentTimeMillis()
            settings.lastBootDetail =
                "attempt=${bootFreezeSettings.lastBootFreezeAttemptTime} " +
                    "freeze=${bootFreezeSettings.lastBootFreezeTime} " +
                    "bootStartedAt=$bootStartedAt"
            ActionLog.info("开机安全解冻已跳过：本次开机已有冻结操作")
            return Result.success()
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

        val service = TargetFreezeService(
            context = applicationContext,
            backend = backend,
            dryRun = false,
            markerBackend = manager.getRootForShutdown()
        )
        val result = service.residualUnfreeze()
        val ok = result.ok
        settings.lastBootDetail = result.summary()
        val resultText = if (ok) "安全解冻成功" else "安全解冻失败"
        ActionLog.info("开机$resultText")
        settings.lastBootResult = resultText
        settings.lastBootTime = System.currentTimeMillis()
        // residualUnfreeze clears applied markers for everything it restored;
        // this stops the safety service when nothing remains frozen.
        SafetyService.sync(applicationContext)
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_SCHEDULED_BOOT_COUNT = "scheduled_boot_count"
    }
}

/**
 * Pure decision helper for a late boot-safety pass. A freeze attempt at or
 * after boot start means the user (or the auto-freeze worker) already acted
 * this boot, so the residual unfreeze must not undo it.
 */
internal object BootSafetyDecision {
    fun frozenThisBoot(
        lastAttemptTime: Long,
        lastFreezeTime: Long,
        lastSucceeded: Boolean = true,
        bootStartedAtMillis: Long
    ): Boolean =
        lastSucceeded && (
            lastAttemptTime >= bootStartedAtMillis ||
                lastFreezeTime >= bootStartedAtMillis
            )
}
