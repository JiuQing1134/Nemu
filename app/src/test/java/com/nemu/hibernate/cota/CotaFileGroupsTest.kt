package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaFileGroupsTest {
    private fun file(name: String, size: Long) = CotaFile(
        name = name,
        sizeBytes = size,
        sha256 = "a".repeat(64),
        mode = "644",
        uid = 0,
        gid = 0,
        kind = CotaKnownFiles.kindOf(name)
    )

    @Test
    fun thermal_group_comes_first_and_root_group_last() {
        val groups = CotaFileGroups.group(
            listOf(
                file("root.txt", 10),
                file("antifraud/policy.json", 20),
                file("thermal_config/version.txt", 30),
                file("thermal_config/thermal_config_cloud.xml", 40)
            )
        )

        assertEquals(
            listOf("thermal_config", "antifraud", ""),
            groups.map { it.key }
        )
        assertEquals(
            listOf("温控（thermal_config）", "antifraud", "根目录"),
            groups.map { it.title }
        )
    }

    @Test
    fun group_sorts_files_and_totals_sizes() {
        val groups = CotaFileGroups.group(
            listOf(
                file("thermal_config/version.txt", 30),
                file("thermal_config/thermal_config_cloud.xml", 452048)
            )
        )

        val thermal = groups.single()
        assertEquals(
            listOf(
                "thermal_config/thermal_config_cloud.xml",
                "thermal_config/version.txt"
            ),
            thermal.files.map { it.name }
        )
        assertEquals(452078L, thermal.totalSizeBytes)
        assertEquals(
            CotaFileKind.THERMAL_CONFIG,
            thermal.files.first { it.name.endsWith(".xml") }.kind
        )
        assertEquals(
            CotaFileKind.METADATA,
            thermal.files.first { it.name.endsWith("version.txt") }.kind
        )
        assertTrue(thermal.files.none { it.kind == CotaFileKind.OTHER })
    }
}
