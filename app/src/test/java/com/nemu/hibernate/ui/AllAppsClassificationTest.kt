package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAppsClassificationTest {
    @Test
    fun rootAndFrameworkManagersAreTheirOwnCategory() {
        assertEquals(
            AppKind.ROOT,
            classifyApp("me.yuki.fina", "FinaSU", systemFlag = true)
        )
        assertEquals(
            AppKind.ROOT,
            classifyApp("org.lsposed.manager", "LSPosed", systemFlag = false)
        )
        assertEquals(
            AppKind.ROOT,
            classifyApp("me.weishu.kernelsu", "KernelSU", systemFlag = true)
        )
        assertEquals(
            AppKind.ROOT,
            classifyApp("com.topjohnwu.magisk", "Magisk", systemFlag = false)
        )
        assertEquals(
            AppKind.ROOT,
            classifyApp("moe.shizuku.privileged.api", "Shizuku", systemFlag = false)
        )
    }

    @Test
    fun onlyOsImagesAreSystemApps() {
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("com.hihonor.ouc", "软件更新", systemFlag = true)
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("com.android.systemui", "系统界面", systemFlag = true)
        )
        assertEquals(
            AppKind.SYSTEM,
            classifyApp("android", "Android 系统", systemFlag = true)
        )
    }

    @Test
    fun preinstalledThirdPartyStillCountsAsNormalApp() {
        // FLAG_SYSTEM alone does not make an app part of Honor's OS.
        assertEquals(
            AppKind.USER,
            classifyApp("com.tencent.mm", "微信", systemFlag = true)
        )
        assertEquals(
            AppKind.USER,
            classifyApp("com.example.app", "Example", systemFlag = false)
        )
    }
}
