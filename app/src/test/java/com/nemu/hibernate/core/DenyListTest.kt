package com.nemu.hibernate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DenyListTest {
    @Test
    fun aiBasicServices_are_protected() {
        assertTrue(DenyList.isProtected("com.hihonor.aipluginengine"))
        assertTrue(DenyList.isProtected("com.hihonor.assistant"))
        assertTrue(DenyList.isProtected("com.hihonor.awareness"))
    }

    @Test
    fun systemAndThermalServices_are_protected() {
        assertTrue(DenyList.isProtected("com.hihonor.systemserver"))
        assertTrue(DenyList.isProtected("com.hihonor.powergenie"))
        assertTrue(DenyList.isProtected("com.hihonor.iaware"))
    }

    @Test
    fun updatePackage_is_not_protected() {
        assertFalse(DenyList.isProtected("com.hihonor.ouc"))
    }

    @Test
    fun aiLeafPackages_are_not_protected() {
        assertFalse(DenyList.isProtected("com.honor.yoyoappsug"))
        assertFalse(DenyList.isProtected("com.honor.yoyocards"))
        assertFalse(DenyList.isProtected("com.hihonor.suggestion"))
    }

    @Test
    fun antiFraudPackages_are_protectedFromGenericFreeze() {
        assertTrue(DenyList.isProtected("com.hihonor.numberidentity"))
        assertTrue(DenyList.isProtected("com.hihonor.securityserver"))
        assertTrue(DenyList.isProtected("com.hihonor.awareness"))
        assertTrue(DenyList.isProtected("com.hihonor.securitypluginbase"))
    }
}
