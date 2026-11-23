package com.nemu.hibernate.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest shutdown fallback. Kept even though the runtime receiver in
 * [SafetyService] is the primary path; on some OEM builds this manifest
 * receiver is not delivered. Both paths delegate to [ShutdownSafety.runAsync],
 * which owns the gate and background execution.
 */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN &&
            intent.action != Intent.ACTION_REBOOT
        ) {
            return
        }

        val pending = goAsync()
        ShutdownSafety.runAsync(context) { pending.finish() }
    }
}
