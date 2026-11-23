package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A policy edit changes the boot plan. The unique WorkManager request must be
 * re-evaluated immediately; otherwise the plan can stay stale until the next
 * launch/boot.
 */
class BootPlanRefreshContractTest {
    @Test
    fun policyEditsRefreshTheBootSchedule() {
        val main = TestSources.read(
            "src/main/java/com/nemu/hibernate/MainActivity.kt"
        )
        val toggle = main.substringAfter("onCatalogToggle =")
            .substringBefore("onCatalogPresetChange =")
        val preset = main.substringAfter("onCatalogPresetChange =")
            .substringBefore("onCatalogComponentsChange =")

        assertTrue(
            "启用/停用目标后必须重新评估开机计划",
            toggle.contains("refreshBootFreezeSchedule()")
        )
        assertTrue(
            "切换预设后必须重新评估开机计划",
            preset.contains("refreshBootFreezeSchedule()")
        )
        assertTrue(
            "统一刷新函数必须调用 ensureScheduled",
            main.contains("BootFreezeScheduler.ensureScheduled")
        )
    }
}
