package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFreezeControllerTest {
    private class FakeBackend(
        private val results: MutableList<ShellResult> = mutableListOf()
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return if (results.isEmpty()) ShellResult(0, "") else results.removeAt(0)
        }
    }

    @Test
    fun apply_only_targets_ai_whitelist() = runBlocking {
        val backend = FakeBackend()
        val outsider = Target(
            packageName = "com.example.notai",
            label = "Not AI",
            risk = RiskLevel.GREEN
        )

        val ok = AiFreezeController(backend, dryRun = false)
            .apply(AiTargets.all + outsider)

        assertFalse(ok)
        assertEquals(
            AiTargets.all.map { it.packageName }.toSet(),
            backend.commands.map { extractPackage(it) }.toSet()
        )
        assertTrue(backend.commands.none { it.contains(outsider.packageName) })
    }

    @Test
    fun apply_suspends_then_force_stops_each_ai_target() = runBlocking {
        val backend = FakeBackend()

        val ok = AiFreezeController(backend, dryRun = false).apply(AiTargets.all)

        assertTrue(ok)
        assertEquals(
            AiTargets.all.flatMap {
                listOf(
                    CommandBuilder.suspend(it.packageName),
                    CommandBuilder.forceStop(it.packageName)
                )
            },
            backend.commands
        )
    }

    @Test
    fun apply_never_clears_cache_even_when_target_requests_it() = runBlocking {
        val backend = FakeBackend()
        val target = AiTargets.byPackage("com.hihonor.aipluginengine")!!
            .copy(clearCache = true)

        val ok = AiFreezeController(backend, dryRun = false).apply(listOf(target))

        assertTrue(ok)
        assertEquals(
            listOf(
                CommandBuilder.suspend(target.packageName),
                CommandBuilder.forceStop(target.packageName)
            ),
            backend.commands
        )
        assertTrue(backend.commands.none { it.contains("clear") })
    }

    @Test
    fun apply_never_disables_or_uninstalls() = runBlocking {
        val backend = FakeBackend()

        AiFreezeController(backend, dryRun = false).apply(AiTargets.all)

        assertTrue(backend.commands.none { it.contains("disable-user") })
        assertTrue(backend.commands.none { it.contains("uninstall") })
        assertTrue(backend.commands.none { it.contains("pm clear") })
    }

    @Test
    fun restore_only_unsuspends_ai_targets() = runBlocking {
        val backend = FakeBackend()
        val outsider = Target(
            packageName = "com.example.notai",
            label = "Not AI",
            risk = RiskLevel.GREEN
        )

        val ok = AiFreezeController(backend, dryRun = false)
            .restore(AiTargets.all + outsider)

        assertFalse(ok)
        assertEquals(
            AiTargets.all.map { CommandBuilder.unsuspend(it.packageName) },
            backend.commands
        )
        assertTrue(backend.commands.all { it.startsWith("pm unsuspend ") })
    }

    @Test
    fun restore_accepts_a_selected_subset_without_touching_other_ai_targets() = runBlocking {
        val backend = FakeBackend()
        val selected = AiTargets.all.take(2)

        val ok = AiFreezeController(backend, dryRun = false).restore(selected)

        assertTrue(ok)
        assertEquals(selected.map { CommandBuilder.unsuspend(it.packageName) }, backend.commands)
    }

    @Test
    fun dry_run_does_not_touch_the_backend() = runBlocking {
        val backend = FakeBackend()

        val applyOk = AiFreezeController(backend, dryRun = true).apply(AiTargets.all)
        val restoreOk = AiFreezeController(backend, dryRun = true).restore(AiTargets.all)

        assertTrue(applyOk)
        assertTrue(restoreOk)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_continues_after_a_command_failure() = runBlocking {
        val backend = FakeBackend(mutableListOf(ShellResult(1, "boom")))

        val ok = AiFreezeController(backend, dryRun = false).apply(AiTargets.all)

        assertFalse(ok)
        assertEquals(AiTargets.all.size * 2, backend.commands.size)
    }

    @Test
    fun empty_target_list_is_a_noop_success() = runBlocking {
        val backend = FakeBackend()

        val ok = AiFreezeController(backend, dryRun = false).apply(emptyList())

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
    }

    private fun extractPackage(command: String): String =
        command.substringAfterLast(' ').trim('\'', '"')
}
