package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentPickerContractTest {
    @Test
    fun selectionChangeDoesNotExecuteRestoreImmediately() {
        val main = TestSources.read(
            "src/main/java/com/nemu/hibernate/MainActivity.kt"
        )
        val block = main.substringAfter("onCatalogComponentsChange =")
            .substringBefore("onCatalogApply =")

        assertFalse(
            "组件选择器只保存选择，不能立即执行恢复",
            block.contains("restoreOwnedComponents")
        )
    }

    @Test
    fun applyRestoresDeselectedOwnedComponents() {
        val service = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/TargetFreezeService.kt"
        )

        assertTrue(
            "应用组件策略前必须恢复取消勾选且仍被本应用拥有的组件",
            service.contains("ComponentSelectionDiff.deselected")
        )
        assertTrue(service.contains("restoreOwnedComponents("))
    }
}
