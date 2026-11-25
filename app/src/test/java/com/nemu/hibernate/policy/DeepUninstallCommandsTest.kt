package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepUninstallCommandsTest {
    @Test
    fun uninstallKeepsUserData() {
        assertEquals(
            "pm uninstall -k --user 0 'com.example.app'",
            DeepUninstallCommands.uninstallForUser("com.example.app")
        )
    }

    @Test
    fun restoreEnablesPackageAfterInstall() {
        assertEquals(
            "pm enable --user 0 'com.example.app'",
            DeepUninstallCommands.enableForUser("com.example.app")
        )
    }

    @Test
    fun uninstalledRecordQueryIncludesUninstalledPackages() {
        assertEquals(
            "pm list packages -u --user 0 'com.example.app'",
            DeepUninstallCommands.listPackagesIncludingUninstalled("com.example.app")
        )
    }

    @Test
    fun backupsAreStoredWithBakSuffixAndRenamedBackForInstall() {
        assertEquals("base.apk.bak", DeepUninstallCommands.backupFileName(0))
        assertEquals("split_0.apk.bak", DeepUninstallCommands.backupFileName(1))
        assertEquals("base.apk", DeepUninstallCommands.installFileName("base.apk.bak"))
        assertEquals("split_3.apk", DeepUninstallCommands.installFileName("split_3.apk.bak"))
        // Legacy backups written before the rename keep working.
        assertEquals("base.apk", DeepUninstallCommands.installFileName("base.apk"))
    }

    @Test
    fun restoreStagesBackupsUnderLocalTmpForSystemServer() {
        // system_server cannot read /data/adb (adb_data_file), so the APK
        // must be copied to shell_data_file under /data/local/tmp first.
        assertEquals(
            "/data/local/tmp/nemu_deep_install/com.example.app",
            DeepUninstallCommands.stageDir("com.example.app")
        )
    }

    @Test
    fun backupInstallUsesPmInstallNotTheMissingInstallMultiple() {
        // MagicOS 10's pm has no install-multiple ("Unknown command"); pm
        // install accepts one or more APK paths, so the backup fallback must
        // use it.
        assertEquals(
            "pm install -r -d --user 0 " +
                "'/data/adb/nemu/deep_uninstall/apk/com.example.app/1/base.apk' " +
                "'/data/adb/nemu/deep_uninstall/apk/com.example.app/1/split_0.apk'",
            DeepUninstallCommands.installMultiple(
                listOf(
                    "/data/adb/nemu/deep_uninstall/apk/com.example.app/1/base.apk",
                    "/data/adb/nemu/deep_uninstall/apk/com.example.app/1/split_0.apk"
                )
            )
        )
    }

    @Test
    fun moduleActiveRequiresBridgeAndEnabledModule() {
        assertEquals(
            "test -f '/data/adb/modules/nemu_boot_autostart/deep_restore.sh' && " +
                "test ! -e '/data/adb/modules/nemu_boot_autostart/disable' && " +
                "test ! -e '/data/adb/modules/nemu_boot_autostart/remove'",
            DeepUninstallCommands.moduleActive()
        )
    }

    @Test
    fun deepRestoreMarkerAcceptsOneTwentyAndNewer() {
        assertTrue(isDeepRestoreReadyVersion("1.20\n"))
        assertTrue(isDeepRestoreReadyVersion("1.21"))
        assertTrue(isDeepRestoreReadyVersion("2.0"))
        assertFalse(isDeepRestoreReadyVersion("1.19\n"))
        assertFalse(isDeepRestoreReadyVersion("garbage"))
        assertFalse(isDeepRestoreReadyVersion(""))
    }
}
