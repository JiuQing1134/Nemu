package com.nemu.hibernate.ui

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaFileGroups
import com.nemu.hibernate.cota.CotaPaths
import com.nemu.hibernate.cota.CotaTimestamps
import com.nemu.hibernate.cota.CotaTree
import com.nemu.hibernate.cota.CotaTreeNode
import com.nemu.hibernate.ui.theme.nemuInset
import java.util.Locale
import kotlinx.coroutines.launch

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
    const val PATH_COPIED = "路径已复制"
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
    val onRestore: (String) -> Unit,
    val onDeleteBackup: (String) -> Unit
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
    var deleteBackupId by remember { mutableStateOf<String?>(null) }
    var autoLoaded by remember { mutableStateOf(false) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copyPath: (String) -> Unit = remember(clipboard, context, scope) {
        { relative ->
            val text = CotaPaths.absolutePathOrNull(relative)
            if (text != null) {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("COTA 路径", text))
                    )
                }
                // Always acknowledge with the same short toast. The system
                // clipboard overlay is not guaranteed (and is different per
                // OEM/API level), and every long-press should confirm the
                // copy with an app-side message.
                Toast.makeText(
                    context,
                    CotaCopy.PATH_COPIED,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
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
                    "当前没有可用的 Root 权限，模块不可用。请在 KernelSU、Magisk 或 APatch 中授权 Nemu 后重试。",
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
                    NemuFilledButton(
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
                val grouped = remember(state.files) {
                    CotaFileGroups.group(state.files).map { group ->
                        group to CotaTree.build(group)
                    }
                }
                // Directories default to expanded, preserving the old flat
                // list's behavior while adding a collapsible hierarchy.
                Text(
                    "共 ${state.files.size} 项 · 已选 ${state.selected.size}；" +
                        "长按复制绝对路径",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                grouped.forEach { (group, tree) ->
                    val selectionEnabled = state.rootAvailable && !state.busy
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
                            .noRippleClickable(
                                onClickLabel = if (allSelected) {
                                    "取消选择分组"
                                } else {
                                    "选择分组全部文件"
                                },
                                onLongClickLabel = "复制分组目录路径",
                                onLongClick = { copyPath(group.key) },
                                onClick = {
                                    if (selectionEnabled) {
                                        toggleGroup(!allSelected)
                                    }
                                }
                            )
                            .clearAndSetSemantics {
                                contentDescription =
                                    "${group.title}，${group.files.size} 个文件，" +
                                        if (allSelected) {
                                            "已选择"
                                        } else if (partialSelected) {
                                            "部分选择"
                                        } else {
                                            "未选择"
                                        }
                                role = Role.Checkbox
                                toggleableState = when {
                                    partialSelected -> ToggleableState.Indeterminate
                                    allSelected -> ToggleableState.On
                                    else -> ToggleableState.Off
                                }
                                onLongClick(label = "复制分组目录路径") {
                                    copyPath(group.key)
                                    true
                                }
                                if (selectionEnabled) {
                                    onClick(label = "选择分组全部文件") {
                                        toggleGroup(!allSelected)
                                        true
                                    }
                                } else {
                                    disabled()
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        NemuSmallCheckbox(
                            checked = allSelected,
                            enabled = selectionEnabled,
                            onCheckedChange = toggleGroup,
                            contentDescription =
                                if (allSelected) {
                                    "取消选择 ${group.title}"
                                } else {
                                    "选择 ${group.title} 全部文件"
                                },
                            indeterminate = partialSelected,
                            touchSize = 22.dp,
                            glyphSize = 14.dp
                        )
                        Spacer(Modifier.width(2.dp))
                        FolderGlyph(
                            color = MaterialTheme.colorScheme.primary.copy(
                                alpha = 0.9f
                            ),
                            size = 15.dp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "${group.files.size} 项 · " +
                                    formatSize(group.totalSizeBytes) +
                                    if (selectedCount > 0) {
                                        " · 已选 $selectedCount"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Always show the tree; one paragraph-style indent under
                    // the group header makes every child visibly subordinate.
                    Column(Modifier.padding(start = 16.dp)) {
                        CotaTreeNodes(
                            nodes = tree,
                            depth = 0,
                            selected = state.selected,
                            selectionEnabled = selectionEnabled,
                            onToggleFile = { name, checked ->
                                callbacks.onToggleSelected(name, checked)
                            },
                            onCopyPath = copyPath
                        )
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
                        NemuOutlinedButton(
                            onClick = { deleteBackupId = backup.id },
                            enabled = state.moduleEnabled && state.rootAvailable &&
                                !state.busy && !state.dryRun
                        ) { Text("删除") }
                    }
                }
            }
        }

    val pendingBackupDelete = deleteBackupId
    if (pendingBackupDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteBackupId = null },
            title = { Text("删除备份 $pendingBackupDelete？") },
            text = {
                Text(
                    "只删除 Nemu 备份目录中的这份副本，" +
                        "不会修改 /data/cota/para 中的当前文件。" +
                        "删除后无法再用它恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteBackupId = null
                        callbacks.onDeleteBackup(pendingBackupDelete)
                    }
                ) { Text("删除备份") }
            },
            dismissButton = {
                TextButton(onClick = { deleteBackupId = null }) { Text("取消") }
            }
        )
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

/**
 * Recursive COTA path tree. Each nested level adds a short indent and a
 * one-pixel guide rail, so a parent directory, its child directory and the
 * files below them never share the same left edge. Directories are rendered
 * as tinted headers with a folder glyph; files keep the checkbox and show the
 * leaf name instead of the full path.
 */
@Composable
private fun CotaTreeNodes(
    nodes: List<CotaTreeNode>,
    depth: Int,
    selected: Set<String>,
    selectionEnabled: Boolean,
    onToggleFile: (String, Boolean) -> Unit,
    onCopyPath: (String) -> Unit
) {
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        nodes.forEach { node ->
            if (node.isDirectory) {
                val allSelected = node.filesUnder.isNotEmpty() &&
                    node.filesUnder.all { it.name in selected }
                val partialSelected = !allSelected &&
                    node.filesUnder.any { it.name in selected }
                CotaDirectoryRow(
                    node = node,
                    depth = depth,
                    checked = allSelected,
                    partial = partialSelected,
                    enabled = selectionEnabled && node.filesUnder.isNotEmpty(),
                    onToggle = { checked ->
                        node.filesUnder.forEach { file ->
                            if ((file.name in selected) != checked) {
                                onToggleFile(file.name, checked)
                            }
                        }
                    },
                    onCopyPath = onCopyPath
                )
                if (node.children.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .drawBehind {
                                val x = 0.5.dp.toPx()
                                drawLine(
                                    color = guideColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                            .padding(start = 6.dp)
                    ) {
                        CotaTreeNodes(
                            nodes = node.children,
                            depth = depth + 1,
                            selected = selected,
                            selectionEnabled = selectionEnabled,
                            onToggleFile = onToggleFile,
                            onCopyPath = onCopyPath
                        )
                    }
                }
            } else {
                val file = node.file ?: return@forEach
                CotaFileRow(
                    file = file,
                    displayName = node.name,
                    selected = file.name in selected,
                    selectionEnabled = selectionEnabled,
                    onToggle = { onToggleFile(file.name, it) },
                    onCopyPath = onCopyPath
                )
            }
        }
    }
}

@Composable
private fun CotaDirectoryRow(
    node: CotaTreeNode,
    depth: Int,
    checked: Boolean,
    partial: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onCopyPath: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberNemuHaptics()
    val tintAlpha = when (depth) {
        0 -> 0.50f
        1 -> 0.34f
        else -> 0.22f
    }
    val accentAlpha = when (depth) {
        0 -> 0.95f
        1 -> 0.70f
        else -> 0.50f
    }
    val accentHeight = when (depth) {
        0 -> 22.dp
        1 -> 20.dp
        else -> 18.dp
    }
    val folderSize = when (depth) {
        0 -> 15.dp
        1 -> 14.dp
        else -> 13.dp
    }
    val nameSize = when (depth) {
        0 -> 13.sp
        1 -> 12.sp
        else -> 11.sp
    }
    val nameWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Medium
    val stateWord = when {
        partial -> "部分选择"
        checked -> "已选择"
        else -> "未选择"
    }
    val toggle: () -> Unit = {
        if (enabled) {
            haptics.perform(NemuHaptic.KeyboardTap.toggleFor(!checked))
            onToggle(!checked)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.nemuInset().copy(alpha = tintAlpha))
            .noRippleClickable(
                onClickLabel = if (checked) {
                    "取消选择目录"
                } else {
                    "选择目录全部文件"
                },
                onLongClickLabel = "复制目录路径",
                onLongClick = { onCopyPath(node.path) },
                onClick = toggle
            )
            .clearAndSetSemantics {
                contentDescription =
                    "目录 ${node.path}，${node.filesUnder.size} 个文件，$stateWord"
                role = Role.Checkbox
                toggleableState = when {
                    partial -> ToggleableState.Indeterminate
                    checked -> ToggleableState.On
                    else -> ToggleableState.Off
                }
                onLongClick(label = "复制目录路径") {
                    onCopyPath(node.path)
                    true
                }
                if (enabled) {
                    onClick(label = if (checked) "取消选择目录" else "选择目录全部文件") {
                        onToggle(!checked)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .padding(start = 5.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(accentHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(scheme.primary.copy(alpha = accentAlpha))
        )
        NemuSmallCheckbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onToggle,
            contentDescription = "选择目录 ${node.name} 的全部文件",
            indeterminate = partial,
            touchSize = 20.dp,
            glyphSize = 12.dp
        )
        FolderGlyph(
            color = scheme.onSurfaceVariant.copy(
                alpha = when (depth) {
                    0 -> 0.95f
                    1 -> 0.85f
                    else -> 0.75f
                }
            ),
            size = folderSize
        )
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = nameSize,
                    fontWeight = nameWeight
                ),
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${node.filesUnder.size} 个文件 · " +
                    formatSize(node.totalSizeBytes) +
                    if (checked || partial) " · $stateWord" else "",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                ),
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.28f)
            lineTo(w * 0.42f, h * 0.28f)
            lineTo(w * 0.50f, h * 0.40f)
            lineTo(w * 0.88f, h * 0.40f)
            lineTo(w * 0.88f, h * 0.78f)
            lineTo(w * 0.12f, h * 0.78f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 1.3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun FileGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.10f)
            lineTo(w * 0.60f, h * 0.10f)
            lineTo(w * 0.78f, h * 0.28f)
            lineTo(w * 0.78f, h * 0.90f)
            lineTo(w * 0.24f, h * 0.90f)
            close()
            moveTo(w * 0.60f, h * 0.10f)
            lineTo(w * 0.60f, h * 0.28f)
            lineTo(w * 0.78f, h * 0.28f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun CotaFileRow(
    file: CotaFile,
    displayName: String,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onCopyPath: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(
                onClickLabel = if (selected) "取消选择" else "选择",
                onLongClickLabel = "复制文件路径",
                onLongClick = { onCopyPath(file.name) },
                onClick = {
                    if (selectionEnabled) onToggle(!selected)
                }
            )
            .clearAndSetSemantics {
                contentDescription =
                    "文件 ${file.name}，${file.kind.label}，" +
                        formatSize(file.sizeBytes) +
                        "，修改时间 ${CotaTimestamps.displayLabel(file.modifiedAtSeconds)}" +
                        if (selected) "，已选择" else "，未选择"
                role = Role.Checkbox
                toggleableState = if (selected) {
                    ToggleableState.On
                } else {
                    ToggleableState.Off
                }
                onLongClick(label = "复制文件路径") {
                    onCopyPath(file.name)
                    true
                }
                if (selectionEnabled) {
                    onClick(label = if (selected) "取消选择" else "选择") {
                        onToggle(!selected)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        NemuSmallCheckbox(
            checked = selected,
            enabled = selectionEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.offset(y = (-2).dp),
            contentDescription =
                if (selected) "取消选择 ${file.name}" else "选择 ${file.name}",
            touchSize = 20.dp,
            glyphSize = 12.dp
        )
        Spacer(Modifier.width(2.dp))
        FileGlyph(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "修改时间 " +
                        CotaTimestamps.displayLabel(file.modifiedAtSeconds),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Fixed-width size column anchored to the row's right edge.
                Text(
                    formatSize(file.sizeBytes),
                    modifier = Modifier.widthIn(min = 72.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
