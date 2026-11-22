package com.nemu.hibernate.data

import android.content.Context

/**
 * 日志开关（docs/07 §7.3）。与核心设置共用 `nemu` SharedPreferences，
 * 使用独立 key，避免与策略字段冲突；默认关闭。
 */
class LogSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)

    var loggingEnabled: Boolean
        get() = prefs.getBoolean("logging_enabled", false)
        set(value) = prefs.edit().putBoolean("logging_enabled", value).apply()
}
