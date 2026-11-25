package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarModeTest {
    @Test
    fun modeLabelShowsTheCurrentBackendBeforeRoot() {
        assertEquals("授权：Root", topBarBackendModeLabel("Root"))
        assertEquals(
            "授权：Shizuku",
            topBarBackendModeLabel("Shizuku（ADB 启动）")
        )
        assertEquals(
            "授权：Shizuku",
            topBarBackendModeLabel("Shizuku（Root 启动）")
        )
        assertEquals(
            "授权：未授权",
            topBarBackendModeLabel("Shizuku（未授权）")
        )
        assertEquals("授权：未授权", topBarBackendModeLabel("未授权"))
        assertEquals("授权：检测中", topBarBackendModeLabel("检测中…"))
    }
}
