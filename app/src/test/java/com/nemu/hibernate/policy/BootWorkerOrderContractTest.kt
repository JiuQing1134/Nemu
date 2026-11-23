package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

class BootWorkerOrderContractTest {
    @Test
    fun freezeDecisionIsEvaluatedAfterTheBackendWait() {
        val source = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/BootWorker.kt"
        )
        val backendWait = source.indexOf("manager.get()")
        val freezeDecision =
            source.indexOf("residualUnfreezeIfNoBootAction")

        assertTrue(backendWait >= 0)
        assertTrue(freezeDecision >= 0)
        assertTrue(
            "等待 Root/Shizuku 期间可能发生新的冻结，必须在等待后重新判断",
            freezeDecision > backendWait
        )
    }
}
