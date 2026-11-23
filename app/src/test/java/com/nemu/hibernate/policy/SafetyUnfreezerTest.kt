package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.core.Targets
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyUnfreezerTest {
    private class FakeBackend(
        private val result: ShellResult = ShellResult(0, "")
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        var lastCommand: String? = null
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            lastCommand = command
            commands += command
            return result
        }
    }

    @Test
    fun unsuspendAll_sends_one_command_per_target() = runBlocking {
        val backend = FakeBackend()
        val names = Targets.all.map { it.packageName }

        val ok = SafetyUnfreezer.unsuspendAll(backend, Targets.all)

        assertTrue(ok)
        assertEquals(names.size, backend.commands.size)
        assertEquals(CommandBuilder.unsuspend(names.last()), backend.lastCommand)
    }

    @Test
    fun unsuspendAll_reports_failure() = runBlocking {
        val backend = FakeBackend(ShellResult(1, "boom"))

        assertFalse(SafetyUnfreezer.unsuspendAll(backend, Targets.all))
    }

    @Test
    fun unsuspendAll_emptyTargets_is_noop() = runBlocking {
        val backend = FakeBackend()

        assertTrue(SafetyUnfreezer.unsuspendAll(backend, emptyList()))
        assertNull(backend.lastCommand)
    }

    @Test
    fun unsuspendAll_fails_when_output_reports_still_suspended() = runBlocking {
        val backend = FakeBackend(
            ShellResult(
                0,
                "Package com.hihonor.ouc new suspended state: true\n"
            )
        )

        val result = SafetyUnfreezer.runUnsuspendPackages(
            backend,
            listOf("com.hihonor.ouc")
        )

        assertFalse(result.ok)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun backendFailureContainingNotFoundIsNotResolved() = runBlocking {
        val backend = FakeBackend(
            ShellResult(-1, "su not found; tried /system/bin/su")
        )

        val result = SafetyUnfreezer.runUnsuspendPackages(
            backend,
            listOf("com.hihonor.ouc")
        )

        assertFalse(result.ok)
    }

    @Test
    fun unsuspendPackages_acceptsExtraSafetyPackages() = runBlocking {
        val backend = FakeBackend()
        val packages = Targets.all.map { it.packageName } +
            listOf("com.hihonor.hiview", "com.hihonor.numberidentity")

        val ok = SafetyUnfreezer.unsuspendPackages(backend, packages)

        assertTrue(ok)
        assertEquals(CommandBuilder.unsuspend(packages.last()), backend.lastCommand)
        assertEquals(packages.size, backend.commands.size)
    }

    @Test
    fun unknown_package_is_treated_as_resolved() = runBlocking {
        val backend = FakeBackend(
            ShellResult(
                1,
                "java.lang.IllegalArgumentException: Unknown target package: com.gone"
            )
        )

        assertTrue(
            SafetyUnfreezer.unsuspendPackages(backend, listOf("com.gone"))
        )
    }
}
