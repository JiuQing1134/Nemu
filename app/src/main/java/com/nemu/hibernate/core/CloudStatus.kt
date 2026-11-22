package com.nemu.hibernate.core

/**
 * Read-only package state used by the cloud/telemetry UI. It comes from
 * PackageManager; it is not a promise that no background work remains.
 */
data class CloudStatus(
    val enabled: Boolean,
    val suspended: Boolean,
    val processRunning: Boolean? = null
) {
    fun describe(action: CloudAction): String = when (action) {
        CloudAction.DISABLE_USER -> buildString {
            append(if (enabled) "已启用" else "已停用")
            appendProcessPresence()
        }
        CloudAction.SUSPEND, CloudAction.OBSERVE_ONLY -> buildString {
            append(if (suspended) "已暂停" else "未暂停")
            if (!enabled) append("，已停用")
            appendProcessPresence()
        }
    }

    private fun StringBuilder.appendProcessPresence() {
        append(
            when (processRunning) {
                true -> "，进程存活"
                false -> "，无目标进程"
                null -> "，进程未知"
            }
        )
    }
}
