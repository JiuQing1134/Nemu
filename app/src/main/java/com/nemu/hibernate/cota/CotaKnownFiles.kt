package com.nemu.hibernate.cota

object CotaKnownFiles {
    private val kinds = mapOf(
        "cpuconfig" to CotaFileKind.CPU_CONFIG,
        "pg_config" to CotaFileKind.PG_CONFIG,
        "thermal_config" to CotaFileKind.THERMAL_CONFIG
    )

    fun kindOf(name: String): CotaFileKind = kinds[name] ?: CotaFileKind.OTHER
}
