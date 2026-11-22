package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AntiFraudAction
import com.nemu.hibernate.core.AntiFraudCommand
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.NumberIdentityMode
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFraudControllerTest {
    private class FakeBackend(
        private val failCommandContaining: String? = null
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return if (failCommandContaining != null &&
                command.contains(failCommandContaining)
            ) {
                ShellResult(1, "boom")
            } else {
                ShellResult(0, "")
            }
        }
    }

    @Test
    fun dryRun_reportsPlannedCommandsWithoutExecuting() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = true)
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.SUSPEND,
            restrictSecurityServer = true
        )

        val results = controller.apply(config)

        assertTrue(backend.commands.isEmpty())
        assertEquals(AntiFraudPolicy.planApply(config).size, results.size)
        assertTrue(results.all { it.dryRun && it.ok })
    }

    @Test
    fun apply_executesPlannedCommandsInOrder() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(
            backend,
            dryRun = false,
            numberIdentityWasEnabled = true,
            enabledStateReader = { true }
        )
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.DISABLE_USER,
            restrictAwareness = true
        )

        val results = controller.apply(config)

        assertEquals(
            AntiFraudPolicy.planApply(config).map { it.shell },
            backend.commands
        )
        assertTrue(results.all { it.ok && !it.dryRun })
    }

    @Test
    fun apply_reportsFailureAndContinuesWithRemainingCommands() = runBlocking {
        val backend = FakeBackend(failCommandContaining = "pm suspend")
        val controller = AntiFraudController(backend, dryRun = false)
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.SUSPEND,
            restrictSecurityServer = true
        )

        val results = controller.apply(config)

        assertEquals(3, results.size)
        assertTrue(results.any { !it.ok })
        assertTrue(results.filter { it.packageName != "com.hihonor.numberidentity" }
            .all { it.ok })
        assertEquals(3, backend.commands.size)
    }

    @Test
    fun restoreAll_executesRestorePlan() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(
            backend,
            dryRun = false,
            numberIdentityWasEnabled = true,
            enabledStateReader = { true }
        )

        val results = controller.restoreAll()

        assertEquals(
            AntiFraudPolicy.planRestore(numberIdentityWasEnabled = true)
                .map { it.shell },
            backend.commands
        )
        assertEquals(6, results.size)
        assertTrue(results.all { it.ok })
    }

    @Test
    fun restoreAll_retainsOwnershipMarkerWhenEnableIsNotVerified() = runBlocking {
        val backend = FakeBackend()
        var markerCleared = false
        val controller = AntiFraudController(
            backend = backend,
            dryRun = false,
            numberIdentityWasEnabled = true,
            enabledStateReader = { false },
            afterRestoreByNemu = {
                markerCleared = true
                true
            }
        )

        val results = controller.restoreAll()

        assertFalse(markerCleared)
        assertTrue(results.any { !it.ok && it.output.contains("未确认启用") })
    }

    @Test
    fun restoreAll_unknownOriginalStateDoesNotEnableNumberIdentity() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(
            backend,
            dryRun = false,
            numberIdentityCurrentlyEnabled = false
        )

        val results = controller.restoreAll()

        assertFalse(backend.commands.any { it.contains("pm enable") })
        assertTrue(results.any { !it.ok && it.output.contains("原始启用状态未知") })
    }

    @Test
    fun execute_rejectsWholePackageDisableForSecurityServer() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = false)
        val forbidden = AntiFraudCommand(
            packageName = AntiFraudTargets.SECURITY_SERVER,
            action = AntiFraudAction.DISABLE_USER,
            shell = "pm disable-user --user 0 'com.hihonor.securityserver'"
        )

        val results = controller.execute(listOf(forbidden))

        assertTrue(backend.commands.isEmpty())
        assertEquals(1, results.size)
        assertFalse(results[0].ok)
        assertTrue(results[0].output.contains("未授权"))
    }

    @Test
    fun execute_rejectsSecurityPluginBase() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = false)
        val forbidden = AntiFraudCommand(
            packageName = AntiFraudTargets.SECURITY_PLUGIN_BASE,
            action = AntiFraudAction.APP_OPS_IGNORE,
            shell = "appops set --user 0 'com.hihonor.securitypluginbase' " +
                "RUN_ANY_IN_BACKGROUND ignore"
        )

        val results = controller.execute(listOf(forbidden))

        assertTrue(backend.commands.isEmpty())
        assertFalse(results[0].ok)
    }
}
