package com.nemu.hibernate.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
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

/** How the picker groups a package. Only OS/OEM packages are "system". */
internal enum class AppKind(val label: String) {
    ROOT("Root/框架"),
    SYSTEM("系统"),
    USER("普通应用")
}

/** Packages that manage root/framework access, not part of the Honor OS. */
private val rootToolPackages = setOf(
    "me.weishu.kernelsu",
    "me.yuki.fina",
    "com.topjohnwu.magisk",
    "com.rifsxd.ksunext",
    "org.lsposed.manager",
    "moe.shizuku.privileged.api"
)

private val rootToolTokens = listOf(
    "kernelsu",
    "magisk",
    "lsposed",
    "shizuku",
    "ksunext",
    "sukisu",
    "apatch"
)

/**
 * OS packages are the Honor/AOSP/vendor image, not every package that happens
 * to carry FLAG_SYSTEM: systemless root modules and preinstalled third-party
 * APKs can carry that flag too.
 */
private val osPackagePrefixes = listOf(
    "com.android.",
    "com.hihonor.",
    "com.honor.",
    "com.huawei.",
    "com.google.android.",
    "com.qualcomm.",
    "com.qti.",
    "org.codeaurora.",
    "com.mediatek."
)

internal fun classifyApp(
    packageName: String,
    label: String,
    systemFlag: Boolean
): AppKind {
    val rootTool = packageName.startsWith("org.lsposed.") ||
        packageName in rootToolPackages ||
        rootToolTokens.any { token ->
            packageName.contains(token, ignoreCase = true) ||
                label.contains(token, ignoreCase = true)
        }
    if (rootTool) return AppKind.ROOT
    val osPackage = packageName == "android" ||
        osPackagePrefixes.any { packageName.startsWith(it) }
    return if (systemFlag && osPackage) AppKind.SYSTEM else AppKind.USER
}

/** One installed package, including system services without a launcher icon. */
private data class AllAppEntry(
    val label: String,
    val packageName: String,
    val kind: AppKind,
    val criticalReason: String?,
    val isSelf: Boolean
)

private enum class AllAppsFilter(val label: String) {
    ALL("全部"),
    ROOT("Root/框架"),
    SYSTEM("系统"),
    USER("普通应用"),
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
                kind = classifyApp(
                    info.packageName,
                    label,
                    system
                ),
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
            // Root tools first, then ordinary apps, then the long tail of
            // iconless system services. Enum ordinal would put SYSTEM before
            // USER, burying the user's real apps under hundreds of services.
            compareBy<AllAppEntry> {
                when (it.kind) {
                    AppKind.ROOT -> 0
                    AppKind.USER -> 1
                    AppKind.SYSTEM -> 2
                }
            }.thenBy { it.label.lowercase() }
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
            AllAppsFilter.SYSTEM -> all.filter { it.kind == AppKind.SYSTEM }
            AllAppsFilter.ROOT -> all.filter { it.kind == AppKind.ROOT }
            // Root/framework tools are still ordinary (non-system) apps;
            // the Root tab is only a pinned shortcut.
            AllAppsFilter.USER -> all.filter { it.kind != AppKind.SYSTEM }
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
    // Selections survive filter/search changes; only entries that left the
    // installed list are pruned. The action bar count must reflect the whole
    // selection, not just the currently visible subset.
    val selectedEntries = all.filter {
        it.packageName in selected &&
            !it.isSelf &&
            it.packageName !in addedPackages
    }
    LaunchedEffect(apps) {
        selected = SelectionRules.prune(
            selected,
            all.map { it.packageName }
        )
    }

    val commit: (List<AllAppEntry>) -> Unit = { entries ->
        entries.forEach { app ->
            callbacks.onCustomTargetAdd(
                app.packageName,
                app.label,
                if (app.kind != AppKind.USER ||
                    app.criticalReason != null
                ) {
                    RiskLevel.RED
                } else {
                    RiskLevel.YELLOW
                }
            )
        }
        message = "已添加 ${entries.size} 个；到“应用”页选择策略并应用。"
        // The inline message is at the top of the list and can be off-screen
        // after adding from deep in the list; make the next step visible.
        Toast.makeText(
            context,
            "已添加 ${entries.size} 个；到“应用”页选择策略并应用",
            Toast.LENGTH_LONG
        ).show()
        selected = emptySet()
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
                        // Rows are always selectable here; show 全选 directly.
                        batchExpanded = true,
                        onBatchToggle = {},
                        batchControlVisible = false,
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
                    Text(
                        "“Root/框架”是常用的 Root/LSP/Shizuku 工具置顶，它们仍属于" +
                            "普通应用；“系统”仅指荣耀/OS 自带应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                it.criticalReason != null ||
                                    it.kind == AppKind.ROOT
                            }
                            if (critical.isEmpty()) {
                                commit(selectedEntries)
                            } else {
                                // Confirm exactly what will be added: the
                                // whole selection, not only the warning rows.
                                pendingCritical = selectedEntries
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
        val rootTools = pendingCritical.filter { it.kind == AppKind.ROOT }
        val systemTools = pendingCritical.filter { it.kind != AppKind.ROOT }
        AlertDialog(
            onDismissRequest = {
                pendingCritical = emptyList()
                riskAcknowledged = false
            },
            title = { Text("添加所选应用？") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (systemTools.isNotEmpty()) {
                        Text(
                            "以下系统/关键应用被停用或隐藏后，可能导致无法开机、" +
                                "没有桌面、没有输入法，或无法恢复：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (rootTools.isNotEmpty()) {
                        Text(
                            "以下 Root/框架工具不是系统应用，但冻结后可能丢失 " +
                                "Root、模块管理或 Shizuku 授权，导致无法恢复：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    val keyEntries = pendingCritical.filter {
                        it.kind == AppKind.ROOT || it.criticalReason != null
                    }
                    val normalEntries = pendingCritical - keyEntries.toSet()
                    keyEntries.forEach { app ->
                        val note = when {
                            app.kind == AppKind.ROOT -> " · Root/框架工具"
                            else -> app.criticalReason?.let { " · $it" } ?: ""
                        }
                        Text(
                            "${app.label} · ${app.packageName}$note",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (normalEntries.isNotEmpty()) {
                        Text(
                            "同时添加（普通应用）：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        normalEntries.forEach { app ->
                            Text(
                                "${app.label} · ${app.packageName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "添加只登记为 Nemu 目标，不会暂停、停用或隐藏任何应用；" +
                            "真正执行时在“应用”页还会再次确认。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            riskAcknowledged = !riskAcknowledged
                        }
                    ) {
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
                        val entries = pendingCritical
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
                    when (app.kind) {
                        AppKind.ROOT -> StatusChip(
                            "Root/框架",
                            tone = StatusChipTone.WARNING
                        )
                        AppKind.SYSTEM -> StatusChip(
                            "系统",
                            tone = StatusChipTone.INFO
                        )
                        AppKind.USER -> Unit
                    }
                    if (app.kind != AppKind.ROOT &&
                        app.criticalReason != null
                    ) {
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
