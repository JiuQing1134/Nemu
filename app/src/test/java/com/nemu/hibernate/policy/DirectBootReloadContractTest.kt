package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Application.onCreate runs once per process. If the process starts in direct
 * boot, credential-encrypted custom targets and log settings are unavailable;
 * USER_UNLOCKED/BOOT_COMPLETED must reload them before the boot worker reads
 * the catalog.
 */
class DirectBootReloadContractTest {
    @Test
    fun bootReceiverReloadsCredentialStateBeforeWork() {
        val receiver = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/BootReceiver.kt"
        )

        assertTrue(
            receiver.contains("NemuApplication.reloadAfterUnlock(context)")
        )
        assertTrue(
            receiver.indexOf("reloadAfterUnlock") <
                receiver.indexOf("SafetyService.sync(context)")
        )
    }

    @Test
    fun applicationExposesOneReloadPath() {
        val app = TestSources.read(
            "src/main/java/com/nemu/hibernate/NemuApplication.kt"
        )

        assertTrue(app.contains("fun reloadAfterUnlock(context: Context)"))
        assertTrue(app.contains("TargetCatalog.setCustomTargets"))
        assertTrue(app.contains("PersistentLogger.init"))
    }
}
