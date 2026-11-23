package com.nemu.hibernate.policy

import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionControllerTest {
    private class FakeBackend(
        private val onExec: (String) -> Unit = {},
        private val exitCode: Int = 0
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            onExec(command)
            return ShellResult(exitCode, "")
        }
    }

    private val target = Target(
        packageName = "com.example.restricted",
        label = "Restricted",
        risk = RiskLevel.RED,
        category = TargetCategory.SAFETY
    )

    private fun controller(
        backend: FakeBackend,
        appOpsState: MutableMap<String, String?>,
        standbyState: MutableMap<String, Int?> = mutableMapOf(),
        dryRun: Boolean = false,
        beforeOwn: suspend (String, RestrictionKind) -> Boolean = { _, _ -> true }
    ): RestrictionController {
        val originals = mutableMapOf<String, String?>()
        val owned = mutableSetOf<String>()
        return RestrictionController(
            backend = backend,
            dryRun = dryRun,
            appOpsReader = { appOpsState[it] },
            standbyReader = { standbyState[it] },
            originalAppOps = { originals[it] },
            onOriginalAppOps = { pkg, mode -> originals[pkg] = mode; true },
            appOpsOwned = { it in owned },
            setAppOpsOwned = { pkg, value ->
                if (value) owned += pkg else owned -= pkg
                true
            },
            beforeOwn = beforeOwn
        )
    }

    @Test
    fun applyCapturesOriginalAndVerifiesIgnore() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "allow")
        val backend = FakeBackend(onExec = { command ->
            if (command.contains("RUN_ANY_IN_BACKGROUND") &&
                command.contains("ignore")
            ) {
                state[target.packageName] = "ignore"
            }
        })
        val controller = controller(backend, state)

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertTrue(result.ok)
        assertTrue(result.appOpsOk == true)
        assertTrue(
            backend.commands.any {
                it.contains("RUN_ANY_IN_BACKGROUND") && it.contains("ignore")
            }
        )
    }

    @Test
    fun alreadyRestrictedIsNotClaimed() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "ignore")
        val backend = FakeBackend()
        val controller = controller(backend, state)

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertTrue(result.ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun unknownAppOpsStateFailsClosed() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to null)
        val backend = FakeBackend()
        val controller = controller(backend, state)

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertFalse(result.ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun restoreUsesOriginalModeAndClearsOwnership() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "ignore")
        val backend = FakeBackend(onExec = { command ->
            if (command.contains("RUN_ANY_IN_BACKGROUND") &&
                command.contains("allow")
            ) {
                state[target.packageName] = "allow"
            }
        })
        var original: String? = "allow"
        var owned = true
        val controller = RestrictionController(
            backend = backend,
            dryRun = false,
            appOpsReader = { state[it] },
            originalAppOps = { original },
            onOriginalAppOps = { _, mode -> original = mode; true },
            appOpsOwned = { owned },
            setAppOpsOwned = { _, value -> owned = value; true }
        )

        val result = controller.restore(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertTrue(result.ok)
        assertTrue(
            backend.commands.any {
                it.contains("RUN_ANY_IN_BACKGROUND") && it.contains("allow")
            }
        )
        assertFalse(owned)
    }

    @Test
    fun standbyApplyAndRestoreUseOriginalBucket() = runBlocking {
        val state = mutableMapOf<String, Int?>(target.packageName to 10)
        val backend = FakeBackend(onExec = { command ->
            when {
                command.trim().endsWith("45") -> state[target.packageName] = 45
                command.trim().endsWith("10") -> state[target.packageName] = 10
            }
        })
        var original: Int? = null
        var owned = false
        val controller = RestrictionController(
            backend = backend,
            dryRun = false,
            standbyReader = { state[it] },
            originalStandby = { original },
            onOriginalStandby = { _, bucket -> original = bucket; true },
            standbyOwned = { owned },
            setStandbyOwned = { _, value -> owned = value; true }
        )

        val applied = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(standby = true)
        )
        assertTrue(applied.ok)
        assertEquals(45, state[target.packageName])

        val restored = controller.restore(
            target,
            com.nemu.hibernate.core.StrategyOptions(standby = true)
        )
        assertTrue(restored.ok)
        assertEquals(10, state[target.packageName])
        assertFalse(owned)
    }

    @Test
    fun dryRunDoesNotExecuteOrWriteOwnership() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "allow")
        val backend = FakeBackend()
        var owned = false
        val controller = RestrictionController(
            backend = backend,
            dryRun = true,
            appOpsReader = { state[it] },
            originalAppOps = { null },
            onOriginalAppOps = { _, _ -> true },
            appOpsOwned = { owned },
            setAppOpsOwned = { _, value -> owned = value; true }
        )

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertTrue(result.ok)
        assertTrue(backend.commands.isEmpty())
        assertFalse(owned)
    }

    /**
     * The set command exited 0 but the read-back was unreadable. The
     * restriction may be live, so ownership must be kept for restore/residual
     * instead of being rolled back and stranding it.
     */
    @Test
    fun successfulCommandWithUnreadableReadBackKeepsOwnership() = runBlocking {
        val backend = FakeBackend()
        var owned = false
        var original: String? = null
        // Baseline read succeeds; the post-command read-back is unreadable.
        var nextRead: String? = "allow"
        val controller = RestrictionController(
            backend = backend,
            dryRun = false,
            appOpsReader = { nextRead.also { nextRead = null } },
            originalAppOps = { original },
            onOriginalAppOps = { _, mode -> original = mode; true },
            appOpsOwned = { owned },
            setAppOpsOwned = { _, value -> owned = value; true }
        )

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertFalse(result.ok)
        assertTrue(backend.commands.isNotEmpty())
        assertTrue(owned)
        assertEquals("allow", original)
    }

    /**
     * `appops set` exits 0 but the readable value never changes. This is a
     * definitive non-application: ownership must be rolled back so the UI
     * does not claim "已限制".
     */
    @Test
    fun readableUnrestrictedStateRollsBackOwnership() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "allow")
        val backend = FakeBackend()
        var owned = false
        val controller = RestrictionController(
            backend = backend,
            dryRun = false,
            appOpsReader = { state[it] },
            originalAppOps = { "allow" },
            onOriginalAppOps = { _, _ -> true },
            appOpsOwned = { owned },
            setAppOpsOwned = { _, value -> owned = value; true }
        )

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertFalse(result.ok)
        assertTrue(backend.commands.isNotEmpty())
        assertFalse(owned)
    }

    /** A command that fails without changing the state releases ownership. */
    @Test
    fun failedCommandWithoutConfirmationRollsBackOwnership() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "allow")
        val backend = FakeBackend(exitCode = 1)
        var owned = false
        val controller = RestrictionController(
            backend = backend,
            dryRun = false,
            appOpsReader = { state[it] },
            originalAppOps = { "allow" },
            onOriginalAppOps = { _, _ -> true },
            appOpsOwned = { owned },
            setAppOpsOwned = { _, value -> owned = value; true }
        )

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertFalse(result.ok)
        assertFalse(owned)
    }

    @Test
    fun markerFailureAbortsBeforeCommand() = runBlocking {
        val state = mutableMapOf<String, String?>(target.packageName to "allow")
        val backend = FakeBackend()
        val controller = controller(
            backend = backend,
            appOpsState = state,
            beforeOwn = { _, _ -> false }
        )

        val result = controller.apply(
            target,
            com.nemu.hibernate.core.StrategyOptions(appOps = true)
        )

        assertFalse(result.ok)
        assertTrue(backend.commands.isEmpty())
    }
}
