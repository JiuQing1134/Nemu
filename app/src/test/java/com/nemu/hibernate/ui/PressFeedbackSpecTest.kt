package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressFeedbackSpecTest {
    @Test
    fun pressedAndEnabledUsesTheSpecWhileEverythingElseRests() {
        val spec = NemuPressSpec.Chip
        assertEquals(PressVisual(0.92f, 0.97f), spec.visualFor(true, true))
        assertEquals(PressVisual(1f, 1f), spec.visualFor(false, true))
        assertEquals(PressVisual(1f, 1f), spec.visualFor(true, false))
    }

    @Test
    fun everyNonNoneSpecHasSomeVisualFeedbackAndSaneValues() {
        listOf(
            NemuPressSpec.Quiet,
            NemuPressSpec.TextButton,
            NemuPressSpec.Chip,
            NemuPressSpec.Button,
            NemuPressSpec.Card,
            NemuPressSpec.Row,
            NemuPressSpec.NavItem,
            NemuPressSpec.Switch,
            NemuPressSpec.Checkbox,
            NemuPressSpec.Swatch
        ).forEach { spec ->
            assertTrue(spec.hasVisualFeedback)
            assertTrue(spec.pressedAlpha in 0f..1f)
            assertTrue(spec.pressedScale > 0f)
            assertTrue(spec.pressMillis > 0)
            assertTrue(spec.releaseMillis > 0)
        }
        assertFalse(NemuPressSpec.None.hasVisualFeedback)
    }

    @Test
    fun api34HapticsFallBackToKeyboardTapOnOlderDevices() {
        assertEquals(NemuHaptic.SegmentTick, NemuHaptic.SegmentTick.resolve(34))
        assertEquals(NemuHaptic.ToggleOn, NemuHaptic.ToggleOn.resolve(36))
        assertEquals(NemuHaptic.KeyboardTap, NemuHaptic.SegmentTick.resolve(33))
        assertEquals(NemuHaptic.KeyboardTap, NemuHaptic.ToggleOff.resolve(30))
        assertEquals(NemuHaptic.Confirm, NemuHaptic.Confirm.resolve(30))
        assertEquals(NemuHaptic.LongPress, NemuHaptic.LongPress.resolve(30))
        assertEquals(NemuHaptic.None, NemuHaptic.None.resolve(30))
    }

    @Test
    fun toggleHapticFollowsTheNextCheckedState() {
        assertEquals(NemuHaptic.ToggleOn, NemuHaptic.KeyboardTap.toggleFor(true))
        assertEquals(NemuHaptic.ToggleOff, NemuHaptic.KeyboardTap.toggleFor(false))
    }
}
