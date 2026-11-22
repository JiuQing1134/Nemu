package com.nemu.hibernate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTargetGuardTest {
    @Test
    fun deniesCriticalSystemAndSelfPackages() {
        listOf(
            "android",
            "com.nemu.hibernate",
            "com.nemu.dream",
            "com.nemu.windstop",
            "moe.shizuku.privileged.api",
            "com.android.systemui",
            "com.android.internal.systemui.navbar.threebutton",
            "com.android.settings",
            "com.hihonor.powergenie"
        ).forEach {
            assertTrue(it, CustomTargetGuard.isDenied(it))
        }
    }

    @Test
    fun deniesInputMethods() {
        assertTrue(CustomTargetGuard.isDenied("com.sohu.inputmethod.sogou.honor"))
    }

    @Test
    fun allowsOrdinaryThirdPartyApp() {
        assertFalse(CustomTargetGuard.isDenied("com.alibaba.wireless"))
    }

    @Test
    fun customTargetOffersAllLeafStrategiesButNoComponentMode() {
        val target = TargetCatalog.customTarget("com.alibaba.wireless", "阿里巴巴")
        assertTrue(FreezeStrategy.SUSPEND in target.supportedStrategies)
        assertTrue(FreezeStrategy.DISABLE_USER in target.supportedStrategies)
        assertTrue(FreezeStrategy.HIDE in target.supportedStrategies)
        assertFalse(FreezeStrategy.COMPONENT in target.supportedStrategies)
        assertNotNull(target.consequenceFor(FreezeStrategy.HIDE))
    }
}
