package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertFalse
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
}
