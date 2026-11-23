package com.nemu.hibernate.cota

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CotaFileKind(val label: String, val description: String) {
    CPU_CONFIG("CPU 调度参数", "厂商下发的 CPU 频率/调度配置"),
    PG_CONFIG("PowerGenie 功耗策略", "厂商下发的功耗与充电策略参数"),
    THERMAL_CONFIG("温控阈值", "厂商下发的温度阈值与降频策略"),
    METADATA("云控元数据", "版本号与变更记录，不含实际参数"),
    OTHER("其他云控参数", "未识别的 COTA 参数文件")
}

data class CotaFile(
    val name: String,
    val sizeBytes: Long,
    /** Kept for backup / delete / restore verification; not shown in lists. */
    val sha256: String,
    val mode: String,
    val uid: Int,
    val gid: Int,
    val kind: CotaFileKind,
    /**
     * Modification time from toybox `stat %Y`, in epoch seconds. `null` when
     * the device did not report a usable value.
     *
     * Toybox's `%z` ("creation time") is deliberately not used: on this device
     * it silently falls back to mtime (verified against procfs, which has no
     * birth time), so the UI labels this honestly as 修改时间 rather than
     * claiming a creation time.
     */
    val modifiedAtSeconds: Long? = null
)

/** Display helpers for COTA file timestamps (JVM-testable, no Android deps). */
object CotaTimestamps {
    private val DISPLAY_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** Formats epoch seconds as `2026-11-23 10:54`, or null when unusable. */
    fun formatModified(
        epochSeconds: Long?,
        zone: ZoneId = ZoneId.systemDefault()
    ): String? {
        if (epochSeconds == null || epochSeconds <= 0L) return null
        return try {
            Instant.ofEpochSecond(epochSeconds)
                .atZone(zone)
                .format(DISPLAY_FORMAT)
        } catch (e: DateTimeException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * Compact single-line label for a file row. Uses modification time on
     * purpose; Android/toybox does not expose a reliable birth time.
     */
    fun displayLabel(
        epochSeconds: Long?,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val formatted = formatModified(epochSeconds, zone)
        return formatted ?: "时间未知"
    }
}

/** Read-only summary for files below one vendor COTA subsystem directory. */
data class CotaDirectoryInventory(
    val name: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val samplePaths: List<String>
)

data class CotaBackupFile(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    val mode: String,
    val uid: Int,
    val gid: Int
)

data class CotaBackup(
    val id: String,
    val dir: String,
    val files: List<CotaBackupFile>
)

data class CotaBackupSummary(
    val id: String,
    val dir: String
)

sealed class CotaResult<out T> {
    data class Ok<T>(val value: T) : CotaResult<T>()
    data class Error(val message: String) : CotaResult<Nothing>()
    data object Disabled : CotaResult<Nothing>()
}
