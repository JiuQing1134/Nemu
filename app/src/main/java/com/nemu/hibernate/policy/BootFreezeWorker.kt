package com.nemu.hibernate.policy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.detection.PackageProcessListReader
import com.nemu.hibernate.privilege.BackendManager
import com.nemu.hibernate.privilege.PrivilegeBackend

/**
 * Delayed boot freeze (design docs/07 §4.1, §5.3–§5.6).
 *
 * Order is safety-critical:
 * 1. arm [SafetyService] so shutdown can always unfreeze;
 * 2. reject work scheduled for a previous boot (WorkManager persists work
 *    across reboots and could otherwise run immediately on the new boot);
 * 3. evaluate [BootFreezePolicy];
 * 4. collect enabled core/AI targets;
 * 5. durably write the attempt boot count/time **before** executing commands;
 * 6. apply core targets through [FreezeController] with `clearCache = false`
 *    and AI targets through [AiFreezeController];
 * 7. record the result. Never reboot.
 */
class BootFreezeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The safety service is started only after something is actually
        // frozen (below). Merely scheduling this worker must not keep a
        // foreground process alive while nothing is frozen.
        val settings = Settings(applicationContext)
        val bootSettings = BootFreezeSettings(applicationContext)
        val bootCount = BootCount.current(applicationContext)

        val scheduledBootCount = inputData.getInt(KEY_SCHEDULED_BOOT_COUNT, -1)
        if (scheduledBootCount >= 0 && scheduledBootCount != bootCount) {
            ActionLog.warn(
                "开机冻结任务来自上一个 boot（计划 $scheduledBootCount，" +
                    "当前 $bootCount），放弃"
            )
            return Result.success()
        }

        val backendManager = BackendManager(settings)
        val backend = backendManager.get()
        if (backend == null) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "失败：无可用权限",
                detail = "无可用后端（Root/Shizuku）",
                succeeded = false
            )
            ActionLog.warn("开机冻结失败：无可用权限")
            return Result.failure()
        }

        if (!isBootCompleted(backend)) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "已跳过：系统尚未完成启动",
                detail = "sys.boot_completed != 1",
                succeeded = false
            )
            ActionLog.warn("开机冻结已跳过：系统尚未完成启动")
            return Result.success()
        }

        val policy = TargetPolicy(applicationContext)
        val markerBackend = backendManager.getRootForShutdown()
        // Safety runs with dryRun=false regardless of the user's dry-run
        // setting; only the policy application honors dry-run.
        val safetyService = TargetFreezeService(
            context = applicationContext,
            backend = backend,
            dryRun = false,
            markerBackend = markerBackend,
            processSnapshotReader = { PackageProcessListReader(backend).read() }
        )
        val service = TargetFreezeService(
            context = applicationContext,
            backend = backend,
            dryRun = settings.dryRun,
            markerBackend = markerBackend,
            processSnapshotReader = { PackageProcessListReader(backend).read() }
        )

        // Recovery before policy: a forced power loss/crash has no shutdown
        // hook, so residual suspensions/components can survive the reboot.
        // Clear them first even when the bootloop guard will skip this boot's
        // freeze.
        val residual = safetyService.residualUnfreeze()
        if (!residual.ok) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "失败：残留冻结未清除",
                detail = residual.summary(),
                succeeded = false
            )
            ActionLog.error("开机恢复：残留解冻失败，跳过本次冻结")
            return Result.failure()
        }

        // Rescue skips policy application but only AFTER the always-on
        // residual recovery above, so a partial guard rescue is retried.
        if (BootGuard.isRescue(backendManager.getRoot())) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "已跳过：自动救砖模式",
                detail = "rescue flag present; residual recovery completed first",
                succeeded = false
            )
            ActionLog.warn("自动救砖已触发：跳过开机冻结")
            return Result.success()
        }

        val decision = BootFreezePolicy.evaluate(
            BootFreezeInput(
                bootFreezeEnabled = bootSettings.bootFreezeEnabled,
                bootCount = bootCount,
                lastSkippedBootCount = bootSettings.bootFreezeSkippedBootCount,
                lastFreezeBootCount = bootSettings.lastBootFreezeBootCount,
                lastAttemptBootCount = bootSettings.lastBootFreezeAttemptBootCount,
                lastAttemptTime = bootSettings.lastBootFreezeAttemptTime,
                lastFreezeSucceeded = bootSettings.lastBootFreezeSucceeded,
                lastFreezeTime = bootSettings.lastBootFreezeTime,
                lastShutdownTime = bootSettings.lastShutdownTime,
                lastShutdownOk = bootSettings.lastShutdownOk
            )
        )
        if (!decision.shouldRun) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "已跳过：${decision.reason}",
                detail = decision.reason,
                succeeded = false
            )
            ActionLog.info("开机冻结已跳过：${decision.reason}")
            return Result.success()
        }

        // The unified catalog is authoritative for boot freeze. It already
        // includes the legacy core/AI targets plus update, cloud, safety and
        // component-only targets. OBSERVE entries never execute commands.
        val targets = TargetCatalog.all
            .filter { policy.isEnabled(it) }
            .filter { policy.strategy(it) != FreezeStrategy.OBSERVE }
            .map { it.copy(clearCache = false) }

        if (targets.isEmpty()) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "没有启用的目标",
                detail = null,
                succeeded = false
            )
            ActionLog.info("开机冻结：没有启用的目标")
            return Result.success()
        }

        // Durability: the attempt is written before any freeze command. If the
        // device dies mid-freeze, the next boot sees this attempt without a
        // later clean shutdown and BootFreezePolicy skips. A dry run executes
        // nothing, so it does not create an attempt record.
        if (!settings.dryRun) {
            bootSettings.recordAttempt(bootCount, System.currentTimeMillis())
        }

        val run = service.apply(targets)
        val ok = run.ok
        val resultText = when {
            settings.dryRun -> "DRY-RUN：开机冻结计划完成"
            ok -> "开机冻结成功"
            else -> "开机冻结有失败"
        }
        val detail = buildDetail(
            backend = backend,
            targets = targets,
            run = run,
            dryRun = settings.dryRun
        )
        if (settings.dryRun) {
            // Preview must not consume this boot's only freeze slot.
            ActionLog.info("$resultText（$detail）")
            return Result.success()
        }
        recordResult(
            bootSettings = bootSettings,
            bootCount = bootCount,
            result = resultText,
            detail = detail,
            succeeded = ok
        )

        if (ok) {
            ActionLog.info("$resultText（$detail）")
            // Now that something is frozen, arm the shutdown unfreeze path.
            // A background FGS start may be refused; the manifest receiver
            // and the boot guard remain as fallbacks.
            SafetyService.sync(applicationContext)
        } else {
            ActionLog.error("$resultText（$detail）")
            SafetyService.sync(applicationContext)
        }
        // Never retry automatically: a failing command won't fix itself and
        // the user can apply the policy manually from the app.
        return if (ok) Result.success() else Result.failure()
    }

    private suspend fun isBootCompleted(backend: PrivilegeBackend): Boolean {
        val result = runCatching { backend.exec("getprop sys.boot_completed") }
            .getOrElse { ShellResult(-1, it.message ?: "getprop failed") }
        if (!result.ok) {
            // A failed read must not silently disable boot freezing; the 60s+
            // delay already leaves the system stable in practice.
            ActionLog.warn("无法读取 sys.boot_completed：${result.output.take(200)}")
            return true
        }
        return result.output.trim() == "1"
    }

    private fun buildDetail(
        backend: PrivilegeBackend,
        targets: List<com.nemu.hibernate.core.Target>,
        run: TargetRunResult,
        dryRun: Boolean
    ): String = buildString {
        append("后端=")
        append(backend.name)
        append(" | 目标=")
        append(targets.size)
        append("（成功 ")
        append(run.applied)
        append("，失败 ")
        append(run.failed)
        append("）")
        val byStrategy = targets.groupingBy { it.category }.eachCount()
        if (byStrategy.isNotEmpty()) {
            append(" | ")
            append(
                byStrategy.entries.joinToString(" ") { "${it.key.name}=${it.value}" }
            )
        }
        if (run.notes.isNotEmpty()) {
            append(" | ")
            append(run.notes.take(4).joinToString("；"))
        }
        if (dryRun) append(" | DRY-RUN")
    }

    private fun recordResult(
        bootSettings: BootFreezeSettings,
        bootCount: Int,
        result: String,
        detail: String?,
        succeeded: Boolean
    ) {
        bootSettings.recordResult(
            bootCount = bootCount,
            result = result,
            detail = detail,
            succeeded = succeeded,
            timestampMillis = System.currentTimeMillis()
        )
    }

    companion object {
        /** Boot count at schedule time; a mismatch means stale work. */
        const val KEY_SCHEDULED_BOOT_COUNT = "scheduled_boot_count"
    }
}
