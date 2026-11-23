package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaCommandBuilderTest {
    @Test
    fun listFiles_recurses_into_subsystem_directories() {
        assertEquals(
            "if [ -d '/data/cota/para' ]; then find '/data/cota/para' " +
                "-mindepth 1 -maxdepth 4 -type f -print0; " +
                "else echo '__NEMU_COTA_DIR_MISSING__'; fi",
            CotaCommandBuilder.listFiles()
        )
    }

    @Test
    fun listSubsystemFiles_reads_only_metadata_below_source_directory() {
        assertEquals(
            "if [ -d '/data/cota/para' ]; then find '/data/cota/para' " +
                "-mindepth 2 -maxdepth 4 -type f -exec stat -c '%n|%s' {} \\; ; fi",
            CotaCommandBuilder.listSubsystemFiles()
        )
    }

    @Test
    fun stat_uses_pipe_format_and_quoted_paths() {
        assertEquals(
            "stat -c '%n|%s|%a|%u|%g' '/data/cota/para/cpuconfig' " +
                "'/data/cota/para/pg_config'",
            CotaCommandBuilder.stat(
                listOf("/data/cota/para/cpuconfig", "/data/cota/para/pg_config")
            )
        )
    }

    @Test
    fun sha256_quotes_paths() {
        assertEquals(
            "sha256sum '/data/cota/para/thermal_config'",
            CotaCommandBuilder.sha256(listOf("/data/cota/para/thermal_config"))
        )
    }

    @Test
    fun backup_creates_parent_then_refuses_to_overwrite_existing_backup() {
        val command = CotaCommandBuilder.backup(
            "/storage/emulated/0/Nemu/cota-backup",
            "20261121-183000",
            listOf("cpuconfig")
        )

        assertTrue(
            command.startsWith(
                "mkdir -p '/storage/emulated/0/Nemu/cota-backup' && " +
                    "mkdir '/storage/emulated/0/Nemu/cota-backup/20261121-183000'"
            )
        )
        assertTrue(command.contains("realpath -m '/data/cota/para/cpuconfig'"))
        assertTrue(command.contains("[ ! -L '/data/cota/para/cpuconfig' ]"))
        assertTrue(
            command.contains(
                "cp -p '/data/cota/para/cpuconfig' " +
                    "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/cpuconfig'"
            )
        )
    }

    @Test
    fun backup_preserves_relative_directories_for_nested_parameters() {
        val command = CotaCommandBuilder.backup(
            "/storage/emulated/0/Nemu/cota-backup",
            "20261121-183000",
            listOf("thermal_config/thermal_config_cloud.xml")
        )

        assertTrue(
            command.contains(
                "mkdir -p '/storage/emulated/0/Nemu/cota-backup/" +
                    "20261121-183000/thermal_config'"
            )
        )
        assertTrue(
            command.contains("[ ! -L '/data/cota/para/thermal_config' ]")
        )
        assertTrue(
            command.contains(
                "cp -p '/data/cota/para/thermal_config/" +
                    "thermal_config_cloud.xml' " +
                    "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/" +
                    "thermal_config/thermal_config_cloud.xml'"
            )
        )
    }

    @Test
    fun writeManifest_publishes_atomically_from_a_reserved_temp_name() {
        assertEquals(
            "printf '%s' 'aGVsbG8=' | base64 -d > " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/.manifest.tmp' && " +
                "sync && mv -f " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/.manifest.tmp' " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/manifest.tsv'",
            CotaCommandBuilder.writeManifest(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                "aGVsbG8="
            )
        )
    }

    @Test
    fun delete_rechecks_backup_and_source_in_the_same_shell() {
        val command = CotaCommandBuilder.deleteVerified(
            "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
            listOf(
                CotaBackupFile(
                    name = "cpuconfig",
                    sha256 = "a".repeat(64),
                    sizeBytes = 1024,
                    mode = "644",
                    uid = 1000,
                    gid = 1000
                )
            )
        )

        assertTrue(command.contains("realpath -m '/data/cota/para/cpuconfig'"))
        assertTrue(
            command.contains(
                "cmp -s '/storage/emulated/0/Nemu/cota-backup/" +
                    "20261121-183000/cpuconfig' '/data/cota/para/cpuconfig'"
            )
        )
        assertTrue(command.endsWith("rm -f '/data/cota/para/cpuconfig'"))
    }

    @Test
    fun restore_copies_then_chowns_then_chmods() {
        val command = CotaCommandBuilder.restore(
            "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
            listOf(
                CotaBackupFile(
                    name = "cpuconfig",
                    sha256 = "a".repeat(64),
                    sizeBytes = 1024,
                    mode = "644",
                    uid = 1000,
                    gid = 1000
                )
            )
        )

        assertTrue(command.contains("realpath -m '/data/cota/para/cpuconfig'"))
        assertTrue(
            command.contains(
                "[ -f '/storage/emulated/0/Nemu/cota-backup/" +
                    "20261121-183000/cpuconfig' ]"
            )
        )
        assertTrue(
            command.contains(
                "[ ! -L '/storage/emulated/0/Nemu/cota-backup/" +
                    "20261121-183000/cpuconfig' ]"
            )
        )
        assertTrue(command.contains("sha256sum"))
        assertTrue(command.contains("mkdir -p '/data/cota/para'"))
        assertTrue(command.contains("[ ! -d '/data/cota/para/cpuconfig' ]"))
        assertTrue(command.contains("cp -p"))
        assertTrue(command.indexOf("sha256sum") < command.indexOf("cp -p"))
        assertTrue(command.indexOf("chown") < command.indexOf("chmod"))
        assertTrue(
            command.indexOf("[ ! -d '/data/cota/para/cpuconfig' ]") <
                command.indexOf("cp -p")
        )
    }

    @Test
    fun restore_recreates_the_nested_target_directory_first() {
        val command = CotaCommandBuilder.restore(
            "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
            listOf(
                CotaBackupFile(
                    name = "thermal_config/thermal_config_cloud.xml",
                    sha256 = "a".repeat(64),
                    sizeBytes = 452048,
                    mode = "644",
                    uid = 0,
                    gid = 0
                )
            )
        )

        assertTrue(command.contains("mkdir -p '/data/cota/para/thermal_config'"))
        assertTrue(command.contains("[ ! -L '/data/cota/para/thermal_config' ]"))
        assertTrue(
            command.contains(
                "cp -p '/storage/emulated/0/Nemu/cota-backup/20261121-183000/" +
                    "thermal_config/thermal_config_cloud.xml' " +
                    "'/data/cota/para/thermal_config/thermal_config_cloud.xml'"
            )
        )
        assertTrue(command.contains("chown '0:0'"))
        assertTrue(command.contains("chmod '644'"))
    }

    @Test
    fun restore_rejects_unsafe_mode() {
        assertThrows(IllegalArgumentException::class.java) {
            CotaCommandBuilder.restore(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                listOf(
                    CotaBackupFile(
                        name = "cpuconfig",
                        sha256 = "a".repeat(64),
                        sizeBytes = 1,
                        mode = "777; rm -rf /",
                        uid = 0,
                        gid = 0
                    )
                )
            )
        }
    }

    @Test
    fun unsafe_names_are_rejected_by_the_path_policy() {
        assertTrue(CotaPaths.isSafeName("thermal_config"))
        assertTrue(CotaPaths.isSafeName("cpuconfig.20261121"))
        assertFalse(CotaPaths.isSafeName("../thermal_config"))
        assertFalse(CotaPaths.isSafeName("thermal config"))
        assertFalse(CotaPaths.isSafeName("-rf"))
        assertFalse(CotaPaths.isSafeName("a/b"))
        assertFalse(CotaPaths.isSafeName(""))
    }

    @Test
    fun special_mode_bits_are_listed_but_not_restored() {
        assertTrue(CotaPaths.isSafeMode("4755"))
        assertTrue(CotaPaths.isSafeMode("2755"))
        assertTrue(CotaPaths.isSafeMode("1755"))
        assertFalse(CotaPaths.isSafeRestoreMode("4755"))
        assertFalse(CotaPaths.isSafeRestoreMode("2755"))
        assertFalse(CotaPaths.isSafeRestoreMode("1755"))
        assertTrue(CotaPaths.isSafeRestoreMode("0755"))
        assertTrue(CotaPaths.isSafeRestoreMode("644"))
        assertTrue(CotaPaths.isSafeMode("0"))
        assertTrue(CotaPaths.isSafeMode("70"))
        assertTrue(CotaPaths.isSafeRestoreMode("0"))
        assertTrue(CotaPaths.isSafeRestoreMode("70"))
    }

    @Test
    fun restore_rejects_special_mode_bits() {
        assertThrows(IllegalArgumentException::class.java) {
            CotaCommandBuilder.restore(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                listOf(
                    CotaBackupFile(
                        name = "thermal_config/thermal_config_cloud.xml",
                        sha256 = "a".repeat(64),
                        sizeBytes = 452048,
                        mode = "4755",
                        uid = 0,
                        gid = 0
                    )
                )
            )
        }
    }

    @Test
    fun relative_paths_allow_subsystem_directories_but_not_traversal() {
        assertTrue(
            CotaPaths.isSafeRelativePath("thermal_config/thermal_config_cloud.xml")
        )
        assertTrue(
            CotaPaths.isSafeRelativePath("iaware/triStatePara/smartAppsControl.xml")
        )
        assertTrue(CotaPaths.isSafeRelativePath("cpuconfig"))
        assertFalse(CotaPaths.isSafeRelativePath("../escape"))
        assertFalse(CotaPaths.isSafeRelativePath("thermal_config/../../escape"))
        assertFalse(CotaPaths.isSafeRelativePath("/data/cota/para/cpuconfig"))
        assertFalse(CotaPaths.isSafeRelativePath("thermal_config/"))
        assertFalse(CotaPaths.isSafeRelativePath("thermal_config//file"))
        assertFalse(CotaPaths.isSafeRelativePath("a/b/c/d/e"))
        assertFalse(CotaPaths.isSafeRelativePath(""))
    }
}
