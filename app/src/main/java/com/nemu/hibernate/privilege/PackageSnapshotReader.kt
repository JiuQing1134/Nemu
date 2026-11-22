package com.nemu.hibernate.privilege

import com.nemu.hibernate.core.ShellQuote

/**
 * Per-user package state read from `dumpsys package <pkg>`.
 *
 * Public PackageManager APIs cannot distinguish a hidden package from an
 * uninstalled one (and `getPackageInfo` throws for a hidden package), so root
 * and shell-uid callers both use dumpsys instead. Only user 0 is supported.
 */
data class PackageSnapshot(
    val exists: Boolean,
    val installed: Boolean? = null,
    val hidden: Boolean? = null,
    val suspended: Boolean? = null,
    val stopped: Boolean? = null,
    /** 0=default, 1=enabled, 2=disabled, 3=disabled-user, 4=disabled-until-used. */
    val enabledState: Int? = null
)

object PackageSnapshotParser {
    fun parse(output: String, userId: Int = 0): PackageSnapshot {
        if (output.contains("Unable to find package:", ignoreCase = true)) {
            return PackageSnapshot(exists = false)
        }
        val prefix = "User $userId:"
        val line = output.lineSequence()
            .firstOrNull { it.trimStart().startsWith(prefix) && it.contains("hidden=") }
            ?: return PackageSnapshot(exists = true)
        return PackageSnapshot(
            exists = true,
            installed = boolValue(line, "installed="),
            hidden = boolValue(line, "hidden="),
            suspended = boolValue(line, "suspended="),
            stopped = boolValue(line, "stopped="),
            enabledState = intValue(line, "enabled=")?.takeIf { it in 0..4 }
        )
    }

    private fun boolValue(line: String, key: String): Boolean? {
        val index = line.indexOf(key)
        if (index < 0) return null
        return when {
            line.startsWith("true", index + key.length) -> true
            line.startsWith("false", index + key.length) -> false
            else -> null
        }
    }

    private fun intValue(line: String, key: String): Int? {
        val index = line.indexOf(key)
        if (index < 0) return null
        val digits = line.substring(index + key.length)
            .takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }
}

class BackendPackageSnapshotReader(
    private val backend: PrivilegeBackend,
    private val userId: Int = 0
) {
    suspend fun read(packageName: String): PackageSnapshot? {
        val result = backend.exec(
            "dumpsys package ${ShellQuote.quote(packageName)}"
        )
        // dumpsys exits non-zero on some OEM builds even when it printed a
        // complete dump; only a completely empty output is a read failure.
        if (result.output.isBlank()) return null
        return PackageSnapshotParser.parse(result.output, userId)
    }
}
