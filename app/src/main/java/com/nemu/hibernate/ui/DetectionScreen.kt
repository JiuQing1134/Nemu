package com.nemu.hibernate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.detection.CloudState
import com.nemu.hibernate.detection.CotaReadStatus
import com.nemu.hibernate.detection.CotaState
import com.nemu.hibernate.detection.DetectionSnapshot
import com.nemu.hibernate.detection.FreezeMode
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
@Composable
fun DetectionScreen(
    state: DetectionScreenUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    var selectedCategory by rememberSaveable { mutableStateOf(DetectionCategory.FREEZE.name) }
    val category = DetectionCategory.entries
        .firstOrNull { it.name == selectedCategory }
        ?: DetectionCategory.FREEZE
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { DetectionHeader(state = state, onRefresh = onRefresh) }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DetectionCategory.entries.forEach { option ->
                    val count = when (option) {
                        DetectionCategory.FREEZE -> snapshot?.freezeStates?.size ?: 0
                        DetectionCategory.TELEMETRY -> snapshot?.cloudStates?.size ?: 0
                        DetectionCategory.SYSTEM ->
                            (snapshot?.thermal?.services?.size ?: 0) + 1
                        DetectionCategory.STARTUP -> 1
                    }
                    FilterChip(
                        selected = category == option,
                        onClick = { selectedCategory = option.name },
                        label = { Text("${option.label}  $count") }
                    )
                }
            }
        }

        if (snapshot == null) {
            item { DetectionEmptyCard(state = state) }
        } else {
            when (category) {
                DetectionCategory.FREEZE -> {
                    item {
                        SectionTitle("冻结状态 · 策略和实际分开看")
                    }
                    item {
                        DetectionInfoCard(
                            "对比“策略”和“实际”状态；不一致标红，本页只读。\n\n" +
                                "暂停 = 不能启动；停用 = 整包禁用；隐藏 = 像卸载；" +
                                "组件 = 只停用后台部件。"
                        )
                    }
                    if (snapshot.freezeStates.isEmpty()) {
                        item { DetectionInfoCard("没有冻结目标数据。") }
                    }
                    items(
                        items = snapshot.freezeStates,
                        key = { "${it.source}:${it.packageName}" }
                    ) { freeze -> FreezeStateCard(freeze) }
                }
                DetectionCategory.TELEMETRY -> {
                    item {
                        SectionTitle("云控 / 遥测 · 只读")
                    }
                    item {
                        DetectionInfoCard(
                            "云控/遥测是厂商用来下发配置、收集诊断数据的通道。" +
                                "冻结它们通常不会影响打电话、拍照等日常功能，但可能减少问题诊断信息。\n\n" +
                                "这里显示策略、PackageManager 暂停状态和实际进程；刷新不会修改系统。"
                        )
                    }
                    items(
                        items = snapshot.cloudStates,
                        key = { it.packageName }
                    ) { cloud -> CloudStateCard(cloud) }
                }
                DetectionCategory.SYSTEM -> {
                    item { SectionTitle("系统服务") }
                    item {
                        DetectionInfoCard(
                            "温控/功耗服务负责调频、发热和充电策略。它们直接关系手机稳定，" +
                                "所以 Nemu 默认不会冻结它们：\n" +
                                "• PowerGenie：系统保护，命令会被拒绝，只观察；\n" +
                                "• iAware：只允许停用两个云配置组件，主调度继续运行；\n" +
                                "• HnAps / MSDP：默认只观察，页面会说明风险。\n\n" +
                                "COTA 参数是厂商下发的配置文件，这里只读展示，不修改。"
                        )
                    }
                    item { ThermalCard(snapshot) }
                    item { CotaCard(snapshot.cota) }
                }
                DetectionCategory.STARTUP -> {
                    item { SectionTitle("启动 / 安全") }
                    item {
                        DetectionInfoCard(
                            "Nemu 在正常关机/重启前会尝试解冻，避免重启后卡住。" +
                                "强制断电、崩溃或 adb reboot 来不及执行，所以下一次开机会由守卫再清理一次。\n\n" +
                                "如果连续多次启动异常，守卫会进入救援模式并停止自动冻结。"
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

private enum class DetectionCategory(val label: String) {
    FREEZE("冻结"),
    TELEMETRY("云控遥测"),
    SYSTEM("系统"),
    STARTUP("启动安全")
}

@Composable
private fun DetectionHeader(
    state: DetectionScreenUiState,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("设备检测", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "只读快照 · 不执行系统操作",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onRefresh, enabled = !state.loading) {
                    Text(if (state.loading) "读取中…" else "刷新")
                }
            }

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
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
                Text(snapshot.describeSummary(), style = MaterialTheme.typography.bodySmall)
                if (snapshot.generatedAtMillis > 0) {
                    Text(
                        "更新于 ${formatDetectionTimestamp(snapshot.generatedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectionEmptyCard(state: DetectionScreenUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun StatusChip(text: String, warning: Boolean = false) {
    val background = if (warning) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = if (warning) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun FreezeStateCard(freeze: FreezeState) {
    var expanded by rememberSaveable(freeze.packageName) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                StatusChip(
                    text = freeze.statusLabel(),
                    warning = freeze.mismatch() || freeze.partiallyApplied()
                )
            }
            Text(
                when (freeze.mode) {
                    FreezeMode.COMPONENT ->
                        "策略 ${freeze.describePolicy()}  ·  组件 " +
                            when (freeze.componentDisabled) {
                                true -> "已停用"
                                false -> "启用"
                                null -> "未知"
                            } +
                            "  ·  进程 ${processPresenceLabel(freeze.processRunning)}"
                    FreezeMode.HIDE ->
                        "策略 ${freeze.describePolicy()}  ·  隐藏 " +
                            if (freeze.installed) "未生效" else "已生效" +
                            "  ·  进程 ${processPresenceLabel(freeze.processRunning)}"
                    else ->
                        "策略 ${freeze.describePolicy()}  ·  暂停 " +
                            "${if (freeze.suspended) "是" else "否"}" +
                            "  ·  进程 ${processPresenceLabel(freeze.processRunning)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        }
    }
}

@Composable
private fun CloudStateCard(cloud: CloudState) {
    var expanded by rememberSaveable(cloud.packageName) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(cloud.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        cloud.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                StatusChip(
                    text = cloud.statusLabel(),
                    warning = cloud.mismatch()
                )
            }
            Text(
                "策略 ${cloud.describePolicy()}  ·  挂起 ${if (cloud.suspended) "是" else "否"}" +
                    "  ·  进程 ${processPresenceLabel(cloud.processRunning)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Text(
                    "操作：${cloudActionLabel(cloud.action)} · 实际：${cloud.describeActual()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "系统“已停止”标记：${if (cloud.stopped) "已设置" else "未设置"}",
                    style = MaterialTheme.typography.bodySmall
                )
                cloud.note?.let { note -> Text(note, style = MaterialTheme.typography.bodySmall) }
            }
            if (expanded && cloud.mismatch()) {
                Text(
                    "策略与实际状态不一致；本页只展示，不会自动修复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("温控基础服务", style = MaterialTheme.typography.titleSmall)
            Text(
                "PowerGenie 负责温控/充电，被系统保护，Nemu 不会也不能冻结它；" +
                    "iAware 只允许停用云配置组件；HnAps/MSDP 默认只观察。" +
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
    Card(modifier = Modifier.fillMaxWidth()) {
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
            Text(
                "开机自动冻结：" +
                    (if (boot.bootFreezeEnabled) "已开启" else "已关闭") +
                    "（延迟 ${boot.bootFreezeDelaySeconds}s）",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "AI 深度冻结：" + if (boot.aiDeepFreezeEnabled) "已开启" else "已关闭",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "上次冻结（自动/手动）：${boot.describeLastBootFreeze()}",
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
            val guardTripped = boot.bootloopGuardTripped() || boot.skippedThisBoot()
            Text(
                boot.describeGuard(),
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

private fun detectionRiskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.GREEN -> "低"
    RiskLevel.YELLOW -> "中"
    RiskLevel.RED -> "高"
}

private fun cloudActionLabel(action: CloudAction): String = when (action) {
    CloudAction.DISABLE_USER -> "停用（可逆）"
    CloudAction.SUSPEND -> "暂停 + 强制停止"
    CloudAction.OBSERVE_ONLY -> "仅观察"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
