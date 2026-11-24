package com.nemu.hibernate.policy

import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(root.commands.single().contains("clean_shutdown.prev"))
        assertTrue(root.commands.single().contains("mv -f"))
    }

    @Test
    fun plannedShutdownTimeRequiresTheCurrentBootObservation() = runBlocking {
        val planned = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String): ShellResult {
                assertTrue(command.contains("last_shutdown"))
                assertTrue(command.contains("NEMU_SEPARATOR"))
                assertTrue(command.contains("/proc/sys/kernel/random/boot_id"))
                return ShellResult(
                    0,
                    "boot-current\nNEMU_SEPARATOR\n" +
                        "boot-current planned boot-prev 1720000000\n"
                )
            }
        }
        val stale = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) = ShellResult(1, "")
        }
        val otherBoot = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) = ShellResult(
                0,
                "boot-current\nNEMU_SEPARATOR\n" +
                    "boot-other planned boot-prev 1720000000\n"
            )
        }
        val clean = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) = ShellResult(
                0,
                "boot-current\nNEMU_SEPARATOR\n" +
                    "boot-current clean boot-prev 1720000000\n"
            )
        }
        val completed = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) = ShellResult(
                0,
                "boot-current\nNEMU_SEPARATOR\n" +
                    "boot-current completed boot-prev 1710000000\n"
            )
        }

        // Guard writes epoch seconds; the App compares milliseconds.
        assertEquals(
            1_720_000_000_000L,
            BootGuard.lastPlannedShutdownTime(planned)
        )
        assertEquals(
            1_710_000_000_000L,
            BootGuard.lastPlannedShutdownTime(completed)
        )
        assertNull(BootGuard.lastPlannedShutdownTime(otherBoot))
        assertNull(BootGuard.lastPlannedShutdownTime(clean))
        assertNull(BootGuard.lastPlannedShutdownTime(stale))
        assertNull(BootGuard.lastPlannedShutdownTime(null))
    }

    @Test
    fun disabledMarkerAcceptsAnyCatalogPackage() = runBlocking {
        val root = FakeRoot()

        assertTrue(
            BootGuard.markDisabledByNemu(
                root,
                "com.hihonor.regservice",
                disabledByNemu = true
            )
        )
        assertTrue(
            BootGuard.markDisabledByNemu(
                root,
                "com.hihonor.systemserver",
                disabledByNemu = true
            )
        )

        assertEquals(2, root.commands.size)
    }

    @Test
    fun hiddenMarkerUsesOwnedDirectoryAndPackageName() = runBlocking {
        val root = FakeRoot()

        assertTrue(
            BootGuard.markHiddenByNemu(
                root,
                "com.hihonor.android.upgradeguide",
                hiddenByNemu = true
            )
        )

        val command = root.commands.single()
        assertTrue(command.contains("hidden_by_nemu"))
        assertTrue(command.contains("com.hihonor.android.upgradeguide"))
    }

    @Test
    fun appOpsMarkerStoresOriginalMode() = runBlocking {
        val root = FakeRoot()

        assertTrue(
            BootGuard.markAppOpsByNemu(
                root,
                "com.hihonor.securityserver",
                owned = true,
                originalMode = "allow"
            )
        )

        val command = root.commands.single()
        assertTrue(command.contains("appops_owned"))
        assertTrue(command.contains("allow"))
    }

    @Test
    fun standbyMarkerStoresOriginalBucket() = runBlocking {
        val root = FakeRoot()

        assertTrue(
            BootGuard.markStandbyByNemu(
                root,
                "com.hihonor.awareness",
                owned = true,
                originalBucket = 10
            )
        )

        val command = root.commands.single()
        assertTrue(command.contains("standby_owned"))
        assertTrue(command.contains("10"))
    }

    @Test
    fun guardHealthRequiresUnhideAndRestrictionSupport() {
        val health = GuardHealthParser.parse(
            "NEMU_GUARD_PRESENT\nNEMU_UNHIDE\nNEMU_APPOPS\nNEMU_STANDBY\n"
        )

        assertTrue(health.ready)
    }

    @Test
    fun guardHealthWithoutRestrictionSupportIsNotReady() {
        val health = GuardHealthParser.parse(
            "NEMU_GUARD_PRESENT\nNEMU_UNHIDE\n"
        )

        assertFalse(health.ready)
    }

    @Test
    fun nullBackendGuardHealthIsNotReady() = runBlocking {
        assertFalse(BootGuard.health(null).ready)
    }

    @Test
    fun rescueStateDistinguishesAbsentFromUnknown() = runBlocking {
        val absent = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) = ShellResult(0, "")
        }
        val present = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) =
                ShellResult(0, "NEMU_RESCUE\n")
        }
        val failed = object : RootPrivilegeBackend {
            override val name = "Root"
            override suspend fun isAvailable() = true
            override suspend fun exec(command: String) = ShellResult(1, "")
        }

        assertFalse(BootGuard.rescueState(absent)!!)
        assertTrue(BootGuard.rescueState(present)!!)
        assertNull(BootGuard.rescueState(failed))
        assertNull(BootGuard.rescueState(null))
    }

    @Test
    fun bootFreezeFailsClosedWhenRescueIsUnknown() {
        val source = com.nemu.hibernate.TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/BootFreezeWorker.kt"
        )

        assertTrue(source.contains("BootGuard.rescueState"))
        assertTrue(source.contains("BootStartupPolicy.rescueUnknown"))
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
