package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarModeTest {
    @Test
    fun modeLabelShowsTheCurrentBackendBeforeRoot() {
        assertEquals("模式：Root", topBarBackendModeLabel("Root"))
        assertEquals(
            "模式：Shizuku",
            topBarBackendModeLabel("Shizuku（ADB 启动）")
        )
        assertEquals(
            "模式：Shizuku",
            topBarBackendModeLabel("Shizuku（Root 启动）")
        )
        assertEquals(
            "模式：未授权",
            topBarBackendModeLabel("Shizuku（未授权）")
        )
        assertEquals("模式：未授权", topBarBackendModeLabel("未授权"))
        assertEquals("模式：检测中", topBarBackendModeLabel("检测中…"))
    }
}
