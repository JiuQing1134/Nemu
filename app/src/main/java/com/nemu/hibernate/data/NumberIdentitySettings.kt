package com.nemu.hibernate.data

import android.content.Context
import com.nemu.hibernate.core.NumberIdentityMode

/**
 * Number-identity state. The preference keys keep their historical
 * `antifraud_` names so modes and restore baselines written by older builds
 * still load; the unified target policy is the source of truth.
 */
class NumberIdentitySettings(context: Context) {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)

    var numberIdentityMode: NumberIdentityMode
        get() = runCatching {
            NumberIdentityMode.valueOf(
                prefs.getString(
                    KEY_NUMBER_IDENTITY_MODE,
                    NumberIdentityMode.OFF.name
                ) ?: NumberIdentityMode.OFF.name
            )
        }.getOrDefault(NumberIdentityMode.OFF)
        set(value) = prefs.edit()
            .putString(KEY_NUMBER_IDENTITY_MODE, value.name)
            .apply()

    var numberIdentityOriginalEnabled: Boolean?
        get() = if (prefs.contains(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED)) {
            prefs.getBoolean(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED, false)
        } else {
            null
        }
        set(value) {
            setNumberIdentityOriginalEnabled(value)
        }

    fun setNumberIdentityOriginalEnabled(enabled: Boolean?): Boolean {
        val editor = prefs.edit()
        if (enabled == null) editor.remove(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED)
        else editor.putBoolean(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED, enabled)
        // Must survive a crash before pm disable-user.
        return editor.commit()
    }

    companion object {
        private const val KEY_NUMBER_IDENTITY_MODE = "antifraud_numberidentity_mode"
        private const val KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED =
            "antifraud_numberidentity_original_enabled"
    }
}
