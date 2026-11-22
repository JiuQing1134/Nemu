package com.nemu.hibernate.ui

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.core.TargetComponent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModelsTest {
    private val component = TargetComponent(
        className = "com.example.app/com.example.app.Service",
        label = "Service"
    )
    private val suspendTarget = Target(
        packageName = "com.example.suspend",
        label = "Suspend",
        risk = RiskLevel.YELLOW,
        category = TargetCategory.AI
    )
    private val disableTarget = Target(
        packageName = "com.example.disable",
        label = "Disable",
        risk = RiskLevel.YELLOW,
        category = TargetCategory.CLOUD,
        defaultStrategy = FreezeStrategy.DISABLE_USER
    )
    private val componentTarget = Target(
        packageName = "com.example.component",
        label = "Component",
        risk = RiskLevel.RED,
        category = TargetCategory.AI,
        defaultStrategy = FreezeStrategy.COMPONENT,
        components = listOf(component)
    )

    @Test
    fun actualSuspensionIsFrozenEvenWithoutMarker() {
        val row = CatalogTargetUiState(
            target = suspendTarget,
            strategy = FreezeStrategy.SUSPEND,
            actual = TargetActualUiState(suspended = true, packageEnabled = true)
        )
        assertTrue(row.hasFrozenState)
    }

    @Test
    fun disabledPackageWithoutOwnershipIsNotListed() {
        val row = CatalogTargetUiState(
            target = disableTarget,
            strategy = FreezeStrategy.DISABLE_USER,
            actual = TargetActualUiState(suspended = false, packageEnabled = false)
        )
        assertFalse(row.hasFrozenState)
    }

    @Test
    fun disabledPackageWithBaselineIsListed() {
        val row = CatalogTargetUiState(
            target = disableTarget,
            strategy = FreezeStrategy.DISABLE_USER,
            actual = TargetActualUiState(suspended = false, packageEnabled = false),
            ownedDisable = true
        )
        assertTrue(row.hasFrozenState)
    }

    @Test
    fun componentStateIsShownAndCountsAsFrozen() {
        val row = CatalogTargetUiState(
            target = componentTarget,
            strategy = FreezeStrategy.COMPONENT,
            actual = TargetActualUiState(suspended = false, packageEnabled = true),
            componentsDisabled = true
        )
        assertTrue(row.hasFrozenState)
        assertTrue(row.actualText().contains("组件已停用"))
    }
}
