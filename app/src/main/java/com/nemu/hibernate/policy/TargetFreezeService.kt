package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.detection.ProcessListSnapshot
import com.nemu.hibernate.privilege.AndroidComponentStateReader
import com.nemu.hibernate.privilege.AndroidPackageEnabledStateReader
import com.nemu.hibernate.privilege.AndroidPackageStatusReader
import com.nemu.hibernate.privilege.ComponentStateReader
import com.nemu.hibernate.privilege.PackageEnabledStateReader
import com.nemu.hibernate.privilege.PackageSnapshot
import com.nemu.hibernate.privilege.BackendPackageSnapshotReader
import com.nemu.hibernate.privilege.PackageStatusReader
import com.nemu.hibernate.privilege.PrivilegeBackend
import com.nemu.hibernate.privilege.RootPrivilegeBackend

data class TargetRunResult(
    val ok: Boolean,
    val attempted: Int,
    val applied: Int,
    val failed: Int,
    val notes: List<String> = emptyList()
) {
    fun summary(): String = buildString {
        append(if (ok) "成功" else "有失败")
        append("：尝试 $attempted，成功 $applied，失败 $failed")
        if (notes.isNotEmpty()) {
            append("；")
            append(notes.take(6).joinToString("；"))
            if (notes.size > 6) append("；…")
        }
    }
}

/**
 * Unified execution path for all catalog strategies. The UI (Freeze/Unfreeze
 * pages), the boot worker and the shutdown path all call this class so a
 * button, an automatic freeze and a rescue cannot disagree about what was
 * applied.
 *
 * Durability contract:
 * - the applied marker is written before any freeze command;
 * - disable-user persists the original enabled state and guard marker first;
 * - component ownership captures the exact original component state first;
 * - a restore only enables a package Nemu owned, never a pre-disabled one.
 */
class TargetFreezeService(
    context: Context,
    private val backend: PrivilegeBackend,
    private val dryRun: Boolean,
    private val markerBackend: RootPrivilegeBackend?,
    private val processSnapshotReader: suspend () -> ProcessListSnapshot? = { null },
    private val enabledStateReader: PackageEnabledStateReader =
        AndroidPackageEnabledStateReader(context),
    private val componentStateReader: ComponentStateReader =
        AndroidComponentStateReader(context),
    private val statusReader: PackageStatusReader =
        AndroidPackageStatusReader(context),
    private val suspendedStateReader: (String) -> Boolean? =
        { statusReader.read(it)?.suspended },
    /** Test seam; production reads `dumpsys package` through the backend. */
    private val snapshotReader: (suspend (PrivilegeBackend, String) -> PackageSnapshot?)? = null
) {
    val policy = TargetPolicy(context)

    private suspend fun snapshot(
        backend: PrivilegeBackend,
        packageName: String
    ): PackageSnapshot? = snapshotReader?.invoke(backend, packageName)
        ?: BackendPackageSnapshotReader(backend).read(packageName)

    suspend fun apply(targets: List<Target>): TargetRunResult {
        val pending = targets.distinctBy { it.packageName }
        var applied = 0
        var failed = 0
        var attempted = 0
        val notes = mutableListOf<String>()
        for (target in pending) {
            val strategy = policy.strategy(target)
            if (strategy == FreezeStrategy.OBSERVE) continue
            val invalid = FreezeStrategyPolicy.validate(target, strategy)
            if (invalid != null) {
                failed++
                notes += "${target.packageName}：$invalid"
                continue
            }
            attempted++
            // `pm hide` needs MANAGE_USERS (uid 0). Prefer the root marker
            // backend when the selected general backend is Shizuku-ADB.
            val commandBackend = if (
                strategy == FreezeStrategy.HIDE &&
                !backend.canSetHidden &&
                markerBackend != null &&
                markerBackend.canSetHidden
            ) {
                markerBackend
            } else {
                backend
            }
            if (strategy == FreezeStrategy.HIDE) {
                if (!commandBackend.canSetHidden) {
                    failed++
                    notes += "${target.packageName}：伪装卸载需要 Root 权限，" +
                        "当前后端（${commandBackend.name}）没有 MANAGE_USERS"
                    continue
                }
                val before = snapshot(commandBackend, target.packageName)
                if (before == null) {
                    failed++
                    notes += "${target.packageName}：无法读取隐藏前状态"
                    continue
                }
                if (!before.exists || before.installed != true) {
                    failed++
                    notes += "${target.packageName}：当前用户未安装或状态未知，无法隐藏"
                    continue
                }
                if (before.hidden == null) {
                    failed++
                    notes += "${target.packageName}：无法确认隐藏前状态，未执行"
                    continue
                }
                if (before.hidden == true) {
                    // Someone else hid it before Nemu. Never claim it:
                    // a restore must not reveal an app the user/OEM hid.
                    failed++
                    notes += "${target.packageName}：操作前已被隐藏，不是本应用设置，不接管"
                    continue
                }
                // Durability: record that the pre-hide state was visible.
                if (!dryRun && !policy.setOriginalHidden(target, false)) {
                    failed++
                    notes += "${target.packageName}：无法写入隐藏基线，未执行"
                    continue
                }
            }
            val original = if (strategy == FreezeStrategy.DISABLE_USER) {
                policy.originalEnabled(target) ?: enabledStateReader.isEnabled(target.packageName)
            } else {
                null
            }
            if (strategy == FreezeStrategy.DISABLE_USER) {
                if (original == null) {
                    failed++
                    notes += "${target.packageName}：无法读取原始 enabled 状态"
                    continue
                }
                if (!original) {
                    // It was already disabled before Nemu. Preserve it
                    // and never claim ownership, original baseline or an
                    // applied badge; no restore is needed for a state Nemu
                    // did not create.
                    applied++
                    notes += "${target.packageName}：原本已停用，保持原状态"
                    continue
                }
                if (!dryRun) policy.setOriginalEnabled(target, true)
            }
            if (!dryRun) policy.setApplied(target, true)

            val controller = FreezeStrategyController(
                backend = commandBackend,
                dryRun = dryRun,
                enabledStateReader = { enabledStateReader.isEnabled(it) },
                processSnapshotReader = processSnapshotReader,
                suspendedStateReader = { suspendedStateReader(it) },
                hiddenStateReader = if (strategy == FreezeStrategy.HIDE) {
                    { packageName ->
                        snapshot(commandBackend, packageName)?.hidden
                    }
                } else {
                    null
                },
                beforeDisableByNemu = { packageName ->
                    BootGuard.markDisabledByNemu(
                        markerBackend,
                        packageName,
                        disabledByNemu = true
                    )
                },
                afterRestoreByNemu = { packageName ->
                    BootGuard.markDisabledByNemu(
                        markerBackend,
                        packageName,
                        disabledByNemu = false
                    )
                },
                markComponentByNemu = { component, disabled ->
                    BootGuard.markComponentDisabled(
                        markerBackend,
                        component,
                        disabled,
                        originalState = if (disabled) {
                            policy.componentOriginalState(target, component.className)
                        } else {
                            null
                        }
                    )
                },
                componentStateReader = { component ->
                    componentStateReader.state(component.className)
                },
                componentOriginalState = { component ->
                    policy.componentOriginalState(target, component.className)
                },
                onComponentOriginalState = { component, state ->
                    policy.setComponentOriginalState(target, component.className, state)
                }
            )
            val result = controller.apply(target, strategy, originalEnabled = original)
            if (result.ok) {
                applied++
                ActionLog.info("应用策略成功：${result.summary()}")
            } else {
                // A failed HIDE must not leave an unverified hidden package.
                // Try to roll back; only keep ownership if the package is
                // still hidden (or its state cannot be read at all).
                var hideRolledBack = false
                if (strategy == FreezeStrategy.HIDE && !dryRun) {
                    when (snapshot(commandBackend, target.packageName)?.hidden) {
                        true -> {
                            commandBackend.exec(
                                CommandBuilder.unhide(target.packageName)
                            )
                            if (snapshot(commandBackend, target.packageName)?.hidden ==
                                false
                            ) {
                                policy.setOriginalHidden(target, null)
                                hideRolledBack = true
                                notes += "${target.packageName}：隐藏未确认，已回滚"
                            }
                        }
                        false -> {
                            policy.setOriginalHidden(target, null)
                            hideRolledBack = true
                        }
                        null -> Unit
                    }
                }
                // Keep ownership only when Nemu persisted a component
                // baseline (or actually disabled a package). Pre-existing
                // disabled states are not ours and must not get an applied
                // badge.
                val keepApplied =
                    (strategy == FreezeStrategy.COMPONENT &&
                        policy.hasComponentOwnership(target)) ||
                    (strategy == FreezeStrategy.DISABLE_USER &&
                        !dryRun &&
                        // Keep ownership unless PackageManager proves the
                        // package is still enabled; null is not proof.
                        enabledStateReader.isEnabled(target.packageName) != true) ||
                    (strategy == FreezeStrategy.HIDE && !dryRun && !hideRolledBack)
                if (!dryRun && !keepApplied) {
                    policy.setApplied(target, false)
                    if (strategy == FreezeStrategy.DISABLE_USER) {
                        policy.setOriginalEnabled(target, null)
                        BootGuard.markDisabledByNemu(
                            markerBackend,
                            target.packageName,
                            disabledByNemu = false
                        )
                    }
                }
                failed++
                notes += result.summary()
            }
        }
        if (!dryRun) {
            ActionLog.info("统一冻结结果：${notes.size} 条备注")
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = applied,
            failed = failed,
            notes = notes
        )
    }

    /**
     * Restores selected targets. [force] is used by the Unfreeze page's
     * explicit "all" action: suspended packages are unsuspended even when the
     * applied marker was lost (e.g. an older build froze them). Disable-user
     * and component restores still require stored ownership.
     */
    suspend fun restore(targets: List<Target>, force: Boolean = false): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val notes = mutableListOf<String>()
        for (target in targets.distinctBy { it.packageName }) {
            val stored = policy.strategy(target)
            val suspendedNow = suspendedStateReader(target.packageName) == true
            val disabledNow = enabledStateReader.isEnabled(target.packageName) == false
            // A recorded hidden ownership always wins: changing the strategy
            // chip after hiding must not strand the package in `pm hide`.
            val strategy = when {
                policy.ownsHidden(target) -> FreezeStrategy.HIDE
                stored != FreezeStrategy.DISABLE_USER &&
                    FreezeStrategy.DISABLE_USER in target.supportedStrategies &&
                    disabledNow -> FreezeStrategy.DISABLE_USER
                else -> stored
            }
            if (!force && !policy.isApplied(target) && !suspendedNow) continue
            attempted++

            // Actual state wins over policy: a target can be suspended under a
            // COMPONENT/DISABLE policy (legacy or manually). Always clear the
            // suspension first; otherwise the row would report success while
            // the package stays suspended.
            var suspendOk = true
            if (suspendedNow) {
                if (dryRun) {
                    ActionLog.info(
                        "DRY-RUN（安全解冻）: " +
                            CommandBuilder.unsuspend(target.packageName)
                    )
                } else {
                    val u = SafetyUnfreezer.runUnsuspendPackages(
                        backend,
                        listOf(target.packageName)
                    )
                    suspendOk = u.ok &&
                        suspendedStateReader(target.packageName) == false
                    if (!suspendOk) {
                        notes += "${target.packageName}：仍处于 suspend"
                    }
                }
            }

            if (strategy == FreezeStrategy.OBSERVE) {
                if (suspendOk) restored++ else failed++
                continue
            }

            val commandBackend = if (
                strategy == FreezeStrategy.HIDE &&
                !backend.canSetHidden &&
                markerBackend != null &&
                markerBackend.canSetHidden
            ) {
                markerBackend
            } else {
                backend
            }
            if (strategy == FreezeStrategy.HIDE) {
                if (!commandBackend.canSetHidden) {
                    failed++
                    notes += "${target.packageName}：解除隐藏需要 Root 权限"
                    continue
                }
                if (!policy.ownsHidden(target)) {
                    val visible = snapshot(commandBackend, target.packageName)
                        ?.hidden == false
                    if (visible) {
                        if (!dryRun) {
                            policy.setApplied(target, false)
                            policy.setOriginalHidden(target, null)
                        }
                        restored++
                        notes += "${target.packageName}：已可见，清理旧标记"
                    } else {
                        notes += "${target.packageName}：没有本应用的隐藏所有权；" +
                            "未执行 pm unhide"
                        failed++
                    }
                    continue
                }
            }

            val ownedOriginalStates = if (strategy == FreezeStrategy.COMPONENT) {
                target.components.mapNotNull { component ->
                    policy.componentOriginalState(target, component.className)
                        ?.let { component to it }
                }.toMap()
            } else {
                emptyMap()
            }
            val ownedComponents = ownedOriginalStates.keys.toList()
            if (strategy == FreezeStrategy.COMPONENT && ownedComponents.isEmpty()) {
                val anyDisabled = target.components.any {
                    componentStateReader.state(it.className) in 2..4
                }
                if (anyDisabled) {
                    notes += "${target.packageName}：组件已停用但缺少原始状态记录"
                    failed++
                } else {
                    if (!dryRun) policy.setApplied(target, false)
                    restored++
                    ActionLog.info(
                        if (dryRun) "DRY-RUN（组件计划）：${target.packageName}（无组件停用）"
                        else "解冻成功：${target.packageName}（无组件停用）"
                    )
                }
                continue
            }

            val original = if (strategy == FreezeStrategy.DISABLE_USER) {
                policy.originalEnabled(target)
            } else {
                null
            }
            val controller = FreezeStrategyController(
                backend = commandBackend,
                dryRun = dryRun,
                enabledStateReader = { enabledStateReader.isEnabled(it) },
                hiddenStateReader = if (strategy == FreezeStrategy.HIDE) {
                    { packageName ->
                        snapshot(commandBackend, packageName)?.hidden
                    }
                } else {
                    null
                },
                afterRestoreByNemu = { packageName ->
                    BootGuard.markDisabledByNemu(
                        markerBackend,
                        packageName,
                        disabledByNemu = false
                    )
                },
                markComponentByNemu = { component, disabled ->
                    BootGuard.markComponentDisabled(markerBackend, component, disabled)
                },
                componentOriginalState = { component ->
                    policy.componentOriginalState(target, component.className)
                },
                onComponentOriginalState = { component, state ->
                    policy.setComponentOriginalState(target, component.className, state)
                }
            )
            val result = controller.restore(
                target,
                strategy,
                originalEnabled = original
            )
            val verified = when (strategy) {
                FreezeStrategy.SUSPEND ->
                    dryRun || !suspendedNow ||
                        suspendedStateReader(target.packageName) == false
                FreezeStrategy.COMPONENT -> dryRun || ownedComponents.none {
                    componentStateReader.state(it.className) in 2..4
                }
                else -> true
            }
            if (result.ok && suspendOk && verified) {
                if (!dryRun) {
                    policy.setApplied(target, false)
                    if (strategy == FreezeStrategy.DISABLE_USER) {
                        policy.setOriginalEnabled(target, null)
                    }
                    if (strategy == FreezeStrategy.HIDE) {
                        policy.setOriginalHidden(target, null)
                    }
                }
                restored++
                ActionLog.info(
                    if (dryRun) "DRY-RUN（解冻计划）：${result.summary()}"
                    else "解冻成功：${result.summary()}"
                )
            } else {
                // The controller may have cleared ownership before this final
                // verification. Re-persist baselines for components that are
                // still disabled so a retry can restore them.
                if (!verified && strategy == FreezeStrategy.COMPONENT) {
                    ownedOriginalStates.forEach { (component, originalState) ->
                        if (componentStateReader.state(component.className) in 2..4) {
                            policy.setComponentOriginalState(
                                target,
                                component.className,
                                originalState
                            )
                            BootGuard.markComponentDisabled(
                                markerBackend,
                                component,
                                disabled = true,
                                originalState = originalState
                            )
                        }
                    }
                }
                failed++
                notes += result.summary()
                if (!suspendOk) notes += "${target.packageName}：suspend 未清除"
                if (!verified) notes += "${target.packageName}：恢复后实际状态未确认"
            }
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = restored,
            failed = failed,
            notes = notes
        )
    }

    fun appliedTargets(): List<Target> =
        TargetCatalog.all.filter { policy.isApplied(it) }

    /**
     * Clears applied markers for SUSPEND targets after a verified safety
     * unsuspend. A re-application sets them again; leaving them would make the
     * Freeze page claim an applied state that no longer exists.
     */
    fun clearSuspendAppliedMarkers() {
        TargetCatalog.all
            .filter { policy.strategy(it) == FreezeStrategy.SUSPEND }
            .filter { !policy.ownsHidden(it) }
            .filter { policy.isApplied(it) }
            .forEach { policy.setApplied(it, false) }
    }

    /**
     * Shutdown restores suspends and components but intentionally leaves
     * disable-user packages alone. Clear applied markers only for targets that
     * are now enabled and no longer own components.
     */
    fun clearRestoredAppliedMarkers() {
        TargetCatalog.all
            .filter { policy.isApplied(it) }
            // HIDE markers are cleared only after a verified `pm unhide`;
            // PackageManager cannot prove an invisible package is enabled.
            .filter { !policy.ownsHidden(it) }
            .filter { !policy.hasComponentOwnership(it) }
            .filter { enabledStateReader.isEnabled(it.packageName) != false }
            .forEach { policy.setApplied(it, false) }
    }

    /**
     * Unhides every package Nemu hid with the HIDE strategy. Shutdown and
     * boot residual cleanup call this so a hidden package never survives an
     * unclean shutdown without an owner that can restore it. Only packages
     * with an applied marker are touched; a package the user hid manually is
     * never unhidden.
     */
    suspend fun restoreOwnedHidden(): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val notes = mutableListOf<String>()
        val commandBackend = if (!backend.canSetHidden &&
            markerBackend != null &&
            markerBackend.canSetHidden
        ) {
            markerBackend
        } else {
            backend
        }
        val owned = TargetCatalog.all.filter {
            policy.isApplied(it) && policy.ownsHidden(it)
        }
        for (target in owned) {
            attempted++
            if (!commandBackend.canSetHidden) {
                failed++
                notes += "${target.packageName}：解除隐藏需要 Root 权限"
                continue
            }
            if (dryRun) {
                ActionLog.info("DRY-RUN（解除隐藏）: ${CommandBuilder.unhide(target.packageName)}")
                restored++
                continue
            }
            val before = snapshot(commandBackend, target.packageName)
            when (before?.hidden) {
                false -> {
                    // Already visible; only stale markers remain.
                    policy.setApplied(target, false)
                    policy.setOriginalHidden(target, null)
                    restored++
                    ActionLog.info("解除隐藏无需执行：${target.packageName}")
                }
                true -> {
                    commandBackend.exec(CommandBuilder.unhide(target.packageName))
                    if (snapshot(commandBackend, target.packageName)?.hidden == false) {
                        policy.setApplied(target, false)
                        policy.setOriginalHidden(target, null)
                        restored++
                        ActionLog.info("解除隐藏成功：${target.packageName}")
                    } else {
                        failed++
                        notes += "${target.packageName}：解除隐藏未确认"
                        ActionLog.warn("解除隐藏失败：${target.packageName}")
                    }
                }
                null -> {
                    failed++
                    notes += "${target.packageName}：无法读取隐藏状态"
                }
            }
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = restored,
            failed = failed,
            notes = notes
        )
    }

    /**
     * Restores every component Nemu owns, regardless of the target's current
     * strategy. Used by the shutdown hook, the boot residual cleanup and the
     * Unfreeze page before a full unfreeze.
     */
    suspend fun restoreOwnedComponents(
        targets: Collection<Target> = TargetCatalog.all
    ): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val notes = mutableListOf<String>()
        for (target in targets.distinctBy { it.packageName }) {
            val failuresBefore = failed
            for (component in target.components) {
                val original = policy.componentOriginalState(target, component.className)
                    ?: continue
                attempted++
                val command = CommandBuilder.restoreComponent(
                    component.className,
                    original
                )
                if (dryRun) {
                    ActionLog.info("DRY-RUN（组件恢复）: $command")
                    restored++
                    continue
                }
                val result = backend.exec(command)
                if (result.ok) {
                    val now = componentStateReader.state(component.className)
                    if (now == null || now == 2 || now == 3 || now == 4) {
                        // Do not delete the only exact-state baseline until the
                        // component is verified restored.
                        failed++
                        notes += "${component.label}：恢复后组件仍停用或状态未知"
                        continue
                    }
                    // Without root there is no guard marker to clear; the
                    // in-app baseline is the only ownership record.
                    val markerCleared = markerBackend == null ||
                        BootGuard.markComponentDisabled(
                            markerBackend,
                            component,
                            disabled = false
                        )
                    if (markerCleared) {
                        policy.setComponentOriginalState(
                            target,
                            component.className,
                            null
                        )
                        restored++
                    } else {
                        failed++
                        notes += "${component.label}：组件已恢复但所有权标记未清除"
                    }
                } else if (result.output.contains(
                        "Unknown component",
                        ignoreCase = true
                    ) || result.output.contains("not found", ignoreCase = true)
                ) {
                    // handled below by resolving the marker
                    // The app update removed/renamed the component. The marker
                    // is no longer actionable; resolve it instead of wedging
                    // every future residual cleanup.
                    policy.setComponentOriginalState(target, component.className, null)
                    BootGuard.markComponentDisabled(
                        markerBackend,
                        component,
                        disabled = false
                    )
                    restored++
                    notes += "${component.label}：组件已不存在，清理所有权"
                } else {
                    failed++
                    notes += "${component.label}：${result.output.take(160)}"
                }
            }
            // All owned components of this target were restored successfully;
            // a leftover applied marker would keep the UI claiming frozen.
            if (!dryRun && failed == failuresBefore &&
                policy.strategy(target) == FreezeStrategy.COMPONENT &&
                !policy.hasComponentOwnership(target)
            ) {
                policy.setApplied(target, false)
            }
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = restored,
            failed = failed,
            notes = notes
        )
    }

    /** Unsuspends every known suspend target and restores owned components. */
    suspend fun residualUnfreeze(): TargetRunResult {
        val suspend = SafetyUnfreezer.runUnsuspendPackages(
            backend,
            SafetyTargets.suspendPackageNames
        )
        if (suspend.ok && !dryRun) {
            clearSuspendAppliedMarkers()
        }
        val components = restoreOwnedComponents()
        val hidden = restoreOwnedHidden()
        val suspendAttempted = SafetyTargets.suspendPackageNames.size
        return TargetRunResult(
            ok = suspend.ok && components.ok && hidden.ok,
            attempted = suspendAttempted + components.attempted + hidden.attempted,
            applied = (if (suspend.ok) suspendAttempted else 0) +
                components.applied + hidden.applied,
            failed = (if (suspend.ok) 0 else 1) + components.failed + hidden.failed,
            notes = buildList {
                if (!suspend.ok) add("suspend 残留：${suspend.output.take(200)}")
                addAll(components.notes)
                addAll(hidden.notes)
            }
        )
    }
}
