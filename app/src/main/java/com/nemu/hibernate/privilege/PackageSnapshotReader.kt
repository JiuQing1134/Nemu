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

/** A per-user uninstall keeps the package section but sets installed=false. */
fun PackageSnapshot.isMissing(): Boolean = !exists || installed == false

object PackageSnapshotParser {
    fun parse(output: String, userId: Int = 0): PackageSnapshot {
        // versionName and other app-controlled fields are printed before the
        // genuine User 0 line. A dump that contains a Package section is never
        // a missing package, even if an injected string says so.
        val lines = output.lineSequence()
            .map { it.trimEnd('\r') }
            .toList()
        val missing = lines.any {
            it.trim().startsWith("Unable to find package:")
        }
        if (missing && !output.contains("Package [")) {
            return PackageSnapshot(exists = false)
        }
        val prefix = "User $userId:"
        val userLines = lines.filter {
            it.trimStart().startsWith(prefix) && it.contains("hidden=")
        }
        // Multiple contradictory state lines mean a malformed/spoofed dump:
        // keep the package as existing but expose no state (fail closed).
        if (userLines.size != 1) {
            return PackageSnapshot(exists = true)
        }
        val line = userLines.single()
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
