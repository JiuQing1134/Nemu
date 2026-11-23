package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTargetGuardTest {
    @Test
    fun criticalWarningCoversSystemAndSelfPackages() {
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
            assertNotNull(it, CustomTargetGuard.criticalReason(it))
        }
    }

    @Test
    fun inputMethodsGetACriticalWarning() {
        assertNotNull(
            CustomTargetGuard.criticalReason("com.sohu.inputmethod.sogou.honor")
        )
    }

    @Test
    fun ordinaryThirdPartyAppHasNoWarning() {
        assertNull(CustomTargetGuard.criticalReason("com.alibaba.wireless"))
    }

    @Test
    fun dynamicDefaultLauncherAndImeGetAWarning() {
        val roles = mapOf(
            "com.teslacoilsw.launcher" to "桌面",
            "com.baidu.input" to "输入法"
        )

        assertEquals(
            "桌面",
            CustomTargetGuard.criticalReason(
                "com.teslacoilsw.launcher",
                roles
            )
        )
        assertEquals(
            "输入法",
            CustomTargetGuard.criticalReason("com.baidu.input", roles)
        )
    }

    @Test
    fun onlyNemuItselfIsAHardBlock() {
        assertTrue(CustomTargetGuard.isSelf("com.nemu.dream"))
        assertTrue(CustomTargetGuard.isSelf("com.nemu.hibernate"))
    }

    @Test
    fun recoveryCriticalCustomTargetsArePromotedToRed() {
        assertEquals(
            RiskLevel.RED,
            TargetCatalog.customTarget("moe.shizuku.privileged.api", "Shizuku").risk
        )
        assertEquals(
            RiskLevel.YELLOW,
            TargetCatalog.customTarget("com.alibaba.wireless", "阿里巴巴").risk
        )
    }

    @Test
    fun removalIsBlockedByOwnershipOrAnyActualFrozenState() {
        assertTrue(
            CustomTargetGuard.shouldBlockRemoval(
                owned = true,
                hasFrozenState = false
            )
        )
        assertTrue(
            CustomTargetGuard.shouldBlockRemoval(
                owned = false,
                hasFrozenState = true
            )
        )
        assertTrue(
            !CustomTargetGuard.shouldBlockRemoval(
                owned = false,
                hasFrozenState = false
            )
        )
    }

    @Test
    fun customTargetOffersWholePackageStrategiesButNoComponentMode() {
        val target = TargetCatalog.customTarget("com.alibaba.wireless", "阿里巴巴")
        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.SUSPEND))
        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.DISABLE_USER))
        assertNull(FreezeStrategyPolicy.validate(target, FreezeStrategy.HIDE))
        assertNotNull(
            FreezeStrategyPolicy.validate(target, FreezeStrategy.COMPONENT)
        )
        assertNotNull(target.consequenceFor(FreezeStrategy.HIDE))
    }
}
