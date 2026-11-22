package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AntiFraudCommand
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudResult
import com.nemu.hibernate.core.AntiFraudAction
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.NumberIdentityMode
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.PrivilegeBackend

class AntiFraudController(
    private val backend: PrivilegeBackend,
    private val dryRun: Boolean,
    private val previousConfig: AntiFraudConfig = AntiFraudConfig(),
    private val numberIdentityWasEnabled: Boolean? = null,
    private val numberIdentityCurrentlyEnabled: Boolean? = null,
    private val beforeDisableByNemu: suspend () -> Boolean = { true },
    private val afterRestoreByNemu: suspend () -> Boolean = { true },
    private val enabledStateReader: (String) -> Boolean? = { null }
) {
    suspend fun apply(config: AntiFraudConfig): List<AntiFraudResult> {
        if (!dryRun &&
            config.numberIdentityMode == NumberIdentityMode.DISABLE_USER &&
            numberIdentityWasEnabled == null
        ) {
            val result = AntiFraudResult(
                packageName = AntiFraudTargets.NUMBER_IDENTITY,
                action = AntiFraudAction.DISABLE_USER,
                shell = "",
                ok = false,
                dryRun = dryRun,
                output = "无法读取操作前启用状态；为避免覆盖原配置，未执行停用"
            )
            ActionLog.warn("反诈停用已拒绝：号码识别原始启用状态未知")
            return listOf(result)
        }
        if (!dryRun &&
            config.numberIdentityMode == NumberIdentityMode.DISABLE_USER &&
            numberIdentityWasEnabled == true && !beforeDisableByNemu()
        ) {
            return listOf(
                AntiFraudResult(
                    packageName = AntiFraudTargets.NUMBER_IDENTITY,
                    action = AntiFraudAction.DISABLE_USER,
                    shell = "",
                    ok = false,
                    dryRun = false,
                    output = "无法记录救援恢复标记；为保证可恢复性，未执行停用"
                )
            )
        }
        val results = execute(
            AntiFraudPolicy.planApply(config, previousConfig)
        )
        ActionLog.info("反诈设置：${AntiFraudPolicy.summarize(results)}")
        return results
    }

    suspend fun restoreAll(): List<AntiFraudResult> {
        val baseline = numberIdentityWasEnabled
            ?: if (numberIdentityCurrentlyEnabled == true) false else null
        val commands = AntiFraudPolicy.planRestore(baseline)
        val unknownBaseline = baseline == null
        val results = execute(commands).toMutableList()
        if (numberIdentityWasEnabled == true &&
            results.filter { it.packageName == AntiFraudTargets.NUMBER_IDENTITY }
                .all { it.ok } && !dryRun &&
            (enabledStateReader(AntiFraudTargets.NUMBER_IDENTITY) != true ||
                !afterRestoreByNemu())
        ) {
            results.add(
                AntiFraudResult(
                    packageName = AntiFraudTargets.NUMBER_IDENTITY,
                    action = AntiFraudAction.ENABLE,
                    shell = "",
                    ok = false,
                    dryRun = false,
                    output = "号码识别未确认启用或救援恢复标记未能清除"
                )
            )
        }
        if (unknownBaseline) {
            results.add(
                AntiFraudResult(
                    packageName = AntiFraudTargets.NUMBER_IDENTITY,
                    action = AntiFraudAction.ENABLE,
                    shell = "",
                    ok = false,
                    dryRun = dryRun,
                    output = "原始启用状态未知；仅解除冻结，未执行 pm enable"
                )
            )
        }
        ActionLog.info("恢复反诈设置：${AntiFraudPolicy.summarize(results)}")
        return results
    }

    internal suspend fun execute(
        commands: List<AntiFraudCommand>
    ): List<AntiFraudResult> {
        val results = ArrayList<AntiFraudResult>(commands.size)
        for (command in commands) {
            if (!AntiFraudPolicy.isAllowed(command.packageName, command.action)) {
                ActionLog.error(
                    "拒绝执行未授权反诈动作：${command.packageName} / ${command.action}"
                )
                results += AntiFraudResult(
                    packageName = command.packageName,
                    action = command.action,
                    shell = command.shell,
                    ok = false,
                    dryRun = false,
                    output = "动作未授权"
                )
                continue
            }
            if (dryRun) {
                ActionLog.info("DRY-RUN（反诈）: ${command.shell}")
                results += AntiFraudResult(
                    packageName = command.packageName,
                    action = command.action,
                    shell = command.shell,
                    ok = true,
                    dryRun = true,
                    output = ""
                )
                continue
            }
            val result = backend.exec(command.shell)
            results += AntiFraudResult(
                packageName = command.packageName,
                action = command.action,
                shell = command.shell,
                ok = result.ok,
                dryRun = false,
                output = result.output.take(500)
            )
            if (result.ok) {
                ActionLog.info("OK(${backend.name}，反诈): ${command.shell}")
            } else {
                ActionLog.error(
                    "FAIL(${backend.name}，反诈，${result.exitCode}): " +
                        "${command.shell}\n${result.output.take(500)}"
                )
            }
        }
        return results
    }
}
