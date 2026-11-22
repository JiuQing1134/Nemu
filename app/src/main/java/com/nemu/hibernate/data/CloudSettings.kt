package com.nemu.hibernate.data

import android.content.Context

/**
 * Cloud-only preferences. Uses the same "nemu" file as [Settings] but a
 * distinct "cloud_" key namespace:
 *   cloud_target_ + package name, cloud_confirmed_ + package name, cloud_last_result
 * The global dry_run key is intentionally shared with the core module.
 */
class CloudSettings(context: Context) : CloudSettingsStore {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)
    private val base = Settings(context)

    override var dryRun: Boolean
        get() = base.dryRun
        set(value) {
            base.dryRun = value
        }

    override var lastResult: String?
        get() = prefs.getString("cloud_last_result", null)
        set(value) = prefs.edit().putString("cloud_last_result", value).apply()

    override fun isEnabled(packageName: String): Boolean =
        prefs.getBoolean("cloud_target_$packageName", false)

    override fun setEnabled(packageName: String, enabled: Boolean) =
        prefs.edit()
            .putBoolean("cloud_target_$packageName", enabled)
            // A changed policy must be applied again before detection treats
            // an actual-state difference as a failed application.
            .putBoolean("cloud_applied_$packageName", false)
            .apply()

    override fun isConfirmed(packageName: String): Boolean =
        prefs.getBoolean("cloud_confirmed_$packageName", false)

    override fun setConfirmed(packageName: String, confirmed: Boolean) =
        prefs.edit().putBoolean("cloud_confirmed_$packageName", confirmed).apply()

    override fun isApplied(packageName: String): Boolean =
        prefs.getBoolean("cloud_applied_$packageName", false)

    override fun setApplied(packageName: String, applied: Boolean) {
        // Written immediately before/after commands; keep it durable.
        prefs.edit().putBoolean("cloud_applied_$packageName", applied).commit()
    }

    override fun originalEnabled(packageName: String): Boolean? {
        val key = "cloud_original_enabled_$packageName"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    override fun setOriginalEnabled(packageName: String, enabled: Boolean?) {
        val key = "cloud_original_enabled_$packageName"
        val editor = prefs.edit()
        if (enabled == null) editor.remove(key)
        else editor.putBoolean(key, enabled)
        // Must survive a crash before pm disable-user.
        editor.commit()
    }
}
