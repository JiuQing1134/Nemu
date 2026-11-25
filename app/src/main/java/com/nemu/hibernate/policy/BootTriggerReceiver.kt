package com.nemu.hibernate.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot trigger sent by the KernelSU module after `sys.boot_completed`.
 *
 * HONOR does not deliver `BOOT_COMPLETED` to Nemu and `LOCKED_BOOT_COMPLETED`
 * only reaches direct boot. Without this trigger a process killed before
 * unlock could miss the safety pass for the whole boot. The module is the
 * only sender: the manifest protects the receiver with
 * `android.permission.DUMP`, which ordinary apps do not hold.
 */
class BootTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOOT_TRIGGER) return
        // A boot broadcast must never take the process down; the module
        // retries, and a later app launch resumes from the pending flag.
        runCatching {
            com.nemu.hibernate.data.ActionLog.info("收到模块开机触发")
            BootReceiver.onBootTrigger(context)
        }
    }

    companion object {
        const val ACTION_BOOT_TRIGGER =
            "com.nemu.dream.action.BOOT_TRIGGER"
    }
}
