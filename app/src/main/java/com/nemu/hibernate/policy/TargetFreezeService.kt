package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.ComponentSelectionDiff
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.HideClaimPolicy
import com.nemu.hibernate.core.RestoreStrategySelector
import com.nemu.hibernate.core.RestrictionParsers
import com.nemu.hibernate.core.ResultCopy
import com.nemu.hibernate.core.ResultNote
import com.nemu.hibernate.core.ResultReason
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.SuspendClaimPolicy
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetComponent
import com.nemu.hibernate.data.BootFreezeSettings
import kotlinx.coroutines.sync.withLock
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.detection.ProcessListSnapshot
import com.nemu.hibernate.privilege.AndroidComponentStateReader
import com.nemu.hibernate.privilege.AndroidPackageEnabledStateReader
import com.nemu.hibernate.privilege.AndroidPackageStatusReader
import com.nemu.hibernate.privilege.ComponentStateReader
import com.nemu.hibernate.privilege.PackageEnabledStateReader
import com.nemu.hibernate.privilege.PackageSnapshot
import com.nemu.hibernate.privilege.BackendPackageSnapshotReader
import com.nemu.hibernate.privilege.isMissing
import com.nemu.hibernate.privilege.PackageStatusReader
import com.nemu.hibernate.privilege.PrivilegeBackend
import com.nemu.hibernate.privilege.RootPrivilegeBackend

data class TargetRunResult(
    val ok: Boolean,
    val attempted: Int,
    val applied: Int,
    val failed: Int,
    val reasons: List<ResultNote> = emptyList()
) {
    /** Chinese UI lines; raw commands/outputs stay in ActionLog. */
    val notes: List<String> get() = reasons.map { it.line() }

    fun summary(): String = ResultCopy.runSummary(
        ok = ok,
        attempted = attempted,
        applied = applied,
        failed = failed,
        reasons = reasons
    )
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

    private suspend fun readAppOps(
        backend: PrivilegeBackend,
        packageName: String
    ): String? {
        val result = backend.exec(CommandBuilder.appOpsGet(packageName))
        if (!result.ok) return null
        return RestrictionParsers.parseAppOpsMode(result.output)
    }

    private suspend fun readStandby(
        backend: PrivilegeBackend,
        packageName: String
    ): Int? {
        val result = backend.exec(CommandBuilder.standbyGet(packageName))
        if (!result.ok) return null
        return RestrictionParsers.parseStandbyBucket(result.output)
    }

    private fun restrictionController(
        commandBackend: PrivilegeBackend,
        target: Target
    ): RestrictionController = RestrictionController(
        backend = commandBackend,
        dryRun = dryRun,
        appOpsReader = { packageName -> readAppOps(commandBackend, packageName) },
        standbyReader = { packageName -> readStandby(commandBackend, packageName) },
        originalAppOps = { policy.originalAppOps(target) },
        onOriginalAppOps = { _, mode -> policy.setOriginalAppOps(target, mode) },
        appOpsOwned = { policy.ownsAppOps(target) },
        setAppOpsOwned = { _, owned -> policy.setAppOpsOwned(target, owned) },
        originalStandby = { policy.originalStandby(target) },
        onOriginalStandby = { _, bucket ->
            policy.setOriginalStandby(target, bucket)
        },
        standbyOwned = { policy.ownsStandby(target) },
        setStandbyOwned = { _, owned -> policy.setStandbyOwned(target, owned) },
        beforeOwn = { _, kind ->
            when (kind) {
                RestrictionKind.APP_OPS -> BootGuard.markAppOpsByNemu(
                    markerBackend,
                    target.packageName,
                    owned = true,
                    originalMode = policy.originalAppOps(target)
                )
                RestrictionKind.STANDBY -> BootGuard.markStandbyByNemu(
                    markerBackend,
                    target.packageName,
                    owned = true,
                    originalBucket = policy.originalStandby(target)
                )
            }
        },
        afterRestore = { _, kind ->
            // No marker backend this session: there is no marker we can
            // clear. The in-app ownership is still cleared by the caller, so
            // treat it as success instead of failing shutdown/residual.
            if (markerBackend == null) {
                true
            } else when (kind) {
                RestrictionKind.APP_OPS -> BootGuard.markAppOpsByNemu(
                    markerBackend,
                    target.packageName,
                    owned = false
                )
                RestrictionKind.STANDBY -> BootGuard.markStandbyByNemu(
                    markerBackend,
                    target.packageName,
                    owned = false
                )
            }
        }
    )

    suspend fun apply(targets: List<Target>): TargetRunResult =
        FreezeOperationLock.mutex.withLock { applyLocked(targets) }

    /**
     * Boot-worker entry: under the same lock as a manual restore, refuse to
     * apply if the user already skipped this boot. Returns null for "skipped".
     */
    suspend fun applyForBoot(
        bootCount: Int,
        bootSettings: BootFreezeSettings,
        targets: List<Target>
    ): TargetRunResult? = FreezeOperationLock.mutex.withLock {
        // Re-check the master switch under the same lock: the user may have
        // turned it off after the worker evaluated the policy.
        if (!bootSettings.bootFreezeEnabled) {
            null
        // -1 means the platform did not expose a boot identity; it must never
        // match the default skip sentinel and suppress a real freeze.
        } else if (bootCount >= 0 &&
            bootSettings.bootFreezeSkippedBootCount == bootCount
        ) {
            null
        } else {
            applyLocked(targets)
        }
    }

    private suspend fun applyLocked(targets: List<Target>): TargetRunResult {
        val pending = targets.distinctBy { it.packageName }
        var applied = 0
        var failed = 0
        var attempted = 0
        val reasons = mutableListOf<ResultNote>()
        for (target in pending) {
            val strategy = policy.strategy(target)
            val options = policy.options(target)
            val hasRestrictions =
                options != null && (options.appOps || options.standby)
            if (strategy == FreezeStrategy.OBSERVE) {
                if (!hasRestrictions) continue
                if (!dryRun && markerBackend == null) {
                    // The ownership marker is the only recovery record for
                    // AppOps/standby changes; without it, do not touch them.
                    failed++
                    reasons += ResultReason.MARKER_WRITE_FAILED.at(target.label)
                    continue
                }
                attempted++
                if (!dryRun) policy.recordBackend(target, backend.name)
                val restriction = restrictionController(backend, target)
                    .apply(target, options)
                if (restriction.ok) {
                    applied++
                    ActionLog.info("限制策略成功：${restriction.summary()}")
                } else {
                    failed++
                    reasons.addResult(
                        target.label,
                        restriction.ok,
                        restriction.reasons
                    )
                }
                continue
            }
            val invalid = FreezeStrategyPolicy.validate(target, strategy)
            if (invalid != null) {
                failed++
                reasons += ResultReason.PRECHECK_FAILED.at(target.label, invalid)
                continue
            }
            attempted++
            if (!dryRun) policy.recordBackend(target, backend.name)
            if (markerBackend == null &&
                (strategy == FreezeStrategy.COMPONENT ||
                    strategy == FreezeStrategy.DISABLE_USER ||
                    strategy == FreezeStrategy.HIDE)
            ) {
                // Without a writable root marker backend, rescue cannot undo
                // these persistent states. Refuse before changing anything.
                failed++
                reasons += ResultReason.MARKER_WRITE_FAILED.at(target.label)
                continue
            }
            if (strategy == FreezeStrategy.COMPONENT &&
                policy.effectiveComponents(target).isEmpty()
            ) {
                // Corrupt options or an empty selection must never expand to
                // the full registered component list.
                failed++
                reasons += ResultReason.NO_COMPONENTS.at(target.label)
                continue
            }
            if (strategy == FreezeStrategy.SUSPEND &&
                !SuspendClaimPolicy.shouldClaim(
                    suspendedStateReader(target.packageName),
                    ownedByNemu = policy.isApplied(target) &&
                        policy.storedStrategy(target) == FreezeStrategy.SUSPEND
                )
            ) {
                // Already suspended by another tool: do not write applied,
                // do not claim ownership, and do not unsuspend it later.
                failed++
                reasons += ResultReason.SUSPENDED_BY_OTHER.at(target.label)
                continue
            }
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
                    reasons += ResultReason.HIDE_NEEDS_ROOT_APPLY.at(target.label)
                    ActionLog.warn(
                        "伪装卸载需要 Root：${target.packageName} / " +
                            "${commandBackend.name} 无 MANAGE_USERS"
                    )
                    continue
                }
                val before = snapshot(commandBackend, target.packageName)
                if (before == null) {
                    failed++
                    reasons += ResultReason.BASELINE_READ_FAILED.at(target.label)
                    continue
                }
                if (!before.exists || before.installed != true) {
                    failed++
                    reasons += ResultReason.NOT_INSTALLED.at(target.label)
                    continue
                }
                if (before.hidden == null) {
                    failed++
                    reasons += ResultReason.HIDE_STATE_UNKNOWN.at(target.label)
                    continue
                }
                if (!HideClaimPolicy.shouldClaim(
                        before.hidden,
                        ownedByNemu = policy.ownsHidden(target)
                    )
                ) {
                    // Someone else hid it before Nemu. Never claim it:
                    // a restore must not reveal an app the user/OEM hid.
                    failed++
                    reasons += ResultReason.HIDDEN_BY_OTHER.at(target.label)
                    continue
                }
                // Durability: record that the pre-hide state was visible.
                if (!dryRun && !policy.setOriginalHidden(target, false)) {
                    failed++
                    reasons += ResultReason.BASELINE_WRITE_FAILED.at(target.label)
                    continue
                }
                if (!dryRun &&
                    !BootGuard.markHiddenByNemu(
                        markerBackend,
                        target.packageName,
                        hiddenByNemu = true
                    )
                ) {
                    policy.setOriginalHidden(target, null)
                    failed++
                    reasons += ResultReason.MARKER_WRITE_FAILED.at(target.label)
                    continue
                }
            }
            var original: Boolean? = null
            if (strategy == FreezeStrategy.DISABLE_USER) {
                val live = enabledStateReader.isEnabled(target.packageName)
                if (live == null) {
                    failed++
                    reasons += ResultReason.BASELINE_READ_FAILED.at(target.label)
                    continue
                }
                val stored = policy.originalEnabled(target)
                original = when {
                    stored == null -> live
                    // A stale false baseline must not make a now-enabled
                    // package look "already disabled"; live state wins.
                    !stored && live -> true
                    else -> stored
                }
                if (!original) {
                    // Truly disabled before Nemu: preserve it and claim
                    // nothing, so restore never enables a foreign state.
                    applied++
                    reasons += ResultReason.ALREADY_DISABLED.at(target.label)
                    continue
                }
                if (!dryRun && !policy.setOriginalEnabled(target, true)) {
                    // The exact original-enabled baseline is the only proof
                    // Nemu may re-enable this package. Do not mutate it.
                    failed++
                    reasons += ResultReason.BASELINE_WRITE_FAILED.at(target.label)
                    continue
                }
            }
            if (!dryRun && !policy.setApplied(target, true)) {
                // Ownership must reach disk before any freeze command; an
                // in-memory/applied flag alone would be lost after a crash.
                failed++
                reasons += ResultReason.OWNERSHIP_WRITE_FAILED.at(target.label)
                continue
            }
            if (strategy == FreezeStrategy.SUSPEND && !dryRun &&
                markerBackend != null &&
                !BootGuard.markSuspendedByNemu(
                    markerBackend,
                    target.packageName,
                    suspendedByNemu = true
                )
            ) {
                // The guard must know a suspend is Nemu's before it happens;
                // without the marker a normal boot could not safely restore
                // it. Refuse rather than leaving an unowned suspension.
                policy.setApplied(target, false)
                failed++
                reasons += ResultReason.MARKER_WRITE_FAILED.at(target.label)
                continue
            }

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
                    markerBackend?.let {
                        BootGuard.markDisabledByNemu(
                            it,
                            packageName,
                            disabledByNemu = false
                        )
                    } ?: true
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
            val componentOverride = if (strategy == FreezeStrategy.COMPONENT) {
                policy.effectiveComponents(target)
            } else {
                null
            }
            if (strategy == FreezeStrategy.COMPONENT && !dryRun) {
                val selected = componentOverride
                    ?.mapTo(mutableSetOf()) { it.className }
                    ?: emptySet()
                val deselected = ComponentSelectionDiff.deselected(
                    owned = policy.ownedComponentNames(target),
                    selected = selected
                )
                if (deselected.isNotEmpty()) {
                    // Apply makes the device match the saved selection.
                    // Restore deselected components now, not as a side
                    // effect of closing the picker.
                    val restored = restoreOwnedComponents(
                        targets = listOf(target),
                        only = deselected
                    )
                    if (!restored.ok) {
                        failed++
                        reasons.addResult(
                            target.label,
                            restored.ok,
                            restored.reasons
                        )
                        continue
                    }
                    // restoreOwnedComponents may clear the marker when the
                    // last owned component is gone; the selected components
                    // are about to be applied below.
                    policy.setApplied(target, true)
                }
            }
            if (strategy == FreezeStrategy.COMPONENT && !dryRun) {
                var baselineOk = true
                for (component in componentOverride.orEmpty()) {
                    val state = componentStateReader.state(component.className)
                    if (state != 0 && state != 1) continue
                    if (!policy.setComponentOriginalState(
                            target,
                            component.className,
                            state
                        )
                    ) {
                        baselineOk = false
                        break
                    }
                }
                if (!baselineOk) {
                    // Never issue pm disable without an exact baseline on disk.
                    failed++
                    reasons += ResultReason.BASELINE_WRITE_FAILED.at(target.label)
                    continue
                }
            }
            val result = controller.apply(
                target,
                strategy,
                originalEnabled = original,
                options = options ?: StrategyOptions(),
                componentsOverride = componentOverride
            )
            if (result.ok) {
                var restrictionOk = true
                if (hasRestrictions) {
                    val restriction = restrictionController(commandBackend, target)
                        .apply(target, options)
                    restrictionOk = restriction.ok
                    if (!restriction.ok) {
                        reasons.addResult(
                            target.label,
                            restriction.ok,
                            restriction.reasons
                        )
                    }
                }
                if (restrictionOk) {
                    applied++
                    ActionLog.info("应用策略成功：${result.summary()}")
                } else {
                    failed++
                    reasons += ResultReason.PARTIAL_SUCCESS.at(target.label)
                }
            } else {
                // A timeout means the command may still land after the
                // wrapper was killed. Do not trust an immediate readback and
                // do not clear the applied flag, baseline or guard marker:
                // keep the recovery record so restore/boot guard can settle
                // the real state later.
                if (result.indeterminate) {
                    failed++
                    reasons.addResult(target.label, false, result.reasons)
                    continue
                }
                // A failed SUSPEND with a verified live package needs no
                // recovery marker; a stale marker would make the guard try
                // an unsuspend next boot for no reason. Keep it when the
                // state is unknown or still suspended.
                if (strategy == FreezeStrategy.SUSPEND && !dryRun &&
                    markerBackend != null &&
                    suspendedStateReader(target.packageName) == false
                ) {
                    BootGuard.markSuspendedByNemu(
                        markerBackend,
                        target.packageName,
                        suspendedByNemu = false
                    )
                }
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
                                hideRolledBack = clearHiddenOwnership(target)
                                if (hideRolledBack) {
                                    reasons += ResultReason.HIDE_ROLLED_BACK.at(
                                        target.label
                                    )
                                } else {
                                    reasons += ResultReason.MARKER_CLEAR_FAILED
                                        .at(target.label)
                                }
                            }
                        }
                        false -> {
                            hideRolledBack = clearHiddenOwnership(target)
                            if (!hideRolledBack) {
                                reasons += ResultReason.MARKER_CLEAR_FAILED
                                    .at(target.label)
                            }
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
                    (strategy == FreezeStrategy.SUSPEND &&
                        !dryRun &&
                        // Keep the marker when the package is still suspended
                        // or the readback is unknown; only a verified
                        // unsuspended package can drop ownership.
                        policy.isApplied(target) &&
                        suspendedStateReader(target.packageName) != false) ||
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
                reasons.addResult(target.label, result.ok, result.reasons)
            }
        }
        if (!dryRun) {
            ActionLog.info("统一冻结结果：${reasons.size} 条备注")
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = applied,
            failed = failed,
            reasons = reasons
        )
    }

    /**
     * Restores selected targets. [force] is used by the Unfreeze page's
     * explicit "all" action: suspended packages are unsuspended even when the
     * applied marker was lost (e.g. an older build froze them). Disable-user
     * and component restores still require stored ownership.
     */
    suspend fun restore(
        targets: List<Target>,
        force: Boolean = false
    ): TargetRunResult = FreezeOperationLock.mutex.withLock {
        restoreLocked(targets, force)
    }

    private suspend fun restoreLocked(
        targets: List<Target>,
        force: Boolean
    ): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val reasons = mutableListOf<ResultNote>()
        for (target in targets.distinctBy { it.packageName }) {
            val reasonsBefore = reasons.size
            // Preview must not clear ownership or root markers.
            if (!dryRun && snapshot(backend, target.packageName)?.isMissing() == true) {
                attempted++
                if (clearMissingPackageOwnership(target)) {
                    restored++
                    reasons += ResultReason.PACKAGE_MISSING.at(target.label)
                } else {
                    failed++
                    reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                        target.label
                    )
                }
                continue
            }
            val stored = policy.storedStrategy(target)
            val suspendedNow = suspendedStateReader(target.packageName) == true
            val disabledNow = enabledStateReader.isEnabled(target.packageName) == false
            // A recorded hidden ownership always wins: changing the strategy
            // chip after hiding must not strand the package in `pm hide`.
            // Stored strategy is used here, not the currently-supported one,
            // so a retired capability can still undo an old applied state.
            val strategy = RestoreStrategySelector.select(
                stored = stored,
                ownsHidden = policy.ownsHidden(target),
                ownsDisable = policy.originalEnabled(target) != null,
                disabledNow = disabledNow
            )
            if (!force && !policy.isApplied(target) && !suspendedNow) continue
            attempted++

            // Restore every owned restriction even when the current policy
            // checkbox was turned off; ownership, not the current selection,
            // decides what is safe to undo.
            val restrictionOptions = StrategyOptions(
                appOps = policy.ownsAppOps(target),
                standby = policy.ownsStandby(target)
            )
            val hasRestrictions = restrictionOptions.appOps ||
                restrictionOptions.standby
            var restrictionOk = true
            if (hasRestrictions) {
                val restriction = restrictionController(backend, target)
                    .restore(target, restrictionOptions)
                restrictionOk = restriction.ok
                if (!restriction.ok) {
                    reasons.addResult(
                        target.label,
                        restriction.ok,
                        restriction.reasons
                    )
                }
            }

            // Actual state wins over policy: a target can be suspended under a
            // COMPONENT/DISABLE policy (legacy or manually). Always clear the
            // suspension first; otherwise the row would report success while
            // the package stays suspended.
            var suspendOk = true
            val ownsSuspension = isOwnedSuspension(
                applied = policy.isApplied(target),
                storedStrategy = policy.storedStrategy(target)
            )
            if (suspendedNow && !ownsSuspension) {
                // Restoring an AppOps/standby/disable ownership must not undo
                // a suspension created by Hail/MDM/adb.
                reasons += ResultReason.SUSPENDED_BY_OTHER.at(target.label)
            } else if (suspendedNow) {
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
                        ActionLog.warn("仍处于暂停：${target.packageName}")
                    }
                }
            }

            if (strategy == FreezeStrategy.OBSERVE) {
                if (suspendOk && restrictionOk) {
                    restored++
                } else {
                    failed++
                    if (!suspendOk) {
                        reasons += ResultReason.STILL_SUSPENDED.at(target.label)
                    }
                    if (reasons.size == reasonsBefore) {
                        reasons += ResultReason.UNKNOWN.at(target.label)
                    }
                }
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
                    reasons += ResultReason.HIDE_NEEDS_ROOT_RESTORE.at(
                        target.label
                    )
                    continue
                }
                if (!policy.ownsHidden(target)) {
                    val visible = snapshot(commandBackend, target.packageName)
                        ?.hidden == false
                    if (visible) {
                        if (dryRun) {
                            restored++
                            reasons += ResultReason.ALREADY_VISIBLE.at(target.label)
                        } else if (clearHiddenOwnership(target)) {
                            policy.setApplied(target, false)
                            restored++
                            reasons += ResultReason.ALREADY_VISIBLE.at(target.label)
                        } else {
                            failed++
                            reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                                target.label
                            )
                        }
                    } else {
                        reasons += ResultReason.HIDE_NOT_OWNED.at(target.label)
                        failed++
                    }
                    continue
                }
            }

            val componentCandidates = if (strategy == FreezeStrategy.COMPONENT) {
                OwnedComponentCatalog.merge(
                    registered = target.components,
                    ownedNames = policy.ownedComponentNames(target)
                )
            } else {
                emptyList()
            }
            val ownedOriginalStates = componentCandidates.mapNotNull { component ->
                policy.componentOriginalState(target, component.className)
                    ?.let { component to it }
            }.toMap()
            val ownedComponents = ownedOriginalStates.keys.toList()
            if (strategy == FreezeStrategy.COMPONENT && ownedComponents.isEmpty()) {
                val anyDisabled = componentCandidates.any {
                    componentStateReader.state(it.className) in 2..4
                }
                if (anyDisabled) {
                    reasons += ResultReason.COMPONENT_BASELINE_MISSING.at(
                        target.label
                    )
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
                    markerBackend?.let {
                        BootGuard.markDisabledByNemu(
                            it,
                            packageName,
                            disabledByNemu = false
                        )
                    } ?: true
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
                if (strategy == FreezeStrategy.COMPONENT) {
                    target.copy(components = ownedComponents)
                } else {
                    target
                },
                strategy,
                originalEnabled = original
            )
            val verified = when (strategy) {
                FreezeStrategy.SUSPEND ->
                    dryRun || !suspendedNow ||
                        suspendedStateReader(target.packageName) == false
                FreezeStrategy.COMPONENT -> dryRun || ownedComponents.all {
                    ComponentRestoreState.isRestored(
                        componentStateReader.state(it.className)
                    )
                }
                else -> true
            }
            if (result.ok && suspendOk && verified && restrictionOk) {
                if (!dryRun) {
                    if (strategy == FreezeStrategy.COMPONENT) {
                        ownedOriginalStates.forEach { (component, _) ->
                            val cleared = markerBackend == null ||
                                BootGuard.markComponentDisabled(
                                    markerBackend,
                                    component,
                                    disabled = false
                                )
                            if (!cleared || !policy.setComponentOriginalState(
                                    target,
                                    component.className,
                                    null
                                )
                            ) {
                                failed++
                                reasons += ResultReason.MARKER_CLEAR_FAILED
                                    .at(component.label)
                            }
                        }
                    }
                    policy.setApplied(target, false)
                    if (strategy == FreezeStrategy.SUSPEND &&
                        markerBackend != null &&
                        !BootGuard.markSuspendedByNemu(
                            markerBackend,
                            target.packageName,
                            suspendedByNemu = false
                        )
                    ) {
                        // The guard would retry an already-cleared suspend;
                        // harmless, but surface the unowned marker.
                        failed++
                        reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                            target.label
                        )
                    }
                    if (strategy == FreezeStrategy.DISABLE_USER) {
                        policy.setOriginalEnabled(target, null)
                    }
                    if (strategy == FreezeStrategy.HIDE &&
                        !clearHiddenOwnership(target)
                    ) {
                        failed++
                        reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                            target.label
                        )
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
                reasons.addResult(target.label, result.ok, result.reasons)
                if (!suspendOk) {
                    reasons += ResultReason.STILL_SUSPENDED.at(target.label)
                }
                if (!verified) {
                    reasons += ResultReason.RESTORE_UNVERIFIED.at(target.label)
                }
                if (reasons.size == reasonsBefore) {
                    reasons += ResultReason.UNKNOWN.at(target.label)
                }
            }
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = restored,
            failed = failed,
            reasons = reasons
        )
    }

    /**
     * Clears both in-app hidden ownership and the root marker. The marker is
     * the boot_guard's only evidence; clearing one without the other can make
     * the guard unhide a package the user hid later.
     */
    private suspend fun clearHiddenOwnership(target: Target): Boolean {
        val markerCleared = markerBackend == null ||
            BootGuard.markHiddenByNemu(
                markerBackend,
                target.packageName,
                hiddenByNemu = false
            )
        if (markerCleared) {
            policy.setOriginalHidden(target, null)
        }
        return markerCleared
    }

    /** Read-back proof for shutdown before it writes the clean marker. */
    suspend fun ownedSuspensionsCleared(): Boolean =
        ownedSuspendPackageNames().all { pkg ->
            when (val state = suspendedStateReader(pkg)) {
                false -> true
                true -> false
                // A removed package cannot be suspended; an unreadable one
                // must still fail closed.
                null -> snapshot(backend, pkg)?.isMissing() == true
            }
        }

    /**
     * Clears applied markers for SUSPEND targets after a verified safety
     * unsuspend. A re-application sets them again; leaving them would make the
     * Freeze page claim an applied state that no longer exists.
     */
    suspend fun clearSuspendAppliedMarkers(checkMissing: Boolean = true) {
        TargetCatalog.all
            .filter { policy.isApplied(it) }
            .forEach { target ->
                // pm reports "Unknown package" for an uninstalled package;
                // there is nothing to unsuspend, but ownership would otherwise
                // keep the safety service and custom-removal guard armed
                // forever. Shutdown skips this expensive per-package dumpsys
                // and lets the next boot residual resolve it.
                if (checkMissing &&
                    snapshot(backend, target.packageName)?.isMissing() == true
                ) {
                    clearMissingPackageOwnership(target)
                    return@forEach
                }
                if (policy.storedStrategy(target) != FreezeStrategy.SUSPEND) {
                    return@forEach
                }
                if (AppliedMarkerCleanup.clearAfterSuspend(
                        suspended = suspendedStateReader(target.packageName),
                        ownsHidden = policy.ownsHidden(target),
                        ownsComponents = policy.hasComponentOwnership(target)
                    )
                ) {
                    policy.setApplied(target, false)
                }
            }
    }

    /**
     * Shutdown restores suspends and components but intentionally leaves
     * disable-user packages alone. Clear applied markers only for targets that
     * are now enabled and no longer own components.
     */
    suspend fun clearRestoredAppliedMarkers(checkMissing: Boolean = true) {
        TargetCatalog.all
            .filter { policy.isApplied(it) }
            .forEach { target ->
                if (checkMissing &&
                    snapshot(backend, target.packageName)?.isMissing() == true
                ) {
                    clearMissingPackageOwnership(target)
                    return@forEach
                }
                if (AppliedMarkerCleanup.clearAfterShutdown(
                        // HIDE markers are cleared only after a verified
                        // `pm unhide`; PackageManager cannot prove an
                        // invisible package is enabled. Null is not proof.
                        enabled = enabledStateReader.isEnabled(
                            target.packageName
                        ),
                        suspended = suspendedStateReader(
                            target.packageName
                        ),
                        ownsHidden = policy.ownsHidden(target),
                        ownsComponents = policy.hasComponentOwnership(target)
                    )
                ) {
                    policy.setApplied(target, false)
                }
            }
    }

    /**
     * Resolves every ownership record for a package that PackageManager
     * confirms no longer exists. Called only with an explicit
     * [PackageSnapshot.exists] == false, never on an unknown read.
     */
    private suspend fun clearMissingPackageOwnership(target: Target): Boolean {
        var ok = true
        if (policy.ownsHidden(target) && !clearHiddenOwnership(target)) {
            ok = false
        }
        val ownedComponents = policy.ownedComponentNames(target)
        for (name in ownedComponents) {
            val component = target.components.firstOrNull {
                it.className == name
            } ?: TargetComponent(name, name)
            val markerCleared = markerBackend == null ||
                BootGuard.markComponentDisabled(
                    markerBackend,
                    component,
                    disabled = false
                )
            if (markerCleared) {
                if (!policy.setComponentOriginalState(target, name, null)) {
                    ok = false
                }
            } else {
                ok = false
            }
        }
        // Keep the in-app ownership record when its root marker could not be
        // removed; the next residual pass retries instead of leaving an
        // orphan marker with no owner.
        if (policy.ownsAppOps(target)) {
            val markerCleared = markerBackend == null ||
                BootGuard.markAppOpsByNemu(
                    markerBackend,
                    target.packageName,
                    owned = false
                )
            if (markerCleared) {
                ok = policy.setAppOpsOwned(target, false) && ok
                ok = policy.setOriginalAppOps(target, null) && ok
            } else {
                ok = false
            }
        }
        if (policy.ownsStandby(target)) {
            val markerCleared = markerBackend == null ||
                BootGuard.markStandbyByNemu(
                    markerBackend,
                    target.packageName,
                    owned = false
                )
            if (markerCleared) {
                ok = policy.setStandbyOwned(target, false) && ok
                ok = policy.setOriginalStandby(target, null) && ok
            } else {
                ok = false
            }
        }
        if (policy.originalEnabled(target) != null) {
            val markerCleared = markerBackend == null ||
                BootGuard.markDisabledByNemu(
                    markerBackend,
                    target.packageName,
                    disabledByNemu = false
                )
            if (markerCleared) {
                ok = policy.setOriginalEnabled(target, null) && ok
            } else {
                ok = false
            }
        }
        if (ok) policy.setApplied(target, false)
        if (ok) {
            ActionLog.info("已清理不存在的目标：${target.packageName}")
        }
        return ok
    }

    /**
     * Unhides every package Nemu hid with the HIDE strategy. Shutdown and
     * boot residual cleanup call this so a hidden package never survives an
     * unclean shutdown without an owner that can restore it. Only packages
     * with an applied marker are touched; a package the user hid manually is
     * never unhidden.
     */
    suspend fun restoreOwnedHidden(
        checkMissing: Boolean = true
    ): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val reasons = mutableListOf<ResultNote>()
        val commandBackend = if (!backend.canSetHidden &&
            markerBackend != null &&
            markerBackend.canSetHidden
        ) {
            markerBackend
        } else {
            backend
        }
        // Owned hidden state is the sole condition. An applied marker may be
        // lost across a crash, but the package must still be unhidden.
        val owned = TargetCatalog.all.filter { policy.ownsHidden(it) }
        for (target in owned) {
            attempted++
            if (!dryRun && checkMissing &&
                snapshot(commandBackend, target.packageName)?.isMissing() == true
            ) {
                if (clearMissingPackageOwnership(target)) {
                    restored++
                    reasons += ResultReason.PACKAGE_MISSING.at(target.label)
                } else {
                    failed++
                    reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                        target.label
                    )
                }
                continue
            }
            if (!commandBackend.canSetHidden) {
                failed++
                reasons += ResultReason.HIDE_NEEDS_ROOT_RESTORE.at(target.label)
                continue
            }
            if (dryRun) {
                ActionLog.info("DRY-RUN（解除隐藏）: ${CommandBuilder.unhide(target.packageName)}")
                restored++
                continue
            }
            val before = snapshot(commandBackend, target.packageName)
            if (!checkMissing && before?.isMissing() == true) {
                // Shutdown budget: an uninstalled package's ownership is
                // resolved by the next boot residual pass; it must not fail
                // the whole pass and withhold the clean-shutdown marker.
                continue
            }
            when (before?.hidden) {
                false -> {
                    // Already visible; only stale markers remain. Do not drop
                    // the in-app ownership baseline unless the root marker was
                    // also cleared, or the guard could later act on stale data.
                    if (clearHiddenOwnership(target)) {
                        policy.setApplied(target, false)
                        restored++
                        ActionLog.info("解除隐藏无需执行：${target.packageName}")
                    } else {
                        failed++
                        reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                            target.label
                        )
                    }
                }
                true -> {
                    commandBackend.exec(CommandBuilder.unhide(target.packageName))
                    if (snapshot(commandBackend, target.packageName)?.hidden == false) {
                        if (clearHiddenOwnership(target)) {
                            policy.setApplied(target, false)
                            restored++
                            ActionLog.info("解除隐藏成功：${target.packageName}")
                        } else {
                            failed++
                            reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                                target.label
                            )
                        }
                    } else {
                        failed++
                        reasons += ResultReason.UNHIDE_NOT_CONFIRMED.at(
                            target.label
                        )
                        ActionLog.warn("解除隐藏失败：${target.packageName}")
                    }
                }
                null -> {
                    failed++
                    reasons += ResultReason.HIDE_STATE_UNKNOWN.at(target.label)
                }
            }
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = restored,
            failed = failed,
            reasons = reasons
        )
    }

    /**
     * Restores every component Nemu owns, regardless of the target's current
     * strategy. Used by the shutdown hook, the boot residual cleanup and the
     * Unfreeze page before a full unfreeze.
     */
    suspend fun restoreOwnedComponents(
        targets: Collection<Target> = TargetCatalog.all,
        only: Set<String>? = null,
        checkMissing: Boolean = true
    ): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val reasons = mutableListOf<ResultNote>()
        for (target in targets.distinctBy { it.packageName }.filter {
                policy.isApplied(it) || policy.hasComponentOwnership(it)
            }) {
            val failuresBefore = failed
            if (!dryRun && checkMissing &&
                snapshot(backend, target.packageName)?.isMissing() == true
            ) {
                attempted++
                if (clearMissingPackageOwnership(target)) {
                    restored++
                    reasons += ResultReason.PACKAGE_MISSING.at(target.label)
                } else {
                    failed++
                    reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                        target.label
                    )
                }
                continue
            }
            for (component in OwnedComponentCatalog.merge(
                registered = target.components,
                ownedNames = policy.ownedComponentNames(target)
            )) {
                if (only != null && component.className !in only) continue
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
                if (ComponentRestoreState.isMissingComponent(result)) {
                    // The app update removed/renamed the component. The marker
                    // is no longer actionable; resolve it instead of wedging
                    // every future residual cleanup. Check this before the
                    // generic ok branch: exit code 0 would otherwise hide it.
                    val markerCleared = markerBackend == null ||
                        BootGuard.markComponentDisabled(
                            markerBackend,
                            component,
                            disabled = false
                        )
                    if (markerCleared && policy.setComponentOriginalState(
                            target,
                            component.className,
                            null
                        )
                    ) {
                        restored++
                        reasons += ResultReason.COMPONENT_MISSING.at(component.label)
                    } else {
                        failed++
                        reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                            component.label
                        )
                    }
                } else if (result.ok) {
                    val now = componentStateReader.state(component.className)
                    if (!ComponentRestoreState.isRestored(now)) {
                        if (!checkMissing && now == null) {
                            // Shutdown budget: an uninstalled package's
                            // component state is unreadable. Defer ownership
                            // cleanup to the next boot residual instead of
                            // failing the pass and skipping auto-freeze.
                            continue
                        }
                        // Do not delete the only exact-state baseline until the
                        // component is verified restored.
                        failed++
                        reasons += if (now == null) {
                            ResultReason.COMPONENT_STATE_UNKNOWN.at(component.label)
                        } else {
                            ResultReason.COMPONENT_STILL_DISABLED.at(component.label)
                        }
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
                    if (markerCleared && policy.setComponentOriginalState(
                            target,
                            component.className,
                            null
                        )
                    ) {
                        restored++
                    } else {
                        failed++
                        reasons += ResultReason.MARKER_CLEAR_FAILED.at(
                            component.label
                        )
                    }
                } else {
                    failed++
                    ActionLog.error(
                        "恢复组件失败：${component.className} / " +
                            result.output.take(300)
                    )
                    reasons += ResultReason.COMMAND_FAILED.at(component.label)
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
            reasons = reasons
        )
    }

    /**
     * Restores AppOps/standby values Nemu owns. Normal shutdown and boot
     * residual pass [orphansOnly] = true so a still-enabled policy is not
     * undone; rescue passes false to restore everything.
     */
    suspend fun restoreOwnedRestrictions(
        orphansOnly: Boolean = false
    ): TargetRunResult {
        var attempted = 0
        var restored = 0
        var failed = 0
        val reasons = mutableListOf<ResultNote>()
        val owned = TargetCatalog.all
            .filter { policy.ownsAppOps(it) || policy.ownsStandby(it) }
            .filter {
                !orphansOnly ||
                    !policy.isEnabled(it) ||
                    !restrictionStillRequested(it)
            }
        for (target in owned) {
            attempted++
            val options = StrategyOptions(
                appOps = policy.ownsAppOps(target),
                standby = policy.ownsStandby(target)
            )
            val result = restrictionController(backend, target)
                .restore(target, options)
            if (result.ok) {
                restored++
                ActionLog.info("恢复限制成功：${result.summary()}")
            } else {
                failed++
                reasons.addResult(target.label, result.ok, result.reasons)
            }
        }
        return TargetRunResult(
            ok = failed == 0,
            attempted = attempted,
            applied = restored,
            failed = failed,
            reasons = reasons
        )
    }

    private fun restrictionStillRequested(target: Target): Boolean {
        val options = policy.options(target) ?: return false
        return (options.appOps && policy.ownsAppOps(target)) ||
            (options.standby && policy.ownsStandby(target))
    }

    /** Unsuspends every known suspend target and restores owned components. */
    /**
     * Suspensions Nemu can prove it owns. Unsuspending every catalog package
     * would also revive apps deliberately suspended by another tool/MDM.
     */
    fun ownedSuspendPackageNames(): List<String> =
        SafetyTargets.suspendPackageNames.filter { packageName ->
            val target = TargetCatalog.byPackage(packageName) ?: return@filter false
            // Same proof as restore: only a SUSPEND-strategy applied marker
            // claims the suspension. An AppOps/standby/component/disable
            // target may coexist with a suspension created by Hail/MDM/adb;
            // unsuspending that foreign state on shutdown would revive it.
            isOwnedSuspension(
                applied = policy.isApplied(target),
                storedStrategy = policy.storedStrategy(target)
            )
        }

    suspend fun residualUnfreeze(): TargetRunResult =
        FreezeOperationLock.mutex.withLock { residualUnfreezeLocked() }

    /**
     * Boot-safety entry. Re-checks the boot state under the same lock as
     * apply/restore; null means "skip cleanup for this boot".
     */
    suspend fun residualUnfreezeIfNoBootAction(
        bootCount: Int,
        bootSettings: BootFreezeSettings,
        bootStartedAtMillis: Long
    ): TargetRunResult? = FreezeOperationLock.mutex.withLock {
        // Unknown boot identity (-1) must not match the default skip sentinel.
        if (bootCount >= 0 &&
            bootSettings.bootFreezeSkippedBootCount == bootCount
        ) {
            return@withLock null
        }
        val acted = BootSafetyDecision.frozenThisBoot(
            lastAttemptTime = bootSettings.lastBootFreezeAttemptTime,
            lastAttemptBootCount = bootSettings.lastBootFreezeAttemptBootCount,
            currentBootCount = bootCount,
            bootStartedAtMillis = bootStartedAtMillis
        )
        if (acted) null else residualUnfreezeLocked()
    }

    private suspend fun residualUnfreezeLocked(): TargetRunResult {
        // Uninstalled packages cannot be read back through pm; clear their
        // ownership explicitly instead of letting the read-back check fail.
        val appliedTargets = TargetCatalog.all.filter { policy.isApplied(it) }
        val missingTargets = appliedTargets.filter {
            snapshot(backend, it.packageName)?.isMissing() == true
        }
        var missingRestored = 0
        var missingFailed = 0
        missingTargets.forEach { target ->
            if (clearMissingPackageOwnership(target)) {
                missingRestored++
            } else {
                missingFailed++
            }
        }
        val missingNames = missingTargets.mapTo(mutableSetOf()) {
            it.packageName
        }
        val suspendTargets = ownedSuspendPackageNames()
            .filterNot { it in missingNames }
        val suspend = SafetyUnfreezer.runUnsuspendPackages(
            backend,
            suspendTargets
        )
        // pm's stdout is not proof: a suspension owned by another backend can
        // survive with exit 0. Require a real read-back per owned package.
        val suspendOk = suspend.ok &&
            (dryRun || suspendTargets.all {
                suspendedStateReader(it) == false
            })
        if (suspendOk && !dryRun) {
            clearSuspendAppliedMarkers()
        }
        val components = restoreOwnedComponents()
        val hidden = restoreOwnedHidden()
        val restrictions = restoreOwnedRestrictions(orphansOnly = true)
        val suspendAttempted = suspendTargets.size
        if (!suspendOk) {
            ActionLog.warn("暂停残留清理失败：${suspend.output.take(300)}")
        }
        return TargetRunResult(
            ok = suspendOk && components.ok && hidden.ok &&
                restrictions.ok && missingFailed == 0,
            attempted = missingTargets.size + suspendAttempted +
                components.attempted + hidden.attempted +
                restrictions.attempted,
            applied = missingRestored + (if (suspendOk) suspendAttempted else 0) +
                components.applied + hidden.applied + restrictions.applied,
            failed = missingFailed + (if (suspendOk) 0 else 1) +
                components.failed + hidden.failed + restrictions.failed,
            reasons = buildList {
                if (!suspendOk) add(ResultReason.SUSPEND_RESIDUAL.at("暂停目标"))
                addAll(components.reasons)
                addAll(hidden.reasons)
                addAll(restrictions.reasons)
            }
        )
    }

    private fun MutableList<ResultNote>.addResult(
        subject: String,
        ok: Boolean,
        reasons: List<ResultNote>
    ) {
        if (reasons.isNotEmpty()) {
            addAll(reasons)
        } else if (!ok) {
            add(ResultReason.UNKNOWN.at(subject))
        }
    }
}

/**
 * True only when Nemu's applied marker and the stored strategy both say the
 * suspension is ours. Shutdown/residual unsuspends use this proof so a
 * suspension created by another tool is never revived.
 */
internal fun isOwnedSuspension(
    applied: Boolean,
    storedStrategy: FreezeStrategy
): Boolean = applied && storedStrategy == FreezeStrategy.SUSPEND
