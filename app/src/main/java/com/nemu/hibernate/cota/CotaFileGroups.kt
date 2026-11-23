package com.nemu.hibernate.cota

/** One vendor COTA subsystem, or the root of /data/cota/para. */
data class CotaFileGroup(
    val key: String,
    val title: String,
    val files: List<CotaFile>
) {
    val totalSizeBytes: Long = files.fold(0L) { total, file ->
        if (Long.MAX_VALUE - total < file.sizeBytes) Long.MAX_VALUE
        else total + file.sizeBytes
    }
}

object CotaFileGroups {
    const val THERMAL_KEY = "thermal_config"
    const val ROOT_KEY = ""

    fun group(files: List<CotaFile>): List<CotaFileGroup> =
        files.groupBy { file ->
            if ('/' in file.name) file.name.substringBefore('/') else ROOT_KEY
        }
            .map { (key, grouped) ->
                CotaFileGroup(
                    key = key,
                    title = titleOf(key),
                    files = grouped.sortedBy { it.name }
                )
            }
            .sortedWith(
                compareBy(
                    { it.key != THERMAL_KEY },
                    { it.key == ROOT_KEY },
                    { it.key }
                )
            )

    private fun titleOf(key: String): String = when (key) {
        ROOT_KEY -> "根目录"
        THERMAL_KEY -> "温控（$THERMAL_KEY）"
        else -> key
    }
}
