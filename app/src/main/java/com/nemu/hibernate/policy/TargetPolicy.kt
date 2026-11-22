package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.NumberIdentityMode
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.data.AntiFraudSettings
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.CloudSettings
import com.nemu.hibernate.data.Settings

/**
 * Single read/write facade for the per-target policy. It deliberately keeps
 * the legacy preference namespaces so the existing detection, cloud section
 * and anti-fraud controls stay consistent with the unified catalog:
 *
 * - AI deep-freeze packages: `ai_deep_freeze_enabled` + `ai_target_<pkg>`;
 * - cloud/telemetry packages: `cloud_target_<pkg>` (+ confirmation/applied);
 * - number identity: `antifraud_numberidentity_mode`;
 * - everything else (update, core AI leaves, thermal components): `target_<pkg>`.
 *
 * `strategy_<pkg>` / `strategy_applied_<pkg>` are shared by every category.
 */
class TargetPolicy(context: Context) {
    val settings = Settings(context)
    private val bootSettings = BootFreezeSettings(context)
    private val cloudSettings = CloudSettings(context)
    private val antiFraudSettings = AntiFraudSettings(context)

    fun isEnabled(target: Target): Boolean {
        if (target.supportedStrategies == setOf(FreezeStrategy.OBSERVE)) {
            return false
        }
        return when {
            target.packageName == NUMBER_IDENTITY ->
                antiFraudSettings.numberIdentityMode != NumberIdentityMode.OFF
            target.packageName in AI_DEEP_FREEZE_PACKAGES ->
                bootSettings.aiDeepFreezeEnabled &&
                    bootSettings.isAiTargetEnabled(target.packageName)
            target.category == TargetCategory.CLOUD ->
                cloudSettings.isEnabled(target.packageName)
            else -> settings.isEnabled(target.packageName)
        }
    }

    /** Enables/disables a target and mirrors the legacy per-category store. */
    fun setEnabled(target: Target, enabled: Boolean) {
        when {
            target.packageName == NUMBER_IDENTITY -> {
                val wasDisableUser = antiFraudSettings.numberIdentityMode ==
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
                antiFraudSettings.numberIdentityMode = if (enabled) {
                    when (strategy(target)) {
                        FreezeStrategy.DISABLE_USER -> NumberIdentityMode.DISABLE_USER
                        FreezeStrategy.SUSPEND -> NumberIdentityMode.SUSPEND
                        else -> NumberIdentityMode.SUSPEND
                    }
                } else {
                    NumberIdentityMode.OFF
                }
            }
            target.packageName in AI_DEEP_FREEZE_PACKAGES -> {
                if (enabled) bootSettings.aiDeepFreezeEnabled = true
                bootSettings.setAiTargetEnabled(target.packageName, enabled)
            }
            target.category == TargetCategory.CLOUD -> {
                // setEnabled clears the legacy applied flag; preserve it when
                // the cloud package is still actually frozen, otherwise the
                // Unfreeze page would lose a disable-user target it must own.
                val wasApplied = cloudSettings.isApplied(target.packageName)
                cloudSettings.setEnabled(target.packageName, enabled)
                if (wasApplied) {
                    // Toggling the policy does not execute a restore in either
                    // direction; keep ownership until an explicit restore.
                    cloudSettings.setApplied(target.packageName, true)
                }
                cloudSettings.setConfirmed(
                    target.packageName,
                    enabled && strategy(target) == FreezeStrategy.DISABLE_USER
                )
            }
            else -> settings.setEnabled(target.packageName, enabled)
        }
    }

    fun strategy(target: Target): FreezeStrategy {
        val stored = settings.strategy(target.packageName, target.defaultStrategy)
        val supported = target.supportedStrategies
        // Legacy anti-fraud control may own numberidentity without having
        // written `strategy_<pkg>`. Its current mode wins while it is active,
        // so an advanced-control switch from DISABLE_USER to SUSPEND is not
        // shadowed by a stale catalog strategy.
        if (target.packageName == NUMBER_IDENTITY) {
            val legacy = when (antiFraudSettings.numberIdentityMode) {
                NumberIdentityMode.DISABLE_USER -> FreezeStrategy.DISABLE_USER
                NumberIdentityMode.SUSPEND -> FreezeStrategy.SUSPEND
                NumberIdentityMode.OFF -> null
            }
            if (legacy != null && FreezeStrategyPolicy.isAllowed(target, legacy)) {
                return legacy
            }
        }
        val valid = supported.filter {
            FreezeStrategyPolicy.validate(target, it) == null
        }
        return when {
            stored in valid -> stored
            target.defaultStrategy in valid -> target.defaultStrategy
            else -> valid.firstOrNull() ?: FreezeStrategy.OBSERVE
        }
    }

    fun setStrategy(target: Target, strategy: FreezeStrategy) {
        if (!FreezeStrategyPolicy.isAllowed(target, strategy)) return
        settings.setStrategy(target.packageName, strategy)
        when {
            target.packageName == NUMBER_IDENTITY -> {
                val mode = when (strategy) {
                    FreezeStrategy.DISABLE_USER -> NumberIdentityMode.DISABLE_USER
                    FreezeStrategy.SUSPEND -> NumberIdentityMode.SUSPEND
                    else -> NumberIdentityMode.OFF
                }
                if (!isEnabled(target)) {
                    // Keep OFF when the target is not selected; the strategy
                    // is remembered for the next enable.
                    antiFraudSettings.numberIdentityMode = NumberIdentityMode.OFF
                } else {
                    antiFraudSettings.numberIdentityMode = mode
                }
            }
            target.category == TargetCategory.CLOUD -> {
                cloudSettings.setConfirmed(
                    target.packageName,
                    isEnabled(target) && strategy == FreezeStrategy.DISABLE_USER
                )
            }
        }
    }

    fun originalEnabled(target: Target): Boolean? =
        settings.originalEnabled(target.packageName)
            ?: if (target.packageName == NUMBER_IDENTITY) {
                antiFraudSettings.numberIdentityOriginalEnabled
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

    fun setOriginalEnabled(target: Target, enabled: Boolean?) {
        settings.setOriginalEnabled(target.packageName, enabled)
        if (target.packageName == NUMBER_IDENTITY) {
            antiFraudSettings.numberIdentityOriginalEnabled = enabled
        }
        if (target.category == TargetCategory.CLOUD) {
            cloudSettings.setOriginalEnabled(target.packageName, enabled)
        }
    }

    /**
     * True when Nemu has applied (or still owns) this target. Legacy cloud
     * applied flags and anti-fraud mode are included so a restore offered by
     * the Unfreeze page never misses a state written by the old controls.
     */
    fun isApplied(target: Target): Boolean =
        settings.isStrategyApplied(target.packageName) ||
            settings.ownedComponents(target.packageName).isNotEmpty() ||
            (target.category == TargetCategory.CLOUD &&
                cloudSettings.isApplied(target.packageName))
    // Number identity deliberately has no policy-mode fallback here: merely
    // checking the policy checkbox must not put it on the Unfreeze page.
    // A legacy applied state still shows via its actual suspended/disabled
    // state (CatalogTargetUiState.hasFrozenState).

    fun setApplied(target: Target, applied: Boolean) {
        settings.setStrategyApplied(target.packageName, applied)
        if (target.category == TargetCategory.CLOUD) {
            cloudSettings.setApplied(target.packageName, applied)
        }
    }

    /**
     * One-time/launch-time normalization: a stored strategy that is no longer
     * supported (e.g. powergenie COMPONENT before it became OBSERVE-only) is
     * rewritten to the effective value so the persisted state matches the UI
     * and the next boot uses the same strategy.
     */
    fun normalizeStrategies() {
        TargetCatalog.all.forEach { target ->
            val stored = settings.strategy(target.packageName, target.defaultStrategy)
            val effective = strategy(target)
            if (stored != effective) {
                settings.setStrategy(target.packageName, effective)
            }
        }
    }

    fun componentOriginalState(target: Target, className: String): Int? =
        settings.componentOriginalState(target.packageName, className)

    fun setComponentOriginalState(target: Target, className: String, state: Int?) {
        settings.setComponentOriginalState(target.packageName, className, state)
        settings.setComponentOwned(target.packageName, className, state != null)
    }

    fun hasComponentOwnership(target: Target): Boolean =
        settings.ownedComponents(target.packageName).isNotEmpty()

    companion object {
        const val NUMBER_IDENTITY = "com.hihonor.numberidentity"
        private val AI_DEEP_FREEZE_PACKAGES = AiTargets.all.map { it.packageName }.toSet()
    }
}
