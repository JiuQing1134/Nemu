package com.nemu.hibernate.policy

import com.nemu.hibernate.core.ShellResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentRestoreStateTest {
    @Test
    fun nullReadbackIsNotRestored() {
        assertFalse(ComponentRestoreState.isRestored(null))
    }

    @Test
    fun disabledReadbackIsNotRestored() {
        assertFalse(ComponentRestoreState.isRestored(2))
        assertFalse(ComponentRestoreState.isRestored(3))
        assertFalse(ComponentRestoreState.isRestored(4))
    }

    @Test
    fun defaultAndEnabledReadbackAreRestored() {
        assertTrue(ComponentRestoreState.isRestored(0))
        assertTrue(ComponentRestoreState.isRestored(1))
    }

    @Test
    fun missingComponentIsRecognisedBeforeGenericSuccess() {
        assertTrue(
            ComponentRestoreState.isMissingComponent(
                ShellResult(0, "Unknown component: x")
            )
        )
        assertTrue(
            ComponentRestoreState.isMissingComponent(
                ShellResult(1, "Unknown component: x")
            )
        )
        assertFalse(
            ComponentRestoreState.isMissingComponent(
                ShellResult(0, "Component x new state: disabled")
            )
        )
    }
}
