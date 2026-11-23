package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyExecutionTest {
    private val componentA = TargetComponent(
        className = "com.example/com.example.A",
        label = "A"
    )
    private val componentB = TargetComponent(
        className = "com.example/com.example.B",
        label = "B"
    )
    private val target = Target(
        packageName = "com.example",
        label = "Example",
        risk = RiskLevel.YELLOW,
        components = listOf(componentA, componentB)
    )

    @Test
    fun observeAloneIsNotActive() {
        assertFalse(
            StrategyExecution.isActive(
                FreezeStrategy.OBSERVE,
                StrategyOptions()
            )
        )
    }

    @Test
    fun observeWithAppOpsIsActive() {
        assertTrue(
            StrategyExecution.isActive(
                FreezeStrategy.OBSERVE,
                StrategyOptions(appOps = true)
            )
        )
    }

    @Test
    fun observeWithStandbyIsActive() {
        assertTrue(
            StrategyExecution.isActive(
                FreezeStrategy.OBSERVE,
                StrategyOptions(standby = true)
            )
        )
    }

    @Test
    fun suspendIsActive() {
        assertTrue(
            StrategyExecution.isActive(
                FreezeStrategy.SUSPEND,
                StrategyOptions()
            )
        )
    }

    @Test
    fun componentWithoutConfiguredComponentsFailsClosed() {
        assertTrue(
            StrategyExecution.effectiveComponents(
                target,
                FreezeStrategy.COMPONENT,
                StrategyOptions(components = null)
            ).isEmpty()
        )
    }

    @Test
    fun componentUsesOnlySelectedRegisteredComponentsInCatalogOrder() {
        val selected = StrategyExecution.effectiveComponents(
            target,
            FreezeStrategy.COMPONENT,
            StrategyOptions(components = setOf(componentB.className, componentA.className))
        )

        assertEquals(listOf(componentA, componentB), selected)
    }

    @Test
    fun componentIgnoresUnknownSelectedClass() {
        val selected = StrategyExecution.effectiveComponents(
            target,
            FreezeStrategy.COMPONENT,
            StrategyOptions(
                components = setOf(
                    componentA.className,
                    "com.example/com.example.Unknown"
                )
            )
        )

        assertEquals(listOf(componentA), selected)
    }

    @Test
    fun nonComponentStrategyHasNoEffectiveComponents() {
        assertTrue(
            StrategyExecution.effectiveComponents(
                target,
                FreezeStrategy.SUSPEND,
                StrategyOptions(components = setOf(componentA.className))
            ).isEmpty()
        )
    }

    @Test
    fun missingOptionsForLegacyComponentMaterializesRegisteredComponents() {
        val resolved = StrategyOptionsResolver.resolve(
            target,
            FreezeStrategy.COMPONENT,
            StoredOptions.Missing
        )

        assertNotNull(resolved)
        assertEquals(
            setOf(componentA.className, componentB.className),
            resolved!!.components
        )
    }

    @Test
    fun invalidOptionsForComponentFailsClosed() {
        assertNull(
            StrategyOptionsResolver.resolve(
                target,
                FreezeStrategy.COMPONENT,
                StoredOptions.Invalid("broken")
            )
        )
    }

    @Test
    fun invalidOptionsForSuspendKeepsLegacyForceStopDefault() {
        val resolved = StrategyOptionsResolver.resolve(
            target,
            FreezeStrategy.SUSPEND,
            StoredOptions.Invalid("broken")
        )

        assertNotNull(resolved)
        assertTrue(resolved!!.forceStop)
    }

    @Test
    fun parsedOptionsAreReturnedUnchanged() {
        val options = StrategyOptions(forceStop = false, appOps = true)
        assertEquals(
            options,
            StrategyOptionsResolver.resolve(
                target,
                FreezeStrategy.OBSERVE,
                StoredOptions.Parsed(options)
            )
        )
    }
}
