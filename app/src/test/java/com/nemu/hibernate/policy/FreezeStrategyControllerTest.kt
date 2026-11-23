package com.nemu.hibernate.policy

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.core.TargetComponent
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeStrategyControllerTest {
    private class FakeBackend(
        private val failAfter: Int = Int.MAX_VALUE,
        private val onExec: (String) -> Unit = {}
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            onExec(command)
            return if (commands.size > failAfter) {
                ShellResult(1, "injected failure")
            } else {
                ShellResult(0, "")
            }
        }
    }

    private val ownedComponent = TargetComponent(
        className = "com.example.app/com.example.app.OwnedReceiver",
        label = "OwnedReceiver"
    )
    private val enabledComponent = TargetComponent(
        className = "com.example.app/com.example.app.EnabledService",
        label = "EnabledService"
    )
    private val alreadyDisabledComponent = TargetComponent(
        className = "com.example.app/com.example.app.UserDisabledService",
        label = "UserDisabledService"
    )
    private val target = Target(
        packageName = "com.example.app",
        label = "Example",
        risk = RiskLevel.RED,
        category = TargetCategory.AI,
        components = listOf(
            ownedComponent,
            enabledComponent,
            alreadyDisabledComponent
        )
    )

    @Test
    fun componentApplyOwnsOnlyComponentsThatWereEnabledOrDefault() = runBlocking {
        var disabled = false
        val backend = FakeBackend(onExec = { command ->
            if (command.contains("pm disable")) disabled = true
        })
        val persisted = mutableMapOf<TargetComponent, Int?>()
        val markers = mutableListOf<Pair<TargetComponent, Boolean>>()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            componentStateReader = { component ->
                when {
                    component == alreadyDisabledComponent -> 2
                    disabled -> 2
                    component == ownedComponent -> 0
                    else -> 1
                }
            },
            markComponentByNemu = { component, disabled ->
                markers += component to disabled
                true
            },
            onComponentOriginalState = { component, state ->
                persisted[component] = state
            }
        )

        val result = controller.apply(target, FreezeStrategy.COMPONENT)

        assertTrue(result.ok)
        assertEquals(0, persisted[ownedComponent])
        assertEquals(1, persisted[enabledComponent])
        assertFalse(persisted.containsKey(alreadyDisabledComponent))
        assertTrue(
            backend.commands.any {
                it.contains("pm disable") && it.contains("OwnedReceiver")
            }
        )
        assertTrue(
            backend.commands.any {
                it.contains("pm disable") && it.contains("EnabledService")
            }
        )
        assertFalse(
            backend.commands.any { it.contains("UserDisabledService") }
        )
        assertEquals(2, markers.count { it.second })
    }

    @Test
    fun componentApplyHonorsSelectedSubset() = runBlocking {
        var disabled = false
        val backend = FakeBackend(onExec = { command ->
            if (command.contains("pm disable")) disabled = true
        })
        val persisted = mutableMapOf<TargetComponent, Int?>()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            componentStateReader = { if (disabled) 2 else 0 },
            onComponentOriginalState = { component, state ->
                persisted[component] = state
            }
        )

        val result = controller.apply(
            target,
            FreezeStrategy.COMPONENT,
            componentsOverride = listOf(ownedComponent)
        )

        assertTrue(result.ok)
        assertTrue(
            backend.commands.any {
                it.contains("pm disable") && it.contains("OwnedReceiver")
            }
        )
        assertFalse(
            backend.commands.any { it.contains("EnabledService") }
        )
        assertEquals(setOf(ownedComponent), persisted.keys)
    }

    @Test
    fun componentRestoreUsesExactOriginalState() = runBlocking {
        val backend = FakeBackend()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            componentOriginalState = { component ->
                when (component) {
                    ownedComponent -> 0
                    enabledComponent -> 1
                    else -> null
                }
            }
        )

        val result = controller.restore(target, FreezeStrategy.COMPONENT)

        assertTrue(result.ok)
        assertTrue(
            backend.commands.any {
                it.contains("pm default-state") && it.contains("OwnedReceiver")
            }
        )
        assertTrue(
            backend.commands.any {
                it.contains("pm enable") && it.contains("EnabledService")
            }
        )
    }

    @Test
    fun dryRunComponentDoesNotPersistOwnership() = runBlocking {
        val backend = FakeBackend()
        val persisted = mutableListOf<Pair<TargetComponent, Int?>>()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = true,
            componentStateReader = { 0 },
            onComponentOriginalState = { component, state ->
                persisted += component to state
            }
        )

        val result = controller.apply(target, FreezeStrategy.COMPONENT)

        assertTrue(result.ok)
        assertTrue(persisted.isEmpty())
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun disableUserRestoreWithUnknownBaselineFailsClosed() = runBlocking {
        val backend = FakeBackend()
        val yellow = target.copy(
            packageName = "com.example.yellow",
            risk = RiskLevel.YELLOW,
            components = emptyList()
        )
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            enabledStateReader = { false }
        )

        val result = controller.restore(
            yellow,
            FreezeStrategy.DISABLE_USER,
            originalEnabled = null
        )

        assertFalse(result.ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun disableUserApplyNullReadbackFailsClosed() = runBlocking {
        val backend = FakeBackend()
        val yellow = target.copy(
            packageName = "com.example.disable",
            risk = RiskLevel.YELLOW,
            components = emptyList()
        )
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            enabledStateReader = { null }
        )

        val result = controller.apply(
            yellow,
            FreezeStrategy.DISABLE_USER,
            originalEnabled = true
        )

        assertFalse(result.ok)
        assertTrue(
            result.reasons.any {
                it.reason == com.nemu.hibernate.core.ResultReason.PACKAGE_NOT_CONFIRMED
            }
        )
    }

    @Test
    fun suspendApplyReportsFailureWhenACommandFails() = runBlocking {
        val backend = FakeBackend(failAfter = 1)
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false
        )

        val result = controller.apply(target, FreezeStrategy.SUSPEND)

        assertFalse(result.ok)
        assertEquals(2, backend.commands.size)
    }

    @Test
    fun componentMarkerFailureIsReportedButDisabledComponentsKeepOwnership() = runBlocking {
        var disabled = false
        val backend = FakeBackend(onExec = { command ->
            if (command.contains("pm disable")) disabled = true
        })
        val persisted = mutableMapOf<TargetComponent, Int?>()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            componentStateReader = { if (disabled) 2 else 0 },
            componentOriginalState = { 0 },
            markComponentByNemu = { _, _ -> false },
            onComponentOriginalState = { component, state ->
                persisted[component] = state
            }
        )

        val result = controller.apply(target, FreezeStrategy.COMPONENT)

        assertFalse(result.ok)
        // The component is already disabled and owned; re-apply is idempotent
        // even though marker rewriting failed.
        assertEquals(0, persisted[ownedComponent])
    }

    @Test
    fun componentApplyUnknownReadbackKeepsBaseline() = runBlocking {
        var disabled = false
        val backend = FakeBackend(onExec = { command ->
            if (command.contains("pm disable")) disabled = true
        })
        val persisted = mutableMapOf<TargetComponent, Int?>()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            componentStateReader = { if (disabled) null else 0 },
            onComponentOriginalState = { component, state ->
                persisted[component] = state
            }
        )

        val result = controller.apply(target, FreezeStrategy.COMPONENT)

        assertFalse(result.ok)
        assertTrue(
            "读回未知时不能删除唯一可恢复的原始状态",
            persisted[ownedComponent] == 0
        )
        assertEquals(
            "读回未知应报告状态未知",
            true,
            result.reasons.any {
                it.reason == com.nemu.hibernate.core.ResultReason.COMPONENT_STATE_UNKNOWN
            }
        )
    }

    @Test
    fun componentApplyWithoutReadableStateSkipsEverything() = runBlocking {
        val backend = FakeBackend()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            componentStateReader = { null }
        )

        val result = controller.apply(target, FreezeStrategy.COMPONENT)

        assertFalse(result.ok)
        assertTrue(backend.commands.isEmpty())
    }

    private class HideBackend : PrivilegeBackend {
        override val name: String = "FakeHide"
        override val canSetHidden: Boolean = true
        var hidden: Boolean = false
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return when {
                command.contains("pm hide") -> {
                    hidden = true
                    ShellResult(0, "Package x new hidden state: true")
                }
                command.contains("pm unhide") -> {
                    hidden = false
                    ShellResult(0, "Package x new hidden state: false")
                }
                else -> ShellResult(0, "")
            }
        }
    }

    @Test
    fun hideApplyIsConfirmedFromDumpsysReader() = runBlocking {
        val yellow = target.copy(
            packageName = "com.example.yellow",
            risk = RiskLevel.YELLOW,
            components = emptyList()
        )
        val backend = HideBackend()
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            hiddenStateReader = { backend.hidden }
        )

        val apply = controller.apply(yellow, FreezeStrategy.HIDE)
        val restore = controller.restore(yellow, FreezeStrategy.HIDE)

        assertTrue(apply.ok)
        assertTrue(restore.ok)
        assertTrue(backend.commands[0].contains("pm hide"))
        assertTrue(backend.commands[1].contains("pm unhide"))
    }

    @Test
    fun hideApplyWithoutStateConfirmationIsReportedAsFailure() = runBlocking {
        val yellow = target.copy(
            packageName = "com.example.yellow",
            risk = RiskLevel.YELLOW,
            components = emptyList()
        )
        val backend = HideBackend()
        // stdout claims success but the authoritative read says still visible.
        val controller = FreezeStrategyController(
            backend = backend,
            dryRun = false,
            hiddenStateReader = { false }
        )

        val apply = controller.apply(yellow, FreezeStrategy.HIDE)

        assertFalse(apply.ok)
    }

    @Test
    fun hideApplyWithoutReaderDoesNotTrustStdout() = runBlocking {
        val yellow = target.copy(
            packageName = "com.example.yellow",
            risk = RiskLevel.YELLOW,
            components = emptyList()
        )
        val backend = HideBackend()
        val controller = FreezeStrategyController(backend = backend, dryRun = false)

        val apply = controller.apply(yellow, FreezeStrategy.HIDE)

        // No reader means unknown, not success.
        assertTrue(apply.notes.any { it.contains("无法确认隐藏") })
    }
}
