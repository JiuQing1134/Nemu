package com.nemu.hibernate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.nemu.hibernate.ui.theme.nemuCard
import com.nemu.hibernate.ui.theme.nemuInset
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
    // Restore is a sub-page of Applications: back returns to Manage instead
    // of leaving the app.
    BackHandler(enabled = mode != 0) { onModeChange(0) }
    Column(modifier = modifier.fillMaxSize()) {
        if (mode == 0) {
            FreezeScreen(
                state,
                callbacks,
                Modifier.weight(1f),
                focusPackage = focusPackage,
                onClearFocus = onClearFocus
            )
        } else {
            UnfreezeScreen(
                state,
                callbacks,
                Modifier.weight(1f)
            )
        }
    }
}

/**
 * Shared applications header: mode chips plus a green text refresh action.
 */
/**
 * Pinned search row with 全选 on the right. There is no batch gate; the
 * checkbox is always present, and the selected-count pill clears the set.
 */
@Composable
internal fun CatalogSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    busy: Boolean,
    allSelected: Boolean,
    selectedCount: Int,
    selectAllEnabled: Boolean,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        // Requested order: 搜索框 → 全选 → 已选 N. Reserve the count slot
        // even at 0 so selecting the first app never narrows the search field
        // or moves 全选; the count only grows inside its fixed slot.
        SelectAllPill(
            checked = allSelected,
            enabled = !busy && selectAllEnabled,
            onToggle = {
                if (allSelected) onClearAll() else onSelectAll()
            }
        )
        Spacer(Modifier.width(6.dp))
        // Fixed slot, compact pill, LEFT-anchored: 9→10 grows only to the
        // right. The slot reserves max width so search/全选 never move; a
        // 1-2 digit count leaves empty slot space on its right.
        Box(
            modifier = Modifier.width(76.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SelectedCountPill(
                count = selectedCount,
                enabled = !busy,
                onClick = onClearAll
            )
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
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.nemuInset()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
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
                        .noRippleClickable { onValueChange("") }
                        .padding(start = 8.dp)
                )
            }
        }
    }
}

private fun sortCatalogTargets(
    targets: List<CatalogTargetUiState>,
    field: AppSortField,
    nameAscending: Boolean,
    timeAscending: Boolean
): List<CatalogTargetUiState> = when (field) {
    AppSortField.NAME -> {
        val comparator = if (nameAscending) {
            compareBy<CatalogTargetUiState> { it.label.lowercase() }
                .thenBy { it.packageName }
        } else {
            compareByDescending<CatalogTargetUiState> { it.label.lowercase() }
                .thenBy { it.packageName }
        }
        targets.sortedWith(comparator)
    }
    AppSortField.INSTALL -> {
        val comparator = if (timeAscending) {
            compareBy<CatalogTargetUiState> { it.installedAt }
                .thenBy { it.label.lowercase() }
        } else {
            compareByDescending<CatalogTargetUiState> { it.installedAt }
                .thenBy { it.label.lowercase() }
        }
        targets.sortedWith(comparator)
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
    onClearFocus: () -> Unit = {}
) {
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortFieldIndex by rememberSaveable { mutableIntStateOf(0) }
    var nameAscending by rememberSaveable { mutableStateOf(true) }
    var timeAscending by rememberSaveable { mutableStateOf(false) }
    val sortField = AppSortField.entries.getOrElse(sortFieldIndex) {
        AppSortField.NAME
    }
    val filter = CategoryFilter.entries.getOrElse(selectedFilter) { CategoryFilter.ALL }
    val baseVisible = remember(state.catalog, filter) {
        if (filter.category == null) state.catalog
        else state.catalog.filter { it.category == filter.category }
    }
    val visible = remember(
        baseVisible,
        state.catalog,
        focusPackage,
        query,
        sortField,
        nameAscending,
        timeAscending
    ) {
        val focused = if (focusPackage == null) {
            baseVisible
        } else {
            state.catalog.filter { it.packageName == focusPackage }
        }
        // A deep link must not be hidden by a search query saved from an
        // earlier visit to this tab.
        val searched = if (focusPackage != null || query.isBlank()) {
            focused
        } else {
            focused.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        sortCatalogTargets(
            searched,
            field = sortField,
            nameAscending = nameAscending,
            timeAscending = timeAscending
        )
    }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showFreezeAll by remember { mutableStateOf(false) }
    // Selection is always available; there is no separate batch mode. The
    // checkbox handles selection while the card body still expands details.
    var selection by remember { mutableStateOf(emptySet<String>()) }
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
    // yank the list away from the card the user just edited. Remember the last
    // key so returning to the tab keeps its restored scroll position.
    val filterKey = "$selectedFilter|${focusPackage.orEmpty()}|$query|" +
        "${sortField.name}|$nameAscending|$timeAscending"
    var lastFilterKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(filterKey) {
        if (lastFilterKey != filterKey) {
            listState.scrollToItem(0)
            lastFilterKey = filterKey
        }
    }

    // Back steps out of the current UI state: deep-link focus → search →
    // category → selection → then normal navigation/exit.
    BackHandler(
        enabled = focusPackage != null ||
            query.isNotBlank() ||
            selectedFilter != 0 ||
            selection.isNotEmpty()
    ) {
        when {
            focusPackage != null -> onClearFocus()
            query.isNotBlank() -> query = ""
            selectedFilter != 0 -> selectedFilter = 0
            else -> selection = emptySet()
        }
    }

    Column(modifier = modifier) {
        // Pinned so 全选/已选 stays in the top-right corner at any scroll
        // position; a long catalog made the in-list control unreachable.
        if (focusPackage == null) {
            CatalogSearchRow(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索应用或包名",
                busy = state.busy,
                allSelected = selectableVisible.isNotEmpty() &&
                    selectableVisible.all { it.packageName in selection },
                selectedCount = selectedTargets.size,
                selectAllEnabled = selectableVisible.isNotEmpty(),
                onSelectAll = {
                    selection = selection +
                        selectableVisible.map { it.packageName }
                },
                onClearAll = { selection = emptySet() },
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 4.dp
                )
            )
            // The category row is part of the pinned header for the same
            // reason as search: switching filters must not require scrolling
            // a long catalog back to the top.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
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
                Spacer(Modifier.width(8.dp))
                AppSortButton(
                    field = sortField,
                    nameAscending = nameAscending,
                    timeAscending = timeAscending,
                    onNameClick = {
                        if (sortField == AppSortField.NAME) {
                            nameAscending = !nameAscending
                        } else {
                            sortFieldIndex = AppSortField.NAME.ordinal
                        }
                    },
                    onTimeClick = {
                        if (sortField == AppSortField.INSTALL) {
                            timeAscending = !timeAscending
                        } else {
                            sortFieldIndex = AppSortField.INSTALL.ordinal
                        }
                    }
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
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
                    TextButton(
                        onClick = {
                            query = ""
                            selectedFilter = 0
                            onClearFocus()
                        }
                    ) {
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
                                    "计划 $planCount"
                                } else {
                                    "计划 $planCount · 未启用"
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
            state.lastCatalogResult?.let { lastResult ->
                item {
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
                            onClick = {
                                query = ""
                                selectedFilter = 0
                                onClearFocus()
                            }
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
                    if (target.ownsAnyState || needsConfirm(target, preset)) {
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
        FastScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
        )
        }
        if (selectedTargets.isNotEmpty()) {
            SelectionActionBar(
                selectedCount = selectedTargets.size,
                actionLabel = "应用 ${selectedTargets.size} 个",
                enabled = !state.busy,
                onClear = { selection = emptySet() },
                onAction = { showFreezeAll = true }
            )
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
                    if (action.kind == PendingKind.STRATEGY &&
                        action.target.ownsAnyState
                    ) {
                        Text(
                            "会先撤销「" +
                                TargetCopy.label(action.target.preset) +
                                "」的当前状态，再应用「" +
                                TargetCopy.label(preset) + "」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                            "不会解冻其他应用；可在“解冻”分段撤销。",
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
                    }
                ) { Text("确认应用") }
            },
            dismissButton = {
                TextButton(onClick = { showFreezeAll = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreezeTargetCard(
    target: CatalogTargetUiState,
    busy: Boolean,
    rootAvailable: Boolean,
    canFreeze: Boolean,
    bootPlanEnabled: Boolean,
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
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val cardPress = remember { MutableInteractionSource() }
    val status = when {
        target.actual.processRunning == true &&
            target.actual.suspended == true ->
            "已${target.suspendWord()}·进程存活"
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
    // Only Nemu-owned state matters for a strategy switch. A package disabled
    // or suspended by another tool must not trap the card on "停用" forever.
    val selectable = SelectionRules.isSelectable(
        target,
        rootAvailable,
        canFreeze
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .nemuPressFeedback(cardPress, NemuPressSpec.Card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.nemuCard()
        ),
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = cardPress,
                    indication = null
                ) { onToggleExpanded() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Always visible: selection no longer hides behind a batch
                // toggle. A disabled checkbox simply cannot enter a batch.
                NemuSmallCheckbox(
                    checked = selected,
                    enabled = !busy && selectable,
                    onCheckedChange = onSelect,
                    contentDescription = "选择 ${target.label}",
                    touchSize = 32.dp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                AppIcon(packageName = target.packageName, size = 38.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Collapsed: name only (or the package itself when the
                    // app has no label). Expanded: the full name and the full
                    // package, allowed to wrap instead of a mid-word ellipsis.
                    val nameIsPackage =
                        target.label == target.packageName
                    Text(
                        target.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 13.sp
                        ),
                        maxLines = if (expanded || nameIsPackage) {
                            Int.MAX_VALUE
                        } else {
                            2
                        },
                        overflow = TextOverflow.Ellipsis
                    )
                    if (expanded && !nameIsPackage) {
                        Text(
                            target.packageName,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (target.risk == RiskLevel.RED) {
                    RiskChip(target.risk)
                }
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
                            "开机 · 未启用"
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
                            enabled = !busy && reason == null,
                            label = { Text(TargetCopy.label(preset)) }
                        )
                    }
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
                        // Turning a plan OFF never needs Root or an available
                        // strategy; only turning it ON does.
                        enabled = !busy && (
                            target.enabled ||
                                (target.active &&
                                    TargetCopy.unavailableReason(
                                        target.target,
                                        target.preset,
                                        rootAvailable
                                    ) == null)
                            ),
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
                if (target.preset == StrategyPreset.OBSERVE) {
                        Text(
                            "仅观察：不会执行冻结。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        NemuFilledButton(
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
                if (target.category == TargetCategory.AUTOMATION) {
                    if (target.hasFrozenState) {
                        Text(
                            "先解冻，才能移除这个自定义目标。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        TextButton(
                            onClick = { showRemoveConfirm = true },
                            enabled = !busy
                        ) {
                            Text("移除自定义目标")
                        }
                        if (showRemoveConfirm) {
                            AlertDialog(
                                onDismissRequest = {
                                    showRemoveConfirm = false
                                },
                                title = { Text("移除自定义目标？") },
                                text = {
                                    Text(
                                        "将删除「${target.label}」以及保存的" +
                                            "策略和开机计划；不会改变手机上" +
                                            "任何应用状态。"
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showRemoveConfirm = false
                                            onRemove()
                                        }
                                    ) { Text("移除") }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showRemoveConfirm = false
                                        }
                                    ) { Text("取消") }
                                }
                            )
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
    modifier: Modifier
) {
    val frozen = remember(state.catalog) {
        state.catalog.filter { it.hasFrozenState || it.applied }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sortFieldIndex by rememberSaveable { mutableIntStateOf(0) }
    var nameAscending by rememberSaveable { mutableStateOf(true) }
    var timeAscending by rememberSaveable { mutableStateOf(false) }
    val sortField = AppSortField.entries.getOrElse(sortFieldIndex) {
        AppSortField.NAME
    }
    val listState = rememberLazyListState()
    val visibleFrozen = remember(
        frozen,
        query,
        sortField,
        nameAscending,
        timeAscending
    ) {
        val searched = if (query.isBlank()) {
            frozen
        } else {
            frozen.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        sortCatalogTargets(
            searched,
            field = sortField,
            nameAscending = nameAscending,
            timeAscending = timeAscending
        )
    }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var showAll by remember { mutableStateOf(false) }
    var showSelected by remember { mutableStateOf(false) }
    // Rows suspended/hidden by another tool are shown for diagnosis but can
    // never be selected: Nemu has no ownership evidence to restore them.
    val selectableFrozen = remember(visibleFrozen) {
        visibleFrozen.filter { it.ownsAnyState }
    }
    LaunchedEffect(selectableFrozen.map { it.packageName }) {
        // Search/catalog changes must not leave an invisible package selected
        // in the restore action.
        selection = SelectionRules.prune(
            selection,
            selectableFrozen.map { it.packageName }
        )
    }

    // Back clears search, then the session selection, before the outer
    // Applications BackHandler returns to the Freeze page.
    BackHandler(enabled = query.isNotBlank() || selection.isNotEmpty()) {
        if (query.isNotBlank()) {
            query = ""
        } else {
            selection = emptySet()
        }
    }

    Column(modifier = modifier) {
        // Pinned selection controls for the same reason as the Freeze page.
        CatalogSearchRow(
            query = query,
            onQueryChange = { query = it },
            placeholder = "搜索已冻结应用",
            busy = state.busy,
            allSelected = selectableFrozen.isNotEmpty() &&
                selectableFrozen.all { it.packageName in selection },
            selectedCount = selection.size,
            selectAllEnabled = selectableFrozen.isNotEmpty(),
            onSelectAll = {
                selection = selectableFrozen.mapTo(mutableSetOf()) {
                    it.packageName
                }
            },
            onClearAll = { selection = emptySet() },
            modifier = Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 4.dp
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            AppSortButton(
                field = sortField,
                nameAscending = nameAscending,
                timeAscending = timeAscending,
                onNameClick = {
                    if (sortField == AppSortField.NAME) {
                        nameAscending = !nameAscending
                    } else {
                        sortFieldIndex = AppSortField.NAME.ordinal
                    }
                },
                onTimeClick = {
                    if (sortField == AppSortField.INSTALL) {
                        timeAscending = !timeAscending
                    } else {
                        sortFieldIndex = AppSortField.INSTALL.ordinal
                    }
                }
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            SectionCard(title = "解冻范围") {
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
                            "预览模式：解冻只记录命令，不改变实际状态；" +
                                "开机/关机安全解冻仍会执行。",
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
                NemuFilledButton(
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
                if (visibleFrozen.size > selectableFrozen.size) {
                    Text(
                        "标为“他人暂停/他人隐藏”的行只读显示；" +
                            "它们不是本应用冻结的，不能在这里解冻。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
        items(visibleFrozen, key = { it.packageName }) { target ->
            val cardPress = remember { MutableInteractionSource() }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .nemuPressFeedback(cardPress, NemuPressSpec.Card),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.nemuCard()
                ),
                shape = MaterialTheme.shapes.small,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                val selectable = target.ownsAnyState
                val rowSelected = selectable &&
                    target.packageName in selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = !state.busy && selectable,
                            interactionSource = cardPress,
                            indication = null
                        ) {
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
                            if (selectable) {
                                selection = if (checked) {
                                    selection + target.packageName
                                } else {
                                    selection - target.packageName
                                }
                            }
                        },
                        enabled = !state.busy && selectable,
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
                        if (!selectable) {
                            StatusChip(
                                text = "只读",
                                tone = StatusChipTone.NEUTRAL
                            )
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
        FastScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
        )
        }
        // Sticky action bar: the same button exists at the top of the list,
        // but after selecting rows far down it would be off-screen.
        if (selection.isNotEmpty()) {
            SelectionActionBar(
                selectedCount = selection.size,
                actionLabel = "解冻所选",
                enabled = !state.busy,
                onClear = { selection = emptySet() },
                onAction = { showSelected = true }
            )
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
                            "预览：只记录解冻命令，不会真正解冻，也不会关闭冻结计划。"
                        } else {
                            "将解冻所选应用；本次开机不再自动冻结。" +
                                "只处理 Nemu 拥有所有权的状态，他人暂停不会接管。" +
                                "已解冻且不再有所有权的目标会关闭计划；" +
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
