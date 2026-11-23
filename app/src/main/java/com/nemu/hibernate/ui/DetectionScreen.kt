package com.nemu.hibernate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.ui.theme.flClashCard
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.detection.CotaReadStatus
import com.nemu.hibernate.detection.CotaState
import com.nemu.hibernate.detection.DetectionSnapshot
import com.nemu.hibernate.detection.FreezeSource
import com.nemu.hibernate.detection.FreezeState
import com.nemu.hibernate.detection.ThermalServiceState
import com.nemu.hibernate.detection.formatDetectionTimestamp
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
@OptIn(ExperimentalLayoutApi::class)
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
    val listState = rememberLazyListState()
    LaunchedEffect(category) {
        listState.scrollToItem(0)
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
                    val freezeRows = snapshot.freezeStates.filter {
                        it.source != FreezeSource.CLOUD
                    }
                    if (freezeRows.isEmpty()) {
                        item { DetectionInfoCard("没有冻结目标数据。") }
                    }
                    items(
                        items = freezeRows,
                        key = { "${it.source}:${it.packageName}" }
                    ) { freeze -> FreezeStateCard(freeze, onOpenTarget) }
                }
                DetectionCategory.TELEMETRY -> {
                    val cloudRows = snapshot.freezeStates.filter {
                        it.source == FreezeSource.CLOUD
                    }
                    if (cloudRows.isEmpty()) {
                        item { DetectionInfoCard("没有云控/遥测目标数据。") }
                    }
                    items(
                        items = cloudRows,
                        key = { "${it.source}:${it.packageName}" }
                    ) { freeze -> FreezeStateCard(freeze, onOpenTarget) }
                }
                DetectionCategory.SYSTEM -> {
                    item { ThermalCard(snapshot) }
                    item { CotaCard(snapshot.cota) }
                }
                DetectionCategory.STARTUP -> {
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

private enum class DetectionCategory(val label: String) {
    FREEZE("冻结"),
    TELEMETRY("云控遥测"),
    SYSTEM("系统"),
    STARTUP("启动安全")
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "设备检测",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 20.sp
                    )
                )
                Text(
                    "只读快照 · 不执行系统操作",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    .clickable(enabled = !state.loading) { onRefresh() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
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
            if (snapshot.generatedAtMillis > 0) {
                Text(
                    "更新于 ${formatDetectionTimestamp(snapshot.generatedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DetectionCategory.entries.forEach { option ->
                NemuFilterChip(
                    selected = category == option,
                    onClick = { onCategoryChange(option) },
                    label = {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        state.snapshot?.let { snapshot ->
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant
            )
            when (category) {
                DetectionCategory.FREEZE -> {
                    val rows = snapshot.freezeStates.filter {
                        it.source != FreezeSource.CLOUD
                    }
                    val mismatched = rows.count { it.mismatch() }
                    SectionHeader(
                        title = "冻结状态",
                        subtitle = "注：策略与实际分开显示；点行展开。",
                        subtitleMaxLines = 1,
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                DetectionStatusChip("${rows.size} 项")
                                if (mismatched > 0) {
                                    DetectionStatusChip(
                                        "不一致 $mismatched",
                                        warning = true
                                    )
                                }
                            }
                        }
                    )
                }
                DetectionCategory.TELEMETRY -> {
                    val rows = snapshot.freezeStates.count {
                        it.source == FreezeSource.CLOUD
                    }
                    SectionHeader(
                        title = "云控 / 遥测",
                        subtitle = "厂商配置/诊断通道，显示应用页保存的策略与实际状态。",
                        trailing = { DetectionStatusChip("$rows 项") }
                    )
                }
                DetectionCategory.SYSTEM -> SectionHeader(
                    title = "系统服务",
                    subtitle = "温控/功耗默认观察；整包策略已开放，部分 PowerGenie 命令被平台拒绝。"
                )
                DetectionCategory.STARTUP -> SectionHeader(
                    title = "启动 / 安全",
                    subtitle = "关机前解冻；异常开机由守卫清理。"
                )
            }
        }
    }
    }
}

@Composable
private fun detectionCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.flClashCard()
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
    val background = if (warning) {
        // Vivid FlClash red; the muted #D32F2F read as dark brown-red.
        Color(0xFFFF383C)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val foreground = if (warning) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Match DetectionHeader's 12dp inset so the count chip and
                // every row's status chip share the same right edge.
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .semantics(mergeDescendants = true) {
                        // Include the status in the clickable row's label;
                        // otherwise TalkBack announces only "expand" and the
                        // mismatch/partial state stays invisible.
                        contentDescription = buildString {
                            append(freeze.label)
                            append("，")
                            append(freeze.statusLabel())
                            append("，")
                            append(if (expanded) "收起" else "展开")
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(freeze.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        freeze.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                DetectionStatusChip(
                    text = freeze.statusLabel(),
                    warning = freeze.mismatch() || freeze.partiallyApplied()
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compact key/value pairs instead of one long "A · B" line:
                // the separator dot made the row feel split apart. Labels are
                // muted, values use the normal ink so each pair reads as one
                // unit; the pair gap is fixed and much smaller than the old
                // double-spaced "·".
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StatusToken("策略", freeze.describePolicy())
                    freeze.detailLabel()?.let { label ->
                        StatusToken(label, freeze.detailValue())
                    }
                }
                // Process presence sits on the right and stays terse:
                // 存活 / 无进程 / 未知.
                Text(
                    processPresenceLabel(freeze.processRunning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (expanded) {
                Text("包状态：${freeze.describeActual()}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "来源：${when (freeze.source) {
                        FreezeSource.CORE -> "核心目标"
                        FreezeSource.AI -> "AI 目标"
                        FreezeSource.CLOUD -> "云控目标"
                        FreezeSource.CATALOG -> "统一目录目标"
                    }} · 系统“已停止”标记：${if (freeze.stopped) "已设置" else "未设置"}",
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
                    "系统显示已暂停，但进程列表仍有该应用进程。强制停止是一次性请求；" +
                        "系统绑定服务可能再次启动它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (expanded) {
                TextButton(onClick = { onOpenTarget(freeze.packageName) }) {
                    Text("在应用页查看 ›")
                }
            }
        }
    }
}

@Composable
private fun StatusToken(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun processPresenceLabel(running: Boolean?): String = when (running) {
    true -> "存活"
    false -> "无进程"
    null -> "未知"
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
                    "被平台拒绝；iAware 建议只停用云配置组件；HnAps/MSDP 默认观察。" +
                    "这里的数字表示实际进程状态，不是“已冻结”。",
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
            "${service.packageName}：${service.describe()}",
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
            Text("COTA 厂商云控参数（仅 Root，只读）", style = MaterialTheme.typography.titleSmall)
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
private fun CotaDirectoryRow(directory: com.nemu.hibernate.cota.CotaDirectoryInventory) {
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "${formatBytes(file.sizeBytes)} · ${file.kind.label} · " +
                "用户=${file.uid} 组=${file.gid} 权限=${file.mode}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "SHA-256 ${file.sha256}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BootSafetyCard(snapshot: DetectionSnapshot) {
    val boot = snapshot.bootSafety
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = detectionCardColors(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("启动 / 关机安全", style = MaterialTheme.typography.titleSmall)
            if (!boot.readOk) {
                Text(
                    "读取失败：以下状态可能不是实际值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                "开机次数（Boot count）：${boot.bootCount}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "上次关机安全解冻：${boot.describeLastShutdown()}",
                style = MaterialTheme.typography.bodySmall
            )
            boot.lastShutdownDetail?.let { detail ->
                Text(
                    "关机详情：$detail",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "上次开机安全解冻：${boot.describeLastBoot()}",
                style = MaterialTheme.typography.bodySmall
            )
            boot.lastBootDetail?.let { detail ->
                Text(
                    "开机详情：$detail",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("开机自动冻结", style = MaterialTheme.typography.bodySmall)
                StatusChip(
                    text = if (boot.bootFreezeEnabled) "已开启" else "已关闭",
                    tone = if (boot.bootFreezeEnabled) {
                        StatusChipTone.SUCCESS
                    } else {
                        StatusChipTone.NEUTRAL
                    }
                )
                Text(
                    if (boot.bootFreezeEnabled) {
                        "延迟 ${boot.bootFreezeDelaySeconds} 秒"
                    } else {
                        "不会执行"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("AI 目标", style = MaterialTheme.typography.bodySmall)
                StatusChip(
                    text = if (boot.aiTargetsEnabledAny) "有启用项" else "无启用项",
                    tone = if (boot.aiTargetsEnabledAny) {
                        StatusChipTone.SUCCESS
                    } else {
                        StatusChipTone.NEUTRAL
                    }
                )
            }
            Text(
                "上次结果（自动/手动/解冻）：${boot.describeLastBootFreeze()}",
                style = MaterialTheme.typography.bodySmall
            )
            boot.lastBootFreezeDetail?.let { detail ->
                Text(
                    "冻结详情：$detail",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val guardNote = boot.guardNote()
            if (guardNote != null) {
                val guardTripped =
                    boot.bootloopGuardTripped() || boot.skippedThisBoot()
                Text(
                    guardNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (guardTripped) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
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
