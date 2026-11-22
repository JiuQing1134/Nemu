package com.nemu.hibernate.privilege

import android.content.Context
import android.content.pm.PackageManager

fun interface PackageEnabledStateReader {
    /** Returns null when PackageManager cannot determine the user state. */
    fun isEnabled(packageName: String): Boolean?
}

class AndroidPackageEnabledStateReader(context: Context) : PackageEnabledStateReader {
    private val packageManager = context.applicationContext.packageManager

    override fun isEnabled(packageName: String): Boolean? = runCatching {
        when (packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> null
        }
    }.getOrNull()
}
