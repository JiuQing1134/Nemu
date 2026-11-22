package com.nemu.hibernate.data

import android.content.Context
import com.nemu.hibernate.core.FreezeStrategy

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)

    var backend: String?
        get() = prefs.getString("backend", null)
        set(value) = prefs.edit().putString("backend", value).apply()

    var dryRun: Boolean
        get() = prefs.getBoolean("dry_run", false)
        set(value) = prefs.edit().putBoolean("dry_run", value).apply()

    var cotaEnabled: Boolean
        get() = prefs.getBoolean("cota_enabled", false)
        set(value) = prefs.edit().putBoolean("cota_enabled", value).apply()

    /** Material3 / M3 Expressive 2025 / MIUIX-style appearance. */
    var themeStyle: String
        get() = prefs.getString("theme_style", "M3E2025") ?: "M3E2025"
        set(value) = prefs.edit().putString("theme_style", value).apply()

    /** Color palette name (ICE/MINT/PEACH/LAVENDER/GRAPHITE). */
    var themePalette: String
        get() = prefs.getString("theme_palette", "MINT") ?: "MINT"
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
     * User-added targets: "packageName\tlabel". Custom packages are resolved
     * into [com.nemu.hibernate.core.TargetCatalog] at process start.
     */
    fun customTargets(): List<Pair<String, String>> =
        prefs.getStringSet("custom_targets", emptySet()).orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split('\t', limit = 2)
                val pkg = parts.firstOrNull()?.trim().orEmpty()
                if (pkg.isBlank()) null else pkg to (parts.getOrNull(1) ?: pkg)
            }
            .sortedBy { it.second.lowercase() }

    fun addCustomTarget(packageName: String, label: String) {
        val entry = "$packageName\t$label"
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

    fun originalEnabled(packageName: String): Boolean? {
        val key = "strategy_original_enabled_$packageName"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setOriginalEnabled(packageName: String, enabled: Boolean?) {
        val key = "strategy_original_enabled_$packageName"
        val editor = prefs.edit()
        if (enabled == null) editor.remove(key) else editor.putBoolean(key, enabled)
        // Synchronous: the baseline must survive a crash before the command.
        editor.commit()
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
    ) {
        val key = componentKey(packageName, className)
        val editor = prefs.edit()
        if (state == null) editor.remove(key) else editor.putInt(key, state)
        // Synchronous: capture the exact baseline before `pm disable`.
        editor.commit()
    }

    fun ownedComponents(packageName: String): List<String> {
        val prefix = "strategy_component_owned_${packageName}_"
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }

    fun setComponentOwned(packageName: String, className: String, owned: Boolean) {
        val key = "strategy_component_owned_${packageName}_$className"
        val editor = prefs.edit()
        if (owned) editor.putBoolean(key, true) else editor.remove(key)
        editor.commit()
    }

    private fun componentKey(packageName: String, className: String): String =
        "strategy_component_original_${packageName}_$className"
}
