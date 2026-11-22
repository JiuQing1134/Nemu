package com.nemu.hibernate.privilege

import android.content.Context
import android.content.pm.PackageManager
import com.nemu.hibernate.core.CloudStatus

interface PackageStatusReader {
    fun read(packageName: String): CloudStatus?
}

/**
 * Read-only package state. `getApplicationEnabledSetting` reports the
 * per-user enabled state (disabled-user is COMPONENT_ENABLED_STATE_DISABLED_USER),
 * and `isPackageSuspended` reports the suspend flag. Both are public APIs.
 * Package visibility is covered by QUERY_ALL_PACKAGES and the manifest queries.
 */
class AndroidPackageStatusReader(private val context: Context) : PackageStatusReader {
    override fun read(packageName: String): CloudStatus? = runCatching {
        val pm = context.packageManager
        val setting = pm.getApplicationEnabledSetting(packageName)
        val enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val suspended = pm.isPackageSuspended(packageName)
        CloudStatus(enabled = enabled, suspended = suspended)
    }.getOrNull()
}
