package com.nemu.hibernate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.core.BootStatusCopy
import com.nemu.hibernate.core.DeepUninstallUiState
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.data.LogEntry
import com.nemu.hibernate.privilege.PrivilegeOverview
import com.nemu.hibernate.ui.theme.ThemeMode
import com.nemu.hibernate.ui.theme.ThemePalette
import com.nemu.hibernate.ui.theme.ThemeStyle
import com.nemu.hibernate.ui.theme.nemuBlock
import com.nemu.hibernate.ui.theme.nemuCard
import com.nemu.hibernate.ui.theme.nemuFloating
import com.nemu.hibernate.ui.theme.nemuInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 底部导航的 5 个目的地。APPLICATIONS 是功能入口（冻结管理、解冻恢复、
 * 开机冻结、深度卸载）；ALL_APPS 是完整的已安装应用列表。
 */
enum class DashboardTab(val label: String) {
    OVERVIEW("概览"),
    APPLICATIONS("功能"),
    ALL_APPS("全部应用"),
    DETECTION("检测"),
    SETTINGS("设置")
}

/** 目标在系统中的实际状态；null 表示尚未读取 / 未知。 */
data class TargetActualUiState(
    val suspended: Boolean? = null,
    val packageEnabled: Boolean? = null,
    val stopped: Boolean? = null,
    val processRunning: Boolean? = null,
    /** Hidden through `pm hide`; PackageManager may not list it at all. */
    val hidden: Boolean? = null,
    /**
     * PackageManager can still resolve the package. False after a deep
     * uninstall (`pm uninstall --user 0`); null when the state was not read.
     */
    val present: Boolean? = null
)

/** 开机自动冻结的只读展示状态（设置键由 BootFreezeSettings 持有）。 */
data class BootFreezeUiState(
    val enabled: Boolean = true,
    val delaySeconds: Int = 120,
    val bootCount: Int? = null,
    val resultBootCount: Int = -1,
    val lastResult: String? = null,
    val lastSucceeded: Boolean = false,
    val lastSkippedReason: String? = null,
    val lastAttemptText: String? = null,
    val lastSuccessText: String? = null,
    /** Targets the user explicitly enabled; 0 means boot freeze is a no-op. */
    val plannedCount: Int = 0,
    /** The boot-freeze window for this boot was observed and already passed. */
    val windowMissed: Boolean = false
)

/** 日志页所需的只读状态（文件由 Logging agent 的 PersistentLogger 持有）。 */
data class LogUiState(
    val enabled: Boolean = false,
    val filePath: String? = null,
    val fileSizeBytes: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
    val fileLines: List<String> = emptyList()
)

/** 批量操作结果条的语义色：成功用主题色，跳过/失败用错误色。 */
enum class BatchFeedbackTone { SUCCESS, WARNING, ERROR, NEUTRAL }

/**
 * 一次批量操作的常驻结果。比 Toast 更容易回看，也承载“撤销上次计划变更”
 * 与完整跳过清单；[undoAvailable] 只在开机冻结批量操作后为 true。
 */
data class BatchFeedbackUi(
    val title: String,
    val detail: String? = null,
    val skipped: List<String> = emptyList(),
    val tone: BatchFeedbackTone = BatchFeedbackTone.NEUTRAL,
    val undoAvailable: Boolean = false
)

/**
 * 仪表盘渲染所需的全部状态。此类型不持有 controller，也不写设置；
 * caller 负责读取 Settings / BootFreezeSettings / Logger 后组装。
 */
data class DashboardState(
    val backendStatus: String = "检测中…",
    val busy: Boolean = false,
    val dryRun: Boolean = true,
    val lastShutdownResult: String = "尚未执行",
    val lastBootResult: String = "尚未执行",
    val bootFreeze: BootFreezeUiState = BootFreezeUiState(),
    val cota: CotaUiState,
    val logs: List<LogEntry> = emptyList(),
    val logSettings: LogUiState = LogUiState(),
    val rescueMode: Boolean = false,
    val catalog: List<CatalogTargetUiState> = emptyList(),
    val lastCatalogResult: String? = null,
    /** A forced actual-state read is running; the header shows 刷新中… */
    val catalogRefreshing: Boolean = false,
    /** Root is required for component ownership markers and whole-package disable. */
    val rootAvailable: Boolean = false,
    /** Plain-language authorization snapshot; null while the first read runs. */
    val privilege: PrivilegeOverview? = null,
    val themeStyle: ThemeStyle = ThemeStyle.VIVID,
    val themePalette: ThemePalette = ThemePalette.BLUE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Last non-empty batch selection, for the “上次选择” restore chip. */
    val lastFreezeSelection: Set<String> = emptySet(),
    val lastUnfreezeSelection: Set<String> = emptySet(),
    /** Last batch actually confirmed for freezing, for “再次冻结”. */
    val lastAppliedFreezeSelection: Set<String> = emptySet(),
    /** Persistent result of the last batch operation on the Applications tab. */
    val batchFeedback: BatchFeedbackUi? = null,
    /** 深度卸载分段：应用清单、备份状态与一次性操作反馈。 */
    val deepUninstall: DeepUninstallUiState = DeepUninstallUiState()
)

/**
 * 仪表盘只调用这些回调；业务逻辑仍留在 MainActivity/controllers。
 * 可选能力（开机冻结/日志/外观）提供默认空实现，避免 UI 反向持有控制器。
 */
data class DashboardCallbacks(
    val onDryRunChange: (Boolean) -> Unit,
    val onOpenStartupSettings: () -> Unit,
    val cotaCallbacks: CotaCallbacks,
    val onBootFreezeEnabledChange: (Boolean) -> Unit = {},
    val onLoggingEnabledChange: (Boolean) -> Unit = {},
    val onRefreshLogs: () -> Unit = {},
    val onCaptureLogcat: () -> Unit = {},
    val onClearLogs: () -> Unit = {},
    val onClearRescue: () -> Unit = {},
    val onTabSelected: (DashboardTab) -> Unit = {},
    val onCatalogToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onCatalogPlanBatch: (Set<String>, Boolean) -> Unit = { _, _ -> },
    /** Close the persistent batch result bar (also drops the plan undo). */
    val onDismissBatchFeedback: () -> Unit = {},
    /** Restore the plan enabled-flags recorded by the last plan batch. */
    val onUndoPlanBatch: () -> Unit = {},
    val onCatalogPresetChange: (String, StrategyPreset) -> Unit = { _, _ -> },
    val onCatalogComponentsChange: (String, Set<String>) -> Unit = { _, _ -> },
    /** second arg = remember this batch as “再次冻结上次选择”. */
    val onCatalogApply: (Set<String>, Boolean) -> Unit = { _, _ -> },
    /** second arg = keep the boot plan after a temporary unfreeze. */
    val onCatalogRestore: (Set<String>, Boolean) -> Unit = { _, _ -> },
    val onCatalogRestoreAll: () -> Unit = {},
    val onCatalogSelectionRemember: (Boolean, Set<String>) -> Unit =
        { _, _ -> },
    val onCatalogRefresh: () -> Unit = {},
    val onCustomTargetAdd:
        (String, String, RiskLevel, StrategyPreset, Boolean) -> Unit =
        { _, _, _, _, _ -> },
    val onCustomTargetRemove: (String) -> Unit = {},
    val onAuthorizeRoot: () -> Unit = {},
    val onAuthorizeShizuku: () -> Unit = {},
    val onRefreshPrivilege: () -> Unit = {},
    val onThemeStyleChange: (ThemeStyle) -> Unit = {},
    val onThemePaletteChange: (ThemePalette) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {},
    /** 深度卸载分段：重新读取已安装应用与 APK 备份。 */
    val onDeepUninstallRefresh: () -> Unit = {},
    /** 先备份 APK，再为用户卸载所选应用。 */
    val onDeepUninstallBackupAndUninstall: (Set<String>) -> Unit = { _ -> },
    /** 从 APK 备份重新安装所选应用。 */
    val onDeepUninstallRestore: (Set<String>) -> Unit = { _ -> },
    /** 关闭深度卸载页的提示条。 */
    val onDeepUninstallMessageDismiss: () -> Unit = {}
)

/**
 * Nemu 布局：Scaffold + TopAppBar + 5 项底部导航。
 *
 * 页面内容全部来自 [DashboardState]，操作只通过 [DashboardCallbacks] 触发。
 * 检测页可注入完整内容；其余页面使用内置 section。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier,
    initialTab: DashboardTab = DashboardTab.OVERVIEW,
    /** Deep link from detection: switch to Applications and focus this package. */
    focusPackage: String? = null,
    onClearFocus: () -> Unit = {},
    detectionContent: @Composable ColumnScope.() -> Unit
) {
    var tabNavigation by rememberSaveable(stateSaver = TabNavigation.Saver) {
        mutableStateOf(TabNavigation.initial(initialTab.ordinal))
    }
    val selectedTab = DashboardTab.entries.getOrElse(
        tabNavigation.selected
    ) {
        DashboardTab.OVERVIEW
    }
    // Hoisted: a deep link (detection, add-custom-target) must land on the
    // manage page even if the user last left Applications in restore mode.
    var applicationsMode by rememberSaveable {
        mutableIntStateOf(APP_PAGE_HUB)
    }
    // Incremented when the overview plan chip asks the freeze page to show
    // the “开机冻结” filter. A counter (not a boolean) lets repeated taps
    // re-apply the filter after the user has changed it.
    var planFilterRequest by rememberSaveable { mutableIntStateOf(0) }
    // “Go configure the apps I just added”: carry the package set into the
    // freeze page and preselect it there.
    var selectionRequest by remember { mutableIntStateOf(0) }
    var selectionRequestPackages by remember {
        mutableStateOf(emptySet<String>())
    }
    // Hoisted so a deep link out of a Settings sub-page can return to that
    // exact sub-page instead of the root list.
    var settingsPage by rememberSaveable { mutableIntStateOf(SETTINGS_ROOT) }
    val tabStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(focusPackage) {
        if (focusPackage != null) {
            tabNavigation = tabNavigation.openProgrammatic(
                DashboardTab.APPLICATIONS.ordinal
            )
            applicationsMode = APP_PAGE_FREEZE
        }
    }
    BackHandler(enabled = tabNavigation.backStack.isNotEmpty()) {
        tabNavigation.back()?.let { target ->
            tabNavigation = target
            // The focus deep link belongs to that Applications visit only.
            onClearFocus()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            DashboardTopBar(
                state = state,
                tab = selectedTab,
                applicationsMode = applicationsMode,
                onBackToHub = {
                    applicationsMode = APP_PAGE_HUB
                    onClearFocus()
                },
                onRefreshApplications = {
                    if (applicationsMode == APP_PAGE_DEEP_UNINSTALL) {
                        callbacks.onDeepUninstallRefresh()
                    } else {
                        callbacks.onCatalogRefresh()
                    }
                }
            )
        },
        bottomBar = {
            DashboardNavigationBar(selectedTab) { tab ->
                if (tab == selectedTab) {
                    // Re-tapping the current tab returns it to its root page
                    // without discarding a deep-link return path.
                    when (tab) {
                        DashboardTab.APPLICATIONS -> {
                            applicationsMode = APP_PAGE_HUB
                            // Re-tapping Applications returns to the hub and
                            // leaves the one-app deep-link view.
                            onClearFocus()
                        }
                        DashboardTab.SETTINGS -> settingsPage = SETTINGS_ROOT
                        else -> Unit
                    }
                } else {
                    tabNavigation = tabNavigation.selectDirect(tab.ordinal)
                    when (tab) {
                        // Bottom navigation is top-level: both sub-pages
                        // start at their root when entered from another tab.
                        DashboardTab.APPLICATIONS -> applicationsMode = APP_PAGE_HUB
                        DashboardTab.SETTINGS -> settingsPage = SETTINGS_ROOT
                        else -> Unit
                    }
                    onClearFocus()
                }
                callbacks.onTabSelected(tab)
            }
        }
    ) { padding ->
        val pageModifier = Modifier
            .padding(padding)
            .fillMaxSize()
            // targetSdk 36 is edge-to-edge; without this the IME covers the
            // bottom selection bar and the list's lower rows.
            .imePadding()
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
        when (selectedTab) {
            DashboardTab.OVERVIEW -> OverviewPage(
                state = state,
                callbacks = callbacks,
                modifier = pageModifier,
                onOpenPlan = {
                    tabNavigation = tabNavigation.openProgrammatic(
                        DashboardTab.APPLICATIONS.ordinal
                    )
                    applicationsMode = APP_PAGE_FREEZE
                    planFilterRequest += 1
                    callbacks.onTabSelected(DashboardTab.APPLICATIONS)
                }
            )
            DashboardTab.APPLICATIONS -> Column(modifier = pageModifier) {
                // Persistent batch result above the app list. weight(1f)
                // gives the list every remaining dp; the bar itself is
                // intrinsic-height and never squeezes the page to nothing.
                state.batchFeedback?.let { feedback ->
                    BatchFeedbackBar(
                        feedback = feedback,
                        onDismiss = callbacks.onDismissBatchFeedback,
                        onUndo = callbacks.onUndoPlanBatch
                    )
                }
                ApplicationsScreen(
                    state = state,
                    callbacks = callbacks,
                    modifier = Modifier.weight(1f),
                    mode = applicationsMode,
                    onModeChange = { applicationsMode = it },
                    onOpenPlan = {
                        planFilterRequest += 1
                        applicationsMode = APP_PAGE_FREEZE
                    },
                    focusPackage = focusPackage,
                    onClearFocus = onClearFocus,
                    planFilterRequest = planFilterRequest,
                    selectionRequest = selectionRequest,
                    selectionRequestPackages = selectionRequestPackages
                )
            }
            DashboardTab.ALL_APPS -> AllAppsScreen(
                state = state,
                callbacks = callbacks,
                modifier = pageModifier,
                onOpenApplications = { packages ->
                    selectionRequestPackages = packages
                    selectionRequest += 1
                    tabNavigation = tabNavigation.openProgrammatic(
                        DashboardTab.APPLICATIONS.ordinal
                    )
                    applicationsMode = APP_PAGE_FREEZE
                    onClearFocus()
                    callbacks.onTabSelected(DashboardTab.APPLICATIONS)
                }
            )
            DashboardTab.DETECTION -> DetectionPage(
                detectionContent = detectionContent,
                modifier = pageModifier
            )
            DashboardTab.SETTINGS -> SettingsPage(
                state = state,
                callbacks = callbacks,
                onOpenOverview = {
                    tabNavigation = tabNavigation.openProgrammatic(
                        DashboardTab.OVERVIEW.ordinal
                    )
                },
                pageIndex = settingsPage,
                onPageChange = { settingsPage = it },
                modifier = pageModifier
            )
        }
        }
    }
}

/** Backend mode shown immediately before the Root/Shizuku status chip. */
internal fun topBarBackendModeLabel(backendStatus: String): String {
    val text = backendStatus.trim()
    return when {
        text.startsWith("Root", ignoreCase = true) -> "授权：Root"
        text.contains("Shizuku", ignoreCase = true) &&
            !text.contains("未授权") -> "授权：Shizuku"
        text.contains("检测中") -> "授权：检测中"
        else -> "授权：未授权"
    }
}

@Composable
private fun TopBarBackButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .nemuClickable(
                spec = NemuPressSpec.Chip,
                haptic = NemuHaptic.SegmentTick,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = size.minDimension * 0.1f
            drawLine(
                color = scheme.onSurface,
                start = Offset(size.width * 0.66f, size.height * 0.18f),
                end = Offset(size.width * 0.32f, size.height * 0.5f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = scheme.onSurface,
                start = Offset(size.width * 0.32f, size.height * 0.5f),
                end = Offset(size.width * 0.66f, size.height * 0.82f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(
    state: DashboardState,
    tab: DashboardTab,
    applicationsMode: Int,
    onBackToHub: () -> Unit,
    onRefreshApplications: () -> Unit
) {
    val backendMode = topBarBackendModeLabel(state.backendStatus)
    val applicationsTitle = when (applicationsMode) {
        APP_PAGE_FREEZE -> "冻结管理"
        APP_PAGE_UNFREEZE -> "解冻恢复"
        APP_PAGE_DEEP_UNINSTALL -> "深度卸载"
        else -> "功能"
    }
    TopAppBar(
        navigationIcon = {
            if (tab == DashboardTab.APPLICATIONS &&
                applicationsMode != APP_PAGE_HUB
            ) {
                TopBarBackButton(onBackToHub)
            }
        },
        title = {
            Text(
                text = if (tab == DashboardTab.APPLICATIONS) {
                    applicationsTitle
                } else {
                    tab.label
                },
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            if (tab == DashboardTab.APPLICATIONS) {
                val canRefresh = !state.busy && !state.catalogRefreshing
                Text(
                    text = if (state.catalogRefreshing) "刷新中" else "刷新",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp
                    ),
                    color = if (canRefresh) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .nemuClickable(
                            enabled = canRefresh,
                            spec = NemuPressSpec.Chip,
                            haptic = NemuHaptic.KeyboardTap,
                            role = Role.Button,
                            onClick = onRefreshApplications
                        )
                        // Invisible padding so the bare label still has a
                        // 48dp-tall touch target inside the top bar.
                        .padding(horizontal = 8.dp, vertical = 16.dp)
                )
            }
            if (state.busy) {
                StatusChip(
                    text = "处理中…",
                    modifier = Modifier.padding(end = 6.dp),
                    tone = StatusChipTone.NEUTRAL
                )
            }
            // One chip only: the original Root/Shizuku status with the
            // requested authorization-mode prefix.
            StatusChip(
                text = backendMode,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .widthIn(max = 150.dp),
                fontSize = 11.sp,
                tone = when (backendMode) {
                    // User preference: keep the original solid blue capsule
                    // with white text for an authorized backend.
                    "授权：Root", "授权：Shizuku" -> StatusChipTone.PRIMARY
                    "授权：检测中" -> StatusChipTone.NEUTRAL
                    else -> StatusChipTone.ERROR
                }
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun DashboardNavigationBar(
    selected: DashboardTab,
    onSelect: (DashboardTab) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            color = scheme.nemuFloating(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            shadowElevation = if (dark) 0.dp else 10.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    val haptics = rememberNemuHaptics()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                interactionSource = interaction,
                                indication = null,
                                onClick = {
                                    haptics.perform(NemuHaptic.SegmentTick)
                                    onSelect(tab)
                                }
                            )
                            .nemuPressFeedback(
                                interaction,
                                NemuPressSpec.NavItem
                            )
                            .semantics { contentDescription = tab.label }
                            .padding(vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 26.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            scheme.nemuInset()
                                        } else {
                                            Color.Transparent
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                DashboardNavIcon(tab)
                            }
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp
                                ),
                                color = if (isSelected) {
                                    scheme.onSurface
                                } else {
                                    scheme.onSurfaceVariant
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class NemuGlyph {
    HOME,
    SNOWFLAKE,
    APPS,
    SEARCH,
    SETTINGS,
    RESTORE,
    UNINSTALL,
    PLAN
}

@Composable
private fun DashboardNavIcon(tab: DashboardTab) {
    // Use the theme's neutral ink instead of pure black/white; selection
    // still does not tint the glyphs with the palette primary.
    val color = MaterialTheme.colorScheme.onSurface
    val glyph = when (tab) {
        DashboardTab.OVERVIEW -> NemuGlyph.HOME
        DashboardTab.APPLICATIONS -> NemuGlyph.SNOWFLAKE
        DashboardTab.ALL_APPS -> NemuGlyph.APPS
        DashboardTab.DETECTION -> NemuGlyph.SEARCH
        DashboardTab.SETTINGS -> NemuGlyph.SETTINGS
    }
    Canvas(Modifier.size(24.dp)) {
        drawNemuGlyph(glyph, color)
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNemuGlyph(
    glyph: NemuGlyph,
    color: androidx.compose.ui.graphics.Color
) {
    val stroke = size.minDimension * 0.085f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = stroke) {
        drawLine(
            color = color,
            start = Offset(x1 * size.width, y1 * size.height),
            end = Offset(x2 * size.width, y2 * size.height),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }

    when (glyph) {
        NemuGlyph.HOME -> {
            line(0.16f, 0.47f, 0.50f, 0.18f)
            line(0.50f, 0.18f, 0.84f, 0.47f)
            line(0.25f, 0.43f, 0.25f, 0.82f)
            line(0.75f, 0.43f, 0.75f, 0.82f)
            line(0.25f, 0.82f, 0.75f, 0.82f)
            line(0.44f, 0.82f, 0.44f, 0.60f)
            line(0.56f, 0.82f, 0.56f, 0.60f)
            line(0.44f, 0.60f, 0.56f, 0.60f)
        }
        NemuGlyph.SNOWFLAKE -> {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension * 0.37f
            // A ship's wheel needs a visible central hole. The old spokes
            // started at the exact centre plus a filled hub, which collapsed
            // into a black blob at 24dp.
            // The old 0.34r hub left a hole only ~2 stroke-widths wide, so the
            // ring read as a thick washer (the "hub twice as thick" report).
            // A larger hole plus spokes seated outside the band keeps the hub
            // stroke visually identical to the spokes.
            val hubRadius = radius * 0.40f
            // A small circle reads bolder than a straight line of the same
            // width; trim the hub stroke optically so the centre matches the
            // spokes instead of looking nearly twice as thick.
            val hubStroke = stroke * 0.78f
            // Round caps end at the outer edge of the hub band instead of
            // merging into it.
            val spokeStart = hubRadius + hubStroke
            // Keep the inward-facing handles near the rim. Their round caps
            // used to reach the hub band and merged with it, which made the
            // centre read as a thick blob even though both strokes were the
            // same width; leave a visible gap instead.
            val branchRadius = radius * 0.78f
            val branchLength = radius * 0.16f
            for (ray in 0 until 6) {
                val angle = -PI / 2.0 + ray * PI / 3.0
                val ux = cos(angle).toFloat()
                val uy = sin(angle).toFloat()
                drawLine(
                    color,
                    Offset(cx + ux * spokeStart, cy + uy * spokeStart),
                    Offset(cx + ux * radius, cy + uy * radius),
                    stroke,
                    cap = StrokeCap.Round
                )
                val branchX = cx + ux * branchRadius
                val branchY = cy + uy * branchRadius
                for (branch in 0..1) {
                    val side = if (branch == 0) -1.0 else 1.0
                    val branchAngle = angle + PI + side * PI / 4.0
                    drawLine(
                        color,
                        Offset(branchX, branchY),
                        Offset(
                            branchX + cos(branchAngle).toFloat() * branchLength,
                            branchY + sin(branchAngle).toFloat() * branchLength
                        ),
                        stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
            drawCircle(
                color = color,
                radius = hubRadius,
                center = Offset(cx, cy),
                style = Stroke(width = hubStroke)
            )
        }
        NemuGlyph.APPS -> {
            val radius = size.minDimension * 0.085f
            listOf(
                0.30f to 0.30f,
                0.70f to 0.30f,
                0.30f to 0.70f,
                0.70f to 0.70f
            ).forEach { (x, y) ->
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(x * size.width, y * size.height)
                )
            }
        }
        NemuGlyph.SEARCH -> {
            val center = Offset(size.width * 0.43f, size.height * 0.42f)
            drawCircle(
                color = color,
                radius = size.minDimension * 0.25f,
                center = center,
                style = Stroke(width = stroke)
            )
            line(0.61f, 0.61f, 0.83f, 0.83f)
        }
        NemuGlyph.SETTINGS -> {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val inner = size.minDimension * 0.28f
            val outer = size.minDimension * 0.43f
            for (ray in 0 until 8) {
                val angle = ray * PI / 4.0
                drawLine(
                    color,
                    Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                    Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                    stroke * 1.3f,
                    cap = StrokeCap.Round
                )
            }
            drawCircle(
                color = color,
                radius = size.minDimension * 0.26f,
                center = Offset(cx, cy),
                style = Stroke(width = stroke)
            )
            drawCircle(color = color, radius = stroke * 0.8f, center = Offset(cx, cy))
        }
        NemuGlyph.RESTORE -> {
            val inset = size.minDimension * 0.22f
            val diameter = size.minDimension - inset * 2
            drawArc(
                color = color,
                startAngle = 35f,
                sweepAngle = 285f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // Arrowhead at the open top-right end of the arc.
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = diameter / 2f
            val angle = Math.toRadians(320.0)
            val ex = cx + cos(angle).toFloat() * radius
            val ey = cy + sin(angle).toFloat() * radius
            line(
                ex / size.width,
                ey / size.height,
                (ex - size.minDimension * 0.16f) / size.width,
                (ey - size.minDimension * 0.02f) / size.height
            )
            line(
                ex / size.width,
                ey / size.height,
                (ex - size.minDimension * 0.02f) / size.width,
                (ey + size.minDimension * 0.16f) / size.height
            )
        }
        NemuGlyph.UNINSTALL -> {
            line(0.24f, 0.26f, 0.76f, 0.26f)
            line(0.42f, 0.18f, 0.58f, 0.18f)
            line(0.42f, 0.18f, 0.42f, 0.26f)
            line(0.58f, 0.18f, 0.58f, 0.26f)
            line(0.30f, 0.30f, 0.35f, 0.84f)
            line(0.70f, 0.30f, 0.65f, 0.84f)
            line(0.35f, 0.84f, 0.65f, 0.84f)
            line(0.44f, 0.38f, 0.46f, 0.76f, stroke * 0.8f)
            line(0.56f, 0.38f, 0.54f, 0.76f, stroke * 0.8f)
        }
        NemuGlyph.PLAN -> {
            drawCircle(
                color = color,
                radius = size.minDimension * 0.32f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = stroke)
            )
            line(0.50f, 0.50f, 0.50f, 0.30f)
            line(0.50f, 0.50f, 0.66f, 0.58f)
        }
    }
}

@Composable
private fun OverviewPage(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier,
    onOpenPlan: () -> Unit = {}
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 4.dp,
            end = 16.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OverviewHeroCard(state = state, callbacks = callbacks)
        }
        item { PrivilegeCard(state, callbacks) }
        if (state.rescueMode) item { RescueModeCard(callbacks) }
        item { BootFreezeCard(state, onOpenPlan) }
    }
}

@Composable
private fun PrivilegeCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val privilege = state.privilege
    SectionCard(title = "授权") {
        if (privilege == null) {
            Text("正在检查权限…", style = MaterialTheme.typography.bodySmall)
            NemuOutlinedButton(
                onClick = callbacks.onRefreshPrivilege,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("刷新授权状态") }
            return@SectionCard
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PrivilegeRow(
            title = "Root",
            detail = if (privilege.rootAvailable) {
                "可冻结、停用组件、隐藏应用"
            } else {
                "未授权；点“授权”弹出 KernelSU/Magisk 窗口"
            },
            chip = if (privilege.rootAvailable) "已授权" else "未授权",
            tone = if (privilege.rootAvailable) {
                StatusChipTone.THEME
            } else {
                StatusChipTone.NEUTRAL
            },
            actionLabel = if (privilege.rootAvailable) "检查" else "授权",
            onAction = callbacks.onAuthorizeRoot
        )
        PrivilegeRow(
            title = "Shizuku",
            detail = when {
                privilege.shizukuGranted -> "可执行冻结与解冻"
                privilege.shizukuRunning -> "已运行，未授权"
                else -> "未运行；先启动 Shizuku"
            },
            chip = if (privilege.shizukuGranted) "已授权" else "未授权",
            tone = if (privilege.shizukuGranted) {
                StatusChipTone.THEME
            } else {
                StatusChipTone.NEUTRAL
            },
            actionLabel = if (privilege.shizukuGranted) "检查" else "授权",
            onAction = callbacks.onAuthorizeShizuku
        )
    }
}

@Composable
private fun PrivilegeRow(
    title: String,
    detail: String,
    chip: String,
    tone: StatusChipTone,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusChip(
            text = chip,
            tone = tone
        )
        if (actionLabel != null && onAction != null) {
            // A real compact pill, not bare text: 授权 and 检查 use the same
            // size and both get the shared press feedback.
            NemuOutlinedButton(
                onClick = onAction,
                modifier = Modifier.widthIn(min = 56.dp)
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun OverviewHeroCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val activeStates = state.catalog.filter {
        it.isInstalled && (it.hasFrozenState || it.applied)
    }
    // Follow each target's selected preset instead of calling every
    // suspension "已冻结": PAUSE targets must read "已暂停".
    val paused = activeStates.count { it.preset == StrategyPreset.PAUSE }
    val frozen = activeStates.size - paused
    val activeText = buildList {
        if (frozen > 0) add("$frozen 个已冻结")
        if (paused > 0) add("$paused 个已暂停")
    }.joinToString("、")
    // A plan is not "planned" for boot while the master switch is off.
    val planned = state.catalog.count {
        it.isInstalled && it.enabled && it.active
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.nemuCard(),
            contentColor = scheme.onSurface
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val radius = size.width * 0.58f
                    val center = Offset(size.width * 0.82f, size.height * 0.18f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                scheme.primary.copy(alpha = 0.16f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                    "系统状态",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)
                )
                    Text(
                        text = if (activeStates.isEmpty()) {
                            "待机中；没有已生效的冻结"
                        } else {
                            "$activeText；后台可能被系统重新拉起"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "开机自动冻结",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                    NemuSwitch(
                        checked = state.bootFreeze.enabled,
                        onCheckedChange = callbacks.onBootFreezeEnabledChange,
                        enabled = !state.busy,
                        modifier = Modifier.semantics {
                            contentDescription = "开机自动冻结"
                        }
                    )
                }
            }
            Surface(
                color = scheme.nemuInset(),
                contentColor = scheme.onSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InlineStat(
                        value = "${activeStates.size} 个",
                        label = "已生效"
                    )
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(scheme.outlineVariant.copy(alpha = 0.55f))
                    )
                    InlineStat(
                        value = "$planned 个",
                        label = if (state.bootFreeze.enabled) {
                            "开机冻结中"
                        } else {
                            "开机冻结未启用"
                        }
                    )
                }
            }
            if (state.dryRun) {
                Text(
                    text = "预览模式：手动操作只记录，不改变手机；开机、关机时的安全解冻仍会执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        }
    }
}

@Composable
private fun InlineStat(value: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val statStyle = MaterialTheme.typography.labelMedium
        Text(
            text = value,
            style = statStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = statStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RescueModeCard(callbacks: DashboardCallbacks) {
    var showConfirm by remember { mutableStateOf(false) }
    SectionCard(
        title = "救援模式已触发",
        subtitle = "连续 3 次开机都未能确认系统正常；冻结功能已暂停，正在尝试恢复。"
    ) {
        Text(
            "确认桌面、设置正常后再清除；清除不会立即重新应用。",
            style = MaterialTheme.typography.bodySmall
        )
        NemuFilledButton(
            onClick = { showConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) { Text("解除救援模式") }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("解除救援模式？") },
            text = {
                Text(
                    "先检查并恢复 Nemu 拥有的状态；任一步失败都会保留救援模式。" +
                        "清除只处理 Nemu 自己标记的状态，不会解冻其他工具暂停的应用。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        callbacks.onClearRescue()
                    }
                ) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BootFreezeCard(
    state: DashboardState,
    onOpenPlan: () -> Unit = {}
) {
    val boot = state.bootFreeze
    SectionCard(
        title = "开机与安全",
        subtitle = when {
            !boot.enabled -> "开机自动冻结已关闭"
            state.dryRun ->
                "开机 ${boot.delaySeconds} 秒后执行；预览不修改手机"
            else -> "开机 ${boot.delaySeconds} 秒后执行开机冻结"
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StatusChipGap)
        ) {
            StatusChip(
                text = if (boot.enabled) "已开启" else "已关闭",
                tone = if (boot.enabled) {
                    StatusChipTone.SUCCESS
                } else {
                    StatusChipTone.NEUTRAL
                }
            )
            StatusChip(
                text = when {
                    boot.plannedCount == 0 -> "去添加开机冻结 ›"
                    else -> "开机冻结 ${boot.plannedCount} ›"
                },
                modifier = Modifier
                    .noRippleClickable(onClick = onOpenPlan)
                    .semantics { contentDescription = "查看开机冻结" },
                tone = if (boot.plannedCount == 0 || !boot.enabled) {
                    StatusChipTone.NEUTRAL
                } else {
                    StatusChipTone.INFO
                }
            )
        }
        if (boot.windowMissed) {
            Text(
                "本次执行时机已错过，下次开机会重试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (boot.lastResult != null) {
            // Same fixed label column + 8dp gap as SafetyRows, so the chip
            // starts at the same x as 关机前/开机后.
            ResultRow(
                label = "上次结果",
                value = BootStatusCopy.of(boot.lastResult).chip,
                tone = bootResultTone(boot)
            )
        }
        if (boot.lastSkippedReason != null) {
            Surface(
                color = MaterialTheme.colorScheme.nemuInset(),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "本次开机未自动冻结：${boot.lastSkippedReason}",
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SafetyRows(state)
    }
}

/** 关机/开机安全解冻结果；概览与设置的开机页共用。 */
@Composable
private fun SafetyRows(state: DashboardState) {
    val shutdown = prettyResultText(state.lastShutdownResult)
    val boot = prettyResultText(state.lastBootResult)
    if (shutdown == "尚未执行" && boot == "尚未执行") return
    Text("上次安全解冻", style = MaterialTheme.typography.titleSmall)
    ResultRow("关机前", shutdown, safetyTone(shutdown))
    ResultRow("开机后", boot, safetyTone(boot))
}

// One table rule for 总开关 / 开机冻结 / 执行状态 / 上次结果 / 关机前 / 开机后:
// labels are flush-left and every label is followed by the same content gap.
// A shorter label simply ends sooner; its value follows at the same gap.
@Composable
private fun ResultRow(
    label: String,
    value: String,
    tone: StatusChipTone = StatusChipTone.NEUTRAL
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TableContentGap)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatusChip(
            text = value,
            tone = tone,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

private fun safetyTone(value: String): StatusChipTone = when {
    value == "尚未执行" -> StatusChipTone.NEUTRAL
    value.contains("失败") -> StatusChipTone.ERROR
    value.contains("成功") -> StatusChipTone.SUCCESS
    value.startsWith("已跳过") -> StatusChipTone.WARNING
    else -> StatusChipTone.INFO
}

@Composable
private fun DetectionPage(
    detectionContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        detectionContent.invoke(this)
    }
}

/**
 * 设置根页只有入口，不直接铺控件；每个入口进二级页后只做一件事。
 * 页面选择不持久化：重新进入设置总是回到根页，避免上次停留的深页造成误解。
 */
private const val SETTINGS_ROOT = -1

@Composable
private fun SettingsPage(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    onOpenOverview: () -> Unit,
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier
) {
    val entries = settingsEntries(state)
    val entryId = SettingsEntryId.entries.getOrNull(pageIndex)
    val entry = entries.firstOrNull { it.id == entryId }
    BackHandler(enabled = entryId != null) { onPageChange(SETTINGS_ROOT) }
    val listState = rememberLazyListState()
    // Scroll to top only when the page actually changes; re-entering from a
    // deep link must keep the reading position that SaveableStateProvider
    // restored.
    var lastEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(entryId) {
        if (lastEntryId != entryId?.name) {
            listState.scrollToItem(0)
            lastEntryId = entryId?.name
        }
    }
    Column(modifier = modifier) {
        if (entry != null) {
            // Pin the return affordance above the scroll area; it used to be
            // the first list item and disappeared when the user scrolled down.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = { onPageChange(SETTINGS_ROOT) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("‹ 返回")
                    }
                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = settingsPageSubtitle(entry.id),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (entry == null) {
                item {
                    SectionCard {
                        Column {
                            entries.forEachIndexed { index, item ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme
                                            .outlineVariant
                                    )
                                }
                                SettingsEntryRow(item) {
                                    onPageChange(item.id.ordinal)
                                }
                            }
                        }
                    }
                }
            } else {
                when (entry.id) {
                    SettingsEntryId.EXECUTION -> item {
                        ExecutionSettingsContent(
                            state,
                            callbacks,
                            onOpenOverview
                        )
                    }
                    SettingsEntryId.BOOT -> {
                        if (state.rescueMode) {
                            item { RescueModeCard(callbacks) }
                        }
                        item { BootStatusContent(state, callbacks) }
                    }
                    SettingsEntryId.APPEARANCE -> item {
                        AppearanceCard(state, callbacks)
                    }
                    SettingsEntryId.LOGS -> item {
                        DefaultLogsContent(state, callbacks)
                    }
                    SettingsEntryId.ADVANCED -> {
                        item {
                            CotaSection(
                                state = state.cota,
                                callbacks = callbacks.cotaCallbacks
                            )
                        }
                        item { ProtectionRulesCard() }
                    }
                }
            }
        }
    }
}

/** 根页的一行；箭头只是提示可进入，点击区域包含整行。 */
@Composable
private fun SettingsEntryRow(
    entry: SettingsEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .nemuClickable(
                spec = NemuPressSpec.Row,
                haptic = NemuHaptic.KeyboardTap,
                onClick = onClick
            )
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 开机页只读展示：总开关留在概览，避免两个开关同时改同一设置。
 */
@Composable
private fun BootStatusContent(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val boot = state.bootFreeze
    SectionCard(title = "开机自动冻结") {
        ResultRow(
            label = "总开关",
            value = if (boot.enabled) "已开启" else "已关闭",
            tone = if (boot.enabled) {
                StatusChipTone.SUCCESS
            } else {
                StatusChipTone.NEUTRAL
            }
        )
        ResultRow(
            label = "开机冻结",
            value = when {
                boot.plannedCount == 0 -> "无"
                !boot.enabled -> "${boot.plannedCount} 个 · 未启用"
                else -> "${boot.plannedCount} 个"
            },
            tone = if (boot.plannedCount == 0 || !boot.enabled) {
                StatusChipTone.NEUTRAL
            } else {
                StatusChipTone.INFO
            }
        )
        ResultRow(
            label = "执行状态",
            value = when {
                !boot.enabled -> "总开关关闭"
                boot.windowMissed -> "本次开机的执行时机已错过，下次开机会自动重试"
                boot.plannedCount == 0 -> "无开机冻结目标"
                else -> "已启用 · ${boot.delaySeconds} 秒后执行"
            },
            tone = when {
                !boot.enabled || boot.plannedCount == 0 ->
                    StatusChipTone.NEUTRAL
                boot.windowMissed -> StatusChipTone.ERROR
                else -> StatusChipTone.SUCCESS
            }
        )
        ResultRow(
            label = "上次结果",
            value = BootStatusCopy.of(boot.lastResult).chip,
            tone = bootResultTone(boot)
        )
        if (boot.lastSkippedReason != null) {
            Surface(
                color = MaterialTheme.colorScheme.nemuInset(),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "本次开机未自动冻结：${boot.lastSkippedReason}",
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        val attempt = boot.lastAttemptText
        val success = boot.lastSuccessText
        if (attempt != null && attempt == success) {
            Text(
                text = "上次执行：$attempt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            if (attempt != null) {
                Text(
                    text = "上次尝试：$attempt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (success != null) {
                Text(
                    text = "上次处理：$success",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        NemuOutlinedButton(
            onClick = callbacks.onOpenStartupSettings,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开「应用启动管理」允许自启动")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SafetyRows(state)
    }
}

/** 固定的安全边界说明；具体不可用原因仍由功能页按目标给出。 */
@Composable
private fun ProtectionRulesCard() {
    SectionCard(
        title = "保护规则",
        subtitle = "不可关闭；无此能力时会置灰并说明原因。"
    ) {
        Text(
            text = "敏感应用 / 红色风险：整包操作只提示高风险，仍可执行。",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "组件缺失或平台明确拒绝时才会置灰并说明；其余策略由用户决定。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Separate, low-emphasis note panel. It is deliberately a sibling card rather
 * than a paragraph inside the form: a long ①②③ list shares the form's column
 * today and reads as if it were another setting.
 */
@Composable
private fun ExecutionNotePanel() {
    // No card/background: the note sits on the page below the form card.
    // Each paragraph starts with two ideographic spaces, so its first line is
    // indented while wrapped lines stay flush left.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = "注：",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Keep wrapped lines aligned with the text after the number:
        // 注：
        // 　① text…
        // 　　 continuation…
        ExecutionNoteItem(
            number = "①",
            text = "冻结、停用、隐藏只改变应用状态，" +
                "不会卸载应用或删除数据；可在「功能 › 解冻恢复」撤销。"
        )
        ExecutionNoteItem(
            number = "②",
            text = "只有「深度卸载」会卸载应用，系统应用也可以卸载；" +
                "它会先备份 APK 并保留应用数据，模块 v1.20 会在开机时自动恢复；" +
                "未安装模块时需手动恢复。"
        )
        ExecutionNoteItem(
            number = "③",
            text = "强制停止是临时的，重新打开应用即恢复运行。"
        )
    }
}

@Composable
private fun ExecutionNoteItem(number: String, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One-character indent after 注：; this is indentation, not a
            // blank line between the heading and the numbered items.
            .padding(start = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExecutionSettingsContent(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    onOpenOverview: () -> Unit
) {
    var showActualConfirm by remember { mutableStateOf(false) }
    // Entering this page re-reads the backend so a Root/Shizuku grant made
    // elsewhere is reflected without leaving the settings screen.
    LaunchedEffect(Unit) { callbacks.onRefreshPrivilege() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionCard(title = "执行与授权") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("实际执行", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (state.dryRun) {
                        "关闭：手动操作只预览；开机、关机时的安全解冻仍执行"
                    } else {
                        "开启：点击后会修改手机"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.dryRun) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            NemuSwitch(
                checked = !state.dryRun,
                onCheckedChange = { actual ->
                    if (actual) {
                        // Turning execution on is the only direction that can
                        // change the phone; confirm before leaving preview mode.
                        if (state.dryRun) showActualConfirm = true
                    } else {
                        callbacks.onDryRunChange(true)
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.semantics {
                    contentDescription = "实际执行"
                }
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("授权状态", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StatusChipGap)
            ) {
                val privilege = state.privilege
                if (privilege == null) {
                    StatusChip(state.backendStatus)
                } else {
                    StatusChip(
                        text = privilege.activeBackendLabel,
                        tone = if (privilege.canFreeze) {
                            StatusChipTone.SUCCESS
                        } else {
                            StatusChipTone.NEUTRAL
                        }
                    )
                    if (privilege.canFreeze) {
                        // Status only: an authorized backend has nothing to
                        // navigate to, so this must not look like a button.
                        StatusChip(
                            text = "已授权",
                            tone = StatusChipTone.SUCCESS
                        )
                    } else {
                        StatusChip(
                            text = "需授权",
                            // Without a backend nothing can run: important.
                            tone = StatusChipTone.ERROR
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onOpenOverview) {
                            Text(
                                "去授权",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
    ExecutionNotePanel()
    }

    if (showActualConfirm) {
        AlertDialog(
            onDismissRequest = { showActualConfirm = false },
            title = { Text("切换到实际执行？") },
            text = {
                Text("关闭预览后，执行操作会真实修改手机；恢复记录仍会保留。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showActualConfirm = false
                        callbacks.onDryRunChange(false)
                    }
                ) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = { showActualConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * Nemu theme picker: eight iOS system colours as swatches, four colour-style
 * options and the three theme modes. Choices apply and persist immediately.
 */
@Composable
private fun AppearanceCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    SectionCard(
        title = "外观",
        subtitle = "主题配色 · 即时生效"
    ) {
        Text("配色", style = MaterialTheme.typography.titleSmall)
        PaletteSwatches(
            selected = state.themePalette,
            onSelected = callbacks.onThemePaletteChange
        )
        Text(
            state.themePalette.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("配色风格", style = MaterialTheme.typography.titleSmall)
        SegmentedChoiceRow(
            options = ThemeStyle.entries,
            selected = state.themeStyle,
            label = { it.label },
            onSelected = callbacks.onThemeStyleChange
        )

        Text("明暗模式", style = MaterialTheme.typography.titleSmall)
        SegmentedChoiceRow(
            options = ThemeMode.entries,
            selected = state.themeMode,
            label = { it.label },
            onSelected = callbacks.onThemeModeChange
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteSwatches(
    selected: ThemePalette,
    onSelected: (ThemePalette) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.nemuInset(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        FlowRow(
            modifier = Modifier
                .padding(12.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThemePalette.entries.forEach { palette ->
                val isSelected = palette == selected
                val fill = palette.swatchColor
                val checkColor = if (fill.luminance() < 0.5f) {
                    Color.White
                } else {
                    Color.Black
                }
                val interaction = remember { MutableInteractionSource() }
                val haptics = rememberNemuHaptics()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .nemuPressFeedback(interaction, NemuPressSpec.Swatch)
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            interactionSource = interaction,
                            indication = null,
                            onClick = {
                                haptics.perform(NemuHaptic.SegmentTick)
                                onSelected(palette)
                            }
                        )
                        .semantics {
                            contentDescription = "配色：${palette.label}"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .border(2.dp, fill, CircleShape)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(fill)
                    )
                    if (isSelected) {
                        Canvas(Modifier.size(16.dp)) {
                            val unit = size.minDimension / 24f
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(9f * unit, 16.17f * unit)
                                lineTo(4.83f * unit, 12f * unit)
                                lineTo(3.41f * unit, 13.41f * unit)
                                lineTo(9f * unit, 19f * unit)
                                lineTo(21f * unit, 7f * unit)
                                lineTo(19.59f * unit, 5.59f * unit)
                                close()
                            }
                            drawPath(path, color = checkColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SegmentedChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val first = index == 0
            val last = index == options.lastIndex
            val outer = 26.dp
            val inner = 10.dp
            val shape = if (isSelected) {
                androidx.compose.foundation.shape.RoundedCornerShape(outer)
            } else {
                androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = if (first) outer else inner,
                    bottomStart = if (first) outer else inner,
                    topEnd = if (last) outer else inner,
                    bottomEnd = if (last) outer else inner
                )
            }
            val interaction = remember { MutableInteractionSource() }
            val haptics = rememberNemuHaptics()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .nemuPressFeedback(interaction, NemuPressSpec.Chip)
                    .clip(shape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.nemuBlock()
                        }
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        interactionSource = interaction,
                        indication = null,
                        onClick = {
                            haptics.perform(NemuHaptic.SegmentTick)
                            onSelected(option)
                        }
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DefaultLogsContent(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var showCaptureConfirm by remember { mutableStateOf(false) }
    var showAllLogs by rememberSaveable { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val recent = state.logs.takeLast(40)
    val visibleLogs = recent.takeLast(if (showAllLogs) 40 else 6)
    val visibleFileLines = state.logSettings.fileLines.takeLast(if (showAllLogs) 40 else 6)

    SectionCard(title = "Nemu 日志") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                StatusChip(
                    text = if (state.logSettings.enabled) "日志已开启" else "日志已关闭",
                    tone = if (state.logSettings.enabled) {
                        StatusChipTone.SUCCESS
                    } else {
                        StatusChipTone.NEUTRAL
                    }
                )
                Text(
                    text = "开关控制常规 Nemu 日志；启动 logcat 抓取会单独写入原始系统日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NemuSwitch(
                checked = state.logSettings.enabled,
                onCheckedChange = callbacks.onLoggingEnabledChange,
                enabled = !state.logSettings.busy,
                modifier = Modifier.semantics {
                    contentDescription = "Nemu 日志"
                }
            )
        }
        val path = state.logSettings.filePath
        if (path != null) {
            Text(
                text = "文件：$path",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "大小：${formatBytes(state.logSettings.fileSizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.logSettings.message != null) {
            Text(
                text = state.logSettings.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NemuOutlinedButton(
                onClick = callbacks.onRefreshLogs,
                enabled = !state.logSettings.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text("刷新")
            }
            NemuOutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = !state.logSettings.busy,
                modifier = Modifier.weight(1f),
                contentColor = MaterialTheme.colorScheme.error
            ) {
                Text("清空")
            }
        }
        NemuOutlinedButton(
            onClick = { showCaptureConfirm = true },
            enabled = !state.logSettings.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("抓取系统日志（最近 3000 行）")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            visibleLogs.isNotEmpty() -> {
                visibleLogs.forEach { entry ->
                    LogLine(entry = entry, formatter = formatter)
                }
                Text(
                    text = "显示最近 ${visibleLogs.size} 条；完整内容见日志文件。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            visibleFileLines.isNotEmpty() -> {
                // After a process restart the in-memory ActionLog is empty;
                // show the persisted file tail instead of "暂无日志".
                visibleFileLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "来自日志文件最近 ${visibleFileLines.size} 行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> Text(
                text = "暂无日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The file tail is only rendered when the in-memory log is empty.
        val availableLineCount = if (recent.isNotEmpty()) {
            recent.size
        } else {
            state.logSettings.fileLines.size
        }
        if (availableLineCount > 6) {
            TextButton(onClick = { showAllLogs = !showAllLogs }) {
                Text(if (showAllLogs) "收起日志" else "显示更多（$availableLineCount 条）")
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空日志") },
            text = { Text("将删除全部 nemu-*.log 与 startup-*.txt 日志文件；不影响冻结策略与历史设置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        callbacks.onClearLogs()
                    }
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
    if (showCaptureConfirm) {
        AlertDialog(
            onDismissRequest = { showCaptureConfirm = false },
            title = { Text("抓取系统日志？") },
            text = {
                Text(
                    "将保存最近的系统 logcat，内容未脱敏，可能包含通知、账号标识和应用运行信息。" +
                        "文件只保存在本机，最多保留 3 份；可随时使用“清空”删除。即使常规日志关闭，这次抓取仍会写入文件。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCaptureConfirm = false
                        callbacks.onCaptureLogcat()
                    }
                ) { Text("抓取并保存") }
            },
            dismissButton = {
                TextButton(onClick = { showCaptureConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun LogLine(
    entry: LogEntry,
    formatter: SimpleDateFormat
) {
    Text(
        text = "${formatter.format(Date(entry.timestamp))}  ${entry.level}  ${entry.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = when (entry.level) {
            "ERROR" -> MaterialTheme.colorScheme.error
            "WARN" -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 6,
        overflow = TextOverflow.Ellipsis
    )
}

private fun bootResultTone(boot: BootFreezeUiState): StatusChipTone {
    val result = boot.lastResult ?: return StatusChipTone.NEUTRAL
    return when {
        result.startsWith("已跳过") ||
            result.startsWith("手动选择性解冻") ||
            result.startsWith("手动全部解冻") -> StatusChipTone.WARNING
        result.startsWith("没有启用") -> StatusChipTone.NEUTRAL
        result.startsWith("手动全部恢复") -> StatusChipTone.NEUTRAL
        result.contains("预览") -> StatusChipTone.INFO
        result.startsWith("开机冻结成功") ||
            result.startsWith("手动冻结成功") -> StatusChipTone.SUCCESS
        result.contains("失败") -> StatusChipTone.ERROR
        boot.lastSucceeded -> StatusChipTone.SUCCESS
        else -> StatusChipTone.NEUTRAL
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 ->
        String.format(Locale.US, "%.2f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 ->
        String.format(Locale.US, "%.2f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * Older builds appended a raw 13-digit epoch in Chinese parentheses. Show it
 * as a readable local time instead of the number.
 */
private val EPOCH_SUFFIX = Regex("（(\\d{13})）")
private fun prettyResultText(value: String): String {
    if (!value.contains(EPOCH_SUFFIX)) return value
    val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    return EPOCH_SUFFIX.replace(value) { match ->
        val millis = match.groupValues[1].toLongOrNull()
        if (millis == null) match.value else "（${formatter.format(Date(millis))}）"
    }
}
