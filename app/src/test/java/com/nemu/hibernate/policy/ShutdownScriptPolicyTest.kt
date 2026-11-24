package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownScriptPolicyTest {
    @Test
    fun shutdownDoesNotReenableDisableUserTargets() {
        val script = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/ShutdownScript.kt"
        )

        assertFalse(
            "关机安全解冻不得逆转 disable-user；只有显式解冻可以",
            script.contains("CommandBuilder.enable")
        )
    }

    @Test
    fun generatedRestoreScriptCountsEveryCommandFailure() {
        val script = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/ShutdownScript.kt"
        )

        assertTrue(
            "脚本必须逐条执行命令并累计失败数",
            script.contains("nemu_run")
        )
        assertTrue(
            "脚本必须以失败数作为退出码",
            script.contains("nemu_fail")
        )
    }

    @Test
    fun generatedPayloadWrapsEveryCommandAndExitsWithFailureCount() {
        val payload = ShutdownScript.renderPayload(
            listOf(
                "pm unsuspend --user 0 'com.example.alpha'",
                "pm unhide --user 0 'com.example.beta'"
            )
        )

        val lines = payload.trimEnd('\n').lines()
        assertEquals("#!/system/bin/sh", lines.first())
        assertTrue("必须把失败计数初始化为 0", lines.contains("nemu_fail=0"))
        assertEquals(
            "每条命令都必须经过 nemu_run",
            listOf(
                "nemu_run pm unsuspend --user 0 'com.example.alpha'",
                "nemu_run pm unhide --user 0 'com.example.beta'"
            ),
            lines.filter { it.startsWith("nemu_run ") }
        )
        assertEquals(
            "脚本必须以失败数作为退出码",
            "exit \"\$nemu_fail\"",
            lines.last()
        )
    }

    @Test
    fun emptyCommandListStillRendersEmptyPayload() {
        assertEquals("", ShutdownScript.renderPayload(emptyList()))
    }
}
