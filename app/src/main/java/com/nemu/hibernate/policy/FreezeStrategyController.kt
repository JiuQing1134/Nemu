package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.ResultCopy
import com.nemu.hibernate.core.ResultNote
import com.nemu.hibernate.core.ResultReason
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetComponent
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.detection.ProcessListSnapshot
import com.nemu.hibernate.privilege.PrivilegeBackend

data class StrategyResult(
    val ok: Boolean,
    val packageName: String,
    val strategy: FreezeStrategy,
    val commands: List<String>,
    val reasons: List<ResultNote> = emptyList(),
    val label: String = packageName,
    /** True when a command timed out and the device state is unknown. */
    val indeterminate: Boolean = false
) {
    /** Chinese UI lines; raw commands/outputs stay in ActionLog. */
    val notes: List<String> get() = reasons.map { it.line() }

    fun summary(): String = ResultCopy.resultLine(ok, label, reasons)
}

/**
 * Executes [FreezeStrategyPlan]s with verification and ownership callbacks.
 * Callers own persistence (strategy/original enabled/component markers); this
 * class remains pure Android-shell + verification so it can be tested with a
 * fake backend.
 *
 * Component ownership is captured before the first `pm disable`: components
 * that were already disabled are never claimed, and the exact original state
 * (default/enabled/...) is persisted so [restore] can reproduce it.
 */
class FreezeStrategyController(
    private val backend: PrivilegeBackend,
    private val dryRun: Boolean,
    private val enabledStateReader: (String) -> Boolean? = { null },
    private val processSnapshotReader: suspend () -> ProcessListSnapshot? = { null },
    private val suspendedStateReader: ((String) -> Boolean?)? = null,
    private val beforeDisableByNemu: suspend (String) -> Boolean = { true },
    private val afterRestoreByNemu: suspend (String) -> Boolean = { true },
    private val markComponentByNemu:
        suspend (TargetComponent, Boolean) -> Boolean = { _, _ -> true },
    private val componentStateReader: (TargetComponent) -> Int? = { null },
    private val componentOriginalState: (TargetComponent) -> Int? = { null },
    private val onComponentOriginalState: (TargetComponent, Int?) -> Unit = { _, _ -> },
    /**
     * Authoritative hidden-state read (dumpsys). stdout is not proof: on this
     * ROM `pm hide` prints "hidden state: true" even for a nonexistent package.
     */
    private val hiddenStateReader: (suspend (String) -> Boolean?)? = null
) {
    private fun result(
        ok: Boolean,
        target: Target,
        strategy: FreezeStrategy,
        commands: List<String> = emptyList(),
        reasons: List<ResultNote> = emptyList(),
        indeterminate: Boolean = false
    ) = StrategyResult(
        ok = ok,
        packageName = target.packageName,
        strategy = strategy,
        commands = commands,
        reasons = reasons,
        label = target.label,
        indeterminate = indeterminate
    )

    suspend fun apply(
        target: Target,
        strategy: FreezeStrategy,
        originalEnabled: Boolean? = null,
        options: StrategyOptions = StrategyOptions(),
        componentsOverride: List<TargetComponent>? = null
    ): StrategyResult {
        val invalid = FreezeStrategyPolicy.validate(target, strategy)
        if (invalid != null) {
            ActionLog.warn("拒绝冻结策略：$invalid")
            return result(
                false,
                target,
                strategy,
                reasons = listOf(
                    ResultReason.PRECHECK_FAILED.at(target.label, invalid)
                )
            )
        }
        if (strategy == FreezeStrategy.OBSERVE) {
            return result(true, target, strategy)
        }
        val reasons = mutableListOf<ResultNote>()
        val plan = if (strategy == FreezeStrategy.COMPONENT) {
            val candidates = componentsOverride ?: target.components
            val owned = mutableListOf<TargetComponent>()
            val originalStates = mutableMapOf<TargetComponent, Int>()
            for (component in candidates) {
                when (val state = componentStateReader(component)) {
                    null -> reasons += ResultReason.COMPONENT_STATE_UNKNOWN.at(
                        component.label
                    )
                    0, 1 -> {
                        owned += component
                        originalStates[component] = state
                    }
                    else -> reasons += ResultReason.ALREADY_DISABLED.at(
                        component.label
                    )
                }
            }
            if (owned.isEmpty()) {
                // Re-applying an already-frozen COMPONENT target is an
                // idempotent no-op when every disabled component still has a
                // stored baseline (i.e. Nemu owns it).
                val idempotent = candidates.isNotEmpty() &&
                    candidates.all { component ->
                        componentStateReader(component) in 2..4 &&
                            componentOriginalState(component) != null
                    }
                if (idempotent) {
                    ActionLog.info(
                        "${target.packageName} 组件已全部停用（幂等）"
                    )
                    return result(
                        true,
                        target,
                        strategy,
                        reasons = listOf(
                            ResultReason.COMPONENTS_ALL_DISABLED.at(target.label)
                        )
                    )
                }
                reasons += ResultReason.NO_COMPONENTS.at(target.label)
                ActionLog.warn(
                    "组件冻结跳过 ${target.packageName}：" +
                        reasons.joinToString { it.line() }
                )
                return result(false, target, strategy, reasons = reasons)
            }
            if (!dryRun) {
                // Durability: persist ownership before executing the command.
                originalStates.forEach { (component, state) ->
                    onComponentOriginalState(component, state)
                }
            }
            FreezeStrategyPlanner.apply(
                target,
                strategy,
                owned,
                options = options
            )
        } else {
            FreezeStrategyPlanner.apply(target, strategy, options = options)
        }
        var disableOriginal: Boolean? = null
        if (strategy == FreezeStrategy.DISABLE_USER) {
            disableOriginal = originalEnabled ?: enabledStateReader(target.packageName)
            if (disableOriginal == null) {
                val note = ResultReason.BASELINE_READ_FAILED.at(target.label)
                ActionLog.warn(note.line())
                return result(false, target, strategy, reasons = listOf(note))
            }
            if (disableOriginal == false) {
                return result(
                    true,
                    target,
                    strategy,
                    reasons = listOf(
                        ResultReason.ALREADY_DISABLED.at(target.label)
                    )
                )
            }
            if (!dryRun && !beforeDisableByNemu(target.packageName)) {
                val note = ResultReason.MARKER_WRITE_FAILED.at(target.label)
                ActionLog.warn(note.line())
                return result(false, target, strategy, reasons = listOf(note))
            }
        }
        if (dryRun) {
            plan.commands.forEach { ActionLog.info("DRY-RUN（策略）: $it") }
            return result(true, target, strategy, plan.commands, reasons)
        }
        val exec = executeAll(plan.commands)
        if (!exec.ok) {
            if (strategy == FreezeStrategy.COMPONENT && !exec.indeterminate) {
                // A partial component failure must not lose ownership of the
                // components that did get disabled. Keep their baseline and
                // guard marker; only drop components that are still enabled.
                plan.components.forEach { component ->
                    val state = componentStateReader(component)
                    when {
                        // Unknown readback: keep the exact-state baseline so a
                        // later restore can still recover the component.
                        state == null -> Unit
                        ComponentRestoreState.isRestored(state) ->
                            onComponentOriginalState(component, null)
                        else -> markComponentByNemu(component, true)
                    }
                }
            }
            reasons += if (exec.indeterminate) {
                ResultReason.COMMAND_TIMEOUT.at(target.label)
            } else {
                ResultReason.COMMAND_FAILED.at(target.label)
            }
            return result(
                false,
                target,
                strategy,
                plan.commands,
                reasons,
                indeterminate = exec.indeterminate
            )
        }

        when (strategy) {
            FreezeStrategy.DISABLE_USER -> {
                if (disableOriginal == true &&
                    enabledStateReader(target.packageName) != false
                ) {
                    // false is the only proof that the package was disabled.
                    // true and null both mean the change is unconfirmed.
                    reasons += ResultReason.PACKAGE_NOT_CONFIRMED.at(target.label)
                }
            }
            FreezeStrategy.COMPONENT -> {
                for (component in plan.components) {
                    // pm can exit 0 without changing the component state.
                    // Verify before claiming ownership/applied.
                    val state = componentStateReader(component)
                    when {
                        state == null -> reasons +=
                            ResultReason.COMPONENT_STATE_UNKNOWN.at(
                                component.label
                            )
                        ComponentRestoreState.isRestored(state) -> {
                            reasons += ResultReason.COMPONENT_NOT_DISABLED.at(
                                component.label
                            )
                            onComponentOriginalState(component, null)
                        }
                        else -> if (!markComponentByNemu(component, true)) {
                            reasons += ResultReason.COMPONENT_OWNERSHIP_FAILED.at(
                                component.label
                            )
                        }
                    }
                }
            }
            FreezeStrategy.SUSPEND -> {
                val suspended = suspendedStateReader?.invoke(target.packageName)
                if (suspended == false) {
                    reasons += ResultReason.PACKAGE_NOT_CONFIRMED.at(target.label)
                } else if (suspendedStateReader != null && suspended == null) {
                    reasons += ResultReason.PACKAGE_STATE_UNKNOWN.at(target.label)
                }
                val r = processSnapshotReader()
                if (r == null) {
                    reasons += ResultReason.PROCESS_STATE_UNKNOWN.at(target.label)
                } else if (r.isRunning(target.packageName)) {
                    reasons += ResultReason.PROCESS_STILL_RUNNING.at(target.label)
                }
            }
            FreezeStrategy.HIDE -> {
                // A hidden package is invisible to PackageManager lookups, so
                // read dumpsys through the backend instead of trusting the
                // command output (which is not authoritative on this ROM).
                val reader = hiddenStateReader
                if (reader == null) {
                    reasons += ResultReason.HIDE_STATE_UNKNOWN.at(target.label)
                } else {
                    when (reader(target.packageName)) {
                        false -> reasons += ResultReason.HIDE_NOT_CONFIRMED.at(
                            target.label
                        )
                        null -> reasons += ResultReason.HIDE_STATE_UNKNOWN.at(
                            target.label
                        )
                        true -> Unit
                    }
                }
            }
            FreezeStrategy.OBSERVE -> Unit
        }
        val ok = reasons.none { it.reason.blocking }
        if (ok) ActionLog.info("冻结策略完成：${target.packageName} / ${strategy.name}")
        else {
            ActionLog.warn(
                "冻结策略有警告：${target.packageName} / " +
                    reasons.joinToString { it.line() }
            )
        }
        return result(ok, target, strategy, plan.commands, reasons)
    }

    suspend fun restore(
        target: Target,
        strategy: FreezeStrategy,
        originalEnabled: Boolean? = null
    ): StrategyResult {
        if (strategy == FreezeStrategy.OBSERVE) {
            return result(true, target, strategy)
        }
        if (strategy == FreezeStrategy.COMPONENT && target.components.isEmpty()) {
            return result(
                true,
                target,
                strategy,
                reasons = listOf(
                    ResultReason.NO_REGISTERED_COMPONENTS.at(target.label)
                )
            )
        }
        val reasons = mutableListOf<ResultNote>()
        val restoreCommands = FreezeStrategyPlanner.restoreOnly(target, strategy)
        val commands = when (strategy) {
            FreezeStrategy.COMPONENT -> {
                val perComponent = target.components.mapNotNull { component ->
                    componentOriginalState(component)
                        ?.takeIf { it == 0 || it == 1 }
                        ?.let { original ->
                            CommandBuilder.restoreComponent(component.className, original)
                        }
                }
                // No stored baseline means Nemu cannot prove it owned the
                // component. Do not default-state blindly: that could enable a
                // component the user had disabled before Nemu.
                if (perComponent.isEmpty()) {
                    reasons += ResultReason.COMPONENT_BASELINE_MISSING.at(
                        target.label
                    )
                    return result(true, target, strategy, reasons = reasons)
                }
                perComponent
            }
            FreezeStrategy.DISABLE_USER -> {
                when (originalEnabled) {
                    true -> restoreCommands
                    false -> emptyList()
                    null -> {
                        // Unknown baseline: enabling could overwrite a state
                        // the user set before Nemu. Fail closed instead.
                        reasons += ResultReason.BASELINE_READ_FAILED.at(
                            target.label
                        )
                        emptyList()
                    }
                }
            }
            FreezeStrategy.HIDE -> restoreCommands
            else -> restoreCommands
        }
        if (dryRun) {
            commands.forEach { ActionLog.info("DRY-RUN（恢复）: $it") }
            return result(true, target, strategy, commands, reasons)
        }
        val exec = executeAll(commands)
        val ok = exec.ok
        if (!ok) {
            reasons += if (exec.indeterminate) {
                ResultReason.COMMAND_TIMEOUT.at(target.label)
            } else {
                ResultReason.COMMAND_FAILED.at(target.label)
            }
        }
        if (ok && strategy == FreezeStrategy.HIDE) {
            val reader = hiddenStateReader
            if (reader == null) {
                reasons += ResultReason.HIDE_STATE_UNKNOWN.at(target.label)
            } else {
                when (reader(target.packageName)) {
                    true -> reasons += ResultReason.UNHIDE_NOT_CONFIRMED.at(
                        target.label
                    )
                    null -> reasons += ResultReason.HIDE_STATE_UNKNOWN.at(
                        target.label
                    )
                    false -> Unit
                }
            }
        }
        if (ok && strategy == FreezeStrategy.DISABLE_USER && originalEnabled == true) {
            if (enabledStateReader(target.packageName) != true) {
                reasons += ResultReason.PACKAGE_NOT_CONFIRMED.at(target.label)
            } else if (!afterRestoreByNemu(target.packageName)) {
                reasons += ResultReason.MARKER_CLEAR_FAILED.at(target.label)
            }
        }
        // Component baselines and guard markers are cleared by
        // TargetFreezeService only after a read-back confirms the component is
        // no longer disabled. Clearing them here would lose the only recovery
        // record if the process died before verification.
        return result(
            ok && reasons.isEmpty(),
            target,
            strategy,
            commands,
            reasons
        )
    }

    private data class ExecOutcome(
        val ok: Boolean,
        val indeterminate: Boolean
    )

    private suspend fun executeAll(commands: List<String>): ExecOutcome {
        var ok = true
        var indeterminate = false
        for (command in commands) {
            val result = backend.exec(command)
            if (result.ok) ActionLog.info("OK(${backend.name}，策略): $command")
            else {
                ActionLog.error(
                    "FAIL(${backend.name}，策略): $command\n" +
                        result.output.take(300)
                )
                ok = false
                if (result.indeterminate) indeterminate = true
            }
        }
        return ExecOutcome(ok, indeterminate)
    }
}
