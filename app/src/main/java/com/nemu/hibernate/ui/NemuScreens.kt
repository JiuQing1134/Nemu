package com.nemu.hibernate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.pm.PackageManager
import com.nemu.hibernate.core.CustomTargetGuard
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.TargetCategory

@Composable
private fun FreezeStep(number: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Text(
                text = number,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleSmall
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class CategoryFilter(val label: String, val category: TargetCategory?) {
    ALL("全部", null),
    UPDATE("系统更新", TargetCategory.UPDATE),
    AI("AI 功能", TargetCategory.AI),
    CLOUD("云控与遥测", TargetCategory.CLOUD),
    SAFETY("反诈", TargetCategory.SAFETY),
    THERMAL("温控", TargetCategory.THERMAL),
    CUSTOM("自定义", TargetCategory.AUTOMATION)
}

private enum class PendingKind { ENABLE, STRATEGY, FREEZE_ONE }

private data class PendingAction(
    val target: CatalogTargetUiState,
    val kind: PendingKind,
    val strategy: FreezeStrategy? = null
)

/**
 * Freeze page: choose targets, choose a strategy, then press a clearly
 * labelled freeze button. Policy switches never execute anything by
 * themselves; every execution is announced with the concrete consequences.
 */
@Composable
fun FreezeScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier
) {
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    val filter = CategoryFilter.entries.getOrElse(selectedFilter) { CategoryFilter.ALL }
    val visible = remember(state.catalog, filter) {
        if (filter.category == null) state.catalog
        else state.catalog.filter { it.category == filter.category }
    }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showFreezeAll by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    val selectedTargets = state.catalog.filter {
        it.enabled && !it.observeOnly && it.strategy != FreezeStrategy.OBSERVE &&
            (state.rootAvailable ||
                (it.strategy != FreezeStrategy.COMPONENT &&
                    it.strategy != FreezeStrategy.DISABLE_USER &&
                    it.strategy != FreezeStrategy.HIDE))
    }
    val frozenCount = state.catalog.count { it.applied }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("冻结应用", style = MaterialTheme.typography.titleLarge)
                Text(
                    "勾选 → 选方式 → 立即冻结；恢复去“解冻”页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FreezeStep("1", "勾选", Modifier.weight(1f))
                    FreezeStep("2", "选方式", Modifier.weight(1f))
                    FreezeStep("3", "立即冻结", Modifier.weight(1f))
                }
                Text(
                    text = "勾选只加入计划，不会改变手机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(
                        text = "已勾选 ${selectedTargets.size}",
                        tone = if (selectedTargets.isNotEmpty()) {
                            StatusChipTone.PRIMARY
                        } else {
                            StatusChipTone.NEUTRAL
                        }
                    )
                    StatusChip(
                        text = "已冻结 $frozenCount",
                        tone = if (frozenCount > 0) {
                            StatusChipTone.SUCCESS
                        } else {
                            StatusChipTone.NEUTRAL
                        }
                    )
                    if (state.dryRun) {
                        StatusChip("预览模式", tone = StatusChipTone.WARNING)
                    }
                }
                if (state.lastCatalogResult != null) {
                    Text(
                        "上次结果：${state.lastCatalogResult}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showFreezeAll = true },
                    enabled = !state.busy && selectedTargets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (selectedTargets.isEmpty()) {
                            "先勾选要冻结的应用"
                        } else {
                            "立即冻结已勾选的 ${selectedTargets.size} 个应用"
                        }
                    )
                }
                OutlinedButton(
                    onClick = callbacks.onCatalogRefresh,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新实际状态")
                }
                OutlinedButton(
                    onClick = { showAddCustom = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("添加任意应用（自定义冻结）")
                }
                Text(
                    "冻结立即执行；重启后按开机计划重跑。无冻结目标时完全待机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.rootAvailable) {
                    Text(
                        "无 Root：仅支持暂停和观察。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryFilter.entries.forEachIndexed { index, option ->
                    val count = if (option.category == null) {
                        state.catalog.size
                    } else {
                        state.catalog.count { it.category == option.category }
                    }
                    FilterChip(
                        selected = selectedFilter == index,
                        onClick = { selectedFilter = index },
                        label = { Text("${option.label} $count") }
                    )
                }
            }
        }
        if (filter == CategoryFilter.CUSTOM) {
            item {
                SectionCard(
                    title = "自定义冻结",
                    subtitle = "可以添加任意已安装应用；优先用“暂停”，停用/隐藏会让它从桌面消失。"
                ) {
                    Button(
                        onClick = { showAddCustom = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("添加任意应用")
                    }
                    Text(
                        "不要添加系统桌面、设置、电话、输入法、权限控制器、Shizuku、Root 管理器或 Nemu 自己；" +
                            "隐藏这些应用可能导致无法开机或无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                SectionCard(title = "没有目标") {
                    Text(
                        "设备上没有该分类的软件包，或目录尚未加载。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        items(visible, key = { it.packageName }) { target ->
            FreezeTargetCard(
                target = target,
                busy = state.busy,
                rootAvailable = state.rootAvailable,
                onToggle = { value ->
                    val strategy = target.strategy
                    if (value && needsConfirm(target, strategy)) {
                        pending = PendingAction(target, PendingKind.ENABLE, strategy)
                    } else {
                        callbacks.onCatalogToggle(target.packageName, value)
                    }
                },
                onStrategy = { strategy ->
                    if (needsConfirm(target, strategy)) {
                        pending = PendingAction(
                            target,
                            PendingKind.STRATEGY,
                            strategy
                        )
                    } else {
                        callbacks.onCatalogStrategyChange(target.packageName, strategy)
                    }
                },
                onFreeze = {
                    pending = PendingAction(
                        target,
                        PendingKind.FREEZE_ONE,
                        target.strategy
                    )
                },
                onRemove = { callbacks.onCustomTargetRemove(target.packageName) }
            )
        }
    }

    val action = pending
    if (action != null) {
        val strategy = action.strategy ?: action.target.strategy
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    when (action.kind) {
                        PendingKind.FREEZE_ONE -> "冻结「${action.target.label}」？"
                        PendingKind.ENABLE -> "启用「${action.target.label}」的冻结计划？"
                        PendingKind.STRATEGY -> "改用「${strategy.label()}」？"
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "冻结方式：${strategy.label()}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        strategy.explain(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "具体会发生什么：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        action.target.target.consequenceFor(strategy)
                            ?: strategy.genericConsequence(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (action.target.risk == RiskLevel.RED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (state.dryRun) {
                        Text(
                            "当前是预览模式：这次只会记录命令，不会真正冻结。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        when (action.kind) {
                            PendingKind.ENABLE ->
                                callbacks.onCatalogToggle(action.target.packageName, true)
                            PendingKind.STRATEGY ->
                                callbacks.onCatalogStrategyChange(
                                    action.target.packageName,
                                    strategy
                                )
                            PendingKind.FREEZE_ONE ->
                                callbacks.onCatalogApply(
                                    setOf(action.target.packageName)
                                )
                        }
                    }
                ) {
                    Text(
                        when (action.kind) {
                            PendingKind.FREEZE_ONE -> "确认冻结"
                            PendingKind.ENABLE -> "启用计划"
                            PendingKind.STRATEGY -> "确认切换"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            }
        )
    }

    if (showFreezeAll) {
        AlertDialog(
            onDismissRequest = { showFreezeAll = false },
            title = { Text("冻结 ${selectedTargets.size} 个应用？") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedTargets.forEach { target ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${target.label} · ${target.strategy.label()}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                target.target.consequenceFor(target.strategy)
                                    ?: target.strategy.genericConsequence(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (state.dryRun) {
                        Text(
                            "当前是预览模式：只记录命令，不会真正冻结。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "不会解冻任何应用。执行后可在“解冻”页撤销。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFreezeAll = false
                        callbacks.onCatalogApply(
                            selectedTargets.map { it.packageName }.toSet()
                        )
                    }
                ) { Text("确认冻结全部") }
            },
            dismissButton = {
                TextButton(onClick = { showFreezeAll = false }) { Text("取消") }
            }
        )
    }

    if (showAddCustom) {
        CustomAppPickerDialog(
            onDismiss = { showAddCustom = false },
            onPick = { packageName, label ->
                showAddCustom = false
                callbacks.onCustomTargetAdd(packageName, label)
            }
        )
    }
}

@Composable
private fun CustomAppPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter {
                CustomTargetGuard.denyReason(it.packageName) == null &&
                    (it.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
            }
            .map { info ->
                val label = runCatching {
                    pm.getApplicationLabel(info).toString()
                }.getOrDefault(info.packageName)
                label to info.packageName
            }
            .sortedBy { it.first.lowercase() }
            .toList()
    }
    val visible = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.first.contains(query, ignoreCase = true) ||
                it.second.contains(query, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索应用名称或包名") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "共 ${visible.size} 个。系统关键应用已从列表排除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(visible, key = { it.second }) { (label, packageName) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(packageName, label) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


@Composable
private fun FreezeTargetCard(
    target: CatalogTargetUiState,
    busy: Boolean,
    rootAvailable: Boolean,
    onToggle: (Boolean) -> Unit,
    onStrategy: (FreezeStrategy) -> Unit,
    onFreeze: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = target.enabled,
                    onCheckedChange = onToggle,
                    enabled = !busy && !target.observeOnly
                )
                AppIcon(packageName = target.packageName, size = 42.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(target.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        target.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RiskChip(target.risk)
            }
            val status = when {
                target.actual.processRunning == true &&
                    target.actual.suspended == true -> "已冻结 · 进程仍在"
                target.applied -> "已冻结"
                target.enabled -> "已勾选 · 待冻结"
                else -> target.actualText()
            }
            val statusTone = when {
                target.actual.processRunning == true &&
                    target.actual.suspended == true -> StatusChipTone.WARNING
                target.applied -> StatusChipTone.SUCCESS
                target.enabled -> StatusChipTone.PRIMARY
                else -> actualTone(target)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(text = status, tone = statusTone)
            }
            if (target.target.description.isNotBlank()) {
                Text(
                    "这个应用是做什么的：${target.target.description}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (target.observeOnly) {
                Text(
                    "为什么只能观察：",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    target.target.consequenceFor(FreezeStrategy.OBSERVE)
                        ?: "系统保护或风险太高，Nemu 不对它下发冻结命令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("选择冻结方式：", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FreezeStrategy.entries
                        .filter {
                            FreezeStrategyPolicy.validate(target.target, it) == null &&
                                (rootAvailable ||
                                    (it != FreezeStrategy.COMPONENT &&
                                        it != FreezeStrategy.DISABLE_USER &&
                                        it != FreezeStrategy.HIDE))
                        }
                        .forEach { strategy ->
                            FilterChip(
                                selected = target.strategy == strategy,
                                onClick = { onStrategy(strategy) },
                                enabled = !busy,
                                label = { Text(strategy.label()) }
                            )
                        }
                }
                if (target.target.components.isNotEmpty()) {
                    Text(
                        "会停用 ${target.target.components.size} 个组件：" +
                            target.target.components.joinToString("、") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    target.strategy.explain(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "点按钮后会先显示“具体会发生什么”，确认后才执行。",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onFreeze,
                        enabled = !busy &&
                            target.enabled &&
                            !target.observeOnly &&
                            target.strategy != FreezeStrategy.OBSERVE &&
                            (rootAvailable ||
                                (target.strategy != FreezeStrategy.COMPONENT &&
                                    target.strategy != FreezeStrategy.DISABLE_USER &&
                                    target.strategy != FreezeStrategy.HIDE))
                    ) {
                        Text(if (target.applied) "重新冻结" else "立即冻结")
                    }
                }
                if (target.category == TargetCategory.AUTOMATION) {
                    if (target.applied) {
                        Text(
                            "先解冻，才能移除这个自定义目标。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        TextButton(
                            onClick = onRemove,
                            enabled = !busy
                        ) {
                            Text("移除自定义目标")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unfreeze page: show what is actually frozen and how it will be restored.
 */
@Composable
fun UnfreezeScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier
) {
    val frozen = remember(state.catalog) {
        state.catalog.filter { it.hasFrozenState || it.applied }
    }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var showAll by remember { mutableStateOf(false) }
    var showSelected by remember { mutableStateOf(false) }
    LaunchedEffect(frozen.map { it.packageName }) {
        selection = selection.intersect(frozen.map { it.packageName }.toSet())
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("解冻与恢复", style = MaterialTheme.typography.titleLarge)
                Text(
                    "恢复冻结或隐藏的应用。解冻所选会同时关闭计划。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.lastCatalogResult != null) {
            item {
                SectionCard(title = "上次操作结果") {
                    Text(
                        state.lastCatalogResult,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.dryRun) {
                        Text(
                            "当前是预览模式：恢复只记录命令，不改变实际状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        item {
            SectionCard(title = "恢复范围") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(
                        text = "当前冻结 ${frozen.size}",
                        tone = if (frozen.isNotEmpty()) {
                            StatusChipTone.WARNING
                        } else {
                            StatusChipTone.SUCCESS
                        }
                    )
                    StatusChip(
                        text = "已选 ${selection.size}",
                        tone = if (selection.isNotEmpty()) {
                            StatusChipTone.PRIMARY
                        } else {
                            StatusChipTone.NEUTRAL
                        }
                    )
                }
                Button(
                    onClick = { showSelected = true },
                    enabled = !state.busy && selection.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (selection.isEmpty()) {
                            "先勾选要解冻的应用"
                        } else {
                            "解冻所选 ${selection.size} 个，并关闭计划"
                        }
                    )
                }
                OutlinedButton(
                    onClick = { showAll = true },
                    enabled = !state.busy && frozen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("临时全部解冻（保留开机冻结计划）")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = callbacks.onCatalogRefresh,
                        enabled = !state.busy
                    ) { Text("刷新实际状态") }
                    TextButton(
                        onClick = { selection = frozen.map { it.packageName }.toSet() },
                        enabled = !state.busy && frozen.isNotEmpty()
                    ) { Text("全选") }
                    TextButton(
                        onClick = { selection = emptySet() },
                        enabled = selection.isNotEmpty()
                    ) { Text("清除选择") }
                }
                Text(
                    "“解冻所选”会同时关闭这些应用的冻结计划，防止下次开机又被冻结；" +
                        "“临时全部解冻”只恢复本次状态，冻结计划保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.rootAvailable) {
                    Text(
                        "当前没有 Root：只能可靠恢复“暂停”；组件/停用/隐藏的恢复需要 Root。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        if (frozen.isEmpty()) {
            item {
                SectionCard(title = "当前没有冻结的应用") {
                    Text(
                        "没有检测到暂停、停用、隐藏或组件停用的目标。" +
                            "如果刚执行过冻结，请点“刷新实际状态”。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        items(frozen, key = { it.packageName }) { target ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = target.packageName in selection,
                        onCheckedChange = { checked ->
                            selection = if (checked) {
                                selection + target.packageName
                            } else {
                                selection - target.packageName
                            }
                        },
                        enabled = !state.busy
                    )
                    AppIcon(packageName = target.packageName, size = 38.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(target.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            target.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "恢复后会怎样：${target.strategy.restoreExplain()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RiskChip(target.risk)
                        StatusChip(
                            text = target.actualText(),
                            tone = actualTone(target)
                        )
                    }
                }
            }
        }
    }

    if (showSelected) {
        AlertDialog(
            onDismissRequest = { showSelected = false },
            title = { Text("解冻所选 ${selection.size} 个并关闭计划？") },
            text = {
                Text(
                    if (state.dryRun) {
                        "预览：只记录恢复命令，不会真正解冻，也不会关闭冻结计划。"
                    } else {
                        "将恢复所选应用，并关闭它们的冻结计划（下次开机不再自动冻结）。" +
                            "停用/隐藏的应用会重新启用或解除隐藏；组件会按冻结前的状态恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSelected = false
                        callbacks.onCatalogRestore(selection)
                    }
                ) { Text("确认解冻") }
            },
            dismissButton = {
                TextButton(onClick = { showSelected = false }) { Text("取消") }
            }
        )
    }
    if (showAll) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text("临时全部解冻") },
            text = {
                Text(
                    if (state.dryRun) {
                        "预览：只记录恢复计划，不取消暂停、不恢复组件，" +
                            "也不写本次开机的跳过标记。"
                    } else {
                        "将取消所有暂停、恢复所有由 Nemu 停用/隐藏的应用，" +
                            "并跳过本次开机自动冻结。冻结计划会保留，下次开机会重新冻结。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAll = false
                        callbacks.onCatalogRestoreAll()
                        selection = emptySet()
                    }
                ) { Text("确认临时解冻") }
            },
            dismissButton = {
                TextButton(onClick = { showAll = false }) { Text("取消") }
            }
        )
    }
}

private fun needsConfirm(target: CatalogTargetUiState, strategy: FreezeStrategy): Boolean =
    strategy == FreezeStrategy.DISABLE_USER ||
        strategy == FreezeStrategy.HIDE ||
        (target.risk == RiskLevel.RED &&
            (strategy == FreezeStrategy.COMPONENT || strategy == FreezeStrategy.SUSPEND))

private fun FreezeStrategy.label(): String = when (this) {
    FreezeStrategy.SUSPEND -> "暂停"
    FreezeStrategy.COMPONENT -> "只禁后台组件"
    FreezeStrategy.DISABLE_USER -> "停用（伪装卸载）"
    FreezeStrategy.HIDE -> "隐藏（彻底伪装）"
    FreezeStrategy.OBSERVE -> "只观察"
}

private fun FreezeStrategy.explain(): String = when (this) {
    FreezeStrategy.SUSPEND ->
        "让系统暂停这个应用：它不能启动、不能后台运行，但数据和桌面图标都保留。" +
            "最温和，解冻即可恢复。"
    FreezeStrategy.COMPONENT ->
        "只停用这个应用里负责后台/云更新的几个组件，应用本身还能打开；需要 Root。"
    FreezeStrategy.DISABLE_USER ->
        "把整个应用停用：桌面图标消失，设置里显示“已停用”，数据保留。" +
            "需要 Root，解冻可恢复。这就是常说的“伪装卸载”。"
    FreezeStrategy.HIDE ->
        "把整个应用从系统隐藏，看起来像已经卸载；APK 和数据仍在。" +
            "需要 Root，解冻可恢复。比停用更彻底。"
    FreezeStrategy.OBSERVE ->
        "不下发任何命令，只显示这个应用当前在做什么。"
}

private fun FreezeStrategy.restoreExplain(): String = when (this) {
    FreezeStrategy.SUSPEND -> "应用会取消暂停，可以重新启动和在后台运行。"
    FreezeStrategy.COMPONENT -> "被停用的后台组件会恢复，相关服务/更新重新工作。"
    FreezeStrategy.DISABLE_USER -> "应用重新启用，桌面图标和入口回来，数据不丢。"
    FreezeStrategy.HIDE -> "应用解除隐藏，桌面图标和入口回来，数据不丢。"
    FreezeStrategy.OBSERVE -> "不需要恢复。"
}

private fun FreezeStrategy.genericConsequence(): String = when (this) {
    FreezeStrategy.SUSPEND ->
        "这个应用在解冻前无法启动或后台运行；依赖它的功能会暂时不可用。"
    FreezeStrategy.COMPONENT ->
        "这个应用的后台服务/云更新组件会停止工作；应用本身仍可打开。"
    FreezeStrategy.DISABLE_USER ->
        "这个应用会被停用，桌面图标消失；数据保留，可在解冻页恢复。"
    FreezeStrategy.HIDE ->
        "这个应用会从系统隐藏，看起来像卸载了；数据保留，可在解冻页恢复。"
    FreezeStrategy.OBSERVE -> "不会改动任何东西。"
}

private fun CatalogTargetUiState.plainActualText(): String = when {
    observeOnly -> "只观察，不执行冻结。"
    actual.hidden == true && ownedHidden ->
        "已对系统隐藏（伪装卸载状态），解冻后恢复。"
    actual.hidden == true ->
        "已被隐藏，但不是 Nemu 设置的；本应用不会去解除它。"
    strategy == FreezeStrategy.HIDE -> if (applied) {
        "已记录隐藏操作，但还没有确认实际状态，点“刷新实际状态”。"
    } else {
        "仍可见，尚未隐藏。"
    }
    strategy == FreezeStrategy.COMPONENT -> when (componentsDisabled) {
        true -> "至少一个后台组件已停用，应用本身可能还能打开。"
        false -> "所有登记的组件都处于启用状态。"
        null -> "暂时读不到组件状态，点“刷新实际状态”再试。"
    }
    actual.packageEnabled == false -> "应用已被停用（设置中显示为“已停用”）。"
    actual.suspended == true && actual.processRunning == true ->
        "已暂停，但进程还没有退出；系统可能会短暂保留进程。"
    actual.suspended == true -> "已暂停，当前没有检测到它的进程。"
    actual.suspended == null && actual.packageEnabled == null ->
        "读不到这个应用的状态（可能不存在，或已隐藏）。"
    else -> "当前没有被冻结。"
}

private fun actualTone(target: CatalogTargetUiState): StatusChipTone = when {
    target.strategy == FreezeStrategy.HIDE ->
        if (target.applied) StatusChipTone.SUCCESS else StatusChipTone.INFO
    target.strategy == FreezeStrategy.COMPONENT ->
        when (target.componentsDisabled) {
            true -> StatusChipTone.SUCCESS
            false -> StatusChipTone.INFO
            null -> StatusChipTone.NEUTRAL
        }
    target.actual.suspended == true && target.actual.processRunning == true ->
        StatusChipTone.WARNING
    target.actual.suspended == true || target.actual.packageEnabled == false ->
        StatusChipTone.SUCCESS
    target.actual.suspended == null && target.actual.packageEnabled == null ->
        StatusChipTone.NEUTRAL
    else -> StatusChipTone.INFO
}
