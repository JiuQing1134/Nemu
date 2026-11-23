package com.nemu.hibernate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nemu.hibernate.core.CustomTargetGuard
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.core.TargetCopy
import com.nemu.hibernate.ui.theme.flClashCard
import com.nemu.hibernate.ui.theme.flClashInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class CategoryFilter(val label: String, val category: TargetCategory?) {
    ALL("全部", null),
    UPDATE("系统更新", TargetCategory.UPDATE),
    AI("AI 功能", TargetCategory.AI),
    CLOUD("云控", TargetCategory.CLOUD),
    SAFETY("安全", TargetCategory.SAFETY),
    THERMAL("温控", TargetCategory.THERMAL),
    CUSTOM("自定义", TargetCategory.AUTOMATION)
}

private enum class PendingKind { ENABLE, STRATEGY, FREEZE_ONE }

private data class PendingAction(
    val target: CatalogTargetUiState,
    val kind: PendingKind,
    val preset: StrategyPreset? = null
)

/** Unified Applications tab: manage policies or restore applied targets. */
@Composable
fun ApplicationsScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier,
    mode: Int = 0,
    onModeChange: (Int) -> Unit = {},
    focusPackage: String? = null,
    onClearFocus: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (mode == 0) {
            FreezeScreen(
                state,
                callbacks,
                Modifier.weight(1f),
                focusPackage = focusPackage,
                onClearFocus = onClearFocus,
                onOpenRestore = { onModeChange(1) }
            )
        } else {
            UnfreezeScreen(
                state,
                callbacks,
                Modifier.weight(1f),
                onOpenManage = { onModeChange(0) }
            )
        }
    }
}

/**
 * Shared applications header: mode chips plus a green text refresh action.
 */
@Composable
private fun ApplicationsHeader(
    manageCount: Int,
    restoreCount: Int,
    mode: Int,
    onModeChange: (Int) -> Unit,
    onRefresh: () -> Unit,
    busy: Boolean,
    refreshing: Boolean = false
) {
    val headerLabel = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NemuFilterChip(
            selected = mode == 0,
            onClick = { onModeChange(0) },
            label = { Text("管理 $manageCount", style = headerLabel) }
        )
        NemuFilterChip(
            selected = mode == 1,
            onClick = { onModeChange(1) },
            label = { Text("恢复 $restoreCount", style = headerLabel) }
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier.height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (refreshing) "刷新中…" else "刷新",
                style = MaterialTheme.typography.labelLarge,
                color = if (busy || refreshing) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                modifier = Modifier
                    .clickable(enabled = !busy && !refreshing) { onRefresh() }
                    .padding(horizontal = 8.dp)
            )
        }
    }
}

/**
 * Search field with the batch label on its right. Clicking 批量 keeps the
 * label in place and adds a small 全选 checkbox directly below it; the search
 * field never changes width or shifts sideways.
 */
@Composable
internal fun CatalogSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    batchExpanded: Boolean,
    onBatchToggle: () -> Unit,
    busy: Boolean,
    allSelected: Boolean,
    selectedCount: Int,
    selectAllEnabled: Boolean,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        CompactSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .padding(top = 10.dp)
                .width(72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "批量",
                style = MaterialTheme.typography.labelLarge,
                color = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                modifier = Modifier
                    .clickable(enabled = !busy) { onBatchToggle() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            if (batchExpanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    NemuSmallCheckbox(
                        checked = allSelected,
                        enabled = !busy && selectAllEnabled,
                        onCheckedChange = { checked ->
                            if (checked) onSelectAll() else onClearAll()
                        },
                        contentDescription = if (allSelected) "取消全选" else "全选"
                    )
                    Text(
                        text = "全选",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        modifier = Modifier
                            .clickable(enabled = !busy && selectAllEnabled) {
                                if (allSelected) onClearAll() else onSelectAll()
                            }
                    )
                }
            }
        }
    }
}

/** 44dp compact search field; the old 56dp OutlinedTextField was too tall. */
@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.flClashInset()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            if (value.isNotEmpty()) {
                Text(
                    text = "清除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onValueChange("") }
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * Freeze page: choose targets, choose a strategy, then press a clearly
 * labelled freeze button. Policy switches never execute anything by
 * themselves; every execution is announced with the concrete consequences.
 */
@Composable
fun FreezeScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier,
    focusPackage: String? = null,
    onClearFocus: () -> Unit = {},
    onOpenRestore: () -> Unit = {}
) {
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val filter = CategoryFilter.entries.getOrElse(selectedFilter) { CategoryFilter.ALL }
    val baseVisible = remember(state.catalog, filter) {
        if (filter.category == null) state.catalog
        else state.catalog.filter { it.category == filter.category }
    }
    val visible = remember(baseVisible, state.catalog, focusPackage, query) {
        val focused = if (focusPackage == null) {
            baseVisible
        } else {
            state.catalog.filter { it.packageName == focusPackage }
        }
        // A deep link must not be hidden by a search query saved from an
        // earlier visit to this tab.
        if (focusPackage != null || query.isBlank()) {
            focused
        } else {
            focused.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showFreezeAll by remember { mutableStateOf(false) }
    // Normal browsing has no checkboxes. Batch mode is explicit and its
    // selection is session-only, independent from the boot plan. The batch
    // toggle lives in the applications header row; leaving batch clears the
    // session selection.
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var batchMode by remember { mutableStateOf(false) }
    LaunchedEffect(batchMode) {
        if (!batchMode) selection = emptySet()
    }
    // Expanding another card must not collapse one above it: that would
    // shift the tapped card upward under the finger.
    var expandedPackages by rememberSaveable(
        stateSaver = Saver<Set<String>, String>(
            save = { it.joinToString("|") },
            restore = { raw ->
                raw.split('|').filter { it.isNotEmpty() }.toSet()
            }
        )
    ) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(focusPackage) {
        if (focusPackage != null) {
            expandedPackages = expandedPackages + focusPackage
        }
    }
    val canFreeze = state.privilege?.canFreeze == true
    val selectableVisible = visible.filter {
        SelectionRules.isSelectable(it, state.rootAvailable, canFreeze)
    }
    val selectedTargets = SelectionRules.selectedForApply(
        visible,
        selection,
        state.rootAvailable,
        canFreeze
    )
    val planCount = SelectionRules.planCount(state.catalog)
    val bootPlanEnabled = state.bootFreeze.enabled
    val listState = rememberLazyListState()
    LaunchedEffect(
        selectedFilter,
        focusPackage,
        query,
        selectableVisible.map { it.packageName }
    ) {
        selection = SelectionRules.prune(
            selection,
            selectableVisible.map { it.packageName }
        )
    }
    // Only a filter/search change should jump to the top. A strategy switch
    // can change `active` and restart the prune effect; scrolling then would
    // yank the list away from the card the user just edited.
    LaunchedEffect(selectedFilter, focusPackage, query) {
        listState.scrollToItem(0)
    }

    Column(modifier = modifier) {
        ApplicationsHeader(
            manageCount = state.catalog.size,
            restoreCount = state.catalog.count {
                it.hasFrozenState || it.applied
            },
            mode = 0,
            onModeChange = { if (it == 1) onOpenRestore() },
            onRefresh = callbacks.onCatalogRefresh,
            busy = state.busy,
            refreshing = state.catalogRefreshing
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        if (focusPackage != null) {
            item {
                SectionCard {
                    val focusedLabel = visible.firstOrNull()?.label
                        ?: focusPackage
                    Text(
                        "已定位：$focusedLabel",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        focusPackage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onClearFocus) {
                        Text("显示全部")
                    }
                }
            }
        }
        if (planCount > 0 || !state.rootAvailable) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (planCount > 0) {
                            StatusChip(
                                text = if (bootPlanEnabled) {
                                    "开机计划 $planCount"
                                } else {
                                    "开机计划 $planCount · 总开关关闭"
                                },
                                tone = if (bootPlanEnabled) {
                                    StatusChipTone.INFO
                                } else {
                                    StatusChipTone.NEUTRAL
                                }
                            )
                        }
                    }
                    if (!state.rootAvailable) {
                        val privilege = state.privilege
                        val text = when {
                            privilege == null -> "正在检查权限…"
                            privilege.canFreeze ->
                                "Shizuku：可暂停/冻结；组件/停用/隐藏需 Root。"
                            else -> "无可用权限：仅支持观察。"
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (privilege?.canFreeze == true) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }
        if (focusPackage == null) {
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
                        if (option.category != null && count == 0 &&
                            index != selectedFilter
                        ) {
                            return@forEachIndexed
                        }
                        NemuFilterChip(
                            selected = selectedFilter == index,
                            onClick = { selectedFilter = index },
                            label = {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
            }
            item {
                CatalogSearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索应用或包名",
                    batchExpanded = batchMode,
                    onBatchToggle = { batchMode = !batchMode },
                    busy = state.busy,
                    allSelected = selectableVisible.isNotEmpty() &&
                        selectableVisible.all { it.packageName in selection },
                    selectedCount = selectedTargets.size,
                    selectAllEnabled = selectableVisible.isNotEmpty(),
                    onSelectAll = {
                        selection = selection +
                            selectableVisible.map { it.packageName }
                    },
                    onClearAll = { selection = emptySet() }
                )
                state.lastCatalogResult?.let { lastResult ->
                    Text(
                        "上次结果：$lastResult",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (filter == CategoryFilter.CUSTOM && focusPackage == null) {
            item {
                Text(
                    "优先用“暂停”；停用/隐藏会让应用从桌面消失。\n" +
                        "不要添加桌面、设置、电话、输入法、权限控制器、Shizuku、" +
                        "Root 管理器或 Nemu 自己，可能无法开机或无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (visible.isEmpty()) {
            item {
                SectionCard(
                    title = when {
                        query.isNotBlank() -> "没有匹配的应用"
                        focusPackage != null -> "目录中没有此包"
                        else -> "没有目标"
                    }
                ) {
                    Text(
                        when {
                            query.isNotBlank() ->
                                "没有名称或包名包含“$query”的目标。"
                            focusPackage != null -> "包名：$focusPackage"
                            else -> "设备上没有该分类的软件包，或目录尚未加载。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    when {
                        query.isNotBlank() -> TextButton(
                            onClick = { query = "" }
                        ) { Text("清除搜索") }
                        focusPackage != null -> TextButton(
                            onClick = onClearFocus
                        ) { Text("显示全部") }
                    }
                }
            }
        }
        items(visible, key = { it.packageName }) { target ->
            FreezeTargetCard(
                target = target,
                busy = state.busy,
                rootAvailable = state.rootAvailable,
                canFreeze = canFreeze,
                bootPlanEnabled = bootPlanEnabled,
                batchMode = batchMode,
                expanded = target.packageName in expandedPackages,
                onToggleExpanded = {
                    expandedPackages = if (target.packageName in expandedPackages) {
                        expandedPackages - target.packageName
                    } else {
                        expandedPackages + target.packageName
                    }
                },
                selected = target.packageName in selection,
                onSelect = { checked ->
                    selection = if (checked) {
                        selection + target.packageName
                    } else {
                        selection - target.packageName
                    }
                },
                onPlanChange = { value ->
                    if (value && needsConfirm(target, target.preset)) {
                        pending = PendingAction(
                            target,
                            PendingKind.ENABLE,
                            target.preset
                        )
                    } else {
                        callbacks.onCatalogToggle(target.packageName, value)
                    }
                },
                onPreset = { preset ->
                    if (needsConfirm(target, preset)) {
                        pending = PendingAction(
                            target,
                            PendingKind.STRATEGY,
                            preset
                        )
                    } else {
                        callbacks.onCatalogPresetChange(
                            target.packageName,
                            preset
                        )
                    }
                },
                onFreeze = {
                    pending = PendingAction(
                        target,
                        PendingKind.FREEZE_ONE,
                        target.preset
                    )
                },
                onComponentsChange = { selected ->
                    callbacks.onCatalogComponentsChange(
                        target.packageName,
                        selected
                    )
                },
                onRemove = { callbacks.onCustomTargetRemove(target.packageName) }
            )
        }
    }
        if (batchMode && selectedTargets.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "已选 ${selectedTargets.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { showFreezeAll = true },
                        enabled = !state.busy
                    ) { Text("应用") }
                }
            }
        }
    }

    val action = pending
    if (action != null) {
        val preset = action.preset ?: action.target.preset
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    when (action.kind) {
                        PendingKind.FREEZE_ONE ->
                            "${TargetCopy.label(preset)}「${action.target.label}」？"
                        PendingKind.ENABLE ->
                            "把「${action.target.label}」加入开机计划？"
                        PendingKind.STRATEGY ->
                            "改用「${TargetCopy.label(preset)}」？"
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
                    TargetCopy.confirmLines(
                        target = action.target.target,
                        preset = preset,
                        enableOnly = action.kind == PendingKind.ENABLE,
                        dryRun = state.dryRun,
                        bootPlanEnabled = state.bootFreeze.enabled
                    ).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.startsWith("影响") &&
                                TargetCopy.riskWarning(
                                    action.target.target,
                                    preset
                                ) != null
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
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
                                callbacks.onCatalogPresetChange(
                                    action.target.packageName,
                                    preset
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
                            PendingKind.FREEZE_ONE ->
                                "确认${TargetCopy.label(preset)}"
                            PendingKind.ENABLE -> "加入计划"
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
            title = { Text("应用 ${selectedTargets.size} 个目标？") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedTargets.forEach { target ->
                        val warning = TargetCopy.riskWarning(
                            target.target,
                            target.preset
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${target.label} · " +
                                    TargetCopy.label(target.preset),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "影响：" + if (warning != null) {
                                    "$warning；" +
                                        TargetCopy.impactFor(
                                            target.target,
                                            target.preset
                                        )
                                } else {
                                    TargetCopy.impactFor(
                                        target.target,
                                        target.preset
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (warning != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    if (state.dryRun) {
                        Text(
                            "预览：只记录，不修改手机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "不会解冻其他应用；可在“恢复”分段撤销。",
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
                        selection = emptySet()
                        batchMode = false
                    }
                ) { Text("确认应用") }
            },
            dismissButton = {
                TextButton(onClick = { showFreezeAll = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FreezeTargetCard(
    target: CatalogTargetUiState,
    busy: Boolean,
    rootAvailable: Boolean,
    canFreeze: Boolean,
    bootPlanEnabled: Boolean,
    batchMode: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onPlanChange: (Boolean) -> Unit,
    onPreset: (StrategyPreset) -> Unit,
    onFreeze: () -> Unit,
    onComponentsChange: (Set<String>) -> Unit,
    onRemove: () -> Unit
) {
    var showComponents by remember { mutableStateOf(false) }
    val status = when {
        target.actual.processRunning == true &&
            target.actual.suspended == true ->
            if (target.preset == StrategyPreset.FREEZE) {
                "已冻结·进程仍在"
            } else {
                "已暂停·进程存活"
            }
        target.applied -> when (target.preset) {
            StrategyPreset.FREEZE -> "已冻结"
            StrategyPreset.PAUSE -> "已暂停"
            StrategyPreset.OBSERVE -> "已恢复"
            else -> "已应用"
        }
        target.appOpsOwned || target.standbyOwned -> "已限制"
        target.enabled -> "已加入计划"
        else -> target.actualText()
    }
    val statusTone = when {
        target.actual.processRunning == true &&
            target.actual.suspended == true -> StatusChipTone.WARNING
        target.applied -> StatusChipTone.SUCCESS
        target.appOpsOwned || target.standbyOwned -> StatusChipTone.SUCCESS
        target.enabled -> StatusChipTone.PRIMARY
        else -> actualTone(target)
    }
    // Only Nemu-owned state blocks a strategy switch. A package disabled or
    // suspended by another tool must not trap the card on "停用" forever.
    val hasFrozenState = target.applied ||
        target.ownedHidden ||
        target.ownedDisable ||
        target.appOpsOwned ||
        target.standbyOwned ||
        (target.strategy == FreezeStrategy.COMPONENT &&
            target.ownedComponents.isNotEmpty())
    val selectable = SelectionRules.isSelectable(
        target,
        rootAvailable,
        canFreeze
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.flClashCard()
        ),
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !batchMode || (!busy && selectable)
                    ) {
                        if (batchMode) {
                            // A disabled checkbox must also make the whole
                            // row inert; otherwise an observe/unavailable
                            // target can still enter the batch silently.
                            if (selectable) onSelect(!selected)
                        } else {
                            onToggleExpanded()
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (batchMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelect,
                        enabled = !busy && selectable,
                        colors = nemuCheckboxColors()
                    )
                }
                AppIcon(packageName = target.packageName, size = 38.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        target.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        target.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (target.risk == RiskLevel.RED) {
                    RiskChip(target.risk)
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onToggleExpanded() }
                        .padding(4.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusChip(text = status, tone = statusTone)
                StatusChip(
                    text = "策略 " + TargetCopy.label(target.preset),
                    tone = StatusChipTone.NEUTRAL
                )
                if (target.enabled) {
                    StatusChip(
                        text = if (bootPlanEnabled) {
                            "开机"
                        } else {
                            "开机（总开关关闭）"
                        },
                        tone = if (bootPlanEnabled) {
                            StatusChipTone.INFO
                        } else {
                            StatusChipTone.NEUTRAL
                        }
                    )
                }
            }
            if (expanded) {
                Text(
                    "软件功能：${TargetCopy.functionLine(target.packageName)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("策略", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TargetCopy.presetsFor(target.target).forEach { preset ->
                        val reason = TargetCopy.unavailableReason(
                            target.target,
                            preset,
                            rootAvailable
                        )
                        NemuFilterChip(
                            selected = target.preset == preset,
                            onClick = { onPreset(preset) },
                            enabled = !busy && reason == null &&
                                (!hasFrozenState || target.preset == preset),
                            label = { Text(TargetCopy.label(preset)) }
                        )
                    }
                }
                if (hasFrozenState) {
                    Text(
                        "已生效；切换策略前先在“恢复”页解冻。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val selectedReason = TargetCopy.unavailableReason(
                    target.target,
                    target.preset,
                    rootAvailable
                )
                Text(
                    if (selectedReason != null) {
                        "${TargetCopy.label(target.preset)}：不可用（$selectedReason）"
                    } else {
                        "${TargetCopy.label(target.preset)}：" +
                            TargetCopy.impactFor(target.target, target.preset)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedReason != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (selectedReason == null) {
                    val warning = TargetCopy.riskWarning(target.target, target.preset)
                    if (warning != null) {
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                val reasons = TargetCopy.presetsFor(target.target).mapNotNull { preset ->
                    if (preset == target.preset) return@mapNotNull null
                    TargetCopy.unavailableReason(
                        target.target,
                        preset,
                        rootAvailable
                    )?.let { "${TargetCopy.label(preset)}（$it）" }
                }
                if (reasons.isNotEmpty()) {
                    Text(
                        "不可用：" + reasons.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "开机计划",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NemuSwitch(
                        checked = target.enabled,
                        onCheckedChange = onPlanChange,
                        enabled = !busy && target.active &&
                            TargetCopy.unavailableReason(
                                target.target,
                                target.preset,
                                rootAvailable
                            ) == null,
                        modifier = Modifier.semantics {
                            contentDescription = "开机计划：${target.label}"
                        }
                    )
                }
                if (target.preset == StrategyPreset.COMPONENT &&
                    target.target.components.isNotEmpty()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "组件：已选 ${target.selectedComponents.size}/" +
                                target.target.components.size,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { showComponents = true },
                            enabled = !busy
                        ) { Text("选择") }
                    }
                }
                if (showComponents) {
                    ComponentPickerDialog(
                        target = target,
                        busy = busy,
                        onDismiss = { showComponents = false },
                        onConfirm = { selected ->
                            showComponents = false
                            onComponentsChange(selected)
                        }
                    )
                }
                if (!batchMode) {
                    if (target.preset == StrategyPreset.OBSERVE) {
                        Text(
                            "仅观察：不会执行冻结。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Button(
                            onClick = onFreeze,
                            enabled = !busy && canFreeze &&
                                TargetCopy.unavailableReason(
                                    target.target,
                                    target.preset,
                                    rootAvailable
                                ) == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (target.applied) "重新应用" else "应用")
                        }
                        if (!canFreeze) {
                            Text(
                                "需要先授权 Root 或 Shizuku 才能应用。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
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

@Composable
private fun ComponentPickerDialog(
    target: CatalogTargetUiState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val components = target.target.components
    var selected by remember(target.packageName, target.selectedComponents) {
        mutableStateOf(target.selectedComponents)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择组件 · ${target.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "已选 ${selected.size}/${components.size} · 只停用勾选组件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            selected = components.mapTo(mutableSetOf()) {
                                it.className
                            }
                        },
                        enabled = !busy
                    ) { Text("全选") }
                    TextButton(
                        onClick = { selected = emptySet() },
                        enabled = !busy && selected.isNotEmpty()
                    ) { Text("清空") }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(components, key = { it.className }) { component ->
                        val checked = component.className in selected
                        val state = target.componentStates[component.className]
                        val owned = component.className in target.ownedComponents
                        val stateText = when {
                            state == 2 || state == 3 || state == 4 ->
                                if (owned) "已停用（本应用）" else "已停用（他人）"
                            state == 1 -> "启用"
                            state == 0 -> "默认"
                            else -> "未知"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    selected = if (checked) {
                                        selected - component.className
                                    } else {
                                        selected + component.className
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { value ->
                                    selected = if (value) {
                                        selected + component.className
                                    } else {
                                        selected - component.className
                                    }
                                },
                                enabled = !busy,
                                colors = nemuCheckboxColors()
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    component.label,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    buildString {
                                        if (component.role.isNotBlank()) {
                                            append(component.role)
                                            append(" · ")
                                        }
                                        append(stateText)
                                        if (owned && !checked) append(" · 应用时恢复")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Text(
                    "只保存选择，不会立即改动手机。取消勾选的组件会在下次应用时恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selected.isEmpty()) {
                    Text(
                        "至少选择 1 个组件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = !busy && selected.isNotEmpty()
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Unfreeze page: show what is actually frozen and how it will be restored.
 */
@Composable
fun UnfreezeScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier,
    onOpenManage: () -> Unit = {}
) {
    val frozen = remember(state.catalog) {
        state.catalog.filter { it.hasFrozenState || it.applied }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val visibleFrozen = remember(frozen, query) {
        if (query.isBlank()) {
            frozen
        } else {
            frozen.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var batchMode by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var showSelected by remember { mutableStateOf(false) }
    LaunchedEffect(visibleFrozen.map { it.packageName }) {
        // Search/catalog changes must not leave an invisible package selected
        // in the restore action.
        selection = SelectionRules.prune(
            selection,
            visibleFrozen.map { it.packageName }
        )
    }

    Column(modifier = modifier) {
        ApplicationsHeader(
            manageCount = state.catalog.size,
            restoreCount = frozen.size,
            mode = 1,
            onModeChange = { if (it == 0) onOpenManage() },
            onRefresh = callbacks.onCatalogRefresh,
            busy = state.busy,
            refreshing = state.catalogRefreshing
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        item {
            CatalogSearchRow(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索已冻结应用",
                batchExpanded = batchMode,
                onBatchToggle = { batchMode = !batchMode },
                busy = state.busy,
                allSelected = visibleFrozen.isNotEmpty() &&
                    visibleFrozen.all { it.packageName in selection },
                selectedCount = selection.size,
                selectAllEnabled = visibleFrozen.isNotEmpty(),
                onSelectAll = {
                    selection = visibleFrozen.mapTo(mutableSetOf()) {
                        it.packageName
                    }
                },
                onClearAll = { selection = emptySet() }
            )
        }
        if (frozen.isEmpty()) {
            item {
                SectionCard(title = "当前没有冻结的应用") {
                    Text(
                        "没有检测到暂停、停用、隐藏、组件停用或后台限制的目标。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.lastCatalogResult != null) {
                        Text(
                            "上次结果：${state.lastCatalogResult}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    NemuOutlinedButton(
                        onClick = callbacks.onCatalogRefresh,
                        enabled = !state.busy && !state.catalogRefreshing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.catalogRefreshing) {
                                "刷新中…"
                            } else {
                                "刷新实际状态"
                            }
                        )
                    }
                }
            }
        } else if (visibleFrozen.isEmpty()) {
            item {
                SectionCard(title = "没有匹配的冻结应用") {
                    Text(
                        "没有名称或包名包含“$query”的冻结目标。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { query = "" }) {
                        Text("清除搜索")
                    }
                }
            }
        } else {
        item {
            SectionCard(title = "恢复范围") {
                if (state.lastCatalogResult != null) {
                    Text(
                        "上次结果：${state.lastCatalogResult}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.dryRun) {
                        Text(
                            "预览模式：恢复只记录命令，不改变实际状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
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
                NemuOutlinedButton(
                    onClick = { showAll = true },
                    enabled = !state.busy && frozen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("临时全部解冻（保留开机冻结计划）")
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
        }
        items(visibleFrozen, key = { it.packageName }) { target ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.flClashCard()
                ),
                shape = MaterialTheme.shapes.small,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                val rowSelected = target.packageName in selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.busy) {
                            selection = if (rowSelected) {
                                selection - target.packageName
                            } else {
                                selection + target.packageName
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = rowSelected,
                        onCheckedChange = { checked ->
                            selection = if (checked) {
                                selection + target.packageName
                            } else {
                                selection - target.packageName
                            }
                        },
                        enabled = !state.busy,
                        colors = nemuCheckboxColors()
                    )
                    AppIcon(packageName = target.packageName, size = 38.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            target.label,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            target.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "恢复：${TargetCopy.restoreLine(target.preset)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (target.risk == RiskLevel.RED) {
                            RiskChip(target.risk)
                        }
                        StatusChip(
                            text = target.actualText(),
                            tone = actualTone(target)
                        )
                    }
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
                val selectedRows = state.catalog.filter {
                    it.packageName in selection
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (state.dryRun) {
                            "预览：只记录恢复命令，不会真正解冻，也不会关闭冻结计划。"
                        } else {
                            "将恢复所选应用；本次开机不再自动冻结。" +
                                "只处理 Nemu 拥有所有权的状态，他人暂停不会接管。" +
                                "已恢复且不再有所有权的目标会关闭计划；" +
                                "若应用曾被强制停止，首次打开后会恢复后台。"
                        }
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        selectedRows.forEach { row ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    row.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (row.risk == RiskLevel.RED) {
                                    RiskChip(row.risk)
                                }
                            }
                        }
                    }
                }
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
                        "将取消所有由 Nemu 暂停的应用、恢复由 Nemu 停用/隐藏的应用；" +
                            "其他工具暂停的应用不会接管。并跳过本次开机自动冻结，" +
                            "冻结计划会保留，下次开机会重新冻结。"
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

private fun needsConfirm(
    target: CatalogTargetUiState,
    preset: StrategyPreset
): Boolean {
    val strategy = TargetCopy.baseStrategy(preset)
    return strategy == FreezeStrategy.DISABLE_USER ||
        strategy == FreezeStrategy.HIDE ||
        (target.risk == RiskLevel.RED &&
            (strategy == FreezeStrategy.COMPONENT ||
                strategy == FreezeStrategy.SUSPEND)) ||
        TargetCopy.riskWarning(target.target, preset) != null
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
