package com.nemu.hibernate.ui

/**
 * Layer-only press feedback values. Kept free of Compose drawing APIs so the
 * decision table is unit-testable and no control ever draws an overlay,
 * ripple, border or shadow.
 */
internal data class PressFeedbackSpec(
    val pressedAlpha: Float = 1f,
    val pressedScale: Float = 1f,
    val pressMillis: Int = 90,
    val releaseMillis: Int = 160
) {
    val hasVisualFeedback: Boolean
        get() = pressedAlpha < 1f || pressedScale < 1f
}

internal object NemuPressSpec {
    /** Bare text, icons and inline actions. */
    val Quiet = PressFeedbackSpec(
        pressedAlpha = 0.62f,
        pressMillis = 80,
        releaseMillis = 140
    )

    /** Material TextButton / dialog actions. */
    val TextButton = PressFeedbackSpec(
        pressedAlpha = 0.66f,
        pressMillis = 80,
        releaseMillis = 140
    )

    /** Capsule filter chips, mode pills, select-all/count pills. */
    val Chip = PressFeedbackSpec(
        pressedAlpha = 0.92f,
        pressedScale = 0.97f
    )

    /** Filled / tonal / outlined buttons. */
    val Button = PressFeedbackSpec(
        pressedAlpha = 0.95f,
        pressedScale = 0.97f
    )

    /** Whole tappable cards; very subtle so large text does not wobble. */
    val Card = PressFeedbackSpec(
        pressedScale = 0.992f,
        pressMillis = 120,
        releaseMillis = 220
    )

    /** List rows and menu items. */
    val Row = PressFeedbackSpec(pressedAlpha = 0.82f)

    /** Bottom navigation tabs. */
    val NavItem = PressFeedbackSpec(
        pressedAlpha = 0.76f,
        pressMillis = 80
    )

    /** Switch track; its thumb animation carries the state change. */
    val Switch = PressFeedbackSpec(pressedScale = 0.96f)

    /** Checkbox glyph; the checkmark carries the state change. */
    val Checkbox = PressFeedbackSpec(
        pressedScale = 0.90f,
        pressMillis = 80,
        releaseMillis = 140
    )

    /** Palette swatches and compact segmented choices. */
    val Swatch = PressFeedbackSpec(
        pressedAlpha = 0.92f,
        pressedScale = 0.92f
    )

    val None = PressFeedbackSpec()
}

internal enum class NemuHaptic {
    None,
    KeyboardTap,
    Confirm,
    SegmentTick,
    ToggleOn,
    ToggleOff,
    LongPress
}

internal data class PressVisual(val alpha: Float, val scale: Float)

internal fun PressFeedbackSpec.visualFor(
    pressed: Boolean,
    enabled: Boolean
): PressVisual = if (pressed && enabled) {
    PressVisual(pressedAlpha, pressedScale)
} else {
    PressVisual(1f, 1f)
}

/** ToggleOn/Off and SegmentTick need API 34; minSdk is 30. */
internal fun NemuHaptic.resolve(sdkInt: Int): NemuHaptic = when {
    this == NemuHaptic.ToggleOn ||
        this == NemuHaptic.ToggleOff ||
        this == NemuHaptic.SegmentTick ->
        if (sdkInt >= 34) this else NemuHaptic.KeyboardTap
    else -> this
}

internal fun NemuHaptic.toggleFor(nextChecked: Boolean): NemuHaptic =
    if (nextChecked) NemuHaptic.ToggleOn else NemuHaptic.ToggleOff
