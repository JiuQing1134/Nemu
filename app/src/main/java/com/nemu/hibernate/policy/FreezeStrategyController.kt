package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
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
    val notes: List<String> = emptyList()
) {
    fun summary(): String = buildString {
        append(packageName)
        append(" [")
        append(strategy.name)
        append("] ")
        append(if (ok) "OK" else "FAIL")
        if (notes.isNotEmpty()) {
            append("；")
            append(notes.joinToString("；"))
        }
    }
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
    suspend fun apply(
        target: Target,
        strategy: FreezeStrategy,
        originalEnabled: Boolean? = null
    ): StrategyResult {
        val invalid = FreezeStrategyPolicy.validate(target, strategy)
        if (invalid != null) {
            ActionLog.warn("拒绝冻结策略：$invalid")
            return StrategyResult(false, target.packageName, strategy, emptyList(), listOf(invalid))
        }
        if (strategy == FreezeStrategy.OBSERVE) {
            return StrategyResult(true, target.packageName, strategy, emptyList())
        }
        val notes = mutableListOf<String>()
        val plan = if (strategy == FreezeStrategy.COMPONENT) {
            val owned = mutableListOf<TargetComponent>()
            val originalStates = mutableMapOf<TargetComponent, Int>()
            for (component in target.components) {
                when (val state = componentStateReader(component)) {
                    null -> notes += "无法读取组件状态，已跳过：${component.label}"
                    0, 1 -> {
                        owned += component
                        originalStates[component] = state
                    }
                    else -> notes += "组件原本已停用，保持原状态：${component.label}"
                }
            }
            if (owned.isEmpty()) {
                // Re-applying an already-frozen COMPONENT target is an
                // idempotent no-op when every disabled component still has a
                // stored baseline (i.e. Nemu owns it).
                val idempotent = target.components.isNotEmpty() &&
                    target.components.all { component ->
                        componentStateReader(component) in 2..4 &&
                            componentOriginalState(component) != null
                    }
                if (idempotent) {
                    ActionLog.info(
                        "${target.packageName} 组件已全部停用（幂等）"
                    )
                    return StrategyResult(
                        true,
                        target.packageName,
                        strategy,
                        emptyList(),
                        listOf("组件已全部停用（幂等）")
                    )
                }
                notes += "没有可停用的组件"
                ActionLog.warn("组件冻结跳过 ${target.packageName}：${notes.joinToString()}")
                return StrategyResult(false, target.packageName, strategy, emptyList(), notes)
            }
            if (!dryRun) {
                // Durability: persist ownership before executing the command.
                originalStates.forEach { (component, state) ->
                    onComponentOriginalState(component, state)
                }
            }
            FreezeStrategyPlanner.apply(target, strategy, owned)
        } else {
            FreezeStrategyPlanner.apply(target, strategy)
        }
        var disableOriginal: Boolean? = null
        if (strategy == FreezeStrategy.DISABLE_USER) {
            disableOriginal = originalEnabled ?: enabledStateReader(target.packageName)
            if (disableOriginal == null) {
                val note = "无法读取操作前 enabled 状态；未执行停用"
                ActionLog.warn(note)
                return StrategyResult(false, target.packageName, strategy, emptyList(), listOf(note))
            }
            if (disableOriginal == false) {
                return StrategyResult(
                    true,
                    target.packageName,
                    strategy,
                    emptyList(),
                    listOf("原本已停用；保留原状态")
                )
            }
            if (!dryRun && !beforeDisableByNemu(target.packageName)) {
                val note = "无法写入救援恢复标记；未执行停用"
                ActionLog.warn(note)
                return StrategyResult(false, target.packageName, strategy, emptyList(), listOf(note))
            }
        }
        if (dryRun) {
            plan.commands.forEach { ActionLog.info("DRY-RUN（策略）: $it") }
            return StrategyResult(true, target.packageName, strategy, plan.commands, notes)
        }
        if (!executeAll(plan.commands)) {
            if (strategy == FreezeStrategy.COMPONENT) {
                // A partial component failure must not lose ownership of the
                // components that did get disabled. Keep their baseline and
                // guard marker; only drop components that are still enabled.
                plan.components.forEach { component ->
                    val state = componentStateReader(component)
                    if (state == 2 || state == 3 || state == 4) {
                        markComponentByNemu(component, true)
                    } else {
                        onComponentOriginalState(component, null)
                    }
                }
            }
            return StrategyResult(false, target.packageName, strategy, plan.commands, notes)
        }

        when (strategy) {
            FreezeStrategy.DISABLE_USER -> {
                if (disableOriginal == true && enabledStateReader(target.packageName) == true) {
                    notes += "PackageManager 未确认停用"
                }
            }
            FreezeStrategy.COMPONENT -> {
                for (component in plan.components) {
                    // pm can exit 0 without changing the component state.
                    // Verify before claiming ownership/applied.
                    if (componentStateReader(component) !in 2..4) {
                        notes += "组件未停用：${component.label}"
                        onComponentOriginalState(component, null)
                        continue
                    }
                    if (!markComponentByNemu(component, true)) {
                        notes += "组件所有权标记失败：${component.label}"
                    }
                }
            }
            FreezeStrategy.SUSPEND -> {
                val suspended = suspendedStateReader?.invoke(target.packageName)
                if (suspended == false) {
                    notes += "PackageManager 未确认暂停"
                } else if (suspendedStateReader != null && suspended == null) {
                    notes += "无法确认暂停状态"
                }
                val r = processSnapshotReader()
                if (r == null) {
                    notes += "无法读取进程快照"
                } else if (r.isRunning(target.packageName)) {
                    notes += "进程仍存活：${target.packageName}"
                }
            }
            FreezeStrategy.HIDE -> {
                // A hidden package is invisible to PackageManager lookups, so
                // read dumpsys through the backend instead of trusting the
                // command output (which is not authoritative on this ROM).
                val reader = hiddenStateReader
                if (reader == null) {
                    notes += "无法确认隐藏状态"
                } else {
                    when (val hidden = reader(target.packageName)) {
                        false -> notes += "PackageManager 未确认隐藏"
                        null -> notes += "无法确认隐藏状态"
                        true -> Unit
                    }
                }
            }
            FreezeStrategy.OBSERVE -> Unit
        }
        val ok = notes.none {
            it.startsWith("PackageManager") ||
                it.startsWith("无法写入") ||
                it.startsWith("组件所有权标记失败") ||
                it.startsWith("组件未停用") ||
                it.startsWith("无法确认暂停") ||
                it.startsWith("无法确认隐藏") ||
                it.startsWith("未确认解除隐藏")
        }
        if (ok) ActionLog.info("冻结策略完成：${target.packageName} / ${strategy.name}")
        else ActionLog.warn("冻结策略有警告：${target.packageName} / ${notes.joinToString()}")
        return StrategyResult(ok, target.packageName, strategy, plan.commands, notes)
    }

    suspend fun restore(
        target: Target,
        strategy: FreezeStrategy,
        originalEnabled: Boolean? = null
    ): StrategyResult {
        if (strategy == FreezeStrategy.OBSERVE) {
            return StrategyResult(true, target.packageName, strategy, emptyList())
        }
        if (strategy == FreezeStrategy.COMPONENT && target.components.isEmpty()) {
            return StrategyResult(
                true,
                target.packageName,
                strategy,
                emptyList(),
                listOf("没有登记组件；无需恢复")
            )
        }
        val notes = mutableListOf<String>()
        val plan = FreezeStrategyPlanner.apply(target, strategy)
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
                    notes += "没有组件原始状态记录；未执行组件恢复"
                    return StrategyResult(
                        true,
                        target.packageName,
                        strategy,
                        emptyList(),
                        notes
                    )
                }
                perComponent
            }
            FreezeStrategy.DISABLE_USER -> {
                when (originalEnabled) {
                    true -> plan.restoreCommands
                    false -> emptyList()
                    null -> {
                        // Unknown baseline: enabling could overwrite a state
                        // the user set before Nemu. Fail closed instead.
                        notes += "原始 enabled 状态未知；未执行 pm enable"
                        emptyList()
                    }
                }
            }
            FreezeStrategy.HIDE -> plan.restoreCommands
            else -> plan.restoreCommands
        }
        if (dryRun) {
            commands.forEach { ActionLog.info("DRY-RUN（恢复）: $it") }
            return StrategyResult(true, target.packageName, strategy, commands, notes)
        }
        val ok = executeAll(commands)
        if (ok && strategy == FreezeStrategy.HIDE) {
            val reader = hiddenStateReader
            if (reader == null) {
                notes += "无法确认隐藏状态"
            } else {
                when (reader(target.packageName)) {
                    true -> notes += "未确认解除隐藏"
                    null -> notes += "无法确认隐藏状态"
                    false -> Unit
                }
            }
        }
        if (ok && strategy == FreezeStrategy.DISABLE_USER && originalEnabled == true) {
            if (enabledStateReader(target.packageName) != true ||
                !afterRestoreByNemu(target.packageName)
            ) {
                notes += "未确认恢复 enable 或所有权标记清除"
            }
        }
        if (ok && strategy == FreezeStrategy.COMPONENT) {
            target.components.forEach { component ->
                if (!markComponentByNemu(component, false)) {
                    notes += "组件所有权标记清除失败：${component.label}"
                }
                onComponentOriginalState(component, null)
            }
        }
        return StrategyResult(
            ok && notes.isEmpty(),
            target.packageName,
            strategy,
            commands,
            notes
        )
    }

    private suspend fun executeAll(commands: List<String>): Boolean {
        var ok = true
        for (command in commands) {
            val result = backend.exec(command)
            if (result.ok) ActionLog.info("OK(${backend.name}，策略): $command")
            else {
                ActionLog.error("FAIL(${backend.name}，策略): $command\n${result.output.take(300)}")
                ok = false
            }
        }
        return ok
    }
}
