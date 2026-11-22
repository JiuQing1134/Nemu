package com.nemu.hibernate.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the privilege labels. The Android-facing reader is not
 * exercised here; only the string rules and the derived canFreeze flag.
 */
class PrivilegeLabelsTest {
    @Test
    fun shizuku_mode_covers_install_run_grant_and_uid() {
        assertEquals(
            "未安装",
            PrivilegeLabels.shizukuModeLabel(installed = false, running = false, granted = false, uid = null)
        )
        assertEquals(
            "未运行",
            PrivilegeLabels.shizukuModeLabel(installed = true, running = false, granted = false, uid = null)
        )
        assertEquals(
            "已运行·未授权",
            PrivilegeLabels.shizukuModeLabel(installed = true, running = true, granted = false, uid = 2000)
        )
        assertEquals(
            "已运行·ADB 授权",
            PrivilegeLabels.shizukuModeLabel(installed = true, running = true, granted = true, uid = 2000)
        )
        assertEquals(
            "已运行·Root 授权",
            PrivilegeLabels.shizukuModeLabel(installed = true, running = true, granted = true, uid = 0)
        )
        assertEquals(
            "已运行·已授权",
            PrivilegeLabels.shizukuModeLabel(installed = true, running = true, granted = true, uid = 10123)
        )
        // Running is authoritative even when package visibility hides the app.
        assertEquals(
            "已运行·未授权",
            PrivilegeLabels.shizukuModeLabel(installed = false, running = true, granted = false, uid = 2000)
        )
    }

    @Test
    fun adb_label_reports_both_switches() {
        assertEquals(
            "ADB 调试已开启；无线调试已开启",
            PrivilegeLabels.adbLabel(adbEnabled = true, wirelessDebuggingEnabled = true)
        )
        assertEquals(
            "ADB 调试已开启；无线调试已关闭",
            PrivilegeLabels.adbLabel(adbEnabled = true, wirelessDebuggingEnabled = false)
        )
        assertEquals(
            "ADB 调试已关闭；无线调试已开启",
            PrivilegeLabels.adbLabel(adbEnabled = false, wirelessDebuggingEnabled = true)
        )
        assertEquals(
            "ADB 调试已关闭；无线调试已关闭",
            PrivilegeLabels.adbLabel(adbEnabled = false, wirelessDebuggingEnabled = false)
        )
    }

    @Test
    fun active_backend_prefers_root_then_shizuku() {
        assertEquals(
            "Root",
            PrivilegeLabels.activeBackendLabel(
                rootAvailable = true, shizukuRunning = true, shizukuGranted = true, shizukuUid = 2000
            )
        )
        assertEquals(
            "Shizuku（ADB 启动）",
            PrivilegeLabels.activeBackendLabel(
                rootAvailable = false, shizukuRunning = true, shizukuGranted = true, shizukuUid = 2000
            )
        )
        assertEquals(
            "Shizuku（Root 启动）",
            PrivilegeLabels.activeBackendLabel(
                rootAvailable = false, shizukuRunning = true, shizukuGranted = true, shizukuUid = 0
            )
        )
        assertEquals(
            "未授权",
            PrivilegeLabels.activeBackendLabel(
                rootAvailable = false, shizukuRunning = true, shizukuGranted = false, shizukuUid = 2000
            )
        )
        assertEquals(
            "未授权",
            PrivilegeLabels.activeBackendLabel(
                rootAvailable = false, shizukuRunning = false, shizukuGranted = false, shizukuUid = null
            )
        )
    }

    @Test
    fun can_freeze_requires_root_or_granted_shizuku() {
        assertTrue(snapshot(rootAvailable = true).canFreeze)
        assertTrue(snapshot(rootAvailable = false, shizukuRunning = true, shizukuGranted = true).canFreeze)
        assertFalse(snapshot(rootAvailable = false, shizukuRunning = true, shizukuGranted = false).canFreeze)
        assertFalse(snapshot(rootAvailable = false, shizukuRunning = false, shizukuGranted = true).canFreeze)
        assertFalse(snapshot(rootAvailable = false).canFreeze)
    }

    @Test
    fun plain_summary_always_explains_and_never_claims_freeze_without_privilege() {
        val noPrivilege = PrivilegeLabels.plainSummary(
            rootAvailable = false,
            shizukuInstalled = false,
            shizukuRunning = false,
            shizukuGranted = false,
            shizukuUid = null,
            adbEnabled = false,
        )
        assertTrue(noPrivilege.contains("还没有任何权限"))
        assertTrue(noPrivilege.contains("不能冻结应用"))

        val root = PrivilegeLabels.plainSummary(
            rootAvailable = true,
            shizukuInstalled = false,
            shizukuRunning = false,
            shizukuGranted = false,
            shizukuUid = null,
            adbEnabled = false,
        )
        assertTrue(root.contains("已经拿到 Root 权限"))
        assertTrue(root.contains("可以正常冻结"))

        val shizuku = PrivilegeLabels.plainSummary(
            rootAvailable = false,
            shizukuInstalled = true,
            shizukuRunning = true,
            shizukuGranted = true,
            shizukuUid = 2000,
            adbEnabled = true,
        )
        assertTrue(shizuku.contains("ADB 方式启动"))
        assertTrue(shizuku.contains("可以正常冻结"))
        assertTrue(shizuku.contains("仍然需要 Root"))
    }

    private fun snapshot(
        rootAvailable: Boolean,
        shizukuRunning: Boolean = false,
        shizukuGranted: Boolean = false,
    ): PrivilegeOverview = PrivilegeOverview(
        rootAvailable = rootAvailable,
        rootDetail = PrivilegeLabels.rootDetail(rootAvailable),
        shizukuInstalled = shizukuRunning,
        shizukuRunning = shizukuRunning,
        shizukuGranted = shizukuGranted,
        shizukuUid = if (shizukuRunning) 2000 else null,
        adbEnabled = false,
        wirelessDebuggingEnabled = false,
        activeBackendLabel = PrivilegeLabels.activeBackendLabel(
            rootAvailable = rootAvailable,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            shizukuUid = if (shizukuRunning) 2000 else null,
        ),
        plainSummary = PrivilegeLabels.plainSummary(
            rootAvailable = rootAvailable,
            shizukuInstalled = shizukuRunning,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            shizukuUid = if (shizukuRunning) 2000 else null,
            adbEnabled = false,
        ),
    )
}
