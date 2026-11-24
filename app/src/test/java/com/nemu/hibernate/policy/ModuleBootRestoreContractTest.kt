package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the KernelSU module: every state Nemu can freeze must be
 * restored before the launcher is reachable, not only after a rescue.
 */
class ModuleBootRestoreContractTest {
    private fun guard(): String =
        TestSources.read("tools/nemu-boot-autostart/boot_guard.sh")

    private fun bootUnfreeze(): String =
        TestSources.read("tools/nemu-boot-autostart/boot_unfreeze.sh")

    private fun service(): String =
        TestSources.read("tools/nemu-boot-autostart/service.sh")

    private fun shutdownDaemon(): String =
        TestSources.read("tools/nemu-boot-autostart/shutdown_daemon.sh")

    @Test
    fun normalBootRestoresEveryOwnedFreezeState() {
        val script = guard()
        val body = Regex(
            "restore_owned_state\\(\\)\\s*\\{(.*?)\\n\\}",
            RegexOption.DOT_MATCHES_ALL
        ).find(script)?.groupValues?.get(1)

        assertNotNull(
            "boot_guard.sh 必须提供 restore_owned_state，普通开机走同一恢复集",
            body
        )
        listOf(
            "enable_disabled_targets",
            "restore_owned_components",
            "restore_owned_hidden",
            "restore_owned_appops",
            "restore_owned_standby",
            "unsuspend_owned"
        ).forEach { call ->
            assertTrue(
                "restore_owned_state 必须恢复 $call",
                body!!.contains(call)
            )
        }

        val serviceStart = script.indexOf("    service)")
        val serviceEnd = script.indexOf("    rescue)", serviceStart)
        assertTrue(serviceStart >= 0 && serviceEnd > serviceStart)
        assertTrue(
            "普通 service 启动必须调用 restore_owned_state",
            script.substring(serviceStart, serviceEnd)
                .contains("restore_owned_state")
        )
    }

    @Test
    fun earlyModeRestoresBeforeBootCompleted() {
        assertTrue(
            "boot_guard.sh 必须提供 early 模式",
            Regex("\\n\\s*early\\)").containsMatchIn(guard())
        )
        assertTrue(
            "boot_unfreeze.sh 必须在 PM 可用后调用 guard early",
            bootUnfreeze().contains("\"\$GUARD\" early")
        )
        assertTrue(
            "boot_unfreeze.sh 必须给本 boot 写 early_done",
            bootUnfreeze().contains("early_done")
        )
        assertTrue(
            "guard service 必须按 early_done 判断是否还需早遍",
            guard().contains("early_done")
        )
    }

    @Test
    fun guardRunsEvenWhenAppRestoreScriptIsMissing() {
        val script = bootUnfreeze()

        assertFalse(
            "空 restore_frozen.sh 不能阻止 guard 按 ownership 标记恢复",
            script.contains("[ -s \"\$SCRIPT\" ] || exit 0")
        )
        assertTrue(
            "boot_unfreeze.sh 必须调用已安装的 guard",
            script.contains("GUARD")
        )
    }

    @Test
    fun pmReadinessProbeIsBounded() {
        val script = bootUnfreeze()

        assertTrue("PM 探测必须集中到 pm_ready", script.contains("pm_ready"))
        assertTrue(
            "PM 探测必须带 3 秒超时，不能在 system_server 卡住时永久阻塞",
            script.contains("timeout 3")
        )
    }

    @Test
    fun lastShutdownRecordUsesFourFields() {
        val script = guard()

        assertTrue(
            "guard 必须写 <boot_id> planned <prev> <epoch> 四字段",
            script.contains("printf '%s planned %s %s\\n'")
        )
        assertFalse(
            "App 按四字段解析，guard 不能写额外的 observed 前缀",
            script.contains("observed %s planned")
        )
    }

    @Test
    fun plannedShutdownResetsTheRescueCounter() {
        assertTrue(
            "guard 必须识别 planned_shutdown",
            guard().contains("planned_shutdown")
        )
        assertTrue(
            "shutdown_daemon 必须写 planned_shutdown",
            shutdownDaemon().contains("planned_shutdown")
        )
        assertTrue(
            "daemon 必须在检查 restore 脚本之前记录 sys.powerctl",
            Regex(
                "getprop sys\\.powerctl[\\s\\S]*planned_shutdown"
            ).containsMatchIn(shutdownDaemon())
        )
    }

    @Test
    fun componentRestoreRequiresADisabledComponentsReadback() {
        assertTrue(
            "组件恢复必须读回 disabledComponents 后才能删标记",
            guard().contains("disabledComponents")
        )
    }

    @Test
    fun allRestorePathsShareOneLock() {
        listOf("boot_guard.sh" to guard(), "boot_unfreeze.sh" to bootUnfreeze())
            .forEach { (name, script) ->
                assertTrue("$name 必须使用 restore.lock", script.contains("restore.lock"))
                assertTrue(
                    "$name 必须通过 acquire_restore_lock 串行化 pm 恢复",
                    script.contains("acquire_restore_lock")
                )
            }
    }

    @Test
    fun guardTimeoutCanCoverARescuePass() {
        assertTrue(
            "service.sh 必须允许配置 guard 超时，240 秒不足以覆盖救援",
            service().contains("NEMU_GUARD_TIMEOUT")
        )
        assertTrue(
            "service.d hook 必须经过有超时的 runner",
            service().contains("boot_guard_runner")
        )
        val runner = TestSources.read(
            "tools/nemu-boot-autostart/boot_guard_runner.sh"
        )
        assertTrue(
            "runner 必须自己写所有权标记并区分 rescue 预算",
            runner.contains("NEMU_GUARD_RUNNER_OWNER") &&
                runner.contains("NEMU_RESCUE_TIMEOUT")
        )
    }

    @Test
    fun restoreParserFixturesPass() {
        val script = listOf(
            File("tools/nemu-boot-autostart/tests/test_restore_parsers.sh"),
            File("../tools/nemu-boot-autostart/tests/test_restore_parsers.sh"),
            File("../../tools/nemu-boot-autostart/tests/test_restore_parsers.sh")
        ).firstOrNull { it.isFile }
            ?: error("找不到 restore parser fixture 测试")

        val process = ProcessBuilder("bash", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val status = process.waitFor()

        assertEquals(
            "解析器 fixture 失败：\n$output",
            0,
            status
        )
    }

    @Test
    fun moduleDescriptionMatchesTheRestoredStateSet() {
        val prop = TestSources.read("tools/nemu-boot-autostart/module.prop")

        assertTrue("模块版本必须递增到 1.18", prop.contains("version=1.18"))
        assertTrue(
            "模块描述必须说明开机恢复 disable-user 等全部状态",
            prop.contains("disable-user")
        )
    }
}
