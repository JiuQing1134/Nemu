package com.nemu.hibernate.ui

import android.content.ClipData
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.ui.theme.nemuCard
import com.nemu.hibernate.ui.theme.nemuInset
import kotlinx.coroutines.launch

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
            .size(width = 52.dp, height = 34.dp)
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
                .size(width = 44.dp, height = 28.dp)
                .nemuPressFeedback(interaction, NemuPressSpec.Switch, active)
                .clip(CircleShape)
                .background(track)
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (3.dp + 18.dp * progress).roundToPx(),
                            y = 0
                        )
                    }
                    .size(20.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(thumb)
            )
        }
    }
}

/**
 * Compact plan switch for dense target cards: 34 x 18dp track inside a
 * 44 x 28dp touch target. Same colors/behavior as [NemuSwitch], without the
 * full-width row a 52 x 32dp switch needs.
 */
@Composable
fun NemuMiniSwitch(
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
        animationSpec = tween(durationMillis = 180),
        label = "miniSwitch"
    )
    val track by animateColorAsState(
        targetValue = when {
            checked && active -> scheme.primary
            checked -> scheme.primary.copy(alpha = 0.55f)
            active -> scheme.nemuInset()
            else -> scheme.nemuInset().copy(alpha = 0.5f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "miniSwitchTrack"
    )
    val thumb by animateColorAsState(
        targetValue = when {
            checked && active -> scheme.onPrimary
            checked -> scheme.onPrimary.copy(alpha = 0.75f)
            active -> scheme.outline
            else -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "miniSwitchThumb"
    )
    Box(
        modifier = modifier
            .size(width = 40.dp, height = 26.dp)
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
                .size(width = 30.dp, height = 16.dp)
                .nemuPressFeedback(interaction, NemuPressSpec.Switch, active)
                .clip(CircleShape)
                .background(track)
        ) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (2.dp + 14.dp * progress).roundToPx(),
                            y = 0
                        )
                    }
                    .size(12.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(thumb)
            )
        }
    }
}

/** Long-press copy for package names; one shared toast and clipboard path. */
@Composable
internal fun rememberPackageNameCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(clipboard, scope, context) {
        { packageName ->
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(
                        ClipData.newPlainText("应用包名", packageName)
                    )
                )
            }
            Toast.makeText(
                context,
                "已复制包名",
                Toast.LENGTH_SHORT
            ).show()
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
            .height(30.dp),
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = scheme.nemuInset(),
            contentColor = contentColor,
            disabledContainerColor = scheme.nemuInset().copy(alpha = 0.5f),
            disabledContentColor = scheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            )
        ) {
            content()
        }
    }
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
        modifier = modifier
            .nemuPressFeedback(
                interaction,
                NemuPressSpec.Button,
                enabled
            )
            .height(30.dp),
        enabled = enabled,
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = colors
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            )
        ) {
            content()
        }
    }
}

/**
 * Shared collapsible group heading used by 全部应用 and 检测. It is a plain
 * tappable row, not a card: the count and chevron carry the state, and the
 * title follows the screen's semantic colour (primary / error / neutral).
 */
@Composable
internal fun CollapsibleGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .nemuClickable(
                spec = NemuPressSpec.Row,
                haptic = NemuHaptic.KeyboardTap,
                role = Role.Button,
                onClickLabel = if (expanded) "收起$title" else "展开$title",
                onClick = onToggle
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$title $count",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = color,
            maxLines = 1
        )
        // A drawn chevron instead of the narrow "⌃/⌄" glyph: wider span and
        // a 2.6dp round stroke stay legible at a glance.
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.6.dp.toPx()
            val yOuter = if (expanded) size.height * 0.62f else size.height * 0.38f
            val yMid = if (expanded) size.height * 0.35f else size.height * 0.65f
            val left = size.width * 0.20f
            val right = size.width * 0.80f
            val mid = size.width * 0.50f
            drawLine(
                color = color,
                start = Offset(left, yOuter),
                end = Offset(mid, yMid),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(mid, yMid),
                end = Offset(right, yOuter),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * "已选 N" is a fixed-left label followed by a left-aligned number, so 9→10
 * only grows to the right; the pill itself never shifts the label. When
 * [onClick] is supplied the pill gains a trailing × and becomes the single
 * “clear all selection” control.
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp
                ),
                maxLines = 1
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp
                ),
                maxLines = 1
            )
            if (onClick != null) {
                // A visible × makes the pill's clear action discoverable
                // instead of looking like a read-only status chip.
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "×",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
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
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
    onAction: () -> Unit,
    actionEnabled: Boolean = enabled,
    secondaryLabel: String? = null,
    secondaryEnabled: Boolean = enabled,
    onSecondary: () -> Unit = {}
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
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.nemuCard(),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // One clear affordance only: the count pill itself clears the
            // whole selection. A separate 清除 pill duplicated it and made
            // the narrow bar wider than necessary.
            SelectedCountPill(
                count = selectedCount,
                enabled = enabled,
                onClick = onClear
            )
            // FlowRow lets the remaining actions wrap to a second line on a
            // 320dp screen or a large font scale. A plain Row measured the
            // primary action last and clipped its label first.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(
                    6.dp,
                    Alignment.End
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                secondaryLabel?.let { label ->
                    PillAction(
                        text = label,
                        enabled = secondaryEnabled,
                        primary = false,
                        onClick = onSecondary
                    )
                }
                PillAction(
                    text = actionLabel,
                    enabled = actionEnabled,
                    primary = true,
                    onClick = onAction
                )
            }
        }
        }
    }
}

/**
 * Persistent batch-result bar above the Applications list. One compact row:
 * title (+ optional 2-line detail), a 详情 action when targets were skipped,
 * 撤销 for the last plan batch, and a 44dp close target. No shadow: the bar
 * sits on the page like an inset card, not a floating snackbar.
 */
@Composable
internal fun BatchFeedbackBar(
    feedback: BatchFeedbackUi,
    onDismiss: () -> Unit,
    onUndo: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var showSkipped by remember { mutableStateOf(false) }
    // In landscape/split-screen the list is already short. Collapse the
    // detail text so the result bar does not consume the remaining rows.
    val compact = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() < 480.dp
    }
    val container = when (feedback.tone) {
        BatchFeedbackTone.SUCCESS -> scheme.primary.copy(alpha = 0.12f)
        BatchFeedbackTone.WARNING -> scheme.secondary.copy(alpha = 0.14f)
        BatchFeedbackTone.ERROR -> scheme.error.copy(alpha = 0.12f)
        BatchFeedbackTone.NEUTRAL -> scheme.surfaceVariant
    }
    val titleColor = when (feedback.tone) {
        BatchFeedbackTone.ERROR -> scheme.error
        else -> scheme.onSurface
    }
    val actionColor = when (feedback.tone) {
        BatchFeedbackTone.SUCCESS -> scheme.primary
        BatchFeedbackTone.ERROR -> scheme.error
        else -> scheme.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = scheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(
                start = 14.dp,
                top = if (compact) 3.dp else 6.dp,
                bottom = if (compact) 3.dp else 6.dp,
                end = 2.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = feedback.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!compact) {
                    feedback.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (feedback.skipped.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .noRippleClickable { showSkipped = true }
                        .semantics {
                            role = Role.Button
                            contentDescription = "查看结果详情"
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            if (feedback.undoAvailable) {
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .noRippleClickable { onUndo() }
                        .semantics {
                            role = Role.Button
                            contentDescription = "撤销上次计划变更"
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "撤销",
                        style = MaterialTheme.typography.labelMedium,
                        color = actionColor
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .noRippleClickable { onDismiss() }
                    .semantics {
                        role = Role.Button
                        contentDescription = "关闭结果提示"
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
    if (showSkipped) {
        AlertDialog(
            onDismissRequest = { showSkipped = false },
            title = { Text("结果详情") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    feedback.skipped.forEach { line ->
                        Text(
                            text = "· $line",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSkipped = false }) {
                    Text("关闭")
                }
            }
        )
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
