package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetPlanContractTest {
    @Test
    fun pureObserveClearsTheBootPlanFlag() {
        val source = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/TargetPolicy.kt"
        )
        val observeBranch = source.substringAfter("StrategyPreset.OBSERVE ->")
            .substringBefore("StrategyPreset.PAUSE ->")

        assertTrue(
            "纯观察不执行任何命令，必须同时关闭开机计划，避免开关显示开启却无计划",
            observeBranch.contains("setEnabled(target, false)")
        )
    }
}
