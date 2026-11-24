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
 * 6. apply all targets through the unified [TargetFreezeService];
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
            when (BootStartupPolicy.missingBackend(runAttemptCount)) {
                BootStartupAction.RETRY -> {
                    ActionLog.warn("开机冻结暂缓：无可用权限，稍后重试")
                    return Result.retry()
                }
                BootStartupAction.GIVE_UP -> {
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
            }
        }

        val bootCompleted = isBootCompleted(backend)
        if (bootCompleted != true) {
            val action = if (bootCompleted == false) {
                BootStartupPolicy.bootNotCompleted(runAttemptCount)
            } else {
                BootStartupPolicy.bootStateUnknown(runAttemptCount)
            }
            when (action) {
                BootStartupAction.RETRY -> {
                    ActionLog.info("开机冻结暂缓：系统启动状态未确认，稍后重试")
                    return Result.retry()
                }
                BootStartupAction.GIVE_UP -> {
                    recordResult(
                        bootSettings = bootSettings,
                        bootCount = bootCount,
                        result = if (bootCompleted == false) {
                            "已跳过：系统尚未完成启动"
                        } else {
                            "已跳过：无法确认系统启动完成"
                        },
                        detail = if (bootCompleted == false) {
                            "系统尚未完成启动"
                        } else {
                            "sys.boot_completed 读取失败；未执行冻结"
                        },
                        succeeded = false
                    )
                    ActionLog.warn("开机冻结已跳过：系统启动状态未确认")
                    return if (bootCompleted == false) {
                        Result.success()
                    } else {
                        Result.failure()
                    }
                }
            }
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

        val bootStartedAt =
            System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

        // Recovery before policy: a forced power loss/crash has no shutdown
        // hook, so residual suspensions/components can survive the reboot.
        // The decision and cleanup share the freeze lock so a manual action
        // cannot land in between; null means an action already happened this
        // boot and residual cleanup must not undo it.
        val residual = safetyService.residualUnfreezeIfNoBootAction(
            bootCount = bootCount,
            bootSettings = bootSettings,
            bootStartedAtMillis = bootStartedAt
        )
        if (residual == null) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "已跳过：本次开机已有冻结/解冻操作",
                detail = "不执行残留清理，避免撤销本 boot 的选择",
                succeeded = false
            )
            ActionLog.info("开机冻结已跳过：本次开机已有操作")
            return Result.success()
        }
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
        // Residual cleanup may have cleared ownership; remove stale shutdown
        // commands before this boot's freeze (if any) is applied.
        markerBackend?.let {
            ShutdownScript.write(it, policy)
        }

        // WorkManager can deliver this work hours late (Doze, force-stop,
        // OEM background policy). Residual recovery above is always safe, but
        // applying a boot freeze during active use is not: stop here and
        // record the skip for the card.
        if (BootFreezeScheduler.bootWindowExpired(
                applicationContext,
                bootCount
            )
        ) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "已跳过：超过开机冻结窗口",
                detail = "任务延迟到窗口之后才执行；未在活跃使用时冻结",
                succeeded = false
            )
            ActionLog.warn("开机冻结已跳过：超过开机冻结窗口")
            return Result.success()
        }

        // Rescue skips policy application but only AFTER the always-on
        // residual recovery above, so a partial guard rescue is retried.
        val rootBackend = backendManager.getRoot()
        val rescue = BootGuard.rescueState(rootBackend)
        val requiresMarkers = TargetCatalog.all.any { target ->
            policy.isEnabled(target) && policy.isActive(target) &&
                policy.strategy(target) in setOf(
                    FreezeStrategy.COMPONENT,
                    FreezeStrategy.DISABLE_USER,
                    FreezeStrategy.HIDE
                )
        }
        when {
            rescue == true -> {
                recordResult(
                    bootSettings = bootSettings,
                    bootCount = bootCount,
                    result = "已跳过：自动救砖模式",
                    detail = "救援标记存在；已先完成残留恢复",
                    succeeded = false
                )
                ActionLog.warn("自动救砖已触发：跳过开机冻结")
                return Result.success()
            }
            rescue == false -> Unit
            rootBackend == null && !requiresMarkers -> Unit
            else -> when (BootStartupPolicy.rescueUnknown(runAttemptCount)) {
                BootStartupAction.RETRY -> {
                    ActionLog.warn("开机冻结暂缓：无法确认救援状态，稍后重试")
                    return Result.retry()
                }
                BootStartupAction.GIVE_UP -> {
                    recordResult(
                        bootSettings = bootSettings,
                        bootCount = bootCount,
                        result = "已跳过：无法确认救援状态",
                        detail = "Root/救援标记不可读；未执行冻结",
                        succeeded = false
                    )
                    ActionLog.warn("开机冻结已跳过：无法确认救援状态")
                    return Result.failure()
                }
            }
        }

        // The root daemon also sees adb reboot/poweroff, which never reaches
        // the App's ACTION_SHUTDOWN path. Treat a guard-classified planned
        // shutdown after our last attempt as clean for this gate; otherwise
        // the first auto-freeze after every adb reboot would be skipped.
        val plannedShutdownTime =
            BootGuard.lastPlannedShutdownTime(markerBackend) ?: 0L
        val shutdownGate = BootFreezePolicy.shutdownGate(
            plannedShutdownMs = plannedShutdownTime,
            appShutdownOk = bootSettings.lastShutdownOk,
            appShutdownTimeMs = bootSettings.lastShutdownTime,
            lastAttemptTimeMs = bootSettings.lastBootFreezeAttemptTime
        )
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
                lastShutdownTime = shutdownGate.time,
                lastShutdownOk = shutdownGate.clean
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

        // The unified catalog is authoritative for boot freeze. It includes
        // the core/AI entries plus update, cloud, safety and component-only
        // targets. OBSERVE entries never execute commands.
        val targets = TargetCatalog.all
            .filter { policy.isEnabled(it) }
            .filter { policy.isActive(it) }

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
        if (!settings.dryRun &&
            !bootSettings.recordAttempt(bootCount, System.currentTimeMillis())
        ) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "失败：冻结尝试无法持久化",
                detail = "SharedPreferences 提交失败，未执行冻结",
                succeeded = false
            )
            ActionLog.error("开机冻结：尝试记录写入失败，未执行命令")
            return Result.failure()
        }

        val run = service.applyForBoot(
            bootCount = bootCount,
            bootSettings = bootSettings,
            targets = targets
        )
        if (run == null) {
            recordResult(
                bootSettings = bootSettings,
                bootCount = bootCount,
                result = "已跳过：手动解冻",
                detail = "本次开机不自动冻结",
                succeeded = false
            )
            ActionLog.info("开机冻结已跳过：用户已在本 boot 手动解冻")
            return Result.success()
        }
        val ok = run.ok
        val resultText = when {
            settings.dryRun -> "预览：开机冻结计划完成"
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
        // Automatic freezes run while the UI is closed; regenerate the
        // KernelSU shutdown script so it reflects this boot's ownership.
        markerBackend?.let {
            ShutdownScript.write(it, policy)
        }
        // Never retry automatically: a failing command won't fix itself and
        // the user can apply the policy manually from the app.
        return if (ok) Result.success() else Result.failure()
    }

    /** Null means the read failed; freeze must not proceed on an unknown. */
    private suspend fun isBootCompleted(backend: PrivilegeBackend): Boolean? {
        val result = runCatching { backend.exec("getprop sys.boot_completed") }
            .getOrElse { ShellResult(-1, it.message ?: "getprop failed") }
        if (!result.ok) {
            ActionLog.warn("无法读取 sys.boot_completed：${result.output.take(200)}")
            return null
        }
        return result.output.trim() == "1"
    }

    private fun buildDetail(
        backend: PrivilegeBackend,
        targets: List<com.nemu.hibernate.core.Target>,
        run: TargetRunResult,
        dryRun: Boolean
    ): String {
        val byStrategy = targets.groupingBy { it.category }.eachCount()
        if (byStrategy.isNotEmpty()) {
            // Category enum names are diagnostics; the UI detail stays Chinese.
            ActionLog.info(
                "开机冻结分类统计（${backend.name}）：" +
                    byStrategy.entries.joinToString(" ") {
                        "${it.key.name}=${it.value}"
                    }
            )
        }
        return buildString {
            append("目标 ")
            append(targets.size)
            append("（成功 ")
            append(run.applied)
            append("，失败 ")
            append(run.failed)
            append("）")
            if (run.notes.isNotEmpty()) {
                append("；")
                append(run.notes.take(4).joinToString("；"))
            }
            if (dryRun) append("；预览，未修改手机")
        }
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
