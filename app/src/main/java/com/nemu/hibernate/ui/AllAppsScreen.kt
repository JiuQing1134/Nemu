package com.nemu.hibernate.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.core.CriticalRoleResolver
import com.nemu.hibernate.core.CustomTargetGuard
import com.nemu.hibernate.core.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed package, including system services without a launcher icon. */
private data class AllAppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val criticalReason: String?,
    val isSelf: Boolean
)

private enum class AllAppsFilter(val label: String) {
    ALL("全部"),
    USER("用户"),
    SYSTEM("系统"),
    ADDED("已添加")
}

private fun loadAllApps(context: Context): List<AllAppEntry> {
    val pm = context.packageManager
    // Installed launchers/IMEs are not identifiable by a name heuristic:
    // e.g. Teslacoil's launcher or com.baidu.input. Resolve the real roles
    // once per list load and use them for warnings/risk.
    val dynamicRoles = CriticalRoleResolver.roles(context)
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .map { info ->
            val label = runCatching {
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(info.packageName)
            val system = info.flags and (
                ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                ) != 0
            val self = CustomTargetGuard.isSelf(info.packageName)
            AllAppEntry(
                label = label.ifBlank { info.packageName },
                packageName = info.packageName,
                isSystem = system,
                criticalReason = if (self) {
                    null
                } else {
                    CustomTargetGuard.criticalReason(
                        info.packageName,
                        dynamicRoles
                    )
                },
                isSelf = self
            )
        }
        .sortedWith(
            compareBy<AllAppEntry> { it.isSystem }
                .thenBy { it.label.lowercase() }
        )
}

/**
 * Dedicated all-packages picker. It never freezes anything; it only adds
 * targets to Nemu. Strategies and execution stay on the Applications page.
 */
@Composable
fun AllAppsScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier,
    onOpenApplications: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AllAppEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var pendingCritical by remember { mutableStateOf<List<AllAppEntry>>(emptyList()) }
    var riskAcknowledged by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(context, reloadToken) {
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { loadAllApps(context) }
        }.onSuccess {
            apps = it
        }.onFailure {
            // Keep null so the explicit error/retry card is reachable.
            apps = null
            loadError = it.message ?: it.javaClass.simpleName
        }
    }

    val filter = AllAppsFilter.entries.getOrElse(filterIndex) {
        AllAppsFilter.ALL
    }
    val addedPackages = remember(state.catalog) {
        state.catalog.map { it.packageName }.toSet()
    }
    val all = apps.orEmpty()
    val filtered = remember(all, filter, query, addedPackages) {
        val byFilter = when (filter) {
            AllAppsFilter.ALL -> all
            AllAppsFilter.USER -> all.filter { !it.isSystem }
            AllAppsFilter.SYSTEM -> all.filter { it.isSystem }
            AllAppsFilter.ADDED -> all.filter { it.packageName in addedPackages }
        }
        if (query.isBlank()) {
            byFilter
        } else {
            byFilter.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    val selectable = filtered.filter {
        !it.isSelf && it.packageName !in addedPackages
    }
    val selectedEntries = filtered.filter {
        it.packageName in selected &&
            !it.isSelf &&
            it.packageName !in addedPackages
    }
    LaunchedEffect(filterIndex, query) {
        selected = SelectionRules.prune(
            selected,
            filtered.map { it.packageName }
        )
    }

    val commit: (List<AllAppEntry>) -> Unit = { entries ->
        entries.forEach { app ->
            callbacks.onCustomTargetAdd(
                app.packageName,
                app.label,
                if (app.isSystem || app.criticalReason != null) {
                    RiskLevel.RED
                } else {
                    RiskLevel.YELLOW
                }
            )
        }
        message = "已添加 ${entries.size} 个；到“应用”页选择策略并应用。"
        selected = emptySet()
        batchMode = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SectionCard {
                    CatalogSearchRow(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "搜索应用或包名",
                        batchExpanded = batchMode,
                        onBatchToggle = { batchMode = !batchMode },
                        busy = state.busy,
                        allSelected = selectable.isNotEmpty() &&
                            selectable.all { it.packageName in selected },
                        selectedCount = selectedEntries.size,
                        selectAllEnabled = selectable.isNotEmpty(),
                        onSelectAll = {
                            selected = selected +
                                selectable.map { it.packageName }
                        },
                        onClearAll = { selected = emptySet() }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AllAppsFilter.entries.forEachIndexed { index, option ->
                            NemuFilterChip(
                                selected = filterIndex == index,
                                onClick = { filterIndex = index },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChip(
                            text = "共 ${filtered.size} 个",
                            tone = StatusChipTone.INFO
                        )
                        if (selectedEntries.isNotEmpty()) {
                            StatusChip(
                                text = "已选 ${selectedEntries.size}",
                                tone = StatusChipTone.PRIMARY
                            )
                        }
                    }
                    message?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onOpenApplications) {
                            Text("去应用页设置策略 ›")
                        }
                    }
                }
            }
            if (apps == null && loadError != null) {
                item {
                    SectionCard {
                        Text(
                            "读取应用列表失败：$loadError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        NemuOutlinedButton(
                            onClick = { reloadToken++ },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("重试") }
                    }
                }
            } else if (apps == null) {
                item {
                    SectionCard {
                        Text(
                            "正在读取已安装应用…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                item {
                    SectionCard(title = "没有匹配的应用") {
                        Text(
                            if (query.isNotBlank()) {
                                "没有名称或包名包含“$query”的应用。"
                            } else {
                                "这个分类下没有已安装应用。"
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
            } else {
                items(filtered, key = { it.packageName }) { app ->
                    AllAppRow(
                        app = app,
                        added = app.packageName in addedPackages,
                        selected = app.packageName in selected,
                        onToggle = {
                            selected = if (app.packageName in selected) {
                                selected - app.packageName
                            } else {
                                selected + app.packageName
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.size(4.dp)) }
        }

        if (selectedEntries.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "已选 ${selectedEntries.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { selected = emptySet() },
                        enabled = !state.busy
                    ) { Text("清除") }
                    Button(
                        onClick = {
                            val critical = selectedEntries.filter {
                                it.criticalReason != null
                            }
                            if (critical.isEmpty()) {
                                commit(selectedEntries)
                            } else {
                                pendingCritical = critical
                                riskAcknowledged = false
                            }
                        },
                        enabled = !state.busy
                    ) {
                        Text("添加 ${selectedEntries.size} 个")
                    }
                }
            }
        }
    }

    if (pendingCritical.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                pendingCritical = emptyList()
                riskAcknowledged = false
            },
            title = { Text("添加恢复关键应用？") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "以下应用被停用或隐藏后，可能导致无法开机、没有桌面、没有输入法，或无法恢复：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    pendingCritical.forEach { app ->
                        Text(
                            "${app.label} · ${app.packageName}" +
                                (app.criticalReason?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "添加后不会自动执行；请仍优先使用“暂停”，不要对它们使用停用/隐藏。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NemuSmallCheckbox(
                            checked = riskAcknowledged,
                            enabled = true,
                            onCheckedChange = { riskAcknowledged = it },
                            contentDescription = "我已了解风险，仍要添加"
                        )
                        Text(
                            "我已了解风险，仍要添加",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val entries = selectedEntries
                        pendingCritical = emptyList()
                        riskAcknowledged = false
                        commit(entries)
                    },
                    enabled = riskAcknowledged
                ) { Text("仍然添加") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingCritical = emptyList()
                        riskAcknowledged = false
                    }
                ) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AllAppRow(
    app: AllAppEntry,
    added: Boolean,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val canSelect = !app.isSelf && !added
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canSelect) { onToggle() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            enabled = canSelect,
            colors = nemuCheckboxColors()
        )
        AppIcon(packageName = app.packageName, size = 40.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (added) {
                    StatusChip("已在 Nemu", tone = StatusChipTone.SUCCESS)
                }
                if (app.isSelf) {
                    StatusChip("Nemu 自身", tone = StatusChipTone.ERROR)
                } else {
                    if (app.isSystem) {
                        StatusChip("系统", tone = StatusChipTone.INFO)
                    }
                    if (app.criticalReason != null) {
                        StatusChip(
                            "关键 · ${app.criticalReason}",
                            tone = StatusChipTone.ERROR
                        )
                    }
                }
            }
        }
    }
}
