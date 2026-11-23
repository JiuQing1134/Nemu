package com.nemu.hibernate.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethod
import android.view.inputmethod.InputMethodManager

/** Reads installed home/input-method packages from the platform. */
object CriticalRoleResolver {
    fun roles(context: Context): Map<String, String> {
        val packageManager = context.packageManager
        val home = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())

        val ime = runCatching {
            val enabled = (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager)
                ?.enabledInputMethodList
                ?.map { it.packageName }
                .orEmpty()
            val services = packageManager.queryIntentServices(
                Intent(InputMethod.SERVICE_INTERFACE),
                PackageManager.MATCH_ALL
            ).mapNotNull { it.serviceInfo?.packageName }
            val default = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )?.substringBefore('/')
            (enabled + services + listOfNotNull(default)).distinct()
        }.getOrDefault(emptyList())

        return CriticalRoles.roles(home, ime)
    }
}
