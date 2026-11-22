package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.detection.ProcessListSnapshot
import com.nemu.hibernate.privilege.PrivilegeBackend

/**
 * Independent controller for the optional AI deep-freeze targets.
 *
 * Safety invariants (design docs/07 §6.2):
 * - only packages in [AiTargets.all] are accepted; everything else is refused;
 * - the only commands ever emitted are `pm suspend` / `am force-stop` on
 *   apply and `pm unsuspend` on restore;
 * - `pm clear`, `disable-user` and `uninstall` are never emitted, even when a
 *   caller passes a [Target] with `clearCache = true`;
 * - it does not consult the generic [com.nemu.hibernate.core.DenyList], because
 *   these system AI packages are exactly the ones that list protects from the
 *   generic path.
 */
class AiFreezeController(
    private val backend: PrivilegeBackend,
    private val dryRun: Boolean,
    private val processSnapshotReader: suspend () -> ProcessListSnapshot? = { null }
) {
    suspend fun apply(targets: List<Target>): Boolean {
        var allOk = true
        val forceStopTargets = mutableListOf<String>()
        for (target in targets) {
            if (!AiTargets.isAllowed(target.packageName)) {
                ActionLog.warn("拒绝 AI 冻结非白名单包：${target.packageName}")
                allOk = false
                continue
            }

            val commands = buildList {
                if (target.suspend) add(CommandBuilder.suspend(target.packageName))
                if (target.forceStop) add(CommandBuilder.forceStop(target.packageName))
                // target.clearCache is intentionally ignored: the boot/AI
                // freeze path never runs `pm clear --cache-only`.
            }
            if (target.forceStop && commands.isNotEmpty()) {
                forceStopTargets += target.packageName
            }
            if (!executeAll(commands)) allOk = false
        }
        if (!dryRun && forceStopTargets.isNotEmpty()) {
            val processSnapshot = readProcessesSafely()
            if (processSnapshot == null) {
                ActionLog.warn("AI 冻结后无法读取进程快照；未确认 force-stop 是否持续生效")
            } else {
                val stillRunning = forceStopTargets.filter(processSnapshot::isRunning)
                if (stillRunning.isEmpty()) {
                    ActionLog.info("AI 冻结后进程核验：未见目标进程")
                } else {
                    ActionLog.warn(
                        "AI force-stop 命令已返回，但仍检测到进程：" +
                            "${stillRunning.joinToString()}；系统绑定服务可能重新启动；不会循环强杀"
                    )
                }
            }
        }
        return allOk
    }

    suspend fun restore(targets: List<Target>): Boolean {
        var allOk = true
        for (target in targets) {
            if (!AiTargets.isAllowed(target.packageName)) {
                ActionLog.warn("拒绝 AI 恢复非白名单包：${target.packageName}")
                allOk = false
                continue
            }
            if (!executeAll(listOf(CommandBuilder.unsuspend(target.packageName)))) {
                allOk = false
            }
        }
        return allOk
    }

    private suspend fun executeAll(commands: List<String>): Boolean {
        var allOk = true
        for (command in commands) {
            if (dryRun) {
                ActionLog.info("DRY-RUN（AI）: $command")
                continue
            }
            val result = backend.exec(command)
            if (result.ok) {
                ActionLog.info("OK(${backend.name}，AI): $command")
            } else {
                ActionLog.error(
                    "FAIL(${backend.name}，AI，${result.exitCode}): $command\n" +
                        result.output.take(500)
                )
                allOk = false
            }
        }
        return allOk
    }

    private suspend fun readProcessesSafely(): ProcessListSnapshot? = try {
        processSnapshotReader()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}
