package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.PrivilegeBackend

/**
 * Always-on safety path: unsuspend every known target, one package per root
 * command so a removed package cannot abort the rest. This deliberately
 * ignores [com.nemu.hibernate.data.Settings.dryRun] — safety must never be
 * disabled by the dry-run setting.
 */
data class SafetyResult(
    val ok: Boolean,
    val backend: String,
    val command: String,
    val exitCode: Int,
    val output: String
)

object SafetyUnfreezer {
    suspend fun unsuspendAll(
        backend: PrivilegeBackend,
        targets: List<Target>
    ): Boolean = unsuspendPackages(backend, targets.map { it.packageName })

    suspend fun unsuspendPackages(
        backend: PrivilegeBackend,
        packages: Collection<String>
    ): Boolean = runUnsuspendPackages(backend, packages).ok

    suspend fun runUnsuspendPackages(
        backend: PrivilegeBackend,
        packages: Collection<String>
    ): SafetyResult {
        val distinct = packages.distinct()
        if (distinct.isEmpty()) {
            return SafetyResult(true, backend.name, "", 0, "empty")
        }

        // One package per command: a removed/renamed package (or one whose
        // suspension is owned by another suspender) must not abort the rest of
        // the safety list. This mirrors boot_guard.sh's per-package pass.
        val commands = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        var ok = true
        var lastExit = 0
        for (packageName in distinct) {
            val command = CommandBuilder.unsuspend(packageName)
            commands += command
            val result = backend.exec(command)
            val text = result.output
            // Only pm's explicit package-not-found text with exit 0 counts as
            // "already resolved". A backend failure such as "su not found"
            // must never clear ownership or satisfy the bootloop guard.
            // exit -1 means the backend itself failed (e.g. su disappeared);
            // a real pm failure has a non-negative exit code.
            val unknown = result.exitCode >= 0 &&
                (text.contains("Unknown target package", ignoreCase = true) ||
                    text.contains("Unknown package", ignoreCase = true))
            // pm unsuspend exits 0 even when it cannot clear a suspension owned
            // by a different suspendingPackage. Treat remaining "state: true"
            // as failure so the bootloop guard fails closed.
            val stillSuspended = text.contains("new suspended state: true")
            val itemOk = (result.ok || unknown) && !stillSuspended
            outputs += "$packageName: ${text.take(200)}"
            if (!itemOk) {
                ok = false
                lastExit = result.exitCode
                ActionLog.error(
                    "安全解冻失败（${backend.name}，${result.exitCode}）：" +
                        "$command\n${text.take(300)}"
                )
            }
        }
        if (ok) {
            ActionLog.info("安全解冻成功（${backend.name}）：${distinct.size} 个目标")
        } else {
            ActionLog.error("安全解冻有不完整项（${backend.name}）：见逐包日志")
        }
        return SafetyResult(
            ok = ok,
            backend = backend.name,
            command = commands.joinToString("; ").take(1000),
            exitCode = lastExit,
            output = outputs.joinToString("\n").take(1000)
        )
    }
}
