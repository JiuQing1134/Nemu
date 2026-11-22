package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudTarget
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.data.CloudSettingsStore
import com.nemu.hibernate.detection.ProcessListSnapshot
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudControllerTest {
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

    private class FakeStore(
        enabled: Set<String> = emptySet(),
        confirmed: Set<String> = emptySet(),
        originalEnabled: Map<String, Boolean> = emptyMap(),
        override var dryRun: Boolean = false
    ) : CloudSettingsStore {
        override var lastResult: String? = null
        private val enabledSet = enabled.toMutableSet()
        private val confirmedSet = confirmed.toMutableSet()
        private val appliedSet = mutableSetOf<String>()
        private val originalEnabledState = originalEnabled.toMutableMap()

        override fun isEnabled(packageName: String): Boolean =
            packageName in enabledSet

        override fun setEnabled(packageName: String, enabled: Boolean) {
            if (enabled) enabledSet.add(packageName) else enabledSet.remove(packageName)
            appliedSet.remove(packageName)
        }

        override fun isConfirmed(packageName: String): Boolean =
            packageName in confirmedSet

        override fun setConfirmed(packageName: String, confirmed: Boolean) {
            if (confirmed) confirmedSet.add(packageName) else confirmedSet.remove(packageName)
        }

        override fun isApplied(packageName: String): Boolean =
            packageName in appliedSet

        override fun setApplied(packageName: String, applied: Boolean) {
            if (applied) appliedSet.add(packageName) else appliedSet.remove(packageName)
        }

        override fun originalEnabled(packageName: String): Boolean? =
            originalEnabledState[packageName]

        override fun setOriginalEnabled(packageName: String, enabled: Boolean?) {
            if (enabled == null) originalEnabledState.remove(packageName)
            else originalEnabledState[packageName] = enabled
        }
    }

    private val regservice = "com.hihonor.regservice"
    private val hiview = "com.hihonor.hiview"

    @Test
    fun apply_skips_targets_that_are_not_enabled() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).apply(CloudTargets.all)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("没有启用的目标"))
    }

    @Test
    fun apply_disable_user_requires_confirmation() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf(regservice))

        val ok = CloudController(backend, store, enabledStateReader = { true })
            .apply(CloudTargets.carrierDm)

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("有失败或拒绝"))
    }

    @Test
    fun apply_disable_user_runs_after_confirmation() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(
            enabled = setOf(regservice),
            confirmed = setOf(regservice)
        )

        val ok = CloudController(backend, store, enabledStateReader = { true })
            .apply(CloudTargets.carrierDm)

        assertTrue(ok)
        assertEquals(listOf(CommandBuilder.disableUser(regservice)), backend.commands)
        assertTrue(store.lastResult!!.startsWith("成功"))
        assertTrue(store.isApplied(regservice))
    }

    @Test
    fun apply_dry_run_does_not_mark_policy_applied() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(
            enabled = setOf(regservice),
            confirmed = setOf(regservice),
            dryRun = true
        )

        CloudController(backend, store, enabledStateReader = { true })
            .apply(CloudTargets.carrierDm)

        assertTrue(backend.commands.isEmpty())
        assertFalse(store.isApplied(regservice))
    }

    @Test
    fun apply_refuses_disable_user_outside_the_carrier_trio() = runBlocking {
        val backend = FakeBackend()
        val target = CloudTarget(
            packageName = "com.example.dm",
            label = "Outside",
            action = CloudAction.DISABLE_USER,
            description = "test"
        )
        val store = FakeStore(
            enabled = setOf(target.packageName),
            confirmed = setOf(target.packageName)
        )

        val ok = CloudController(backend, store).apply(listOf(target))

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_refuses_denylisted_target() = runBlocking {
        val backend = FakeBackend()
        val target = CloudTarget(
            packageName = "com.hihonor.android.pushagent",
            label = "Push",
            action = CloudAction.SUSPEND,
            description = "test"
        )
        val store = FakeStore(enabled = setOf(target.packageName))

        val ok = CloudController(backend, store).apply(listOf(target))

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_suspend_target_runs_suspend_then_force_stop() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf(hiview))

        val ok = CloudController(backend, store).apply(CloudTargets.telemetry)

        assertTrue(ok)
        assertEquals(
            listOf(
                CommandBuilder.suspend(hiview),
                CommandBuilder.forceStop(hiview)
            ),
            backend.commands
        )
    }

    @Test
    fun apply_marksLiveProcessAsPartialEvenWhenSuspendCommandSucceeded() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf(hiview))

        val ok = CloudController(
            backend = backend,
            store = store,
            suspendedStateReader = { true },
            processSnapshotReader = {
                ProcessListSnapshot(setOf("$hiview:service"))
            }
        ).apply(CloudTargets.telemetry)

        assertTrue(ok)
        assertTrue(store.isApplied(hiview))
        assertTrue(store.lastResult!!.contains("仍有进程"))
    }

    @Test
    fun applyReportsPmSuspendMismatchAfterSuccessfulShellExit() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf(hiview))

        val ok = CloudController(
            backend = backend,
            store = store,
            suspendedStateReader = { false },
            processSnapshotReader = { ProcessListSnapshot(emptySet()) }
        ).apply(CloudTargets.telemetry)

        assertFalse(ok)
        assertTrue(store.isApplied(hiview))
        assertTrue(store.lastResult!!.contains("PackageManager 未确认"))
    }

    @Test
    fun apply_observe_only_target_is_a_noop() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf("com.hihonor.cipservice"))

        val ok = CloudController(backend, store).apply(CloudTargets.all)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_dry_run_does_not_call_backend() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(
            enabled = setOf(regservice),
            confirmed = setOf(regservice),
            dryRun = true
        )

        val ok = CloudController(backend, store, enabledStateReader = { true })
            .apply(CloudTargets.carrierDm)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("成功"))
        assertTrue(store.lastResult!!.contains("DRY-RUN"))
    }

    @Test
    fun apply_continues_after_a_command_failure() = runBlocking {
        val backend = FakeBackend(mutableListOf(ShellResult(1, "boom")))
        val store = FakeStore(enabled = setOf(hiview))

        val ok = CloudController(backend, store).apply(CloudTargets.telemetry)

        assertFalse(ok)
        assertEquals(
            listOf(
                CommandBuilder.suspend(hiview),
                CommandBuilder.forceStop(hiview)
            ),
            backend.commands
        )
    }

    @Test
    fun restore_all_enables_disable_user_targets() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(originalEnabled = mapOf(regservice to true))

        val ok = CloudController(backend, store, enabledStateReader = { true })
            .restoreAll(CloudTargets.carrierDm.take(1))

        assertTrue(ok)
        assertEquals(
            listOf(CommandBuilder.enable(regservice)),
            backend.commands
        )
    }

    @Test
    fun restore_all_dryRunDoesNotClearOwnedDisableMarkerOrBaseline() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(
            originalEnabled = mapOf(regservice to true),
            dryRun = true
        )
        var markerCleared = false

        val ok = CloudController(
            backend = backend,
            store = store,
            enabledStateReader = { true },
            afterRestoreByNemu = {
                markerCleared = true
                true
            }
        ).restoreAll(CloudTargets.carrierDm.take(1))

        assertTrue(ok)
        assertFalse(markerCleared)
        assertEquals(true, store.originalEnabled(regservice))
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun restore_allKeepsOwnedMarkerUntilPackageIsVerifiedEnabled() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(originalEnabled = mapOf(regservice to true))
        var markerCleared = false

        val ok = CloudController(
            backend = backend,
            store = store,
            enabledStateReader = { false },
            afterRestoreByNemu = {
                markerCleared = true
                true
            }
        ).restoreAll(CloudTargets.carrierDm.take(1))

        assertFalse(ok)
        assertFalse(markerCleared)
        assertEquals(true, store.originalEnabled(regservice))
    }

    @Test
    fun restore_all_doesNotEnableTargetDisabledBeforeNemu() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(originalEnabled = mapOf(regservice to false))

        val ok = CloudController(backend, store).restoreAll(
            CloudTargets.carrierDm.take(1)
        )

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun restore_all_unknownDisabledBaselineFailsClosed() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).restoreAll(
            CloudTargets.carrierDm.take(1)
        )

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun restore_all_unsuspends_telemetry_targets() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).restoreAll(CloudTargets.telemetry)

        assertTrue(ok)
        assertEquals(
            CloudTargets.telemetry.map { CommandBuilder.unsuspend(it.packageName) },
            backend.commands
        )
    }

    @Test
    fun restore_all_ignores_enabled_flags_and_observe_only() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store, enabledStateReader = { true })
            .restoreAll(CloudTargets.all)

        assertTrue(ok)
        assertEquals(CloudTargets.telemetry.size, backend.commands.size)
        assertFalse(backend.commands.any { it.contains("cipservice") })
    }

    @Test
    fun restore_all_respects_dry_run() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(dryRun = true)

        val ok = CloudController(backend, store, enabledStateReader = { true })
            .restoreAll(CloudTargets.all)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("成功"))
    }

    @Test
    fun last_result_is_recorded_for_restore() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        CloudController(backend, store, enabledStateReader = { true })
            .restoreAll(CloudTargets.all)

        assertTrue(store.lastResult!!.startsWith("成功"))
    }
}
