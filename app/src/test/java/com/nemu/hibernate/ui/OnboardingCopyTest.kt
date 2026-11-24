package com.nemu.hibernate.ui

import com.nemu.hibernate.privilege.PrivilegeOverview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for the four “开始使用” card states. PrivilegeOverview is
 * a plain data class, so no Compose or Android runtime is involved.
 */
class OnboardingCopyTest {
    private fun privilege(
        root: Boolean = false,
        running: Boolean = false,
        granted: Boolean = false
    ) = PrivilegeOverview(
        rootAvailable = root,
        rootDetail = "",
        shizukuInstalled = true,
        shizukuRunning = running,
        shizukuGranted = granted,
        shizukuUid = if (granted) 2000 else null,
        adbEnabled = false,
        wirelessDebuggingEnabled = false,
        activeBackendLabel = "",
        plainSummary = ""
    )

    @Test
    fun checkingWhilePrivilegeIsNull() {
        assertEquals("正在检查权限…", onboardingMessage(null))
    }

    @Test
    fun rootUnlocksEverything() {
        assertEquals(
            "已就绪：可冻结应用、停用组件、伪装卸载，并启用开机自动恢复。",
            onboardingMessage(privilege(root = true, running = true))
        )
    }

    @Test
    fun rootWinsOverGrantedShizuku() {
        assertEquals(
            "已就绪：可冻结应用、停用组件、伪装卸载，并启用开机自动恢复。",
            onboardingMessage(privilege(root = true, running = true, granted = true))
        )
    }

    @Test
    fun shizukuOnlyFreezesAndUnfreezes() {
        assertEquals(
            "可用：冻结和解冻。开机自动恢复、隐藏和停用组件需要 Root。",
            onboardingMessage(privilege(running = true, granted = true))
        )
    }

    @Test
    fun noBackendSuggestsBrowsingAndPlansFirst() {
        assertEquals(
            "可先浏览目标并加入开机计划；执行冻结前需要授权 Root 或 Shizuku。",
            onboardingMessage(privilege(running = true, granted = false))
        )
    }
}
