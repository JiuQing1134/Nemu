package com.nemu.hibernate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.cota.CotaDirectoryInventory
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaTimestamps
import com.nemu.hibernate.detection.BootSafetyState
import com.nemu.hibernate.detection.CotaReadStatus
import com.nemu.hibernate.detection.CotaState
import com.nemu.hibernate.detection.DetectionSnapshot
import com.nemu.hibernate.detection.FreezeMode
import com.nemu.hibernate.detection.FreezeSource
import com.nemu.hibernate.detection.FreezeState
import com.nemu.hibernate.detection.ThermalServiceState
import com.nemu.hibernate.detection.ThermalState
import com.nemu.hibernate.detection.formatDetectionTimestamp
import com.nemu.hibernate.ui.theme.nemuCard
import com.nemu.hibernate.ui.theme.nemuInset
import java.util.Locale

data class DetectionScreenUiState(
    val snapshot: DetectionSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * Read-only detection screen. The only callback is [onRefresh]; there are no
 * freeze, stop, delete, backup or restore actions by design.
 */
@Composable
fun DetectionScreen(
    state: DetectionScreenUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTarget: (String) -> Unit = {}
) {
    val snapshot = state.snapshot
    var selectedCategory by rememberSaveable { mutableStateOf(DetectionCategory.FREEZE.name) }
    val category = DetectionCategory.entries
        .firstOrNull { it.name == selectedCategory }
        ?: DetectionCategory.FREEZE
    var showMismatchGroup by rememberSaveable { mutableStateOf(true) }
    var showEffectiveGroup by rememberSaveable { mutableStateOf(true) }
    // The unfrozen rows are the long tail; collapse them by default.
    var showInactiveGroup by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    // Re-entering the tab restores the saved list position; only an actual
    // category change should jump to the top.
    var lastCategory by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(category) {
        if (lastCategory != category.name) {
            listState.scrollToItem(0)
            lastCategory = category.name
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 6.dp,
            end = 16.dp,
            bottom = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            DetectionHeader(
                state = state,
                category = category,
                onCategoryChange = { selectedCategory = it.name },
                onRefresh = onRefresh
            )
        }

        if (snapshot == null) {
            item { DetectionEmptyCard(state = state) }
        } else {
            when (category) {
                DetectionCategory.FREEZE -> {
                    val rows = nonCloudFreezeRows(snapshot.freezeStates)
                        .sortedWith(
                            compareBy(
                                { appNameSortKey(it.label) },
                                { it.packageName }
                            )
                        )
                    val mismatched = rows.filter { it.mismatch() }
                    val effective = rows.filter {
                        it.actuallyFrozen && !it.mismatch()
                    }
                    val inactive = rows.filter {
                        !it.actuallyFrozen && !it.mismatch()
                    }
                    if (rows.isEmpty()) {
                        item { DetectionInfoCard("没有应用冻结数据。") }
                    } else {
                        item { SectionHeader(title = "应用状态") }
                        if (mismatched.isNotEmpty()) {
                            item(key = "freeze-status:mismatch") {
                                CollapsibleGroupHeader(
                                    title = "不一致",
                                    count = mismatched.size,
                                    expanded = showMismatchGroup,
                                    onToggle = {
                                        showMismatchGroup = !showMismatchGroup
                                    },
                                    color = scheme.error
                                )
                            }
                            if (showMismatchGroup) {
                                items(
                                    items = mismatched,
                                    key = { "mismatch:${it.source}:${it.packageName}" }
                                ) { freeze ->
                                    FreezeStateCard(freeze, onOpenTarget)
                                }
                            }
                        }
                        if (effective.isNotEmpty()) {
                            item(key = "freeze-status:effective") {
                                CollapsibleGroupHeader(
                                    title = "已生效",
                                    count = effective.size,
                                    expanded = showEffectiveGroup,
                                    onToggle = {
                                        showEffectiveGroup = !showEffectiveGroup
                                    },
                                    color = scheme.primary
                                )
                            }
                            if (showEffectiveGroup) {
                                items(
                                    items = effective,
                                    key = { "effective:${it.source}:${it.packageName}" }
                                ) { freeze ->
                                    FreezeStateCard(freeze, onOpenTarget)
                                }
                            }
                        }
                        if (inactive.isNotEmpty()) {
                            item(key = "freeze-status:inactive") {
                                CollapsibleGroupHeader(
                                    title = "未生效",
                                    count = inactive.size,
                                    expanded = showInactiveGroup,
                                    onToggle = {
                                        showInactiveGroup = !showInactiveGroup
                                    },
                                    color = scheme.primary
                                )
                            }
                            if (showInactiveGroup) {
                                items(
                                    items = inactive,
                                    key = { "inactive:${it.source}:${it.packageName}" }
                                ) { freeze ->
                                    FreezeStateCard(freeze, onOpenTarget)
                                }
                            }
                        }
                    }
                }
                DetectionCategory.TELEMETRY -> {
                    val cloudRows = cloudFreezeRows(snapshot.freezeStates)
                        .sortedWith(
                            compareBy(
                                { appNameSortKey(it.label) },
                                { it.packageName }
                            )
                        )
                    if (cloudRows.isEmpty()) {
                        item { DetectionInfoCard("没有云控遥测数据。") }
                    } else {
                        item {
                            SectionHeader(
                                title = "云控 / 遥测",
                                subtitle = "云控来源应用 · 只读",
                                subtitleMaxLines = 1,
                                trailing = {
                                    DetectionStatusChip(
                                        "${cloudRows.count { it.actuallyFrozen }}" +
                                            " / ${cloudRows.size}"
                                    )
                                }
                            )
                        }
                        items(
                            items = cloudRows,
                            key = { "${it.source}:${it.packageName}" }
                        ) { freeze -> FreezeStateCard(freeze, onOpenTarget) }
                    }
                }
                DetectionCategory.SYSTEM -> {
                    item {
                        SectionHeader(
                            title = "温控与功耗",
                            subtitle = "PowerGenie / iAware / HnAps / MSDP 基础服务（只读）"
                        )
                    }
                    item { ThermalCard(snapshot) }
                    item {
                        SectionHeader(
                            title = "COTA 厂商云控",
                            subtitle = "仅 Root 可读；管理在 设置 › 高级"
                        )
                    }
                    item { CotaCard(snapshot.cota) }
                }
                DetectionCategory.STARTUP -> {
                    item {
                        SectionHeader(
                            title = "启动 / 关机安全",
                            subtitle = "关机前自动解冻；上次未正常关机会跳过本次自动冻结"
                        )
                    }
                    item { BootSafetyCard(snapshot) }
                }
            }
            if (snapshot.notes.isNotEmpty()) {
                item {
                    DetectionInfoCard(
                        snapshot.notes.joinToString("\n") { "提示：$it" }
                    )
                }
            }
        }
        item { Spacer(Modifier.size(4.dp)) }
    }
}

enum class DetectionCategory(val label: String) {
    FREEZE("应用冻结"),
    TELEMETRY("云控遥测"),
    SYSTEM("系统服务"),
    STARTUP("启动安全")
}

/** Tone of a compact overview block; danger stays the theme error token. */
internal enum class DetectionSummaryTone {
    NEUTRAL,
    PRIMARY,
    ERROR
}

internal data class DetectionSummary(
    val label: String,
    val tone: DetectionSummaryTone
)

/** Counts and labels shown in the four-block overview above the categories. */
internal data class DetectionOverview(
    /** Actual frozen rows, not the catalog size. */
    val freezeCount: Int,
    /** All non-cloud catalog rows, shown as the denominator. */
    val freezeTotal: Int,
    val freezeMismatchCount: Int,
    /** Actual restricted/frozen cloud rows. */
    val cloudCount: Int,
    val cloudTotal: Int,
    val cloudMismatchCount: Int,
    val system: DetectionSummary,
    val startup: DetectionSummary
)

internal fun nonCloudFreezeRows(states: List<FreezeState>): List<FreezeState> =
    states.filter { it.source != FreezeSource.CLOUD }

internal fun cloudFreezeRows(states: List<FreezeState>): List<FreezeState> =
    states.filter { it.source == FreezeSource.CLOUD }

internal fun freezeSourceTitle(source: FreezeSource): String = when (source) {
    FreezeSource.CORE -> "核心应用"
    FreezeSource.AI -> "AI 应用"
    FreezeSource.CLOUD -> "云控应用"
    FreezeSource.CATALOG -> "其他应用"
}

internal fun DetectionSnapshot.overviewSummary(): DetectionOverview {
    val freezeRows = nonCloudFreezeRows(freezeStates)
    val cloudRows = cloudFreezeRows(freezeStates)
    return DetectionOverview(
        freezeCount = freezeRows.count { it.actuallyFrozen },
        freezeTotal = freezeRows.size,
        freezeMismatchCount = freezeRows.count { it.mismatch() },
        cloudCount = cloudRows.count { it.actuallyFrozen },
        cloudTotal = cloudRows.size,
        cloudMismatchCount = cloudRows.count { it.mismatch() },
        system = systemSummary(thermal, cota),
        startup = startupSummary(bootSafety)
    )
}

/** N/status shown on the 系统服务 overview block. */
internal fun systemSummary(
    thermal: ThermalState,
    cota: CotaState
): DetectionSummary {
    val thermalFailed = thermal.services.any { !it.actualKnown } ||
        (thermal.services.isEmpty() && thermal.message?.contains("失败") == true)
    val failed = thermalFailed || cota.status == CotaReadStatus.ERROR
    val readable = thermal.services.isNotEmpty() ||
        cota.status == CotaReadStatus.OK
    return DetectionSummary(
        label = when {
            failed -> "失败"
            thermal.services.isNotEmpty() -> "${thermal.services.size} 项"
            cota.status == CotaReadStatus.OK -> "已读取"
            cota.status == CotaReadStatus.NO_ROOT -> "无 Root"
            else -> "未读取"
        },
        tone = when {
            failed -> DetectionSummaryTone.ERROR
            readable -> DetectionSummaryTone.PRIMARY
            else -> DetectionSummaryTone.NEUTRAL
        }
    )
}

/** 正常 / 异常 / 未读 for the 启动安全 overview block. */
internal fun startupSummary(boot: BootSafetyState): DetectionSummary = when {
    // A snapshot exists but its boot-safety section failed to read. The
    // empty-state card is used before the first snapshot, so this is an
    // error, not "not read yet".
    !boot.readOk -> DetectionSummary("读取失败", DetectionSummaryTone.ERROR)
    boot.startupAnomaly() -> DetectionSummary("异常", DetectionSummaryTone.ERROR)
    else -> DetectionSummary("未见异常", DetectionSummaryTone.PRIMARY)
}

/**
 * A failed/interrupted shutdown or a tripped guard is an anomaly. A never-run
 * flow ("尚未执行") and an unreadable snapshot are not.
 */
internal fun BootSafetyState.startupAnomaly(): Boolean {
    if (!readOk) return false
    return bootloopGuardTripped() ||
        shutdownAbnormal() ||
        lastBootResult?.contains("失败") == true ||
        lastBootFreezeResult?.contains("失败") == true
}

internal fun BootSafetyState.shutdownAbnormal(): Boolean =
    lastShutdownResult
        ?.takeUnless { it.isBlank() || it == "尚未执行" }
        ?.let { !lastShutdownClean() }
        ?: false

internal fun bootPlanLabel(boot: BootSafetyState): String =
    if (boot.bootFreezeEnabled) {
        "已开启 · 延迟 ${boot.bootFreezeDelaySeconds} 秒"
    } else {
        "已关闭"
    }

/** Compact guard state for the boot summary card. */
internal fun bootGuardLabel(boot: BootSafetyState): String = when {
    !boot.readOk -> "读取失败"
    else -> boot.guardNote() ?: if (boot.bootFreezeEnabled) "未触发" else "未启用"
}

/** One compact line replacing three competing chips on the freeze cards. */
internal fun freezeSummaryLine(freeze: FreezeState): String {
    if (freeze.mode == FreezeMode.OBSERVE) return "仅观察"
    return buildString {
        append(
            if (freeze.policyEnabled) "已添加" else "未添加"
        )
        append(" · ")
        append(freeze.compactActual())
        // Only surface process presence when it contradicts "frozen";
        // otherwise "无进程/未知" is noise.
        if (freeze.mode == FreezeMode.SUSPEND &&
            freeze.processRunning == true
        ) {
            append(" · 仍有进程")
        }
    }
}

/** Short actual-state word for the summary line; nulls stay 未知. */
internal fun FreezeState.compactActual(): String = when {
    mode == FreezeMode.OBSERVE -> "未执行"
    mode == FreezeMode.HIDE -> detailValue()
    mode == FreezeMode.RESTRICT && !actualKnown -> "读取失败"
    !actualKnown -> "未知"
    mode == FreezeMode.SUSPEND -> when {
        suspended && !policyApplied -> "他人暂停"
        suspended -> "已${suspendWord()}"
        else -> "未${suspendWord()}"
    }
    mode == FreezeMode.DISABLE -> if (!enabled) "已停用" else "仍启用"
    mode == FreezeMode.COMPONENT -> when {
        componentPartial -> "组件部分停用"
        componentDisabled == true -> "组件已停用"
        componentDisabled == false -> "组件启用"
        else -> "组件状态未知"
    }
    mode == FreezeMode.RESTRICT ->
        if (restrictionActual ?: restrictionApplied) "已限制" else "未限制"
    else -> "未知"
}

@Composable
private fun DetectionHeader(
    state: DetectionScreenUiState,
    category: DetectionCategory,
    onCategoryChange: (DetectionCategory) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "设备检测",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp
                        )
                    )
                    state.snapshot?.let { snapshot ->
                        if (snapshot.generatedAtMillis > 0) {
                            Text(
                                "更新于 ${formatDetectionTimestamp(snapshot.generatedAtMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = if (state.loading) "读取中…" else "刷新",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.loading) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .nemuClickable(
                            enabled = !state.loading,
                            spec = NemuPressSpec.Chip,
                            haptic = NemuHaptic.KeyboardTap,
                            role = Role.Button,
                            onClick = onRefresh
                        )
                        // Invisible padding: 48dp-tall touch target while the
                        // label itself stays a bare text control.
                        .padding(horizontal = 10.dp, vertical = 16.dp)
                )
            }

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在读取状态…", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.error?.let { error ->
                Text(
                    "读取错误：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.snapshot?.let { snapshot ->
                DetectionOverviewRow(
                    overview = snapshot.overviewSummary(),
                    category = category,
                    onCategoryChange = onCategoryChange
                )
            }

        }
    }
}

@Composable
private fun DetectionOverviewRow(
    overview: DetectionOverview,
    category: DetectionCategory,
    onCategoryChange: (DetectionCategory) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DetectionOverviewBlock(
            title = "应用冻结",
            value = "${overview.freezeCount}/${overview.freezeTotal}",
            tone = if (overview.freezeMismatchCount > 0) {
                DetectionSummaryTone.ERROR
            } else {
                DetectionSummaryTone.PRIMARY
            },
            selected = category == DetectionCategory.FREEZE,
            onClick = { onCategoryChange(DetectionCategory.FREEZE) },
            modifier = Modifier.weight(1f)
        )
        DetectionOverviewBlock(
            title = "云控遥测",
            value = "${overview.cloudCount}/${overview.cloudTotal}",
            tone = if (overview.cloudMismatchCount > 0) {
                DetectionSummaryTone.ERROR
            } else {
                DetectionSummaryTone.PRIMARY
            },
            selected = category == DetectionCategory.TELEMETRY,
            onClick = { onCategoryChange(DetectionCategory.TELEMETRY) },
            modifier = Modifier.weight(1f)
        )
        DetectionOverviewBlock(
            title = "系统服务",
            value = overview.system.label,
            tone = overview.system.tone,
            selected = category == DetectionCategory.SYSTEM,
            onClick = { onCategoryChange(DetectionCategory.SYSTEM) },
            modifier = Modifier.weight(1f)
        )
        DetectionOverviewBlock(
            title = "启动安全",
            value = overview.startup.label,
            tone = overview.startup.tone,
            selected = category == DetectionCategory.STARTUP,
            onClick = { onCategoryChange(DetectionCategory.STARTUP) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DetectionOverviewBlock(
    title: String,
    value: String,
    tone: DetectionSummaryTone,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberNemuHaptics()
    Surface(
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = {
                    haptics.perform(NemuHaptic.SegmentTick)
                    onClick()
                }
            )
            .nemuPressFeedback(interaction, NemuPressSpec.Chip)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title，$value"
            },
        shape = RoundedCornerShape(12.dp),
        color = scheme.nemuInset(),
        contentColor = scheme.onSurface,
        border = if (selected) BorderStroke(1.5.dp, scheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                color = when {
                    tone == DetectionSummaryTone.ERROR -> scheme.error
                    selected -> scheme.primary
                    else -> scheme.onSurface
                },
                // One line only: four equal blocks on a 360dp screen must not
                // wrap "系统服务" values onto a second row.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun detectionCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.nemuCard()
)

@Composable
private fun DetectionEmptyCard(state: DetectionScreenUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("尚未读取检测状态", style = MaterialTheme.typography.titleMedium)
            Text(
                "点击“刷新”读取冻结、云控/遥测、温控/COTA、启动/安全状态。",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.loading) {
                Text("正在读取…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DetectionInfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DetectionStatusChip(text: String, warning: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    // Inconsistent actual state is important: keep the theme's danger red.
    val background = if (warning) scheme.error else scheme.primary
    val foreground = if (warning) scheme.onError else scheme.onPrimary
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
private fun FreezeStateCard(
    freeze: FreezeState,
    onOpenTarget: (String) -> Unit
) {
    var expanded by rememberSaveable(freeze.packageName) { mutableStateOf(false) }
    val cardPress = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .nemuPressFeedback(cardPress, NemuPressSpec.Card),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Whole card is the expand target; no visible 展开 text.
                .clickable(
                    interactionSource = cardPress,
                    indication = null
                ) { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(freeze.label)
                        append("，")
                        append(freeze.statusLabel())
                        append("，")
                        append(if (expanded) "点按收起" else "点按展开")
                    }
                }
                // Match DetectionHeader's 12dp inset so the count chip and
                // every row's status chip share the same right edge.
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(freeze.label, style = MaterialTheme.typography.titleSmall)
                    if (shouldShowPackageName(freeze.label, freeze.packageName)) {
                        Text(
                            freeze.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DetectionStatusChip(
                        text = freeze.statusLabel(),
                        warning = freeze.mismatch() ||
                            freeze.partiallyApplied()
                    )
                }
            }
            // One compact line instead of three competing chips: policy,
            // actual state and process presence. The plan prefix stays muted;
            // only the actual state carries the theme colour.
            val summary = freezeSummaryLine(freeze)
            val summaryNormal = !freeze.mismatch() && !freeze.partiallyApplied()
            val summaryColor = when {
                freeze.mismatch() -> MaterialTheme.colorScheme.error
                freeze.partiallyApplied() -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val primary = MaterialTheme.colorScheme.primary
            val summaryText = remember(summary, summaryNormal, primary) {
                buildAnnotatedString {
                    val separator = summary.indexOf(" · ")
                    if (summaryNormal && separator >= 0) {
                        append(summary.substring(0, separator + 3))
                        withStyle(
                            SpanStyle(
                                color = primary,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(summary.substring(separator + 3))
                        }
                    } else {
                        append(summary)
                    }
                }
            }
            Text(
                summaryText,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = summaryColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (expanded) {
                Text("包状态：${freeze.describeActual()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "来源：${freezeSourceTitle(freeze.source)}" +
                        " · 系统“已停止”标记：" +
                        (if (freeze.stopped) "已设置" else "未设置"),
                    style = MaterialTheme.typography.bodySmall
                )
                freeze.risk?.let { risk ->
                    Text("风险：${detectionRiskLabel(risk)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (expanded && freeze.mismatch()) {
                Text(
                    "策略与实际状态不一致；本页只展示，不会自动修复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (expanded && freeze.partiallyApplied()) {
                Text(
                    "系统显示已${freeze.suspendWord()}，但进程列表仍有该应用进程。" +
                        "强制停止是一次性请求；系统绑定服务可能再次启动它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (expanded) {
                TextButton(onClick = { onOpenTarget(freeze.packageName) }) {
                    Text("在冻结管理页查看 ›")
                }
            }
        }
    }
}

@Composable
private fun ThermalCard(snapshot: DetectionSnapshot) {
    val thermal = snapshot.thermal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("温控基础服务", style = MaterialTheme.typography.titleSmall)
            Text(
                "PowerGenie 负责温控/充电；整包策略已开放但高风险，部分组件命令" +
                    "系统不支持；iAware 建议只停用云配置组件；HnAps/MSDP 默认观察。" +
                    "这里的数字表示包是否安装或启用，不代表进程是否存活。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (thermal.services.isEmpty()) {
                Text(
                    thermal.message ?: "未检测",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    thermal.describe(),
                    style = MaterialTheme.typography.bodySmall
                )
                thermal.services.forEach { service ->
                    ThermalServiceRow(service)
                }
            }
            Text(
                thermal.readOnlyNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ThermalServiceRow(service: ThermalServiceState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(service.label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (shouldShowPackageName(service.label, service.packageName)) {
                "${service.packageName}：${service.describe()}"
            } else {
                service.describe()
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CotaCard(cota: CotaState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "COTA 参数详情",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                cota.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = if (cota.status == CotaReadStatus.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (cota.files.isEmpty() && cota.message != null &&
                cota.status != CotaReadStatus.ERROR
            ) {
                Text(cota.message, style = MaterialTheme.typography.bodySmall)
            }
            cota.files.forEach { file -> CotaFileRow(file) }
            if (cota.directories.isNotEmpty()) {
                Text("子目录配置索引", style = MaterialTheme.typography.titleSmall)
                cota.directories.forEach { directory ->
                    CotaDirectoryRow(directory)
                }
            }
            Text(
                "子目录仅展示文件名与大小，不读取内容或修改文件。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CotaDirectoryRow(directory: CotaDirectoryInventory) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(directory.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${directory.fileCount} 个文件 · ${formatBytes(directory.totalSizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        directory.samplePaths.forEach { path ->
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CotaFileRow(file: CotaFile) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                CotaTimestamps.displayLabel(file.modifiedAtSeconds),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatBytes(file.sizeBytes),
                modifier = Modifier.width(72.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Semantic emphasis for one boot-safety value. */
internal enum class BootLineTone { PRIMARY, NEUTRAL, WARNING, ERROR }

/** One compact row in the merged boot-safety card. */
internal data class BootStatusLine(
    val label: String,
    val value: String,
    val danger: Boolean = false,
    /** Substring of [value] that carries the state; the rest stays neutral. */
    val highlight: String? = null,
    val tone: BootLineTone = if (danger) BootLineTone.ERROR else BootLineTone.NEUTRAL
)

/** First word of a result string, before a timestamp or explanation. */
internal fun bootResultKeyword(result: String?): String? {
    if (result.isNullOrBlank()) return null
    return result
        .substringBefore("：")
        .substringBefore("（")
        .substringBefore("(")
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * The four summary facts rendered once at the top of the boot-safety card:
 * boot plan, last shutdown, last boot and the bootloop guard. The plan row is
 * also the single place for the boot auto-freeze switch state and delay; the
 * guard row carries the guard note, so neither is repeated below the divider.
 *
 * Only the conclusion word gets the semantic colour; timestamps and the
 * "延迟 120 秒" tail stay neutral.
 */
internal fun bootSummaryLines(boot: BootSafetyState): List<BootStatusLine> {
    val readOk = boot.readOk
    val shutdownAbnormal = readOk && boot.shutdownAbnormal()
    val bootFailed = readOk && boot.lastBootResult?.contains("失败") == true
    val bootSkipped = readOk && boot.lastBootResult?.contains("跳过") == true
    val guardTripped = readOk && boot.bootloopGuardTripped()
    val guardSkipped = readOk && boot.bootFreezeEnabled && boot.skippedThisBoot()
    return listOf(
        BootStatusLine(
            label = "开机冻结",
            value = if (readOk) bootPlanLabel(boot) else "读取失败",
            danger = !readOk,
            highlight = when {
                !readOk -> "读取失败"
                boot.bootFreezeEnabled -> "已开启"
                else -> "已关闭"
            },
            tone = when {
                !readOk -> BootLineTone.ERROR
                boot.bootFreezeEnabled -> BootLineTone.PRIMARY
                else -> BootLineTone.NEUTRAL
            }
        ),
        BootStatusLine(
            label = "上次关机",
            value = boot.describeLastShutdown(),
            danger = shutdownAbnormal,
            highlight = bootResultKeyword(boot.lastShutdownResult),
            tone = when {
                shutdownAbnormal -> BootLineTone.ERROR
                readOk && boot.lastShutdownClean() -> BootLineTone.PRIMARY
                else -> BootLineTone.NEUTRAL
            }
        ),
        BootStatusLine(
            label = "上次开机",
            value = boot.describeLastBoot(),
            danger = bootFailed,
            highlight = bootResultKeyword(boot.lastBootResult),
            tone = when {
                bootFailed -> BootLineTone.ERROR
                bootSkipped -> BootLineTone.WARNING
                readOk && boot.lastBootResult?.startsWith("成功") == true ->
                    BootLineTone.PRIMARY
                else -> BootLineTone.NEUTRAL
            }
        ),
        BootStatusLine(
            label = "守卫",
            // A manual unfreeze skip is a user choice, not an anomaly. Only
            // the bootloop guard is dangerous; red "未启用" was confusing.
            value = bootGuardLabel(boot),
            danger = guardTripped,
            highlight = when {
                !readOk -> "读取失败"
                guardTripped -> "守卫触发"
                guardSkipped -> "已跳过"
                boot.bootFreezeEnabled && boot.guardNote() == null -> "未触发"
                boot.guardNote() == null -> "未启用"
                else -> null
            },
            tone = when {
                !readOk || guardTripped -> BootLineTone.ERROR
                guardSkipped -> BootLineTone.WARNING
                // Only the plain "未触发" state is healthy; the note variants
                // ("本次开机已有冻结结果"/"已尝试冻结") stay neutral.
                boot.bootFreezeEnabled && boot.guardNote() == null ->
                    BootLineTone.PRIMARY
                else -> BootLineTone.NEUTRAL
            }
        )
    )
}

/** One secondary detail line in the merged boot-safety card. */
internal data class BootDetailLine(
    val text: String,
    val danger: Boolean = false,
    /** True for one item of a semicolon-separated detail list. */
    val bullet: Boolean = false,
    /** Section caption such as "关机详情"; rendered as a coloured header. */
    val header: Boolean = false,
    val highlight: String? = null,
    val tone: BootLineTone = if (danger) BootLineTone.ERROR else BootLineTone.NEUTRAL
)

/** Status word of one detail segment: the first segment is the result itself. */
private fun detailSegmentStatus(segment: String, index: Int): String =
    if (index == 0) {
        segment
    } else {
        segment.substringAfter("：", segment).substringBefore("，")
    }

private fun detailSegmentTone(segment: String, index: Int): BootLineTone {
    val status = detailSegmentStatus(segment, index)
    return when {
        status.contains("失败") -> BootLineTone.ERROR
        status.contains("成功") -> BootLineTone.PRIMARY
        else -> BootLineTone.NEUTRAL
    }
}

private fun detailSegmentHighlight(segment: String, index: Int): String? {
    val status = detailSegmentStatus(segment, index)
    return when {
        // The first segment is the overall result ("安全解冻成功"), so the
        // whole phrase is highlighted, not just its last two characters.
        index == 0 && (status.contains("失败") || status.contains("成功")) ->
            segment
        status.contains("失败") -> "失败"
        status.contains("成功") -> "成功"
        else -> null
    }
}

/** Separate the run counts so "成功，尝试 0，成功 0" does not read as one blob. */
private fun detailDisplayText(text: String): String = text
    .replace("，尝试", " · 尝试")
    .replace("，成功", " · 成功")
    .replace("，失败", " · 失败")

/**
 * A detail string with semicolons becomes a header plus one bullet per
 * segment. This keeps the shutdown result ("安全解冻成功；组件：…；隐藏：…")
 * from turning into a dense paragraph.
 */
private fun addBootDetailLines(
    out: MutableList<BootDetailLine>,
    label: String,
    detail: String
) {
    val clean = detail.trim()
    if (clean.isEmpty()) return
    val parts = clean.split("；", ";").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size <= 1) {
        out.add(BootDetailLine("$label：${detailDisplayText(clean)}"))
        return
    }
    out.add(BootDetailLine(label, header = true))
    parts.forEachIndexed { index, part ->
        out.add(
            BootDetailLine(
                text = detailDisplayText(part),
                bullet = true,
                highlight = detailSegmentHighlight(part, index),
                tone = detailSegmentTone(part, index)
            )
        )
    }
}

/**
 * Fields the four summary rows do not already carry: boot count, shutdown and
 * boot details, AI target state, last boot-freeze result and its detail.
 * Shutdown / boot / plan / guard values stay in [bootSummaryLines] only, so
 * the two sections never repeat each other.
 */
internal fun bootDetailLines(boot: BootSafetyState): List<BootDetailLine> = buildList {
    add(BootDetailLine("开机次数：" + if (boot.bootCount >= 0) boot.bootCount.toString() else "未知"))
    boot.lastShutdownDetail?.takeIf { it.isNotBlank() }?.let { detail ->
        addBootDetailLines(this, "关机详情", detail)
    }
    boot.lastBootDetail?.takeIf { detail ->
        detail.isNotBlank() &&
            // The skip path writes the same fact into result and detail;
            // keep the summary's result and drop the duplicate detail.
            !(
                boot.lastBootResult?.contains("跳过") == true &&
                    detail.contains("跳过")
                )
    }?.let { detail ->
        addBootDetailLines(this, "开机详情", detail)
    }
    add(
        BootDetailLine(
            text = "AI 应用：${if (boot.aiTargetsEnabledAny) "有启用项" else "无启用项"}",
            highlight = if (boot.aiTargetsEnabledAny) "有启用项" else null,
            tone = if (boot.aiTargetsEnabledAny) {
                BootLineTone.WARNING
            } else {
                BootLineTone.NEUTRAL
            }
        )
    )
    add(
        BootDetailLine(
            text = "上次开机冻结结果：${boot.describeLastBootFreeze()}",
            danger = boot.readOk &&
                boot.lastBootFreezeResult?.contains("失败") == true,
            highlight = bootResultKeyword(boot.lastBootFreezeResult),
            tone = when {
                boot.readOk && boot.lastBootFreezeResult?.contains("失败") == true ->
                    BootLineTone.ERROR
                boot.readOk &&
                    boot.lastBootFreezeResult?.contains("成功") == true ->
                    BootLineTone.PRIMARY
                else -> BootLineTone.NEUTRAL
            }
        )
    )
    boot.lastBootFreezeDetail?.takeIf { it.isNotBlank() }?.let { detail ->
        addBootDetailLines(this, "冻结详情", detail)
    }
}

/** Base text with only the conclusion word in its semantic colour. */
@Composable
private fun bootEmphasisText(
    text: String,
    highlight: String?,
    tone: BootLineTone
): androidx.compose.ui.text.AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    // Text-only emphasis: no filled/highlight background. The status word
    // itself uses the theme primary (readable on the card); errors stay red.
    val emphasis = when (tone) {
        BootLineTone.PRIMARY, BootLineTone.WARNING -> scheme.primary
        BootLineTone.ERROR -> scheme.error
        BootLineTone.NEUTRAL -> scheme.onSurfaceVariant
    }
    return remember(text, highlight, tone, emphasis) {
        buildAnnotatedString {
            append(text)
            val mark = highlight
            val start = mark?.let(text::indexOf) ?: -1
            when {
                start >= 0 && mark != null -> addStyle(
                    SpanStyle(
                        color = emphasis,
                        fontWeight = FontWeight.Medium
                    ),
                    start,
                    start + mark.length
                )
                tone != BootLineTone.NEUTRAL -> addStyle(
                    SpanStyle(color = emphasis),
                    0,
                    text.length
                )
            }
        }
    }
}

@Composable
private fun BootSummaryRow(
    label: String,
    value: String,
    tone: BootLineTone,
    highlight: String?,
    valueMaxLines: Int = 1
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        // Two-column table behaviour: the label and the first value line
        // share the top edge; a wrapped value continues in its own column.
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            // Flush-left row header; the fixed content gap follows it, so a
            // shorter label (守卫) simply ends sooner instead of being
            // indented to a common right edge.
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = bootEmphasisText(value, highlight, tone),
            modifier = Modifier
                .weight(1f)
                .padding(start = TableContentGap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BootDetailRow(line: BootDetailLine) {
    val scheme = MaterialTheme.colorScheme
    if (line.header) {
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = scheme.primary
        )
        return
    }
    val text = bootEmphasisText(line.text, line.highlight, line.tone)
    if (line.bullet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                "·",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface
            )
        }
    } else {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface
        )
    }
}

/**
 * One compact card for the 启动安全 category. The four summary rows appear
 * once at the top; below the divider only fields the summary does not carry
 * are listed. This replaces the old summary + detail card pair that repeated
 * the last shutdown / last boot values.
 */
@Composable
private fun BootSafetyCard(snapshot: DetectionSnapshot) {
    val boot = snapshot.bootSafety
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (!boot.readOk) {
                // Every field below would be a default value (-1, 尚未执行,
                // 无启用项). Showing them as facts is worse than one error.
                Text(
                    "读取失败：启动/安全状态不可用，请刷新后重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                bootSummaryLines(boot).forEach { line ->
                    BootSummaryRow(
                        label = line.label,
                        value = line.value,
                        tone = line.tone,
                        highlight = line.highlight,
                        valueMaxLines = if (
                            line.label == "上次关机" ||
                            line.label == "上次开机"
                        ) {
                            // Timestamps at the end must not be ellipsized.
                            2
                        } else {
                            1
                        }
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 3.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                bootDetailLines(boot).forEach { line ->
                    // Shutdown/freeze details are the only place the full
                    // reason lives; do not ellipsize them.
                    BootDetailRow(line)
                }
            }
        }
    }
}

private fun detectionRiskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.GREEN -> "低"
    RiskLevel.YELLOW -> "中"
    RiskLevel.RED -> "高"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
