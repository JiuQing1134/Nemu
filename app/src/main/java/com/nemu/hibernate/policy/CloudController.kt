package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudTarget
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.DenyList
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.CloudSettingsStore
import com.nemu.hibernate.detection.ProcessListSnapshot
import com.nemu.hibernate.privilege.PrivilegeBackend

/**
 * Applies cloud/telemetry policy. Two independent guards protect disable-user:
 * the static allowlist [CloudTargets.disableUserAllowed] and the persisted
 * per-target confirmation flag. The controller never calls uninstall.
 */
class CloudController(
    private val backend: PrivilegeBackend,
    private val store: CloudSettingsStore,
    private val enabledStateReader: (String) -> Boolean? = { null },
    private val beforeDisableByNemu: suspend (String) -> Boolean = { true },
    private val afterRestoreByNemu: suspend (String) -> Boolean = { true },
    private val suspendedStateReader: (String) -> Boolean? = { null },
    private val processSnapshotReader: suspend () -> ProcessListSnapshot? = { null }
) {
    suspend fun apply(targets: List<CloudTarget>): Boolean {
        var allOk = true
        var attempted = 0
        val suspendTargets = mutableListOf<String>()

        for (target in targets) {
            if (target.action == CloudAction.OBSERVE_ONLY) continue
            if (!store.isEnabled(target.packageName)) continue
            attempted++

            val reason = refusalReason(target)
            if (reason != null) {
                ActionLog.warn("拒绝云控操作：${target.packageName}（$reason）")
                if (!store.dryRun) {
                    store.setApplied(target.packageName, false)
                }
                allOk = false
                continue
            }

            if (target.action == CloudAction.DISABLE_USER && !store.dryRun) {
                val original = store.originalEnabled(target.packageName)
                    ?: enabledStateReader(target.packageName)
                if (original == null) {
                    ActionLog.warn(
                        "拒绝停用 ${target.packageName}：无法读取操作前启用状态"
                    )
                    store.setApplied(target.packageName, false)
                    allOk = false
                    continue
                }
                store.setOriginalEnabled(target.packageName, original)
                if (!original) {
                    // It was disabled before Nemu; preserve that state and
                    // avoid claiming ownership of the change.
                    store.setApplied(target.packageName, true)
                    ActionLog.info("保持原有停用状态：${target.packageName}")
                    continue
                }
                if (!beforeDisableByNemu(target.packageName)) {
                    ActionLog.warn(
                        "拒绝停用 ${target.packageName}：无法记录救援恢复标记"
                    )
                    store.setApplied(target.packageName, false)
                    allOk = false
                    continue
                }
            }

            val succeeded = executeAll(commandsFor(target))
            if (!succeeded) allOk = false
            if (!store.dryRun) {
                store.setApplied(target.packageName, succeeded)
                if (target.action == CloudAction.SUSPEND && succeeded) {
                    suspendTargets += target.packageName
                }
            }
        }

        val runtimeNotes = if (!store.dryRun && suspendTargets.isNotEmpty()) {
            verifySuspendTargets(suspendTargets)
        } else {
            emptyList()
        }
        if (runtimeNotes.any { it.startsWith("PackageManager") }) allOk = false

        store.lastResult = resultText(
            when {
                attempted == 0 -> "没有启用的目标（${System.currentTimeMillis()}）"
                runtimeNotes.isNotEmpty() ->
                    "命令已执行；${runtimeNotes.joinToString("；")}（${System.currentTimeMillis()}）"
                allOk -> "成功（${System.currentTimeMillis()}）"
                else -> "有失败或拒绝（${System.currentTimeMillis()}）"
            }
        )
        return allOk
    }

    suspend fun restoreAll(targets: List<CloudTarget>): Boolean {
        var allOk = true

        for (target in targets) {
            if (target.action == CloudAction.OBSERVE_ONLY) continue

            val commands = if (target.action == CloudAction.DISABLE_USER) {
                when (store.originalEnabled(target.packageName)) {
                    true -> listOf(CommandBuilder.enable(target.packageName))
                    false -> emptyList()
                    null -> when (enabledStateReader(target.packageName)) {
                        true -> emptyList() // Already enabled; nothing to restore.
                        false, null -> {
                            ActionLog.warn(
                                "无法安全恢复 ${target.packageName}：原始启用状态未知，未执行 pm enable"
                            )
                            allOk = false
                            emptyList()
                        }
                    }
                }
            } else {
                listOf(CommandBuilder.unsuspend(target.packageName))
            }
            val succeeded = executeAll(commands)
            var restored = succeeded
            if (!store.dryRun && succeeded && target.action == CloudAction.DISABLE_USER &&
                store.originalEnabled(target.packageName) == true
            ) {
                restored = enabledStateReader(target.packageName) == true &&
                    afterRestoreByNemu(target.packageName)
                if (!restored) {
                    ActionLog.warn(
                        "${target.packageName} 未确认启用或救援恢复标记未能清除"
                    )
                }
            }
            if (!restored) allOk = false
            if (!store.dryRun && restored) {
                store.setApplied(target.packageName, false)
                if (target.action == CloudAction.DISABLE_USER) {
                    store.setOriginalEnabled(target.packageName, null)
                }
            }
        }

        store.lastResult = resultText(
            if (allOk) {
                "成功（${System.currentTimeMillis()}）"
            } else {
                "有失败或拒绝（${System.currentTimeMillis()}）"
            }
        )
        return allOk
    }

    private fun resultText(base: String): String =
        if (store.dryRun) "$base（DRY-RUN，未执行）" else base

    private fun refusalReason(target: CloudTarget): String? = when {
        DenyList.isProtected(target.packageName) -> "在禁止清单中"
        target.action == CloudAction.DISABLE_USER &&
            target.packageName !in CloudTargets.disableUserAllowed ->
            "disable-user 只允许运营商 DM 三件套"
        target.action == CloudAction.DISABLE_USER &&
            !store.isConfirmed(target.packageName) ->
            "未确认停用风险"
        else -> null
    }

    private fun commandsFor(target: CloudTarget): List<String> = when (target.action) {
        CloudAction.DISABLE_USER -> listOf(CommandBuilder.disableUser(target.packageName))
        CloudAction.SUSPEND -> listOf(
            CommandBuilder.suspend(target.packageName),
            CommandBuilder.forceStop(target.packageName)
        )
        CloudAction.OBSERVE_ONLY -> emptyList()
    }

    private suspend fun executeAll(commands: List<String>): Boolean {
        var allOk = true
        for (command in commands) {
            if (store.dryRun) {
                ActionLog.info("DRY-RUN: $command")
                continue
            }
            val result = backend.exec(command)
            if (result.ok) {
                ActionLog.info("OK(${backend.name}): $command")
            } else {
                ActionLog.error(
                    "FAIL(${backend.name}, ${result.exitCode}): $command\n" +
                        result.output.take(500)
                )
                allOk = false
            }
        }
        return allOk
    }

    private suspend fun verifySuspendTargets(targets: List<String>): List<String> {
        val notes = mutableListOf<String>()
        val notSuspended = targets.filter { suspendedStateReader(it) == false }
        if (notSuspended.isNotEmpty()) {
            ActionLog.warn(
                "pm suspend 命令返回成功，但 PackageManager 复核未暂停：" +
                    notSuspended.joinToString()
            )
            notes += "PackageManager 未确认 ${notSuspended.size} 项暂停"
        }

        val processSnapshot = try {
            processSnapshotReader()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (processSnapshot == null) {
            ActionLog.warn("云控执行后无法读取进程快照；未确认 force-stop 是否持续生效")
            notes += "进程状态未知"
        } else {
            val stillRunning = targets.filter(processSnapshot::isRunning)
            if (stillRunning.isNotEmpty()) {
                ActionLog.warn(
                    "遥测 force-stop 命令已返回，但仍检测到进程：" +
                        "${stillRunning.joinToString()}；系统服务可能重新启动；不会循环强杀"
                )
                notes += "${stillRunning.size} 项仍有进程"
            } else {
                ActionLog.info("云控执行后进程核验：未见遥测目标进程")
            }
        }
        return notes
    }
}
