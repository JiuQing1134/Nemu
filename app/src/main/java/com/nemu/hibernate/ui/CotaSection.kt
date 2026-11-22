package com.nemu.hibernate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaFile
import java.util.Locale

data class CotaUiState(
    val moduleEnabled: Boolean = false,
    val rootAvailable: Boolean = false,
    val busy: Boolean = false,
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

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "温控 / COTA 参数（仅 Root）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "读取 /data/cota/para 下的厂商云控参数。此模块只使用 Root；" +
                    "Shizuku 是 shell 权限，无法访问该目录，也不会被使用。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "风险：删除温控/功耗参数可能改变降频与充电策略，导致发热、" +
                    "充电变慢或系统不稳定；不保证提升性能；系统可能重新下发参数；" +
                    "错误文件或没有备份可能导致无法恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.moduleEnabled,
                    onCheckedChange = callbacks.onEnabledChange,
                    enabled = !state.busy
                )
                Spacer(Modifier.width(8.dp))
                Text("启用温控/COTA 模块（默认关闭）")
            }
            if (!state.rootAvailable) {
                Text(
                    "当前没有 Root，模块不可用。请先在 KSU 管理器中授权 Nemu。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.message != null) {
                Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
            // Stacked on purpose: three labelled buttons overflow at phone
            // width (~360 dp card content), wrapping "删除选中（危险）".
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = callbacks.onRefresh,
                    enabled = state.moduleEnabled && state.rootAvailable && !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("刷新文件/备份") }
                Button(
                    onClick = callbacks.onBackup,
                    enabled = state.moduleEnabled && state.rootAvailable &&
                        !state.busy && state.selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("备份选中") }
                OutlinedButton(
                    onClick = {
                        riskChecked = false
                        showDeleteDialog = true
                    },
                    enabled = state.moduleEnabled && state.rootAvailable &&
                        !state.busy && state.selected.isNotEmpty() &&
                        state.lastBackupId != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除选中（危险）") }
            }
            if (state.lastBackupId != null) {
                Text(
                    "删除将使用备份：${state.lastBackupId}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.files.isEmpty()) {
                Text("尚未读取文件。", style = MaterialTheme.typography.bodySmall)
            }
            state.files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = file.name in state.selected,
                        onCheckedChange = {
                            callbacks.onToggleSelected(file.name, it)
                        },
                        enabled = state.moduleEnabled && state.rootAvailable && !state.busy
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${file.name}（${file.kind.label}）",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            file.kind.description,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "大小：${formatSize(file.sizeBytes)}  " +
                                "mode=${file.mode} ${file.uid}:${file.gid}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            file.sha256,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (state.backups.isNotEmpty()) {
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
                        OutlinedButton(
                            onClick = { restoreId = backup.id },
                            enabled = state.moduleEnabled && state.rootAvailable &&
                                !state.busy
                        ) { Text("恢复") }
                    }
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
                    state.selected.sorted().forEach { name ->
                        Text(name, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        "已备份到：${state.lastBackupId}。删除后请观察发热、充电和" +
                            "系统稳定性；系统可能重新下发参数。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = riskChecked,
                            onCheckedChange = { riskChecked = it }
                        )
                        Text(
                            "我已了解风险，并确认备份可用",
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
                Text(
                    "将用备份 ${restoreId} 覆盖 /data/cota/para 中的同名文件；" +
                        "当前内容会被替换。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = restoreId
                        restoreId = null
                        if (id != null) callbacks.onRestore(id)
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreId = null }) { Text("取消") }
            }
        )
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
