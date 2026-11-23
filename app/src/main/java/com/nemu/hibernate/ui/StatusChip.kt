package com.nemu.hibernate.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.ui.theme.nemuInset

/** 状态 chip 的语义色；具体颜色由当前主题明暗推导。 */
enum class StatusChipTone {
    NEUTRAL,
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
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp
) {
    val (container, content) = chipColors(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            // A hair larger than Material's 11sp labelSmall; keeps every
            // status/policy chip consistent while making it easier to read.
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = fontSize
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
        // Every theme-owned control uses the selected palette's vivid primary
        // and the designed on-primary ink (white on the blue controls;
        // dark ink only where the palette itself specifies it).
        StatusChipTone.PRIMARY,
        StatusChipTone.SUCCESS,
        StatusChipTone.INFO,
        // Warnings are informational, not dangerous: follow the selected
        // palette instead of the old fixed orange, which looked wrong under
        // every non-orange theme.
        StatusChipTone.WARNING,
        StatusChipTone.THEME ->
            scheme.primary to scheme.onPrimary
        // Danger is the one non-palette semantic the user keeps: use the
        // theme's error token rather than a duplicated ARGB literal.
        StatusChipTone.ERROR ->
            scheme.error to scheme.onError
    }
}

/** Nemu filter chip: vivid primary when selected, inset when idle. */
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
    FilterChip(
        selected = selected,
        onClick = {
            haptics.perform(NemuHaptic.SegmentTick)
            onClick()
        },
        modifier = modifier.nemuPressFeedback(
            interaction,
            NemuPressSpec.Chip,
            enabled
        ),
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(percent = 50),
        border = null,
        label = label,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.nemuInset(),
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            disabledContainerColor = scheme.nemuInset().copy(alpha = 0.5f),
            disabledLabelColor = scheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    )
}
