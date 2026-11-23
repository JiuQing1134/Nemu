package com.nemu.hibernate.cota

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
    val sha256: String,
    val mode: String,
    val uid: Int,
    val gid: Int,
    val kind: CotaFileKind
)

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
