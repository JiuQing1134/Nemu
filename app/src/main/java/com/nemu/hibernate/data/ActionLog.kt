package com.nemu.hibernate.data

import com.nemu.hibernate.logging.PersistentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)

object ActionLog {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun add(level: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _entries.update { (it + entry).takeLast(200) }
        // Mirror to the opt-in file log; no-op when logging is disabled.
        PersistentLogger.log(level, "action", message)
    }

    fun info(message: String) = add("INFO", message)
    fun warn(message: String) = add("WARN", message)
    fun error(message: String) = add("ERROR", message)
}
