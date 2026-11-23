package com.nemu.hibernate.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.ui.theme.flClashInset

/** 状态 chip 的语义色；具体颜色由当前主题明暗推导。 */
enum class StatusChipTone {
    NEUTRAL,
    PRIMARY,
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
    /** Fixed green for low-risk labels, independent of the user palette. */
    LOW_RISK,
    /** Follows the selected palette instead of a fixed semantic colour. */
    THEME
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusChipTone = StatusChipTone.NEUTRAL
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
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
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
        StatusChipTone.NEUTRAL ->
            scheme.flClashInset() to scheme.onSurfaceVariant
        // Every theme-owned control uses the selected palette's vivid primary
        // and FlClash's designed on-primary ink (white on the blue controls;
        // dark ink only where the palette itself specifies it).
        StatusChipTone.PRIMARY ->
            scheme.primary to scheme.onPrimary
        StatusChipTone.SUCCESS ->
            scheme.primary to scheme.onPrimary
        StatusChipTone.INFO ->
            scheme.primary to scheme.onPrimary
        StatusChipTone.WARNING ->
            Color(0xFFFF8D28) to Color(0xFF1A1200)
        // High-risk chips use the vivid FlClash red (same as the RED palette
        // and the high-risk body ink), not the old muted brick shade.
        StatusChipTone.ERROR ->
            Color(0xFFFF383C) to Color.White
        StatusChipTone.LOW_RISK ->
            Color(0xFF2E7D32) to Color.White
        StatusChipTone.THEME ->
            scheme.primary to scheme.onPrimary
    }
}

/** FlClash-style filter chip: vivid primary when selected, inset when idle. */
@Composable
fun NemuFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        border = null,
        label = label,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.flClashInset(),
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
            disabledContainerColor = scheme.flClashInset().copy(alpha = 0.5f),
            disabledLabelColor = scheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    )
}
