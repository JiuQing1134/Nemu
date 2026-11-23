package com.nemu.hibernate.ui

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

/**
 * Layer-only press feedback: a short alpha/scale change on the composable
 * itself. It never draws an overlay, ripple, border, shadow or background,
 * so it cannot reintroduce the grey "ghost" rectangles the old ripple left on
 * bare text controls. The layer is read inside graphicsLayer to avoid a
 * recomposition on every animation frame.
 */
@Composable
internal fun Modifier.nemuPressFeedback(
    interactionSource: InteractionSource,
    spec: PressFeedbackSpec,
    enabled: Boolean = true
): Modifier {
    if (!spec.hasVisualFeedback) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled
    val alpha = animateFloatAsState(
        targetValue = if (active) spec.pressedAlpha else 1f,
        animationSpec = tween(
            durationMillis = if (active) spec.pressMillis else spec.releaseMillis,
            easing = if (active) LinearOutSlowInEasing else FastOutSlowInEasing
        ),
        label = "nemuPressAlpha"
    )
    val scale = animateFloatAsState(
        targetValue = if (active) spec.pressedScale else 1f,
        animationSpec = tween(
            durationMillis = if (active) spec.pressMillis else spec.releaseMillis,
            easing = if (active) LinearOutSlowInEasing else FastOutSlowInEasing
        ),
        label = "nemuPressScale"
    )
    return graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Clickable with the standard Nemu press treatment and haptics. Long press
 * owns its own haptic; foundation's automatic haptic is disabled so the same
 * gesture cannot buzz twice.
 */
@Composable
internal fun Modifier.nemuClickable(
    enabled: Boolean = true,
    spec: PressFeedbackSpec = NemuPressSpec.Quiet,
    haptic: NemuHaptic = NemuHaptic.None,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    return combinedClickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick?.let { action ->
            {
                haptics.perform(NemuHaptic.LongPress)
                action()
            }
        },
        hapticFeedbackEnabled = false,
        onClick = {
            haptics.perform(haptic)
            onClick()
        }
    ).nemuPressFeedback(interaction, spec, enabled)
}

@Composable
internal fun rememberNemuHaptics(): NemuHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { NemuHaptics(feedback) }
}

@Stable
internal class NemuHaptics(
    private val feedback: HapticFeedback
) {
    fun perform(haptic: NemuHaptic) {
        val effective = haptic.resolve(Build.VERSION.SDK_INT)
        if (effective == NemuHaptic.None) return
        feedback.performHapticFeedback(effective.toComposeType())
    }
}

private fun NemuHaptic.toComposeType(): HapticFeedbackType = when (this) {
    NemuHaptic.None -> HapticFeedbackType.KeyboardTap
    NemuHaptic.KeyboardTap -> HapticFeedbackType.KeyboardTap
    NemuHaptic.Confirm -> HapticFeedbackType.Confirm
    NemuHaptic.SegmentTick -> HapticFeedbackType.SegmentTick
    NemuHaptic.ToggleOn -> HapticFeedbackType.ToggleOn
    NemuHaptic.ToggleOff -> HapticFeedbackType.ToggleOff
    NemuHaptic.LongPress -> HapticFeedbackType.LongPress
}
