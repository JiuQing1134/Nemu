package com.nemu.hibernate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaFileGroups
import java.util.Locale

object CotaCopy {
    const val TITLE = "温控 / COTA（仅 Root）"
    const val PURPOSE =
        "用途：读取、备份或删除 /data/cota/para（含子目录）中的荣耀温控、充电等云控参数；" +
            "只清理云控文件，不修改内核温控。删除后系统可能重新生成或下发默认配置，" +
            "不保证提升性能，可用于排查更新后的降频、发热或充电异常。"
    const val RISK =
        "风险：删除会真实移除云控温控/充电参数，可能导致过热、充电变慢或系统不稳定；" +
            "删除前先备份。"
    const val OFF_HINT = "默认关闭；开启后可查看、备份或恢复。"
}

data class CotaUiState(
    val moduleEnabled: Boolean = false,
    val rootAvailable: Boolean = false,
    val busy: Boolean = false,
    val dryRun: Boolean = false,
    val loaded: Boolean = false,
    val files: List<CotaFile> = emptyList(),
    val selected: Set<String> = emptySet(),
    val backups: List<CotaBackupSummary> = emptyList(),
    val lastBackupId: String? = null,
    val message: String? = null
)

data class CotaCallbacks(
    val onEnabledChange: (Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onToggleSelected: (String, Boolean) -> Unit,
    val onBackup: () -> Unit,
    val onDeleteConfirmed: () -> Unit,
    val onRestore: (String) -> Unit
)

@Composable
fun CotaSection(
    state: CotaUiState,
    callbacks: CotaCallbacks
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var riskChecked by remember { mutableStateOf(false) }
    var restoreId by remember { mutableStateOf<String?>(null) }
    var restoreRiskChecked by remember { mutableStateOf(false) }
    var autoLoaded by remember { mutableStateOf(false) }

    // Opening Advanced with the module already enabled should show the real
    // file list instead of a stale "尚未读取文件" until the user finds 刷新.
    // The latch matters: without it a failed read flips busy back to false and
    // the effect retries forever.
    LaunchedEffect(state.moduleEnabled, state.rootAvailable, state.busy) {
        if (!autoLoaded && state.moduleEnabled && state.rootAvailable &&
            !state.loaded && !state.busy
        ) {
            autoLoaded = true
            callbacks.onRefresh()
        }
    }

    SectionCard(title = CotaCopy.TITLE) {
            Text(
                CotaCopy.PURPOSE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                CotaCopy.RISK,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("启用模块", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.moduleEnabled) {
                            "已启用；不会自动删除任何文件。"
                        } else {
                            CotaCopy.OFF_HINT
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NemuSwitch(
                    checked = state.moduleEnabled,
                    onCheckedChange = callbacks.onEnabledChange,
                    enabled = !state.busy,
                    modifier = Modifier.semantics {
                        contentDescription = "启用温控/COTA 模块"
                    }
                )
            }
            if (state.moduleEnabled && !state.rootAvailable) {
                Text(
                    "当前没有 Root，模块不可用。请先在 KSU 管理器中授权 Nemu。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.message != null) {
                Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
            if (state.moduleEnabled && state.rootAvailable) {
                // Stacked on purpose: three labelled buttons overflow at phone
                // width (~360 dp card content), wrapping "删除选中（危险）".
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NemuOutlinedButton(
                        onClick = callbacks.onRefresh,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("刷新列表") }
                    Button(
                        onClick = callbacks.onBackup,
                        enabled = !state.busy && state.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("备份选中") }
                    NemuOutlinedButton(
                        onClick = {
                            riskChecked = false
                            showDeleteDialog = true
                        },
                        enabled = !state.busy && !state.dryRun &&
                            state.selected.isNotEmpty() &&
                            state.lastBackupId != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("删除选中（危险）") }
                }
                if (state.dryRun) {
                    Text(
                        "预览模式：可以读取和备份；删除/恢复不会执行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.selected.isNotEmpty() && state.lastBackupId == null) {
                    Text(
                        "删除前需先备份选中文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.moduleEnabled && state.lastBackupId != null) {
                Text(
                    "删除前会用最新备份 ${state.lastBackupId} 校验；" +
                        "不在备份中的文件会被拒绝。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.moduleEnabled && !state.loaded) {
                Text(
                    if (state.busy) "正在读取…" else "尚未读取文件。",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (state.moduleEnabled && state.files.isEmpty()) {
                Text("没有可处理的文件。", style = MaterialTheme.typography.bodySmall)
            }
            if (state.moduleEnabled && state.files.isNotEmpty()) {
                val groups = remember(state.files) {
                    CotaFileGroups.group(state.files)
                }
                var expandedGroups by rememberSaveable {
                    mutableStateOf<Map<String, Boolean>>(emptyMap())
                }
                Text(
                    "共 ${state.files.size} 个文件，已选 ${state.selected.size} 项，" +
                        "按子系统分组。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                groups.forEach { group ->
                    val isExpanded = expandedGroups[group.key]
                        ?: (group.key == CotaFileGroups.THERMAL_KEY)
                    val selectedCount =
                        group.files.count { it.name in state.selected }
                    val allSelected = selectedCount == group.files.size
                    val partialSelected =
                        selectedCount in 1 until group.files.size
                    val toggleGroup: (Boolean) -> Unit = { checked ->
                        group.files.forEach { file ->
                            if ((file.name in state.selected) != checked) {
                                callbacks.onToggleSelected(file.name, checked)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = state.rootAvailable && !state.busy
                            ) { toggleGroup(!allSelected) },
                        verticalAlignment = Alignment.Top
                    ) {
                        NemuSmallCheckbox(
                            checked = allSelected,
                            enabled = state.rootAvailable && !state.busy,
                            onCheckedChange = toggleGroup,
                            contentDescription =
                                if (allSelected) {
                                    "取消选择 ${group.title}"
                                } else {
                                    "选择 ${group.title} 全部文件"
                                },
                            indeterminate = partialSelected
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    expandedGroups =
                                        expandedGroups + (group.key to !isExpanded)
                                }
                        ) {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${group.files.size} 项 · " +
                                    formatSize(group.totalSizeBytes) +
                                    if (selectedCount > 0) " · 已选 $selectedCount" else "",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            if (isExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                expandedGroups =
                                    expandedGroups + (group.key to !isExpanded)
                            }
                        )
                    }
                    if (isExpanded) {
                        group.files.forEach { file ->
                            CotaFileRow(
                                file = file,
                                displayName = file.name,
                                selected = file.name in state.selected,
                                enabled =
                                    state.rootAvailable && !state.busy,
                                onToggle = {
                                    callbacks.onToggleSelected(file.name, it)
                                }
                            )
                        }
                    }
                }
            }
            if (state.moduleEnabled && state.backups.isNotEmpty()) {
                Text("备份", style = MaterialTheme.typography.titleSmall)
                state.backups.forEach { backup ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(backup.id)
                            Text(
                                backup.dir,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        NemuOutlinedButton(
                            onClick = {
                                restoreRiskChecked = false
                                restoreId = backup.id
                            },
                            enabled = state.moduleEnabled && state.rootAvailable &&
                                !state.busy && !state.dryRun
                        ) { Text("恢复") }
                    }
                }
            }
        }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除 COTA 参数") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将删除以下文件：")
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        state.selected.sorted().forEach { name ->
                            Text(name, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(
                        "上次备份：${state.lastBackupId}。只有当前选择全部在该备份中" +
                            "时才会删除，否则会拒绝；删除后请观察发热、充电和系统稳定性。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            riskChecked = !riskChecked
                        }
                    ) {
                        NemuSmallCheckbox(
                            checked = riskChecked,
                            enabled = true,
                            onCheckedChange = { riskChecked = it },
                            contentDescription = "我已了解风险"
                        )
                        Text(
                            "我已了解风险",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        callbacks.onDeleteConfirmed()
                    },
                    enabled = riskChecked
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (restoreId != null) {
        AlertDialog(
            onDismissRequest = { restoreId = null },
            title = { Text("确认恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将用备份 ${restoreId} 恢复该备份中的全部文件，并重建当前" +
                            "不存在的文件；现有相同路径的内容会被替换。"
                    )
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.clickable {
                            restoreRiskChecked = !restoreRiskChecked
                        }
                    ) {
                        NemuSmallCheckbox(
                            checked = restoreRiskChecked,
                            enabled = true,
                            onCheckedChange = { restoreRiskChecked = it },
                            contentDescription = "我已了解恢复会覆盖当前内容"
                        )
                        Text(
                            "我已了解会覆盖当前内容",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = restoreId
                        restoreId = null
                        if (id != null) callbacks.onRestore(id)
                    },
                    enabled = restoreRiskChecked
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreId = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CotaFileRow(
    file: CotaFile,
    displayName: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!selected) },
        verticalAlignment = Alignment.Top
    ) {
        NemuSmallCheckbox(
            checked = selected,
            enabled = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.offset(y = (-2).dp),
            contentDescription =
                if (selected) "取消选择 $displayName" else "选择 $displayName"
        )
        Column(Modifier.weight(1f)) {
            Text(
                "$displayName（${file.kind.label}）",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                file.kind.description,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                )
            )
            Text(
                "大小：${formatSize(file.sizeBytes)}  " +
                    "mode=${file.mode} ${file.uid}:${file.gid}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                )
            )
            Text(
                file.sha256,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp
                ),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 ->
        String.format(Locale.US, "%.2f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 ->
        String.format(Locale.US, "%.2f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 ->
        String.format(Locale.US, "%.2f KiB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
