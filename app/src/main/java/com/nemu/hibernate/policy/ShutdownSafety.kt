package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.BackendManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronous shutdown path. Broadcast receivers on this OEM can have their
 * process killed before a coroutine runs, so we block the receiver thread for
 * at most 8s (the broadcast window is ~10s) and finish the unfreeze inline.
 */
object ShutdownSafety {
    private val inFlight = AtomicBoolean(false)

    fun runBlockingUnfreeze(context: Context): String {
        if (!inFlight.compareAndSet(false, true)) {
            return "已由另一路径执行"
        }
        return try {
            runCatching {
        runBlocking {
            withTimeoutOrNull(8_000) {
                val settings = Settings(context)
                val manager = BackendManager(settings)
                val backend = manager.getForShutdown()
                if (backend == null) {
                    settings.lastShutdownDetail = "无可用后端"
                    "无可用权限"
                } else {
                    val result = SafetyUnfreezer.runUnsuspendPackages(
                        backend,
                        SafetyTargets.suspendPackageNames
                    )
                    val rootForMarker =
                        (backend as? com.nemu.hibernate.privilege.RootPrivilegeBackend)
                            ?: manager.getRootForShutdown()
                    // Component disables are restored on shutdown as well, so
                    // the next boot starts from the manifest defaults. Boot
                    // freeze re-applies the current component policy later.
                    val service = TargetFreezeService(
                        context = context,
                        backend = backend,
                        dryRun = false,
                        markerBackend = rootForMarker
                    )
                    val componentResult = service.restoreOwnedComponents()
                    val hiddenResult = service.restoreOwnedHidden()
                    val allOk = result.ok && componentResult.ok && hiddenResult.ok
                    if (allOk) {
                        service.clearSuspendAppliedMarkers()
                        service.clearRestoredAppliedMarkers()
                    }
                    val cleanMarkerWritten = allOk &&
                        BootGuard.markCleanShutdown(rootForMarker)
                    if (allOk && !cleanMarkerWritten) {
                        com.nemu.hibernate.data.ActionLog.warn(
                            "关机解冻成功，但自动救援干净关机标记未写入"
                        )
                    }
                    settings.lastShutdownDetail =
                        "${result.backend} | exit=${result.exitCode} | " +
                            "${result.command} | ${result.output}" +
                            " | components=${componentResult.summary()}" +
                            " | hidden=${hiddenResult.summary()}" +
                            (if (allOk) " | rescue_marker=$cleanMarkerWritten" else "")
                    if (allOk) "成功" else "失败"
                }
            } ?: "超时"
        }
            }.getOrElse { throwable ->
                "异常：${throwable.message}"
            }
        } finally {
            inFlight.set(false)
        }
    }
}
