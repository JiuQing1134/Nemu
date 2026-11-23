package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.AppliedOwnership
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.NumberIdentityMode
import com.nemu.hibernate.core.StoredOptions
import com.nemu.hibernate.core.StrategyExecution
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.StrategyOptionsCodec
import com.nemu.hibernate.core.StrategyOptionsResolver
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.core.TargetComponent
import com.nemu.hibernate.data.NumberIdentitySettings
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.CloudSettings
import com.nemu.hibernate.data.Settings

/** Serializes the read-modify-write of the encoded strategy options. */
private val OPTIONS_WRITE_LOCK = Any()

/**
 * Single read/write facade for the per-target policy. Category stores keep
 * the preference keys written by older builds so saved policies still load:
 *
 * - AI targets: per-target `ai_target_<pkg>`;
 * - cloud/telemetry targets: `cloud_target_<pkg>` (+ applied/original state);
 * - number identity: `antifraud_numberidentity_mode`;
 * - everything else: `target_<pkg>`.
 *
 * `strategy_<pkg>` / `strategy_applied_<pkg>` / options are shared by every
 * category, and detection reads the same keys.
 */
class TargetPolicy(context: Context) {
    val settings = Settings(context)
    private val bootSettings = BootFreezeSettings(context)
    private val cloudSettings = CloudSettings(context)
    private val numberIdentitySettings = NumberIdentitySettings(context)

    fun isEnabled(target: Target): Boolean {
        return when {
            target.packageName == NUMBER_IDENTITY ->
                numberIdentitySettings.numberIdentityMode != NumberIdentityMode.OFF
            target.packageName in AI_DEEP_FREEZE_PACKAGES ->
                // Per-target flag is the source of truth; the old global
                // "AI 深度冻结" switch is no longer exposed in the UI.
                bootSettings.isAiTargetEnabled(target.packageName)
            target.category == TargetCategory.CLOUD ->
                cloudSettings.isEnabled(target.packageName)
            else -> settings.isEnabled(target.packageName)
        }
    }

    /** Enables/disables a target and mirrors its category store. */
    fun setEnabled(target: Target, enabled: Boolean) {
        when {
            target.packageName == NUMBER_IDENTITY -> {
                val wasDisableUser = numberIdentitySettings.numberIdentityMode ==
                    NumberIdentityMode.DISABLE_USER
                if (!enabled && wasDisableUser) {
                    // Unchecking the policy does not restore the package. Keep
                    // DISABLE_USER as the stored strategy so the Unfreeze page
                    // can still see it via the actual disabled state; do NOT
                    // mark it applied, or a successful restore would be
                    // immediately re-marked by this call.
                    settings.setStrategy(
                        target.packageName,
                        FreezeStrategy.DISABLE_USER
                    )
                }
                numberIdentitySettings.numberIdentityMode = if (enabled) {
                    NumberIdentityMode.fromStrategy(strategy(target))
                        .takeUnless { it == NumberIdentityMode.OFF }
                        ?: NumberIdentityMode.SUSPEND
                } else {
                    NumberIdentityMode.OFF
                }
            }
            target.packageName in AI_DEEP_FREEZE_PACKAGES -> {
                bootSettings.setAiTargetEnabled(target.packageName, enabled)
            }
            target.category == TargetCategory.CLOUD -> {
                // setEnabled clears the applied flag; preserve it when
                // the cloud package is still actually frozen, otherwise the
                // Unfreeze page would lose a disable-user target it must own.
                val wasApplied = cloudSettings.isApplied(target.packageName)
                cloudSettings.setEnabled(target.packageName, enabled)
                if (wasApplied) {
                    // Toggling the policy does not execute a restore in either
                    // direction; keep ownership until an explicit restore.
                    cloudSettings.setApplied(target.packageName, true)
                }
            }
            else -> settings.setEnabled(target.packageName, enabled)
        }
    }

    fun strategy(target: Target): FreezeStrategy {
        val stored = settings.strategy(target.packageName, target.defaultStrategy)
        // Older builds stored numberidentity's mode only in
        // `antifraud_numberidentity_mode`. While that mode is active it wins,
        // so a switch from DISABLE_USER to SUSPEND is not shadowed by a stale
        // catalog strategy.
        if (target.packageName == NUMBER_IDENTITY) {
            val legacy = numberIdentitySettings.numberIdentityMode.toStrategy()
            if (legacy != null && FreezeStrategyPolicy.isAllowed(target, legacy)) {
                return legacy
            }
        }
        return when {
            FreezeStrategyPolicy.validate(target, stored) == null -> stored
            FreezeStrategyPolicy.validate(target, target.defaultStrategy) == null ->
                target.defaultStrategy
            else -> FreezeStrategy.OBSERVE
        }
    }

    fun preset(target: Target): StrategyPreset =
        options(target)?.presetOf(strategy(target)) ?: StrategyPreset.OBSERVE

    /**
     * Persists the UI preset. PAUSE/FREEZE share the SUSPEND strategy and
     * differ only by forceStop; other presets map one-to-one.
     *
     * A whole-package preset clears the restriction flags: otherwise switching
     * from "限制后台" to "观察" would leave appOps=true and the card would snap
     * back to RESTRICT_BACKGROUND. The two restriction presets are mutually
     * exclusive for the same reason.
     */
    fun setPreset(target: Target, preset: StrategyPreset) =
        synchronized(OPTIONS_WRITE_LOCK) {
        val current = options(target) ?: StrategyOptions()
        val plain = current.copy(appOps = false, standby = false)
        when (preset) {
            StrategyPreset.OBSERVE -> {
                setStrategy(target, FreezeStrategy.OBSERVE)
                // Pure observe has no executable plan. Keeping enabled=true
                // would show a plan switch on while it could never run.
                setEnabled(target, false)
                settings.setStrategyOptions(
                    target.packageName,
                    plain.copy(forceStop = true)
                )
            }
            StrategyPreset.PAUSE -> {
                setStrategy(target, FreezeStrategy.SUSPEND)
                settings.setStrategyOptions(
                    target.packageName,
                    plain.copy(forceStop = false)
                )
            }
            StrategyPreset.FREEZE -> {
                setStrategy(target, FreezeStrategy.SUSPEND)
                settings.setStrategyOptions(
                    target.packageName,
                    plain.copy(forceStop = true)
                )
            }
            StrategyPreset.COMPONENT -> {
                setStrategy(target, FreezeStrategy.COMPONENT)
                settings.setStrategyOptions(
                    target.packageName,
                    plain.copy(
                        components = current.components
                            ?.takeIf { it.isNotEmpty() }
                            ?: target.components.map { it.className }.toSet()
                    )
                )
            }
            StrategyPreset.DISABLE_USER -> {
                setStrategy(target, FreezeStrategy.DISABLE_USER)
                settings.setStrategyOptions(target.packageName, plain)
            }
            StrategyPreset.HIDE -> {
                setStrategy(target, FreezeStrategy.HIDE)
                settings.setStrategyOptions(target.packageName, plain)
            }
            StrategyPreset.RESTRICT_BACKGROUND -> {
                setStrategy(target, FreezeStrategy.OBSERVE)
                settings.setStrategyOptions(
                    target.packageName,
                    current.copy(appOps = true, standby = false)
                )
            }
            StrategyPreset.RESTRICT_STANDBY -> {
                setStrategy(target, FreezeStrategy.OBSERVE)
                settings.setStrategyOptions(
                    target.packageName,
                    current.copy(appOps = false, standby = true)
                )
            }
        }
        }

    fun setStrategy(target: Target, strategy: FreezeStrategy) {
        if (!FreezeStrategyPolicy.isAllowed(target, strategy)) return
        settings.setStrategy(target.packageName, strategy)
        when {
            target.packageName == NUMBER_IDENTITY -> {
                val mode = NumberIdentityMode.fromStrategy(strategy)
                if (!isEnabled(target)) {
                    // Keep OFF when the target is not selected; the strategy
                    // is remembered for the next enable.
                    numberIdentitySettings.numberIdentityMode = NumberIdentityMode.OFF
                } else {
                    numberIdentitySettings.numberIdentityMode = mode
                }
            }
        }
    }

    /**
     * Strategy actually stored on disk. Restore uses this instead of [strategy]
     * so retiring a capability cannot strand an already-applied state.
     */
    fun storedStrategy(target: Target): FreezeStrategy =
        settings.strategy(target.packageName, target.defaultStrategy)

    /**
     * Parsed options, or null when a COMPONENT target has corrupt/unsupported
     * options. Callers must fail closed on null; never treat it as "all".
     */
    fun options(target: Target): StrategyOptions? {
        val raw = settings.strategyOptionsRaw(target.packageName)
        val stored = when {
            raw == null -> StoredOptions.Missing
            else -> StrategyOptionsCodec.decode(raw)
                ?.let { StoredOptions.Parsed(it) }
                ?: StoredOptions.Invalid(raw)
        }
        return StrategyOptionsResolver.resolve(target, strategy(target), stored)
    }

    /** True when the target participates in apply/boot planning. */
    fun isActive(target: Target): Boolean {
        val options = options(target) ?: return true
        return StrategyExecution.isActive(strategy(target), options)
    }

    /** Components this apply may touch; empty means fail closed. */
    fun effectiveComponents(target: Target): List<TargetComponent> {
        val options = options(target) ?: return emptyList()
        return StrategyExecution.effectiveComponents(
            target,
            strategy(target),
            options
        )
    }

    fun selectedComponents(target: Target): Set<String> =
        options(target)?.components
            ?: target.components.mapTo(mutableSetOf()) { it.className }

    /**
     * Persists the component checkboxes. Unknown class names are dropped;
     * an empty selection is refused while COMPONENT is active so a failed
     * dialog cannot expand to the full list.
     */
    fun setComponentSelection(target: Target, selected: Set<String>): Boolean =
        synchronized(OPTIONS_WRITE_LOCK) {
            val allowed = target.components.mapTo(mutableSetOf()) { it.className }
            val filtered = selected.filterTo(mutableSetOf()) { it in allowed }
            if (strategy(target) == FreezeStrategy.COMPONENT &&
                filtered.isEmpty()
            ) {
                return@synchronized false
            }
            val current = options(target) ?: StrategyOptions()
            settings.setStrategyOptions(
                target.packageName,
                current.copy(components = filtered)
            )
        }

    fun originalAppOps(target: Target): String? =
        settings.originalAppOps(target.packageName)

    fun setOriginalAppOps(target: Target, mode: String?): Boolean =
        settings.setOriginalAppOps(target.packageName, mode)

    fun ownsAppOps(target: Target): Boolean =
        settings.appOpsOwned(target.packageName) &&
            settings.originalAppOps(target.packageName) != null

    fun setAppOpsOwned(target: Target, owned: Boolean): Boolean =
        settings.setAppOpsOwned(target.packageName, owned)

    fun originalStandby(target: Target): Int? =
        settings.originalStandby(target.packageName)

    fun setOriginalStandby(target: Target, bucket: Int?): Boolean =
        settings.setOriginalStandby(target.packageName, bucket)

    fun ownsStandby(target: Target): Boolean =
        settings.standbyOwned(target.packageName) &&
            settings.originalStandby(target.packageName) != null

    fun setStandbyOwned(target: Target, owned: Boolean): Boolean =
        settings.setStandbyOwned(target.packageName, owned)

    /** Records which backend actually applied this target. */
    fun recordBackend(target: Target, backendName: String) =
        synchronized(OPTIONS_WRITE_LOCK) {
            val current = options(target) ?: StrategyOptions()
            settings.setStrategyOptions(
                target.packageName,
                current.copy(backend = backendName)
            )
        }

    fun recordedBackend(target: Target): String? = options(target)?.backend

    fun originalEnabled(target: Target): Boolean? =
        settings.originalEnabled(target.packageName)
            ?: if (target.packageName == NUMBER_IDENTITY) {
                numberIdentitySettings.numberIdentityOriginalEnabled
            } else {
                null
            }
            ?: if (target.category == TargetCategory.CLOUD) {
                cloudSettings.originalEnabled(target.packageName)
            } else {
                null
            }

    /** Hidden baseline/ownership; only HIDE uses these. */
    fun originalHidden(target: Target): Boolean? =
        settings.originalHidden(target.packageName)

    fun setOriginalHidden(target: Target, hidden: Boolean?): Boolean {
        val baseline = settings.setOriginalHidden(target.packageName, hidden)
        val owned = if (hidden == false) {
            settings.setHiddenOwned(target.packageName, true)
        } else {
            settings.setHiddenOwned(target.packageName, false)
        }
        return baseline && owned
    }

    fun ownsHidden(target: Target): Boolean =
        settings.hiddenOwned(target.packageName) &&
            settings.originalHidden(target.packageName) == false

    fun setOriginalEnabled(target: Target, enabled: Boolean?): Boolean {
        var ok = settings.setOriginalEnabled(target.packageName, enabled)
        if (target.packageName == NUMBER_IDENTITY) {
            ok = numberIdentitySettings.setNumberIdentityOriginalEnabled(enabled) && ok
        }
        if (target.category == TargetCategory.CLOUD) {
            ok = cloudSettings.setOriginalEnabled(target.packageName, enabled) && ok
        }
        return ok
    }

    /**
     * True when Nemu has applied (or still owns) this target. The cloud
     * applied flag is included so a restore offered by the Unfreeze page
     * never misses a state written by an older build.
     */
    fun isApplied(target: Target): Boolean =
        AppliedOwnership.isApplied(
            applied = settings.isStrategyApplied(target.packageName),
            ownsComponents = settings.ownedComponents(target.packageName).isNotEmpty(),
            ownsAppOps = ownsAppOps(target),
            ownsStandby = ownsStandby(target),
            ownsHidden = ownsHidden(target),
            hasOriginalEnabled = originalEnabled(target) != null,
            legacyCloudApplied = target.category == TargetCategory.CLOUD &&
                cloudSettings.isApplied(target.packageName)
        )
    // Number identity deliberately has no policy-mode fallback here: merely
    // checking the policy checkbox must not put it on the Unfreeze page.
    // A legacy applied state still shows via its actual suspended/disabled
    // state (CatalogTargetUiState.hasFrozenState).

    fun setApplied(target: Target, applied: Boolean): Boolean {
        var ok = settings.setStrategyApplied(target.packageName, applied)
        if (target.category == TargetCategory.CLOUD) {
            ok = cloudSettings.setApplied(target.packageName, applied) && ok
        }
        return ok
    }

    /**
     * Launch-time option materialization. A stored COMPONENT strategy without
     * options (written by an older build) gets the currently registered
     * components once, so apply and restore agree on the same set.
     */
    fun normalizeStrategies() {
        TargetCatalog.all.forEach { target ->
            // Materialize legacy COMPONENT selections once. Do not rewrite a
            // stored strategy that a newer build no longer supports: restore
            // still needs the original value to undo an old applied state.
            materializeOptions(target)
        }
    }

    private fun materializeOptions(target: Target) =
        synchronized(OPTIONS_WRITE_LOCK) {
            if (strategy(target) != FreezeStrategy.COMPONENT) {
                return@synchronized
            }
            if (settings.strategyOptionsRaw(target.packageName) != null) {
                return@synchronized
            }
            settings.setStrategyOptions(
                target.packageName,
                StrategyOptions(
                    components = target.components.map { it.className }.toSet()
                )
            )
        }

    fun componentOriginalState(target: Target, className: String): Int? =
        settings.componentOriginalState(target.packageName, className)

    fun setComponentOriginalState(
        target: Target,
        className: String,
        state: Int?
    ): Boolean {
        val baseline = settings.setComponentOriginalState(
            target.packageName,
            className,
            state
        )
        val owned = settings.setComponentOwned(
            target.packageName,
            className,
            state != null
        )
        if (!baseline || !owned) {
            // Never leave a half-written baseline that looks like ownership
            // but cannot be restored with the exact original state.
            if (!owned) {
                settings.setComponentOriginalState(
                    target.packageName,
                    className,
                    null
                )
            }
            if (!baseline) {
                settings.setComponentOwned(
                    target.packageName,
                    className,
                    false
                )
            }
        }
        return baseline && owned
    }

    fun ownedComponentNames(target: Target): List<String> =
        settings.ownedComponents(target.packageName)

    fun hasComponentOwnership(target: Target): Boolean =
        ownedComponentNames(target).isNotEmpty()

    companion object {
        const val NUMBER_IDENTITY = "com.hihonor.numberidentity"
        private val AI_DEEP_FREEZE_PACKAGES = AiTargets.all.map { it.packageName }.toSet()
    }
}
