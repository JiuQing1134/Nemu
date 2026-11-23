package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CriticalRolesTest {
    @Test
    fun homeAndInputMethodRolesAreBothReported() {
        val roles = CriticalRoles.roles(
            homePackages = listOf("com.teslacoilsw.launcher"),
            imePackages = listOf("com.baidu.input")
        )

        assertEquals("桌面", roles["com.teslacoilsw.launcher"])
        assertEquals("输入法", roles["com.baidu.input"])
    }

    @Test
    fun packageThatIsBothHomeAndImeGetsACombinedReason() {
        val roles = CriticalRoles.roles(
            homePackages = listOf("com.example.multi"),
            imePackages = listOf("com.example.multi")
        )

        assertEquals("桌面/输入法", roles["com.example.multi"])
    }
}
