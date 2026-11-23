package com.nemu.hibernate.data

import android.content.Context
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.StrategyOptionsCodec

/** One user-added target; risk is persisted so system apps keep their warning. */
data class CustomTargetRecord(
    val packageName: String,
    val label: String,
    val risk: RiskLevel = RiskLevel.YELLOW
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)

    var backend: String?
        get() = prefs.getString("backend", null)
        set(value) = prefs.edit().putString("backend", value).apply()

    var dryRun: Boolean
        get() = prefs.getBoolean("dry_run", DEFAULT_DRY_RUN)
        set(value) = prefs.edit().putBoolean("dry_run", value).apply()

    /**
     * One-time cleanup of states written by builds before the unified catalog
     * validated stored strategies. Kept false until a restore succeeds.
     */
    var policyMigrationDone: Boolean
        get() = prefs.getBoolean("migration_policy_v2", false)
        set(value) {
            prefs.edit().putBoolean("migration_policy_v2", value).commit()
        }

    var cotaEnabled: Boolean
        get() = prefs.getBoolean("cota_enabled", false)
        set(value) = prefs.edit().putBoolean("cota_enabled", value).apply()

    /** Nemu colour style: VIVID / TINTED / SOFT / MONOCHROME. */
    var themeStyle: String
        get() = prefs.getString("theme_style", "VIVID") ?: "VIVID"
        set(value) = prefs.edit().putString("theme_style", value).apply()

    /** Nemu palette name (BLUE/MAGENTA/ROSE/RED/ORANGE/YELLOW/GREEN/GRAPHITE). */
    var themePalette: String
        get() = prefs.getString("theme_palette", "BLUE") ?: "BLUE"
        set(value) = prefs.edit().putString("theme_palette", value).apply()

    /** SYSTEM / LIGHT / DARK. */
    var themeMode: String
        get() = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var lastBootResult: String?
        get() = prefs.getString("last_boot_result", null)
        set(value) {
            prefs.edit().putString("last_boot_result", value).commit()
        }

    var lastShutdownResult: String?
        get() = prefs.getString("last_shutdown_result", null)
        set(value) {
            prefs.edit().putString("last_shutdown_result", value).commit()
        }

    var lastShutdownDetail: String?
        get() = prefs.getString("last_shutdown_detail", null)
        set(value) {
            prefs.edit().putString("last_shutdown_detail", value).commit()
        }

    var lastShutdownTime: Long
        get() = prefs.getLong("last_shutdown_time", 0L)
        set(value) {
            prefs.edit().putLong("last_shutdown_time", value).commit()
        }

    var lastBootDetail: String?
        get() = prefs.getString("last_boot_detail", null)
        set(value) {
            prefs.edit().putString("last_boot_detail", value).commit()
        }

    var lastBootTime: Long
        get() = prefs.getLong("last_boot_time", 0L)
        set(value) {
            prefs.edit().putLong("last_boot_time", value).commit()
        }

    fun isEnabled(packageName: String): Boolean =
        prefs.getBoolean("target_$packageName", false)

    fun setEnabled(packageName: String, enabled: Boolean) =
        prefs.edit().putBoolean("target_$packageName", enabled).apply()

    fun strategy(packageName: String, default: FreezeStrategy): FreezeStrategy =
        runCatching {
            FreezeStrategy.valueOf(
                prefs.getString("strategy_$packageName", default.name) ?: default.name
            )
        }.getOrDefault(default)

    /**
     * User-added targets: "packageName\tlabel\trisk". The third field is
     * optional for entries written by older builds; those default to YELLOW.
     */
    fun customTargets(): List<CustomTargetRecord> =
        prefs.getStringSet("custom_targets", emptySet()).orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split('\t', limit = 3)
                val pkg = parts.firstOrNull()?.trim().orEmpty()
                if (pkg.isBlank()) {
                    null
                } else {
                    val risk = parts.getOrNull(2)
                        ?.let { name ->
                            runCatching { RiskLevel.valueOf(name) }.getOrNull()
                        }
                        ?: RiskLevel.YELLOW
                    CustomTargetRecord(
                        packageName = pkg,
                        label = parts.getOrNull(1) ?: pkg,
                        risk = risk
                    )
                }
            }
            .sortedBy { it.label.lowercase() }

    fun addCustomTarget(
        packageName: String,
        label: String,
        risk: RiskLevel = RiskLevel.YELLOW
    ) {
        val entry = "$packageName\t$label\t${risk.name}"
        val next = prefs.getStringSet("custom_targets", emptySet()).orEmpty()
            .filterNot { it.substringBefore('\t') == packageName }
            .toSet() + entry
        prefs.edit().putStringSet("custom_targets", next).apply()
    }

    fun removeCustomTarget(packageName: String) {
        val next = prefs.getStringSet("custom_targets", emptySet()).orEmpty()
            .filterNot { it.substringBefore('\t') == packageName }
            .toSet()
        prefs.edit().putStringSet("custom_targets", next).apply()
    }

    fun setStrategy(packageName: String, strategy: FreezeStrategy) =
        prefs.edit().putString("strategy_$packageName", strategy.name).apply()

    /** Raw versioned options; null means the target never stored options. */
    fun strategyOptionsRaw(packageName: String): String? =
        prefs.getString("strategy_opts_$packageName", null)

    fun setStrategyOptions(packageName: String, options: StrategyOptions): Boolean =
        prefs.edit()
            .putString("strategy_opts_$packageName", StrategyOptionsCodec.encode(options))
            .commit()

    fun originalAppOps(packageName: String): String? {
        val key = "strategy_appops_original_$packageName"
        return if (prefs.contains(key)) prefs.getString(key, null) else null
    }

    fun setOriginalAppOps(packageName: String, mode: String?): Boolean {
        val key = "strategy_appops_original_$packageName"
        val editor = prefs.edit()
        if (mode == null) editor.remove(key) else editor.putString(key, mode)
        return editor.commit()
    }

    fun appOpsOwned(packageName: String): Boolean =
        prefs.getBoolean("strategy_appops_owned_$packageName", false)

    fun setAppOpsOwned(packageName: String, owned: Boolean): Boolean {
        val key = "strategy_appops_owned_$packageName"
        val editor = prefs.edit()
        if (owned) editor.putBoolean(key, true) else editor.remove(key)
        return editor.commit()
    }

    fun originalStandby(packageName: String): Int? {
        val key = "strategy_standby_original_$packageName"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun setOriginalStandby(packageName: String, bucket: Int?): Boolean {
        val key = "strategy_standby_original_$packageName"
        val editor = prefs.edit()
        if (bucket == null) editor.remove(key) else editor.putInt(key, bucket)
        return editor.commit()
    }

    fun standbyOwned(packageName: String): Boolean =
        prefs.getBoolean("strategy_standby_owned_$packageName", false)

    fun setStandbyOwned(packageName: String, owned: Boolean): Boolean {
        val key = "strategy_standby_owned_$packageName"
        val editor = prefs.edit()
        if (owned) editor.putBoolean(key, true) else editor.remove(key)
        return editor.commit()
    }

    fun originalEnabled(packageName: String): Boolean? {
        val key = "strategy_original_enabled_$packageName"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setOriginalEnabled(packageName: String, enabled: Boolean?): Boolean {
        val key = "strategy_original_enabled_$packageName"
        val editor = prefs.edit()
        if (enabled == null) editor.remove(key) else editor.putBoolean(key, enabled)
        // Synchronous: the baseline must survive a crash before the command.
        return editor.commit()
    }

    /**
     * Hidden state observed before Nemu's first `pm hide`. Null means
     * Nemu has no baseline and must not unhide the package.
     */
    fun originalHidden(packageName: String): Boolean? {
        val key = "strategy_original_hidden_$packageName"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setOriginalHidden(packageName: String, hidden: Boolean?): Boolean {
        val key = "strategy_original_hidden_$packageName"
        val editor = prefs.edit()
        if (hidden == null) editor.remove(key) else editor.putBoolean(key, hidden)
        // Synchronous: false means the baseline did not reach disk and HIDE
        // must not touch the package.
        return editor.commit()
    }

    /** True only after Nemu itself flipped hidden false -> true. */
    fun hiddenOwned(packageName: String): Boolean =
        prefs.getBoolean("strategy_hidden_owned_$packageName", false)

    fun setHiddenOwned(packageName: String, owned: Boolean): Boolean {
        val key = "strategy_hidden_owned_$packageName"
        val editor = prefs.edit()
        if (owned) editor.putBoolean(key, true) else editor.remove(key)
        return editor.commit()
    }

    /**
     * True after the current strategy was executed successfully and before it
     * is restored. Persisted before any command so a crash cannot lose the
     * fact that Nemu owns the state.
     */
    fun isStrategyApplied(packageName: String): Boolean =
        prefs.getBoolean("strategy_applied_$packageName", false)

    fun setStrategyApplied(packageName: String, applied: Boolean) =
        prefs.edit().putBoolean("strategy_applied_$packageName", applied).commit()

    /**
     * Original [android.content.pm.PackageManager] component enabled state
     * (0=default, 1=enabled, 2=disabled, 3=disabled-user, 4=disabled-until-used)
     * captured before Nemu disables a component. Null means Nemu does not
     * own that component.
     */
    fun componentOriginalState(packageName: String, className: String): Int? {
        val key = componentKey(packageName, className)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    fun setComponentOriginalState(
        packageName: String,
        className: String,
        state: Int?
    ): Boolean {
        val key = componentKey(packageName, className)
        val editor = prefs.edit()
        if (state == null) editor.remove(key) else editor.putInt(key, state)
        // Synchronous: capture the exact baseline before `pm disable`.
        return editor.commit()
    }

    fun ownedComponents(packageName: String): List<String> =
        ComponentPreferenceKeys.ownedNames(prefs.all.keys, packageName)

    fun setComponentOwned(
        packageName: String,
        className: String,
        owned: Boolean
    ): Boolean {
        val key = "strategy_component_owned_${packageName}_$className"
        val editor = prefs.edit()
        if (owned) editor.putBoolean(key, true) else editor.remove(key)
        return editor.commit()
    }

    private fun componentKey(packageName: String, className: String): String =
        "strategy_component_original_${packageName}_$className"

    /**
     * Seeds `dry_run` once. Builds before 2026-11-23 defaulted to false but
     * never persisted it; a missing key on an existing install must keep the
     * old real-execution behaviour, while a fresh install stays in preview.
     */
    fun ensureDryRunDefault() {
        val seed = dryRunForUpgrade(
            hasDryRunKey = prefs.contains("dry_run"),
            hasOtherKeys = prefs.all.keys.any { it != "dry_run" }
        ) ?: return
        prefs.edit().putBoolean("dry_run", seed).commit()
    }

    companion object {
        /** Safe first-install default: a new user cannot modify the phone by accident. */
        const val DEFAULT_DRY_RUN = true

        /** null = keep existing key; false = upgraded install; true = fresh. */
        internal fun dryRunForUpgrade(
            hasDryRunKey: Boolean,
            hasOtherKeys: Boolean
        ): Boolean? = when {
            hasDryRunKey -> null
            hasOtherKeys -> false
            else -> true
        }
    }
}
