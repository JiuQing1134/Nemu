package com.nemu.hibernate.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.ui.theme.nemuInset

/** 状态 chip 的语义色；具体颜色由当前主题明暗推导。 */
enum class StatusChipTone {
    NEUTRAL,
    /** App-category chip (系统/用户/Root 管理器/…); keeps a quiet plate. */
    CATEGORY,
    PRIMARY,
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
    /** Quiet theme-neutral tone for low-risk labels. */
    LOW_RISK,
    /** Follows the selected palette instead of a fixed semantic colour. */
    THEME
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusChipTone = StatusChipTone.NEUTRAL,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.sp
) {
    // Informational/neutral statuses are plain themed text, not a filled
    // plate. The dark-on-pale chips were hard to read and competed with the
    // actual state; solid primary/error capsules stay for actionable state
    // (authorization, destructive state).
    val textOnly = when (tone) {
        StatusChipTone.INFO,
        StatusChipTone.WARNING,
        StatusChipTone.NEUTRAL,
        StatusChipTone.LOW_RISK -> true
        else -> false
    }
    if (textOnly) {
        Text(
            text = text,
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f
            ),
            color = when (tone) {
                StatusChipTone.INFO,
                StatusChipTone.WARNING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    val (container, content) = chipColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = fontSize,
                // A 13sp hub status needs more line height than the default
                // 10sp label; keeping the old lineHeight made it look smaller
                // than the glyphs actually were.
                lineHeight = fontSize * 1.25f
            ),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RiskChip(
    risk: RiskLevel,
    modifier: Modifier = Modifier
) {
    StatusChip(
        text = riskLabel(risk),
        modifier = modifier,
        tone = when (risk) {
            RiskLevel.GREEN -> StatusChipTone.LOW_RISK
            RiskLevel.YELLOW -> StatusChipTone.WARNING
            RiskLevel.RED -> StatusChipTone.ERROR
        }
    )
}

fun riskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.GREEN -> "低风险"
    RiskLevel.YELLOW -> "中风险"
    RiskLevel.RED -> "高风险"
}

@Composable
private fun chipColors(tone: StatusChipTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        StatusChipTone.NEUTRAL,
        StatusChipTone.LOW_RISK ->
            scheme.nemuInset() to scheme.onSurfaceVariant
        // Solid primary is the strongest level: effective state, active plan,
        // and the selected strategy.
        StatusChipTone.PRIMARY,
        StatusChipTone.SUCCESS,
        StatusChipTone.THEME,
        // App-category capsule: same solid control as the primary chips, with
        // white (on-primary) text, shared by All Apps and Deep Uninstall.
        StatusChipTone.CATEGORY ->
            scheme.primary to scheme.onPrimary

        // Metadata / membership: same palette, softer treatment so a
        // category or an "已添加" label does not compete with the state.
        StatusChipTone.INFO ->
            scheme.primaryContainer to scheme.onPrimaryContainer

        // Warning / partial / skipped: reuse the same light-blue container as
        // the informational chips. The blue palette's tertiary role is
        // purple, and its secondary container is still too saturated next to
        // the rest of the card; the user-approved light blue is primaryContainer.
        StatusChipTone.WARNING ->
            scheme.primaryContainer to scheme.onPrimaryContainer
        // Danger is the one non-palette semantic the user keeps: use the
        // theme's error token rather than a duplicated ARGB literal.
        StatusChipTone.ERROR ->
            scheme.error to scheme.onError
    }
}

/**
 * Compact Nemu filter chip: a custom pill, not Material's 32dp FilterChip.
 * The category row ("系统更新", "开机冻结", …) now measures about 21dp, so
 * more list remains visible without reducing the label below 11sp.
 */
@Composable
fun NemuFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    Surface(
        modifier = modifier
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.perform(NemuHaptic.SegmentTick)
                    onClick()
                }
            )
            .nemuPressFeedback(
                interaction,
                NemuPressSpec.Chip,
                enabled
            ),
        shape = RoundedCornerShape(percent = 50),
        color = when {
            selected && enabled -> scheme.primary
            selected -> scheme.primary.copy(alpha = 0.38f)
            enabled -> scheme.nemuInset()
            else -> scheme.nemuInset().copy(alpha = 0.5f)
        },
        contentColor = when {
            selected -> scheme.onPrimary
            enabled -> scheme.onSurfaceVariant
            else -> scheme.onSurfaceVariant.copy(alpha = 0.38f)
        }
    ) {
        Box(Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.labelSmall
                    .copy(fontSize = 11.sp)
            ) {
                label()
            }
        }
    }
}
