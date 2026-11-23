package com.nemu.hibernate.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.ui.theme.nemuCard
import com.nemu.hibernate.ui.theme.nemuInset

/**
 * Nemu switch: a 52 x 32 pill with a solid 24 dp thumb, no Material
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
    val haptics = rememberNemuHaptics()
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
            active -> scheme.nemuInset()
            else -> scheme.nemuInset().copy(alpha = 0.5f)
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
                onValueChange = {
                    haptics.perform(NemuHaptic.SegmentTick)
                    onCheckedChange?.invoke(it)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .nemuPressFeedback(interaction, NemuPressSpec.Switch, active)
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
    indeterminate: Boolean = false,
    /** Invisible touch area; the drawn box stays [glyphSize]. */
    touchSize: androidx.compose.ui.unit.Dp = 20.dp,
    /** Visual box size; child rows use a smaller glyph than a group header. */
    glyphSize: androidx.compose.ui.unit.Dp = 14.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    val toggle: (Boolean) -> Unit = { next ->
        haptics.perform(NemuHaptic.KeyboardTap.toggleFor(next))
        onCheckedChange(next)
    }
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
            .size(touchSize)
            .clip(RoundedCornerShape(4.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = null,
                onValueChange = toggle
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
                        toggle(!checked)
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
                .size(glyphSize)
                .nemuPressFeedback(
                    interaction,
                    NemuPressSpec.Checkbox,
                    enabled
                )
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
                Canvas(Modifier.size(glyphSize * 0.72f)) {
                    drawLine(
                        color = contentColor,
                        start = Offset(1.dp.toPx(), size.height / 2f),
                        end = Offset(size.width - 1.dp.toPx(), size.height / 2f),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            } else if (checked) {
                Canvas(Modifier.size(glyphSize * 0.72f)) {
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

/** Nemu tonal button: inset fill, primary ink, no thin outline. */
@Composable
fun NemuOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    androidx.compose.material3.Button(
        onClick = {
            haptics.perform(NemuHaptic.KeyboardTap)
            onClick()
        },
        modifier = modifier
            .nemuPressFeedback(interaction, NemuPressSpec.Button, enabled)
            .heightIn(min = 44.dp),
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = scheme.nemuInset(),
            contentColor = contentColor,
            disabledContainerColor = scheme.nemuInset().copy(alpha = 0.5f),
            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.38f)
        ),
        content = content
    )
}

/** Primary filled button with the shared press/haptic treatment. */
@Composable
fun NemuFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.ButtonColors =
        androidx.compose.material3.ButtonDefaults.buttonColors(),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    androidx.compose.material3.Button(
        onClick = {
            haptics.perform(NemuHaptic.KeyboardTap)
            onClick()
        },
        modifier = modifier.nemuPressFeedback(
            interaction,
            NemuPressSpec.Button,
            enabled
        ),
        enabled = enabled,
        interactionSource = interaction,
        colors = colors,
        content = content
    )
}

/**
 * "已选 N" is a fixed-left label followed by a left-aligned number, so 9→10
 * only grows to the right; the pill itself never shifts the label.
 */
@Composable
internal fun SelectedCountPill(
    count: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .nemuClickable(
                        enabled = enabled,
                        spec = NemuPressSpec.Chip,
                        haptic = NemuHaptic.KeyboardTap,
                        onClick = onClick
                    )
                    .semantics { contentDescription = "取消所有已选" }
            } else {
                Modifier
            }
        ),
        shape = RoundedCornerShape(percent = 50),
        color = scheme.primary,
        contentColor = scheme.onPrimary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }
}

/** Pill action used by the selection bar; same language as filter chips. */
@Composable
internal fun PillAction(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        primary && enabled -> scheme.primary
        primary -> scheme.primary.copy(alpha = 0.38f)
        else -> scheme.nemuInset()
    }
    val content = when {
        primary -> scheme.onPrimary
        enabled -> scheme.onSurfaceVariant
        else -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Surface(
        modifier = modifier.nemuClickable(
            enabled = enabled,
            spec = NemuPressSpec.Chip,
            haptic = NemuHaptic.KeyboardTap,
            role = Role.Button,
            onClick = onClick
        ),
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Compact selection action bar (~30dp). Every control is the same pill shape
 * as the filter chips; the selected-count pill clears the selection.
 */
@Composable
internal fun SelectionActionBar(
    selectedCount: Int,
    actionLabel: String,
    enabled: Boolean,
    onClear: () -> Unit,
    onAction: () -> Unit
) {
    // Floating card instead of a full-width band: a 12dp inset + rounded
    // card + soft shadow separates it from the page and the bottom nav.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.nemuCard(),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SelectedCountPill(
                count = selectedCount,
                enabled = enabled,
                onClick = onClear
            )
            Spacer(Modifier.weight(1f))
            PillAction(
                text = "清除",
                enabled = enabled,
                primary = false,
                onClick = onClear
            )
            PillAction(
                text = actionLabel,
                enabled = enabled,
                primary = true,
                onClick = onAction
            )
        }
        }
    }
}

/** Stateless 14dp checkbox glyph used inside pill controls. */
@Composable
private fun CheckboxGlyph(
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(3.dp)
    val fill = if (enabled) scheme.primary else scheme.primary.copy(alpha = 0.38f)
    val border = if (enabled) scheme.outline
    else scheme.outline.copy(alpha = 0.38f)
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(shape)
            .background(if (checked) fill else Color.Transparent)
            .border(1.dp, if (checked) fill else border, shape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
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
                drawPath(path, color = scheme.onPrimary)
            }
        }
    }
}

/**
 * Neutral pill wrapping the 全选 checkbox, matching the selected-count pill.
 * The whole pill is one toggle target, so the tiny 14dp glyph is easy to hit.
 */
@Composable
internal fun SelectAllPill(
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    Surface(
        modifier = modifier.nemuPressFeedback(
            interaction,
            NemuPressSpec.Chip,
            enabled
        ),
        shape = RoundedCornerShape(percent = 50),
        color = if (enabled) scheme.nemuInset()
        else scheme.nemuInset().copy(alpha = 0.5f),
        contentColor = if (enabled) scheme.onSurfaceVariant
        else scheme.onSurfaceVariant.copy(alpha = 0.38f)
    ) {
        Row(
            modifier = Modifier
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    interactionSource = interaction,
                    indication = null,
                    onValueChange = {
                        haptics.perform(
                            NemuHaptic.KeyboardTap.toggleFor(!checked)
                        )
                        onToggle()
                    }
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckboxGlyph(checked = checked, enabled = enabled)
            Spacer(Modifier.width(4.dp))
            Text(
                text = "全选",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }
}


/**
 * Click target for bare text/icon controls with no capsule behind them.
 * Keeps the click and semantics, but suppresses the rectangular/circular
 * ripple that otherwise looks like a stray grey ghost. Pressing dims the
 * content slightly so a bare control still acknowledges the tap. Optional
 * long-press support uses the same interaction source, so a successful long
 * press suppresses the short click instead of firing both.
 */
@Composable
internal fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = nemuClickable(
    enabled = enabled,
    spec = NemuPressSpec.Quiet,
    onClickLabel = onClickLabel,
    onLongClickLabel = onLongClickLabel,
    onLongClick = onLongClick,
    onClick = onClick
)
