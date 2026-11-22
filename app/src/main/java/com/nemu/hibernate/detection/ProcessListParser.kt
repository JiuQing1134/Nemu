package com.nemu.hibernate.detection

import com.nemu.hibernate.privilege.PrivilegeBackend

/** One read-only snapshot of process names reported by `ps -A -o PID,ARGS`. */
data class ProcessListSnapshot(val processNames: Set<String>) {
    fun isRunning(packageName: String): Boolean = processNames.any { processName ->
        processName == packageName || processName.startsWith("$packageName:")
    }
}

/** Parses Android/toybox `ps -A -o PID,ARGS` output without matching substrings. */
object ProcessListParser {
    const val LIST_COMMAND = "ps -A -o PID,ARGS"

    fun parse(output: String): ProcessListSnapshot? {
        val processNames = output.lineSequence().mapNotNull { line ->
            val row = PROCESS_ROW.matchEntire(line) ?: return@mapNotNull null
            row.groupValues[1].trim().split(WHITESPACE, limit = 2).firstOrNull()
                ?.takeIf(String::isNotEmpty)
        }.toSet()
        return if (processNames.isEmpty()) null else ProcessListSnapshot(processNames)
    }

    private val PROCESS_ROW = Regex("^\\s*\\d+\\s+(.+?)\\s*$")
    private val WHITESPACE = Regex("\\s+")
}

class PackageProcessListReader(private val backend: PrivilegeBackend) {
    suspend fun read(): ProcessListSnapshot? {
        val result = backend.exec(ProcessListParser.LIST_COMMAND)
        if (!result.ok) return null
        return ProcessListParser.parse(result.output)
    }
}
