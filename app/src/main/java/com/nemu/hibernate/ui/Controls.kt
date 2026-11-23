package com.nemu.hibernate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.ui.theme.flClashInset

/**
 * FlClash's switch: a 52 x 32 pill with a solid 24 dp thumb, no Material
 * ripple or outlined track. Off is `surfaceContainerHighest`, on is the
 * palette's primary; the thumb is `outline` when off and `onPrimary` when on.
 */
@Composable
fun NemuSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val active = enabled && onCheckedChange != null
    val interaction = remember { MutableInteractionSource() }
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "switch"
    )
    // Keep the checked state visible even while the switch is temporarily
    // disabled (busy/unavailable). Only a genuinely off switch is neutral
    // grey; every on switch uses the same palette primary, disabled or not.
    val track by animateColorAsState(
        targetValue = when {
            checked && active -> scheme.primary
            checked -> scheme.primary.copy(alpha = 0.55f)
            active -> scheme.flClashInset()
            else -> scheme.flClashInset().copy(alpha = 0.5f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "switchTrack"
    )
    val thumb by animateColorAsState(
        targetValue = when {
            checked && active -> scheme.onPrimary
            checked -> scheme.onPrimary.copy(alpha = 0.75f)
            active -> scheme.outline
            else -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "switchThumb"
    )
    Box(
        modifier = modifier
            .size(width = 60.dp, height = 40.dp)
            .toggleable(
                value = checked,
                enabled = active,
                role = Role.Switch,
                indication = null,
                interactionSource = interaction,
                onValueChange = { onCheckedChange?.invoke(it) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(CircleShape)
                .background(track)
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (4.dp + 20.dp * progress).roundToPx(),
                            y = 0
                        )
                    }
                    .size(24.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(thumb)
            )
        }
    }
}

/**
 * One neutral/primary palette for every Material checkbox. The default
 * Material unchecked colour is a darker onSurfaceVariant, which made some
 * checkboxes look almost black next to the grey switch/off controls.
 */
@Composable
fun nemuCheckboxColors(): CheckboxColors {
    val scheme = MaterialTheme.colorScheme
    return CheckboxDefaults.colors(
        checkedColor = scheme.primary,
        checkmarkColor = scheme.onPrimary,
        uncheckedColor = scheme.outline,
        disabledCheckedColor = scheme.primary.copy(alpha = 0.38f),
        disabledUncheckedColor = scheme.outline.copy(alpha = 0.38f),
        disabledIndeterminateColor = scheme.primary.copy(alpha = 0.38f)
    )
}

/**
 * Compact 14dp checkbox with a 20dp touch target, for dense file/target rows.
 * The first-line + top-aligned layout keeps it level with a single text line
 * instead of centring it against a multi-line block.
 */
@Composable
fun NemuSmallCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    indeterminate: Boolean = false
) {
    val shape = RoundedCornerShape(3.dp)
    val checkedFill = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
    }
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)
    }
    val contentColor = if (checked || indeterminate) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        Color.Transparent
    }
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .clearAndSetSemantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
                this.role = Role.Checkbox
                this.toggleableState = when {
                    indeterminate -> ToggleableState.Indeterminate
                    checked -> ToggleableState.On
                    else -> ToggleableState.Off
                }
                if (enabled) {
                    onClick(label = contentDescription) {
                        onCheckedChange(!checked)
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(shape)
                .background(
                    if (checked || indeterminate) checkedFill
                    else Color.Transparent
                )
                .border(
                    1.dp,
                    if (checked || indeterminate) checkedFill else borderColor,
                    shape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (indeterminate) {
                Canvas(Modifier.size(10.dp)) {
                    drawLine(
                        color = contentColor,
                        start = Offset(1.dp.toPx(), size.height / 2f),
                        end = Offset(size.width - 1.dp.toPx(), size.height / 2f),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            } else if (checked) {
                Canvas(Modifier.size(10.dp)) {
                    val unit = size.minDimension / 24f
                    val path = Path().apply {
                        moveTo(9f * unit, 16.17f * unit)
                        lineTo(4.83f * unit, 12f * unit)
                        lineTo(3.41f * unit, 13.41f * unit)
                        lineTo(9f * unit, 19f * unit)
                        lineTo(21f * unit, 7f * unit)
                        lineTo(19.59f * unit, 5.59f * unit)
                        close()
                    }
                    drawPath(path, color = contentColor)
                }
            }
        }
    }
}

/** FlClash-style tonal button: inset fill, primary ink, no thin outline. */
@Composable
fun NemuOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = scheme.flClashInset(),
            contentColor = contentColor,
            disabledContainerColor = scheme.flClashInset().copy(alpha = 0.5f),
            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.38f)
        ),
        content = content
    )
}
