package com.nemu.hibernate.ui

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The user keeps red as the only fixed semantic colour; every other control
 * must follow the selected theme palette. These literals were the old fixed
 * warning orange, low-risk green and duplicated danger red.
 */
class UiColorPolicyTest {
    private fun uiSources(): Map<String, String> =
        TestSources.uiFiles() + listOf(
            "StatusChip.kt",
            "Controls.kt",
            "AllAppsScreen.kt"
        ).associateWith {
            TestSources.read("src/main/java/com/nemu/hibernate/ui/$it")
        }

    @Test
    fun noFixedWarningOrangeOrLowRiskGreenInUiSources() {
        uiSources().forEach { (name, source) ->
            assertFalse(
                "$name 仍含固定警告橙 0xFFFF8D28；应使用主题色",
                source.contains("0xFFFF8D28", ignoreCase = true)
            )
            assertFalse(
                "$name 仍含固定低风险绿 0xFF2E7D32；应使用主题中性色",
                source.contains("0xFF2E7D32", ignoreCase = true)
            )
        }
    }

    @Test
    fun dangerRedComesFromTheThemeTokenNotAUIArgbLiteral() {
        uiSources().forEach { (name, source) ->
            assertFalse(
                "$name 仍含重复的红色字面量 0xFFFF383C；应使用 scheme.error",
                source.contains("0xFFFF383C", ignoreCase = true)
            )
        }
    }
}
