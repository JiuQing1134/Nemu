package com.nemu.hibernate.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.core.RiskLevel

/** 状态 chip 的语义色；具体颜色由当前主题明暗推导。 */
enum class StatusChipTone {
    NEUTRAL,
    PRIMARY,
    SUCCESS,
    INFO,
    WARNING,
    ERROR
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
            style = MaterialTheme.typography.labelMedium,
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
            RiskLevel.GREEN -> StatusChipTone.SUCCESS
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
    val dark = scheme.surface.luminance() < 0.5f
    return when (tone) {
        StatusChipTone.NEUTRAL ->
            scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        // Solid accent: readable even on a primaryContainer hero card.
        StatusChipTone.PRIMARY -> scheme.primary to scheme.onPrimary
        // Risk colours stay semantically green/amber/red; everything else is
        // derived from the selected palette.
        StatusChipTone.SUCCESS -> if (dark) {
            Color(0xFF1F5223) to Color(0xFFB7F0BC)
        } else {
            Color(0xFFDCF3DD) to Color(0xFF1B5E20)
        }
        StatusChipTone.INFO ->
            scheme.secondaryContainer to scheme.onSecondaryContainer
        StatusChipTone.WARNING -> if (dark) {
            Color(0xFF5C4100) to Color(0xFFFFE08A)
        } else {
            Color(0xFFFFF0C2) to Color(0xFF5C4100)
        }
        StatusChipTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
}
