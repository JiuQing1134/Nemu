package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaCommandBuilderTest {
    @Test
    fun listFiles_uses_fixed_source_dir_and_missing_sentinel() {
        assertEquals(
            "if [ -d '/data/cota/para' ]; then find '/data/cota/para' " +
                "-maxdepth 1 -type f -print; else echo '__NEMU_COTA_DIR_MISSING__'; fi",
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
        assertEquals(
            "mkdir -p '/storage/emulated/0/Nemu/cota-backup' && " +
                "mkdir '/storage/emulated/0/Nemu/cota-backup/20261121-183000' && " +
                "cp -p '/data/cota/para/cpuconfig' " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/'",
            CotaCommandBuilder.backup(
                "/storage/emulated/0/Nemu/cota-backup",
                "20261121-183000",
                listOf("/data/cota/para/cpuconfig")
            )
        )
    }

    @Test
    fun writeManifest_decodes_base64_into_the_backup_directory() {
        assertEquals(
            "printf '%s' 'aGVsbG8=' | base64 -d > " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/manifest.tsv'",
            CotaCommandBuilder.writeManifest(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                "aGVsbG8="
            )
        )
    }

    @Test
    fun delete_uses_rm_f_on_selected_files_only() {
        assertEquals(
            "rm -f '/data/cota/para/cpuconfig' '/data/cota/para/pg_config'",
            CotaCommandBuilder.delete(
                listOf("/data/cota/para/cpuconfig", "/data/cota/para/pg_config")
            )
        )
    }

    @Test
    fun restore_copies_then_chowns_then_chmods() {
        assertEquals(
            "cp -p '/storage/emulated/0/Nemu/cota-backup/20261121-183000/cpuconfig' " +
                "'/data/cota/para/cpuconfig' && " +
                "chown '1000:1000' '/data/cota/para/cpuconfig' && " +
                "chmod '644' '/data/cota/para/cpuconfig'",
            CotaCommandBuilder.restore(
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
        )
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
}
