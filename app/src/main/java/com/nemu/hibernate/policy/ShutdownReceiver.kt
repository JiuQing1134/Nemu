package com.nemu.hibernate.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.Settings

/**
 * Manifest shutdown fallback. Kept even though the runtime receiver in
 * [SafetyService] is the primary path; on some OEM builds this manifest
 * receiver is not delivered.
 */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN && intent.action != Intent.ACTION_REBOOT) return

        val settings = Settings(context)

        val pending = goAsync()
        try {
            settings.lastShutdownTime = System.currentTimeMillis()
            val result = ShutdownSafety.runBlockingUnfreeze(context)
            settings.lastShutdownResult = result
            ActionLog.info("关机安全解冻：$result")
        } catch (throwable: Throwable) {
            settings.lastShutdownResult = "异常：${throwable.message}"
            ActionLog.error("关机安全解冻异常：${throwable.message}")
        } finally {
            pending.finish()
        }
    }
}
