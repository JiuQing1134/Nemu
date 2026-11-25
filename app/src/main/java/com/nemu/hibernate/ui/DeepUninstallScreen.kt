package com.nemu.hibernate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.DeepUninstallItem
import com.nemu.hibernate.ui.theme.nemuCard

private val DeepSelectionSaver = Saver<Set<String>, String>(
    save = { it.joinToString("|") },
    restore = { raw ->
        raw.split('|').filter { it.isNotEmpty() }.toSet()
    }
)

/**
 * 深度卸载分段：先备份 APK，再为用户卸载；也可以从备份重新安装。
 *
 * 安装模块 v1.20 后，卸载的应用会在开机时由模块自动恢复、系统稳定后
 * 重新卸载；未安装模块时页面顶部和关键应用确认弹窗会明确提示需要
 * 手动恢复。恢复只作用于有备份的目标；未安装但有备份的行保留在
 * 列表里，供用户重新安装。
 */
@Composable
fun DeepUninstallScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier
) {
    val ui = state.deepUninstall
    var query by rememberSaveable { mutableStateOf("") }
    // 全部 / 已卸载 filter chip: lets the user jump straight to the apps
    // that can be restored, instead of scrolling to the second group.
    var uninstalledOnly by rememberSaveable { mutableStateOf(false) }
    var showUninstalled by rememberSaveable { mutableStateOf(true) }
    var showInstalled by rememberSaveable { mutableStateOf(true) }
    var selection by rememberSaveable(stateSaver = DeepSelectionSaver) {
        mutableStateOf(emptySet<String>())
    }
    // 弹窗打开时固定目标快照，避免刷新在确认过程中改变清单。
    var pendingUninstall by remember {
        mutableStateOf<List<DeepUninstallItem>?>(null)
    }
    var pendingRestore by remember {
        mutableStateOf<List<DeepUninstallItem>?>(null)
    }
    // 只在进入页面时刷新一次；分段切走再切回即视为重新进入。
    LaunchedEffect(Unit) { callbacks.onDeepUninstallRefresh() }
    LaunchedEffect(ui.items) {
        val available = ui.items.mapTo(mutableSetOf()) { it.packageName }
        selection = selection.intersect(available)
    }

    val uninstalledCount = ui.items.count { !it.installed }
    val installedCount = ui.items.count { it.installed }
    val visible = ui.items
        .filter { !uninstalledOnly || !it.installed }
        .filter { query.isBlank() || matchesAppQuery(it.label, it.packageName, query) }
    // 未安装且没有备份的行没有任何可执行动作，只做展示。
    val selectableVisible = visible.filter { it.installed || it.backup != null }
    val selectedItems = ui.items.filter { it.packageName in selection }
    val uninstallable = selectedItems.filter { it.installed }
    val restorable = selectedItems.filter { it.backup != null }
    val busy = ui.busy

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                DeepUninstallHeader(bootRestoreReady = ui.bootRestoreReady)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(FilterChipGap)) {
                    NemuFilterChip(
                        selected = !uninstalledOnly,
                        onClick = { uninstalledOnly = false },
                        label = { Text("全部 ${ui.items.size}") }
                    )
                    // Same filter row as the other lists. Selecting it shows
                    // only installed=false targets, where 恢复所选 works.
                    NemuFilterChip(
                        selected = uninstalledOnly,
                        onClick = { uninstalledOnly = true },
                        label = { Text("已卸载 $uninstalledCount") }
                    )
                }
            }
            val notice = ui.error ?: ui.message
            if (notice != null) {
                item {
                    DeepNoticeBar(
                        text = notice,
                        isError = ui.error != null,
                        onDismiss = callbacks.onDeepUninstallMessageDismiss
                    )
                }
            }
            item {
                CatalogSearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "应用 / 包名 / 拼音",
                    busy = busy,
                    allSelected = selectableVisible.isNotEmpty() &&
                        selectableVisible.all { it.packageName in selection },
                    selectAllEnabled = selectableVisible.isNotEmpty(),
                    onSelectAll = {
                        selection = selection + selectableVisible.map {
                            it.packageName
                        }
                    },
                    onClearVisible = {
                        selection = selection - selectableVisible.mapTo(
                            mutableSetOf()
                        ) { it.packageName }
                    }
                )
            }
            when {
                ui.loading && ui.items.isEmpty() -> item {
                    SectionCard(title = "正在读取应用与备份…") {}
                }
                ui.items.isEmpty() -> item {
                    SectionCard(title = "没有可管理的应用") {
                        Text(
                            "未读取到可备份或恢复的应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                visible.isEmpty() -> item {
                    SectionCard(
                        title = if (uninstalledOnly && query.isBlank()) {
                            "没有已卸载的应用"
                        } else {
                            "没有匹配的应用"
                        }
                    ) {
                        Text(
                            when {
                                uninstalledOnly && query.isBlank() ->
                                    "当前没有需要恢复的已卸载应用。"
                                uninstalledOnly ->
                                    "没有已卸载应用的名称、包名或拼音包含“$query”。"
                                else ->
                                    "没有名称、包名或拼音包含“$query”的应用。"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (query.isNotBlank()) {
                            TextButton(onClick = { query = "" }) {
                                Text("清除搜索")
                            }
                        }
                    }
                }
                // 已卸载 filter: one flat restore list; group headers would
                // only repeat the active chip and hide rows if collapsed.
                uninstalledOnly -> {
                    items(visible, key = { it.packageName }) { row ->
                        DeepUninstallRow(
                            item = row,
                            selected = row.packageName in selection,
                            enabled = !busy,
                            onToggle = { checked ->
                                selection = if (checked) {
                                    selection + row.packageName
                                } else {
                                    selection - row.packageName
                                }
                            }
                        )
                    }
                }
                else -> {
                    item {
                        CollapsibleGroupHeader(
                            title = "未卸载",
                            count = installedCount,
                            expanded = showInstalled,
                            onToggle = { showInstalled = !showInstalled }
                        )
                    }
                    if (showInstalled) {
                        items(
                            visible.filter { it.installed },
                            key = { it.packageName }
                        ) { row ->
                            DeepUninstallRow(
                                item = row,
                                selected = row.packageName in selection,
                                enabled = !busy,
                                onToggle = { checked ->
                                    selection = if (checked) {
                                        selection + row.packageName
                                    } else {
                                        selection - row.packageName
                                    }
                                }
                            )
                        }
                    }
                    item {
                        CollapsibleGroupHeader(
                            title = "已卸载",
                            count = uninstalledCount,
                            expanded = showUninstalled,
                            onToggle = { showUninstalled = !showUninstalled }
                        )
                    }
                    if (showUninstalled) {
                        items(
                            visible.filter { !it.installed },
                            key = { it.packageName }
                        ) { row ->
                            DeepUninstallRow(
                                item = row,
                                selected = row.packageName in selection,
                                enabled = !busy,
                                onToggle = { checked ->
                                    selection = if (checked) {
                                        selection + row.packageName
                                    } else {
                                        selection - row.packageName
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        if (selection.isNotEmpty()) {
            SelectionActionBar(
                selectedCount = selection.size,
                actionLabel = "备份并卸载 ${uninstallable.size} 个",
                enabled = !busy,
                actionEnabled = !busy && uninstallable.isNotEmpty(),
                onClear = { selection = emptySet() },
                onAction = {
                    pendingUninstall = uninstallable
                },
                secondaryLabel = "恢复所选",
                // 只有选中了有备份或未安装的目标，恢复才有意义。
                secondaryEnabled = !busy && (
                    restorable.isNotEmpty() ||
                        selectedItems.any { !it.installed }
                    ),
                onSecondary = {
                    if (restorable.isNotEmpty()) {
                        pendingRestore = restorable
                    }
                }
            )
        }
    }

    pendingUninstall?.let { targets ->
        DeepUninstallConfirmDialog(
            targets = targets,
            busy = busy,
            bootRestoreReady = ui.bootRestoreReady,
            onDismiss = { pendingUninstall = null },
            onConfirm = {
                pendingUninstall = null
                callbacks.onDeepUninstallBackupAndUninstall(
                    targets.mapTo(mutableSetOf()) { it.packageName }
                )
            }
        )
    }
    pendingRestore?.let { targets ->
        DeepRestoreConfirmDialog(
            targets = targets,
            busy = busy,
            onDismiss = { pendingRestore = null },
            onConfirm = {
                pendingRestore = null
                callbacks.onDeepUninstallRestore(
                    targets.mapTo(mutableSetOf()) { it.packageName }
                )
            }
        )
    }
}

/**
 * One-line warning. The top bar already names the page; details belong in the
 * confirmation dialog, not in a permanent paragraph above every list.
 */
@Composable
private fun DeepUninstallHeader(bootRestoreReady: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (bootRestoreReady) {
                "开机自动恢复，稳定后重新卸载"
            } else {
                "开机自动恢复未接入（需模块 v1.20）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (bootRestoreReady) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            maxLines = 1
        )
        if (bootRestoreReady) {
            Text(
                "开机只恢复「已卸载」的应用；「已恢复」不会被自动重卸；备份一直保留。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/** 一条紧凑的操作提示卡，带关闭按钮。 */
@Composable
private fun DeepNoticeBar(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (isError) scheme.error else scheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        contentColor = if (isError) scheme.error else scheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}

/** 单个应用：复选框、图标、名称/包名/版本与状态 chip。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepUninstallRow(
    item: DeepUninstallItem,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val selectable = item.installed || item.backup != null
    val interaction = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .nemuPressFeedback(
                interaction,
                NemuPressSpec.Card,
                enabled && selectable
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.nemuCard()
        ),
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled && selectable,
                    interactionSource = interaction,
                    indication = null
                ) { onToggle(!selected) }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NemuSmallCheckbox(
                checked = selected,
                enabled = enabled && selectable,
                onCheckedChange = onToggle,
                contentDescription = "选择 ${item.label}",
                touchSize = 32.dp
            )
            AppIcon(packageName = item.packageName, size = 34.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (shouldShowPackageName(item.label, item.packageName)) {
                    Text(
                        item.packageName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(StatusChipGap),
                    verticalArrangement = Arrangement.spacedBy(StatusChipGap)
                ) {
                    // Same category chip as the All Apps list: 系统 is red,
                    // 用户/Root/Shizuku/LSPosed use the blue capsule.
                    StatusChip(
                        item.kindLabel,
                        tone = if (item.systemCategory) {
                            StatusChipTone.ERROR
                        } else {
                            StatusChipTone.CATEGORY
                        }
                    )
                    when {
                        item.installed && item.backup != null -> StatusChip(
                            "已恢复 · 保留备份",
                            tone = StatusChipTone.NEUTRAL
                        )
                        !item.installed && item.backup != null -> StatusChip(
                            "已卸载 · 开机恢复",
                            tone = StatusChipTone.INFO
                        )
                        !item.installed -> StatusChip(
                            "已卸载 · 无备份",
                            tone = StatusChipTone.NEUTRAL
                        )
                    }
                }
            }
        }
    }
}

/**
 * 卸载确认：列出目标；关键应用单独用错误色列出，并在有关键应用时
 * 要求勾选关键应用的知晓项后才能确认。
 */
@Composable
private fun DeepUninstallConfirmDialog(
    targets: List<DeepUninstallItem>,
    busy: Boolean,
    bootRestoreReady: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var acknowledged by remember { mutableStateOf(false) }
    val critical = targets.filter { it.critical }
    val normal = targets.filterNot { it.critical }
    val criticalNote = if (bootRestoreReady) {
        "我已知晓：关键应用会在开机时自动恢复、稳定后重新卸载；仍可能导致启动异常"
    } else {
        "我已知晓：当前版本还没有开机自动恢复，卸载关键应用可能导致无法启动"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备份并卸载所选 ${targets.size} 个？") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (critical.isNotEmpty()) {
                    Text(
                        if (bootRestoreReady) {
                            "以下关键应用会在开机时自动恢复；若恢复失败，仍可能导致" +
                                "无法开机、没有桌面或输入法："
                        } else {
                            "以下关键应用卸载后可能导致无法开机、没有桌面或输入法："
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    critical.forEach { item ->
                        Text(
                            "· ${appTitle(item.label, item.packageName)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (normal.isNotEmpty()) {
                    if (critical.isNotEmpty()) {
                        Text(
                            "其他应用：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    normal.forEach { item ->
                        Text(
                            "· ${appTitle(item.label, item.packageName)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    if (bootRestoreReady) {
                        "先备份 APK，再为用户卸载（保留应用数据）；" +
                            "开机时自动恢复，稳定后重新卸载。"
                    } else {
                        "先备份 APK，再为用户卸载所选应用（保留应用数据）；" +
                            "备份用于手动恢复。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (critical.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !busy) {
                            acknowledged = !acknowledged
                        }
                    ) {
                        NemuSmallCheckbox(
                            checked = acknowledged,
                            enabled = !busy,
                            onCheckedChange = { acknowledged = it },
                            contentDescription = criticalNote
                        )
                        Text(
                            criticalNote,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && (critical.isEmpty() || acknowledged)
            ) {
                Text("确认备份并卸载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 恢复确认：只列出确实有 APK 备份的目标。 */
@Composable
private fun DeepRestoreConfirmDialog(
    targets: List<DeepUninstallItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val withBackup = targets.filter { it.backup != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复所选 ${withBackup.size} 个？") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "优先恢复系统当前安装，缺失时从 APK 备份重装；恢复后会自动启用。",
                    style = MaterialTheme.typography.bodySmall
                )
                withBackup.forEach { item ->
                    Text(
                        "· ${item.label} · ${item.packageName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && withBackup.isNotEmpty()
            ) {
                Text("确认恢复")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
