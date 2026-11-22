package com.nemu.hibernate.privilege

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Reads the explicit component enabled state of the current Android user.
 * Returns the raw PackageManager value so a restore can reproduce the exact
 * previous state instead of assuming "default".
 */
interface ComponentStateReader {
    fun state(component: String): Int?
}

class AndroidComponentStateReader(context: Context) : ComponentStateReader {
    private val packageManager = context.packageManager

    override fun state(component: String): Int? = runCatching {
        val name = ComponentName.unflattenFromString(component) ?: return null
        packageManager.getComponentEnabledSetting(name)
    }.getOrNull()
}
