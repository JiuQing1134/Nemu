package com.nemu.hibernate.data

import android.content.Context
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.NumberIdentityMode

class AntiFraudSettings(context: Context) {
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

    var restrictSecurityServer: Boolean
        get() = prefs.getBoolean(KEY_RESTRICT_SECURITY_SERVER, false)
        set(value) = prefs.edit()
            .putBoolean(KEY_RESTRICT_SECURITY_SERVER, value)
            .apply()

    var restrictAwareness: Boolean
        get() = prefs.getBoolean(KEY_RESTRICT_AWARENESS, false)
        set(value) = prefs.edit()
            .putBoolean(KEY_RESTRICT_AWARENESS, value)
            .apply()

    var lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    var numberIdentityOriginalEnabled: Boolean?
        get() = if (prefs.contains(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED)) {
            prefs.getBoolean(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED, false)
        } else {
            null
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED)
            else editor.putBoolean(KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED, value)
            // Must survive a crash before pm disable-user.
            editor.commit()
        }

    fun config(): AntiFraudConfig = AntiFraudConfig(
        numberIdentityMode = numberIdentityMode,
        restrictSecurityServer = restrictSecurityServer,
        restrictAwareness = restrictAwareness
    )

    fun save(config: AntiFraudConfig) {
        prefs.edit()
            .putString(KEY_NUMBER_IDENTITY_MODE, config.numberIdentityMode.name)
            .putBoolean(KEY_RESTRICT_SECURITY_SERVER, config.restrictSecurityServer)
            .putBoolean(KEY_RESTRICT_AWARENESS, config.restrictAwareness)
            .apply()
    }

    fun reset() = save(AntiFraudConfig())

    companion object {
        private const val KEY_NUMBER_IDENTITY_MODE = "antifraud_numberidentity_mode"
        private const val KEY_RESTRICT_SECURITY_SERVER =
            "antifraud_securityserver_restrict"
        private const val KEY_RESTRICT_AWARENESS = "antifraud_awareness_restrict"
        private const val KEY_LAST_RESULT = "antifraud_last_result"
        private const val KEY_NUMBER_IDENTITY_ORIGINAL_ENABLED =
            "antifraud_numberidentity_original_enabled"
    }
}
