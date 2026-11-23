package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.core.RestoreBatchPlan
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.BackendManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shutdown path. The manifest receiver and the safety-service receiver both
 * use [runAsync], so the blocking backend work never runs on the main thread.
 * [ShutdownGate] gives one delivery ownership; the duplicate delivery returns
 * without touching the persisted result.
 */
object ShutdownSafety {
    const val SHUTDOWN_BUDGET_MS = RestoreBatchPlan.SHUTDOWN_BUDGET_MS
    private const val DUPLICATE_WINDOW_MS = 15_000L
    private val gate = ShutdownGate(DUPLICATE_WINDOW_MS)

    /**
     * Runs the unfreeze synchronously on the caller's worker thread. Returns
     * null when another receiver already owns this shutdown pass.
     */
    fun runBlockingUnfreeze(context: Context): String? = gate.run {
        val settings = Settings(context)
        BootFreezeSettings(context).markShutdownStarted()
        try {
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_BUDGET_MS) {
                    val manager = BackendManager(settings)
                    val backend = manager.getForShutdown()
                    if (backend == null) {
                        settings.lastShutdownDetail = "无可用后端"
                        "无可用权限"
                    } else {
                        val rootForMarker =
                            (backend as? com.nemu.hibernate.privilege.RootPrivilegeBackend)
                                ?: manager.getRootForShutdown()
                        // Component disables are restored on shutdown as well,
                        // so the next boot starts from manifest defaults. Boot
                        // freeze re-applies the current component policy later.
                        val service = TargetFreezeService(
                            context = context,
                            backend = backend,
                            dryRun = false,
                            markerBackend = rootForMarker
                        )
                        // The whole destructive pass shares the apply/restore
                        // lock; otherwise a boot freeze can interleave between
                        // the owned list and the commands.
                        val restore = FreezeOperationLock.mutex.withLock {
                            // Only undo suspensions Nemu actually owns.
                            val suspendResult =
                                SafetyUnfreezer.runUnsuspendPackages(
                                    backend,
                                    service.ownedSuspendPackageNames()
                                )
                            // No per-target dumpsys in the 8 s shutdown
                            // budget; missing packages are resolved by the
                            // next boot residual pass instead.
                            val components = service.restoreOwnedComponents(
                                checkMissing = false
                            )
                            val hidden = service.restoreOwnedHidden(
                                checkMissing = false
                            )
                            val restrictions =
                                service.restoreOwnedRestrictions(
                                    orphansOnly = true
                                )
                            val suspendVerified =
                                service.ownedSuspensionsCleared()
                            val ok = suspendResult.ok && suspendVerified &&
                                components.ok && hidden.ok && restrictions.ok
                            if (ok) {
                                service.clearSuspendAppliedMarkers(
                                    checkMissing = false
                                )
                                service.clearRestoredAppliedMarkers(
                                    checkMissing = false
                                )
                            }
                            ShutdownRestoreResults(
                                suspendResult,
                                components,
                                hidden,
                                restrictions,
                                suspendVerified,
                                ok
                            )
                        }
                        val result = restore.suspendResult
                        val componentResult = restore.components
                        val hiddenResult = restore.hidden
                        val restrictionResult = restore.restrictions
                        val allOk = restore.ok
                        val cleanMarkerWritten = allOk &&
                            BootGuard.markCleanShutdown(rootForMarker)
                        if (allOk && rootForMarker != null) {
                            // A successful pass leaves nothing to restore; do
                            // not let the next shutdown replay stale commands.
                            runCatching {
                                ShutdownScript.write(
                                    rootForMarker,
                                    TargetPolicy(context)
                                )
                            }
                        }
                        if (allOk && !cleanMarkerWritten) {
                            ActionLog.warn(
                                "关机解冻成功，但自动救援干净关机标记未写入"
                            )
                        }
                        settings.lastShutdownDetail = buildString {
                            append(if (allOk) "安全解冻成功" else "安全解冻有失败")
                            if (!result.ok || !restore.suspendVerified) {
                                append("；暂停残留未清除")
                            }
                            append("；组件：${componentResult.summary()}")
                            append("；隐藏：${hiddenResult.summary()}")
                            append("；限制：${restrictionResult.summary()}")
                            if (allOk && !cleanMarkerWritten) {
                                append("；救援标记未写入")
                            }
                        }
                        if (allOk) "成功" else "失败"
                    }
                } ?: "超时"
            }
        } catch (throwable: Throwable) {
            settings.lastShutdownDetail = "异常：${throwable.message}"
            "异常：${throwable.message}"
        }
    }

    private data class ShutdownRestoreResults(
        val suspendResult: SafetyResult,
        val components: com.nemu.hibernate.policy.TargetRunResult,
        val hidden: com.nemu.hibernate.policy.TargetRunResult,
        val restrictions: com.nemu.hibernate.policy.TargetRunResult,
        val suspendVerified: Boolean,
        val ok: Boolean
    )

    /**
     * Shared receiver entry point. Launches on IO so a slow root/Shizuku
     * backend cannot block the broadcast main thread. Only the gate owner
     * writes `lastShutdownResult`; both paths always finish their pending
     * broadcast result.
     */
    fun runAsync(context: Context, onFinished: () -> Unit) {
        val appContext = context.applicationContext ?: context
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                runBlockingUnfreeze(appContext)
            } catch (throwable: Throwable) {
                "异常：${throwable.message}"
            }
            if (result != null) {
                BootFreezeSettings(appContext).markShutdownFinished(result)
                ActionLog.info("关机安全解冻：$result")
            }
            runCatching(onFinished)
        }
    }
}
