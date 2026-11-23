package com.nemu.hibernate.cota

object CotaKnownFiles {
    private val kinds = mapOf(
        "cpuconfig" to CotaFileKind.CPU_CONFIG,
        "pg_config" to CotaFileKind.PG_CONFIG,
        "thermal_config" to CotaFileKind.THERMAL_CONFIG
    )

    fun kindOf(relativePath: String): CotaFileKind {
        val base = relativePath.substringAfterLast('/')
        if (base == "version.txt" || base == "changelog.xml") {
            return CotaFileKind.METADATA
        }
        val top = relativePath.substringBefore('/')
        kinds[top]?.let { return it }
        return when {
            base.startsWith("thermal_config") -> CotaFileKind.THERMAL_CONFIG
            base.startsWith("pg_config") -> CotaFileKind.PG_CONFIG
            base.startsWith("cpuconfig") -> CotaFileKind.CPU_CONFIG
            else -> CotaFileKind.OTHER
        }
    }
}
