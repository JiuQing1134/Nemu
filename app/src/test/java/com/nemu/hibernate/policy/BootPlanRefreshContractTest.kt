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
            .substringBefore("onCatalogPlanBatch =")
        val batch = main.substringAfter("onCatalogPlanBatch =")
            .substringBefore("onCatalogSelectionRemember =")
        val preset = main.substringAfter("onCatalogPresetChange =")
            .substringBefore("onCatalogComponentsChange =")

        assertTrue(
            "单卡开关改计划后必须重新评估开机冻结",
            toggle.contains("refreshBootFreezeSchedule()")
        )
        assertTrue(
            "批量加入/移出开机冻结后必须重新评估开机冻结",
            batch.contains("refreshBootFreezeSchedule()")
        )
        assertTrue(
            "切换预设后必须重新评估开机冻结",
            preset.contains("refreshBootFreezeSchedule()")
        )
        assertTrue(
            "统一刷新函数必须调用 ensureScheduled",
            main.contains("BootFreezeScheduler.ensureScheduled")
        )
    }
}
