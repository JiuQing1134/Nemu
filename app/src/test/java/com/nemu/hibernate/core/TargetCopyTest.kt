package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCopyTest {
    private val strategies = FreezeStrategy.entries

    @Test
    fun pauseAndFreezeHaveDistinctCopy() {
        assertEquals("暂停", TargetCopy.label(StrategyPreset.PAUSE))
        assertEquals("冻结", TargetCopy.label(StrategyPreset.FREEZE))
        assertTrue(
            TargetCopy.impact(StrategyPreset.PAUSE).contains("不结束进程")
        )
        assertTrue(
            TargetCopy.impact(StrategyPreset.FREEZE).contains("结束当前进程")
        )
        assertFalse(
            TargetCopy.impact(StrategyPreset.PAUSE) ==
                TargetCopy.impact(StrategyPreset.FREEZE)
        )
    }

    @Test
    fun mainPresetsIncludePauseAndFreeze() {
        assertTrue(StrategyPreset.PAUSE in TargetCopy.mainPresets)
        assertTrue(StrategyPreset.FREEZE in TargetCopy.mainPresets)
        assertFalse(StrategyPreset.RESTRICT_BACKGROUND in TargetCopy.mainPresets)
    }

    @Test
    fun restrictionsOnlyAppearForRegisteredTargets() {
        val securityServer = TargetCatalog.byPackage(
            "com.hihonor.securityserver"
        )
        assertNotNull(securityServer)
        assertTrue(
            StrategyPreset.RESTRICT_BACKGROUND in
                TargetCopy.presetsFor(securityServer!!)
        )

        val ouc = TargetCatalog.byPackage("com.hihonor.ouc")
        assertNotNull(ouc)
        assertFalse(
            StrategyPreset.RESTRICT_BACKGROUND in TargetCopy.presetsFor(ouc!!)
        )
    }

    @Test
    fun restrictionPresetsRequireRootInTheUi() {
        val securityServer = TargetCatalog.byPackage(
            "com.hihonor.securityserver"
        )
        assertNotNull(securityServer)

        assertEquals(
            "需要 Root",
            TargetCopy.unavailableReason(
                securityServer!!,
                StrategyPreset.RESTRICT_BACKGROUND,
                rootAvailable = false
            )
        )
        assertEquals(
            "需要 Root",
            TargetCopy.unavailableReason(
                securityServer,
                StrategyPreset.RESTRICT_STANDBY,
                rootAvailable = false
            )
        )
    }

    @Test
    fun restrictionRestoreLinesAreSpecific() {
        assertEquals(
            "恢复原始后台模式",
            TargetCopy.restoreLine(StrategyPreset.RESTRICT_BACKGROUND)
        )
        assertEquals(
            "恢复原始待机桶",
            TargetCopy.restoreLine(StrategyPreset.RESTRICT_STANDBY)
        )
    }

    @Test
    fun pauseCapabilityMatchesSuspend() {
        val hiview = TargetCatalog.byPackage("com.hihonor.hiview")
        assertNotNull(hiview)
        assertEquals(
            "系统不支持",
            TargetCopy.unavailableReason(hiview!!, StrategyPreset.PAUSE)
        )

        val ouc = TargetCatalog.byPackage("com.hihonor.ouc")
        assertNotNull(ouc)
        assertEquals(
            null,
            TargetCopy.unavailableReason(ouc!!, StrategyPreset.PAUSE)
        )
    }

    @Test
    fun everyCatalogTargetHasTerseFunctionLine() {
        TargetCatalog.all.forEach { target ->
            val line = TargetCopy.functionLine(target.packageName)
            assertTrue("${target.packageName} empty", line.isNotBlank())
            assertTrue("${target.packageName} too long: $line", line.length <= 24)
            assertFalse(line.contains("这个应用"))
            assertFalse(line.contains("。"))
        }
    }

    @Test
    fun everyStrategyHasTerseImpactAndLabel() {
        strategies.forEach { strategy ->
            val impact = TargetCopy.impact(strategy)
            assertTrue("$strategy empty", impact.isNotBlank())
            assertTrue("$strategy too long: $impact", impact.length <= 28)
            assertFalse(impact.contains("。"))
            assertTrue(TargetCopy.label(strategy).length <= 4)
            assertTrue(TargetCopy.restoreLine(strategy).length <= 28)
        }
    }

    @Test
    fun everyUnavailableReasonIsShortAndChinese() {
        TargetCatalog.all.forEach { target ->
            strategies.forEach { strategy ->
                val reason = TargetCopy.unavailableReason(target, strategy)
                if (reason != null) {
                    assertTrue(
                        "${target.packageName} $strategy: $reason",
                        reason.length <= 12
                    )
                    assertFalse(reason.contains("SUSPEND"))
                    assertFalse(reason.contains("OBSERVE"))
                }
            }
        }
    }

    @Test
    fun platformRejectionsAreExplicit() {
        val hiview = TargetCatalog.byPackage("com.hihonor.hiview")
        assertNotNull(hiview)
        assertEquals(
            "系统不支持",
            TargetCopy.unavailableReason(hiview!!, FreezeStrategy.SUSPEND)
        )

        val powergenie = TargetCatalog.byPackage("com.hihonor.powergenie")
        assertNotNull(powergenie)
        assertEquals(
            "系统不支持",
            TargetCopy.unavailableReason(
                powergenie!!,
                FreezeStrategy.COMPONENT
            )
        )
    }

    @Test
    fun denyListWarnsButDoesNotBlockWholePackageStrategies() {
        val pushagent = TargetCatalog.byPackage(
            "com.hihonor.android.pushagent"
        )
        assertNotNull(pushagent)
        assertEquals(
            null,
            TargetCopy.unavailableReason(pushagent!!, FreezeStrategy.SUSPEND)
        )
        assertNotNull(
            TargetCopy.riskWarning(pushagent, FreezeStrategy.SUSPEND)
        )

        val plugins = TargetCatalog.byPackage("com.hihonor.securitypluginbase")
        assertNotNull(plugins)
        assertEquals(
            null,
            TargetCopy.unavailableReason(plugins!!, FreezeStrategy.HIDE)
        )
        assertNotNull(
            TargetCopy.riskWarning(plugins, FreezeStrategy.HIDE)
        )
    }

    @Test
    fun redAiTargetsCanDisableOrHideWithWarning() {
        val ai = TargetCatalog.byPackage("com.hihonor.aipluginengine")
        assertNotNull(ai)

        assertEquals(
            null,
            TargetCopy.unavailableReason(ai!!, FreezeStrategy.DISABLE_USER)
        )
        assertEquals(
            null,
            TargetCopy.unavailableReason(ai, FreezeStrategy.HIDE)
        )
        assertNotNull(TargetCopy.riskWarning(ai, FreezeStrategy.HIDE))
    }

    @Test
    fun wholePackageStrategiesAreAvailableForEveryCatalogTarget() {
        val hnaps = TargetCatalog.byPackage("com.hihonor.android.hnaps")
        assertNotNull(hnaps)
        assertEquals(
            null,
            TargetCopy.unavailableReason(hnaps!!, FreezeStrategy.DISABLE_USER)
        )
        assertEquals(
            null,
            TargetCopy.unavailableReason(hnaps, FreezeStrategy.HIDE)
        )
    }

    @Test
    fun noRootExplainsRestrictedStrategies() {
        val ouc = TargetCatalog.byPackage("com.hihonor.ouc")
        assertNotNull(ouc)
        assertEquals(
            "需要 Root",
            TargetCopy.unavailableReason(
                ouc!!,
                FreezeStrategy.COMPONENT,
                rootAvailable = false
            )
        )
    }

    @Test
    fun emptyComponentTargetDoesNotClaimNoneExist() {
        val empty = Target(
            packageName = "com.example.empty",
            label = "空组件",
            risk = RiskLevel.GREEN,
            category = TargetCategory.AI,
            components = emptyList()
        )

        assertEquals(
            "没有可停用的组件",
            TargetCopy.unavailableReason(empty, FreezeStrategy.COMPONENT)
        )
    }

    @Test
    fun aiExceptionKeepsSuspendSelectable() {
        val ai = TargetCatalog.byPackage("com.hihonor.aipluginengine")
        assertNotNull(ai)
        assertEquals(
            null,
            TargetCopy.unavailableReason(ai!!, FreezeStrategy.SUSPEND)
        )
    }
}
