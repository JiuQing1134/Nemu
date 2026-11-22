package com.nemu.hibernate.policy

import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootGuardTest {
    private class FakeRoot : RootPrivilegeBackend {
        override val name = "Root"
        val commands = mutableListOf<String>()
        override suspend fun isAvailable() = true
        override suspend fun exec(command: String): ShellResult {
            commands += command
            return ShellResult(0, "")
        }
    }

    @Test
    fun cleanShutdownMarkerUsesCurrentBootIdAndPrivateFileMode() = runBlocking {
        val root = FakeRoot()

        assertTrue(BootGuard.markCleanShutdown(root))

        assertTrue(root.commands.single().contains("/proc/sys/kernel/random/boot_id"))
        assertTrue(root.commands.single().contains("chmod 600"))
    }

    @Test
    fun disabledMarkerOnlyAcceptsKnownDisableTargets() = runBlocking {
        val root = FakeRoot()

        assertTrue(
            BootGuard.markDisabledByNemu(
                root,
                "com.hihonor.regservice",
                disabledByNemu = true
            )
        )
        assertFalse(
            BootGuard.markDisabledByNemu(
                root,
                "com.hihonor.systemserver",
                disabledByNemu = true
            )
        )

        assertEquals(1, root.commands.size)
        assertTrue(root.commands.single().contains("com.hihonor.regservice"))
    }

    @Test
    fun clearingRescuePreservesOwnershipMarkersUntilRecoverySucceeds() = runBlocking {
        val root = FakeRoot()

        assertTrue(BootGuard.clear(root))

        val command = root.commands.single()
        assertTrue(command.contains("/data/adb/nemu/boot_guard/boot_guard.sh clear"))
        assertFalse(command.contains("disabled_by_nemu/*"))
    }
}
