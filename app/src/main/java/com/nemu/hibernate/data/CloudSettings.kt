package com.nemu.hibernate.data

import android.content.Context

/**
 * Storage for cloud/telemetry targets. Unlike the other categories these keep
 * their historical `cloud_` key namespace so policies saved by older builds
 * still load; the unified [com.nemu.hibernate.policy.TargetPolicy] reads and
 * writes them through this facade.
 */
class CloudSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)

    fun isEnabled(packageName: String): Boolean =
        prefs.getBoolean("cloud_target_$packageName", false)

    fun setEnabled(packageName: String, enabled: Boolean) =
        prefs.edit()
            .putBoolean("cloud_target_$packageName", enabled)
            // A changed policy must be applied again before detection treats
            // an actual-state difference as a failed application.
            .putBoolean("cloud_applied_$packageName", false)
            .apply()

    fun isApplied(packageName: String): Boolean =
        prefs.getBoolean("cloud_applied_$packageName", false)

    fun setApplied(packageName: String, applied: Boolean): Boolean {
        // Written immediately before/after commands; keep it durable.
        return prefs.edit()
            .putBoolean("cloud_applied_$packageName", applied)
            .commit()
    }

    fun originalEnabled(packageName: String): Boolean? {
        val key = "cloud_original_enabled_$packageName"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setOriginalEnabled(packageName: String, enabled: Boolean?): Boolean {
        val key = "cloud_original_enabled_$packageName"
        val editor = prefs.edit()
        if (enabled == null) editor.remove(key)
        else editor.putBoolean(key, enabled)
        // Must survive a crash before pm disable-user.
        return editor.commit()
    }
}
