package com.nemu.hibernate.cota

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaParsersTest {
    private val hash = "a".repeat(64)

    @Test
    fun parseFileNames_keeps_safe_names_and_counts_skipped_entries() {
        val output = listOf(
            "/data/cota/para/cpuconfig",
            "/data/cota/para/pg_config",
            "/data/cota/para/thermal_config",
            "/data/cota/para/bad name",
            "/data/cota/para/../escape"
        ).joinToString("\n")

        val (names, skipped) = CotaParsers.parseFileNames(output)

        assertEquals(listOf("cpuconfig", "pg_config", "thermal_config"), names)
        assertEquals(2, skipped)
    }

    @Test
    fun parseFileNames_keeps_nested_parameter_paths() {
        val output = listOf(
            "/data/cota/para/thermal_config/thermal_config_cloud.xml",
            "/data/cota/para/thermal_config/version.txt",
            "/data/cota/para/iaware/triStatePara/smartAppsControl.xml",
            "/data/cota/para/thermal_config/../escape"
        ).joinToString("\n")

        val (names, skipped) = CotaParsers.parseFileNames(output)

        assertEquals(
            listOf(
                "iaware/triStatePara/smartAppsControl.xml",
                "thermal_config/thermal_config_cloud.xml",
                "thermal_config/version.txt"
            ),
            names
        )
        assertEquals(1, skipped)
    }

    @Test
    fun parseSubsystemFiles_groups_safe_relative_paths_and_deduplicates() {
        val output = listOf(
            "/data/cota/para/antifraud/FraudCallContentPolicy.json|23989",
            "/data/cota/para/antifraud/FraudVideoPolicy.json|7506",
            "/data/cota/para/antifraud/FraudVideoPolicy.json|7506",
            "/data/cota/para/iaware/triStatePara/smartAppsControl.xml|322",
            "/data/cota/para/thermal_config/thermal_config_cloud.xml|452048",
            "/data/cota/para/../escape/file|100",
            "/other/thermal_config/file|100"
        ).joinToString("\n")

        val inventory = CotaParsers.parseSubsystemFiles(output)

        assertEquals(listOf("antifraud", "iaware", "thermal_config"), inventory.map { it.name })
        assertEquals(listOf(2, 1, 1), inventory.map { it.fileCount })
        assertEquals(listOf(31_495L, 322L, 452_048L), inventory.map { it.totalSizeBytes })
        assertEquals(
            listOf("antifraud/FraudCallContentPolicy.json", "antifraud/FraudVideoPolicy.json"),
            inventory.first().samplePaths
        )
    }

    @Test
    fun parseStat_maps_pipe_separated_metadata_and_mtime() {
        val output = "/data/cota/para/cpuconfig|1024|644|1000|1000|1700000000\n"

        val stats = CotaParsers.parseStat(output)

        assertEquals(1, stats.size)
        assertEquals("cpuconfig", stats[0].name)
        assertEquals(1024L, stats[0].sizeBytes)
        assertEquals("644", stats[0].mode)
        assertEquals(1000, stats[0].uid)
        assertEquals(1000, stats[0].gid)
        assertEquals(1_700_000_000L, stats[0].modifiedAtSeconds)
    }

    @Test
    fun parseStat_keeps_the_row_when_mtime_is_missing_or_unparseable() {
        val output = "/data/cota/para/cpuconfig|1024|644|1000|1000\n" +
            "/data/cota/para/pg_config|2048|644|0|0|?\n" +
            "/data/cota/para/thermal_config|4096|644|0|0|-5\n"

        val stats = CotaParsers.parseStat(output)

        assertEquals(3, stats.size)
        assertEquals(
            listOf(null, null, null),
            stats.map { it.modifiedAtSeconds }
        )
    }

    @Test
    fun parseStat_keeps_nested_paths_relative_to_the_source_directory() {
        val output =
            "/data/cota/para/thermal_config/thermal_config_cloud.xml|452048|644|0|0\n"

        val stats = CotaParsers.parseStat(output)

        assertEquals(1, stats.size)
        assertEquals(
            "thermal_config/thermal_config_cloud.xml",
            stats[0].name
        )
        assertEquals(452048L, stats[0].sizeBytes)
    }

    @Test
    fun parseStat_keeps_special_mode_bits_instead_of_dropping_the_row() {
        val output = "/data/cota/para/thermal_config/special|1|4755|0|0\n"

        val stats = CotaParsers.parseStat(output)

        assertEquals(1, stats.size)
        assertEquals("4755", stats[0].mode)
    }

    @Test
    fun parseStat_drops_malformed_lines() {
        val output = "garbage\n/data/cota/para/cpuconfig|x|644|1000|1000\n"
        assertTrue(CotaParsers.parseStat(output).isEmpty())
    }

    @Test
    fun parseSha256_accepts_both_markers() {
        val output = "$hash  /data/cota/para/cpuconfig\n" +
            "${"b".repeat(64)} */data/cota/para/pg_config\n"

        val hashes = CotaParsers.parseSha256(output)

        assertEquals(hash, hashes["cpuconfig"])
        assertEquals("b".repeat(64), hashes["pg_config"])
    }

    @Test
    fun parseSha256_keeps_nested_paths_relative_to_the_source_directory() {
        val output =
            "$hash  /data/cota/para/thermal_config/thermal_config_cloud.xml\n"

        val hashes = CotaParsers.parseSha256(output)

        assertEquals(
            hash,
            hashes["thermal_config/thermal_config_cloud.xml"]
        )
    }

    @Test
    fun parseSha256_ignores_unsafe_paths() {
        val output = "$hash  /data/cota/para/bad name\n"
        assertTrue(CotaParsers.parseSha256(output).isEmpty())
    }

    @Test
    fun manifest_round_trips() {
        val files = listOf(
            CotaBackupFile("cpuconfig", hash, 1024, "644", 1000, 1000),
            CotaBackupFile("thermal_config", "c".repeat(64), 2048, "644", 0, 0),
            CotaBackupFile(
                "thermal_config/thermal_config_cloud.xml",
                "d".repeat(64),
                452048,
                "644",
                0,
                0
            )
        )

        val parsed = CotaParsers.parseManifest(CotaParsers.renderManifest(files))

        assertEquals(files.sortedBy { it.name }, parsed)
    }

    @Test
    fun parseFileNames_reads_nul_delimited_find_output() {
        val output =
            "/data/cota/para/thermal_config/thermal_config_cloud.xml\u0000" +
                "/data/cota/para/thermal_config/version.txt\u0000" +
                "/data/cota/para/bad\nname\u0000"

        val (names, skipped) = CotaParsers.parseFileNames(output)

        assertEquals(
            listOf(
                "thermal_config/thermal_config_cloud.xml",
                "thermal_config/version.txt"
            ),
            names
        )
        assertEquals(1, skipped)
    }

    @Test
    fun manifest_rejects_duplicate_names_and_negative_sizes() {
        assertNull(
            CotaParsers.parseManifest(
                "#nemu-cota-backup-v1\n" +
                    "cpuconfig\t$hash\t1\t644\t0\t0\n" +
                    "cpuconfig\t$hash\t2\t644\t0\t0\n"
            )
        )
        assertNull(
            CotaParsers.parseManifest(
                "#nemu-cota-backup-v1\n" +
                    "cpuconfig\t$hash\t-1\t644\t0\t0\n"
            )
        )
    }

    @Test
    fun manifest_rejects_missing_header_and_bad_rows() {
        assertNull(CotaParsers.parseManifest("cpuconfig\t$hash\t1\t644\t0\t0\n"))
        assertNull(
            CotaParsers.parseManifest(
                "#nemu-cota-backup-v1\ncpuconfig\tnot-a-hash\t1\t644\t0\t0\n"
            )
        )
    }

    @Test
    fun displayLabel_formats_mtime_compactly_and_labels_it_honestly() {
        // 1700000000 = 2023-11-14 22:13:20 UTC.
        assertEquals(
            "修改 2023-11-14 22:13",
            CotaTimestamps.displayLabel(1_700_000_000L, ZoneOffset.UTC)
        )
    }

    @Test
    fun displayLabel_falls_back_without_claiming_a_creation_time() {
        listOf(null, 0L, -1L, Long.MAX_VALUE).forEach { value ->
            assertEquals(
                "修改时间未知",
                CotaTimestamps.displayLabel(value, ZoneOffset.UTC)
            )
            assertNull(CotaTimestamps.formatModified(value, ZoneOffset.UTC))
        }
    }
}
