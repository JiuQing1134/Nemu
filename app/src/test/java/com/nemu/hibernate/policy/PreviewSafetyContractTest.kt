package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-04: preview only gates user-initiated apply/restore. Safety paths
 * (residual unfreeze, shutdown, boot recovery, HIDE rescue) always execute.
 */
class PreviewSafetyContractTest {
    private class FakeBackend : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return ShellResult(0, "")
        }
    }

    @Test
    fun safetyUnsuspendHasNoPreviewGate() = runBlocking {
        val backend = FakeBackend()

        SafetyUnfreezer.unsuspendPackages(backend, listOf("com.hihonor.ouc"))

        assertEquals(1, backend.commands.size)
        assertEquals(CommandBuilder.unsuspend("com.hihonor.ouc"), backend.commands[0])
    }

    @Test
    fun shutdownBudgetIsEightSeconds() {
        assertEquals(8_000L, ShutdownSafety.SHUTDOWN_BUDGET_MS)
    }

    @Test
    fun safetyConstructorsForceActualExecution() {
        listOf(
            "src/main/java/com/nemu/hibernate/MainActivity.kt",
            "src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt",
            "src/main/java/com/nemu/hibernate/policy/BootWorker.kt",
            "src/main/java/com/nemu/hibernate/policy/BootFreezeWorker.kt"
        ).forEach { path ->
            val source = TestSources.read(path)
            assertTrue("$path 未强制 dryRun=false", source.contains("dryRun = false"))
        }
    }

    @Test
    fun previewRestoreAllSkipsActualSafetyResidual() {
        val main = TestSources.read(
            "src/main/java/com/nemu/hibernate/MainActivity.kt"
        )

        assertTrue(
            "全部解冻必须通过执行策略决定是否跑安全残留",
            Regex(
                "RestoreExecutionPolicy\\.runsResidualCleanup\\(\\s*settings\\.dryRun"
            ).containsMatchIn(main)
        )
        assertTrue(
            "预览必须使用明确的跳过文案",
            main.contains("RestoreExecutionPolicy.residualLabel(true)")
        )
    }

    @Test
    fun safetyUnsuspendHasNoDryRunParameter() {
        val source = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/SafetyUnfreezer.kt"
        )

        assertFalse(
            "SafetyUnfreezer 不应接受或读取 dryRun",
            Regex("dryRun\\s*[:=]").containsMatchIn(source)
        )
    }
}
