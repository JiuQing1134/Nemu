package com.nemu.hibernate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.ui.theme.nemuCard

/**
 * Applications tab root: four compact rows, one per feature.
 *
 * Each row is a single line plus one status chip. Explanations belong inside
 * the page they describe, not on the entry screen.
 */
@Composable
fun ApplicationsHubScreen(
    state: DashboardState,
    onOpenFreeze: () -> Unit,
    onOpenUnfreeze: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenDeepUninstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val installed = state.catalog.filter { it.isInstalled }
    val frozenCount = installed.count { it.hasFrozenState }
    val restorableCount = installed.count {
        it.ownsAnyState && it.hasFrozenState
    }
    val planCount = installed.count { it.enabled && it.active }
    val deep = state.deepUninstall.items
    val backupCount = deep.count { it.backup != null }
    val uninstalledCount = deep.count { !it.installed }
    val deepStatus = when {
        !state.deepUninstall.bootRestoreReady -> "需手动恢复" to
            StatusChipTone.ERROR
        uninstalledCount > 0 ->
            "已卸载 $uninstalledCount" to StatusChipTone.WARNING
        backupCount > 0 -> "可恢复 $backupCount" to StatusChipTone.INFO
        !state.rootAvailable -> "需要 Root" to StatusChipTone.ERROR
        else -> "未使用" to StatusChipTone.NEUTRAL
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            HubRow(
                title = "冻结管理",
                status = if (frozenCount > 0) {
                    "已冻结 $frozenCount"
                } else {
                    "未冻结"
                },
                tone = if (frozenCount > 0) {
                    StatusChipTone.INFO
                } else {
                    StatusChipTone.NEUTRAL
                },
                glyph = NemuGlyph.SNOWFLAKE,
                onClick = onOpenFreeze
            )
        }
        item {
            HubRow(
                title = "解冻恢复",
                status = if (restorableCount > 0) {
                    "可解冻 $restorableCount"
                } else {
                    "无冻结"
                },
                tone = if (restorableCount > 0) {
                    StatusChipTone.PRIMARY
                } else {
                    StatusChipTone.NEUTRAL
                },
                glyph = NemuGlyph.RESTORE,
                onClick = onOpenUnfreeze
            )
        }
        item {
            HubRow(
                title = "开机冻结",
                status = when {
                    planCount == 0 -> "未设置"
                    state.bootFreeze.enabled -> "计划 $planCount"
                    else -> "计划 $planCount · 关闭"
                },
                tone = when {
                    planCount == 0 -> StatusChipTone.NEUTRAL
                    state.bootFreeze.enabled -> StatusChipTone.PRIMARY
                    else -> StatusChipTone.NEUTRAL
                },
                glyph = NemuGlyph.PLAN,
                onClick = onOpenPlan
            )
        }
        item {
            HubRow(
                title = "深度卸载",
                status = deepStatus.first,
                tone = deepStatus.second,
                glyph = NemuGlyph.UNINSTALL,
                danger = true,
                onClick = onOpenDeepUninstall
            )
        }
    }
}

@Composable
private fun HubRow(
    title: String,
    status: String,
    tone: StatusChipTone,
    glyph: NemuGlyph,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (danger) scheme.error else scheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = scheme.nemuCard()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nemuClickable(
                    spec = NemuPressSpec.Row,
                    haptic = NemuHaptic.KeyboardTap,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(22.dp)) {
                    drawNemuGlyph(glyph, tint)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 16.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            StatusChip(
                text = status,
                tone = tone,
                // Feature titles are 16sp. A strict 2sp gap (14sp) disappears
                // against the filled pill; 12sp keeps the state visibly
                // secondary while still readable.
                fontSize = 12.sp
            )
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp
                ),
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
