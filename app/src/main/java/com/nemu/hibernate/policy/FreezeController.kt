package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.DenyList
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.PrivilegeBackend

class FreezeController(
    private val backend: PrivilegeBackend,
    private val settings: Settings
) {
    suspend fun apply(targets: List<Target>): Boolean {
        var allOk = true
        for (target in targets) {
            if (!settings.isEnabled(target.packageName)) continue
            if (DenyList.isProtected(target.packageName)) {
                ActionLog.warn("拒绝冻结受保护包：${target.packageName}")
                allOk = false
                continue
            }
            val commands = buildList {
                if (target.suspend) add(CommandBuilder.suspend(target.packageName))
                if (target.forceStop) add(CommandBuilder.forceStop(target.packageName))
                if (target.clearCache) add(CommandBuilder.clearCache(target.packageName))
            }
            if (!executeAll(commands)) allOk = false
        }
        return allOk
    }

    suspend fun restore(targets: List<Target>): Boolean {
        var allOk = true
        for (target in targets) {
            if (!executeAll(listOf(CommandBuilder.unsuspend(target.packageName)))) {
                allOk = false
            }
        }
        return allOk
    }

    private suspend fun executeAll(commands: List<String>): Boolean {
        var allOk = true
        for (command in commands) {
            if (settings.dryRun) {
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
}
