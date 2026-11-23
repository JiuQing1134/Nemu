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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
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
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.data.LogEntry
import com.nemu.hibernate.privilege.PrivilegeOverview
import com.nemu.hibernate.ui.theme.ThemeMode
import com.nemu.hibernate.ui.theme.ThemePalette
import com.nemu.hibernate.ui.theme.ThemeStyle
import com.nemu.hibernate.ui.theme.flClashBlock
import com.nemu.hibernate.ui.theme.flClashCard
import com.nemu.hibernate.ui.theme.flClashFloating
import com.nemu.hibernate.ui.theme.flClashInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 底部导航的 5 个目的地：应用页内用“管理/解冻”分段。 */
enum class DashboardTab(val label: String) {
    OVERVIEW("概览"),
    APPLICATIONS("应用"),
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
    val hidden: Boolean? = null
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
    val plannedCount: Int = 0
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
    val themeMode: ThemeMode = ThemeMode.SYSTEM
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
    val onCatalogPresetChange: (String, StrategyPreset) -> Unit = { _, _ -> },
    val onCatalogComponentsChange: (String, Set<String>) -> Unit = { _, _ -> },
    val onCatalogApply: (Set<String>) -> Unit = {},
    val onCatalogRestore: (Set<String>) -> Unit = {},
    val onCatalogRestoreAll: () -> Unit = {},
    val onCatalogRefresh: () -> Unit = {},
    val onCustomTargetAdd: (String, String, RiskLevel) -> Unit =
        { _, _, _ -> },
    val onCustomTargetRemove: (String) -> Unit = {},
    val onAuthorizeRoot: () -> Unit = {},
    val onAuthorizeShizuku: () -> Unit = {},
    val onRefreshPrivilege: () -> Unit = {},
    val onThemeStyleChange: (ThemeStyle) -> Unit = {},
    val onThemePaletteChange: (ThemePalette) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {}
)

/**
 * Nemu 布局：Scaffold + TopAppBar + 4 项底部导航。
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
    var applicationsMode by rememberSaveable { mutableIntStateOf(0) }
    // Hoisted so a deep link out of a Settings sub-page can return to that
    // exact sub-page instead of the root list.
    var settingsPage by rememberSaveable { mutableIntStateOf(SETTINGS_ROOT) }
    val tabStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(focusPackage) {
        if (focusPackage != null) {
            tabNavigation = tabNavigation.openProgrammatic(
                DashboardTab.APPLICATIONS.ordinal
            )
            applicationsMode = 0
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
        topBar = { DashboardTopBar(state, selectedTab) },
        bottomBar = {
            DashboardNavigationBar(selectedTab) { tab ->
                if (tab == selectedTab) {
                    // Re-tapping the current tab returns it to its root page
                    // without discarding a deep-link return path.
                    when (tab) {
                        DashboardTab.APPLICATIONS -> {
                            applicationsMode = 0
                            // Re-tapping Applications leaves the one-app
                            // deep-link view, but keeps the back path.
                            onClearFocus()
                        }
                        DashboardTab.SETTINGS -> settingsPage = SETTINGS_ROOT
                        else -> Unit
                    }
                } else {
                    tabNavigation = tabNavigation.selectDirect(tab.ordinal)
                    if (tab == DashboardTab.SETTINGS) {
                        settingsPage = SETTINGS_ROOT
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
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
        when (selectedTab) {
            DashboardTab.OVERVIEW -> OverviewPage(state, callbacks, pageModifier)
            DashboardTab.APPLICATIONS -> ApplicationsScreen(
                state = state,
                callbacks = callbacks,
                modifier = pageModifier,
                mode = applicationsMode,
                onModeChange = { applicationsMode = it },
                focusPackage = focusPackage,
                onClearFocus = onClearFocus
            )
            DashboardTab.ALL_APPS -> AllAppsScreen(
                state = state,
                callbacks = callbacks,
                modifier = pageModifier,
                onOpenApplications = {
                    tabNavigation = tabNavigation.openProgrammatic(
                        DashboardTab.APPLICATIONS.ordinal
                    )
                    applicationsMode = 0
                }
            )
            DashboardTab.DETECTION -> DetectionPage(
                detectionContent = detectionContent,
                modifier = pageModifier
            )
            DashboardTab.SETTINGS -> SettingsPage(
                state = state,
                callbacks = callbacks,
                onOpenOverviewAuth = {
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
        text.startsWith("Root", ignoreCase = true) -> "模式：Root"
        text.contains("Shizuku", ignoreCase = true) &&
            !text.contains("未授权") -> "模式：Shizuku"
        text.contains("检测中") -> "模式：检测中"
        else -> "模式：未授权"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(state: DashboardState, tab: DashboardTab) {
    val backendMode = topBarBackendModeLabel(state.backendStatus)
    TopAppBar(
        title = {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.headlineLarge
            )
        },
        actions = {
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
                    .widthIn(max = 220.dp),
                tone = when (backendMode) {
                    "模式：Root", "模式：Shizuku" -> StatusChipTone.PRIMARY
                    "模式：检测中" -> StatusChipTone.NEUTRAL
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
            color = scheme.flClashFloating(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            shadowElevation = if (dark) 0.dp else 10.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = { onSelect(tab) }
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
                                    .size(width = 38.dp, height = 28.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            scheme.flClashInset()
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

private enum class NemuGlyph { HOME, SNOWFLAKE, APPS, SEARCH, SETTINGS }

@Composable
private fun DashboardNavIcon(tab: DashboardTab) {
    // The five dock glyphs stay pure black in light mode and pure white in
    // dark mode; selection must not tint them blue/blue-black.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = if (dark) Color.White else Color.Black
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNemuGlyph(
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
    }
}

@Composable
private fun OverviewPage(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OverviewHeroCard(state = state, callbacks = callbacks)
        }
        item { PrivilegeCard(state, callbacks) }
        if (state.rescueMode) item { RescueModeCard(callbacks) }
        item { BootFreezeCard(state) }
    }
}

@Composable
private fun PrivilegeCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val privilege = state.privilege
    SectionCard(
        title = "授权",
        subtitle = "Root / Shizuku 执行；未授权时点“授权”。"
    ) {
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
        NemuOutlinedButton(
            onClick = callbacks.onRefreshPrivilege,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("刷新授权状态")
        }
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
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun OverviewHeroCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val frozen = state.catalog.count { it.hasFrozenState || it.applied }
    // A plan is not "planned" for boot while the master switch is off.
    val planned = if (state.bootFreeze.enabled) {
        state.catalog.count { it.enabled && it.active }
    } else {
        0
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = scheme.flClashCard(),
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
                .padding(12.dp),
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
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp)
                )
                    Text(
                        text = if (frozen == 0) {
                            "待机中；没有已冻结的应用"
                        } else {
                            "$frozen 个已冻结；后台可能被系统重新拉起"
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
                color = scheme.flClashInset(),
                contentColor = scheme.onSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InlineStat(value = "$frozen", label = "已冻结")
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(scheme.outlineVariant.copy(alpha = 0.55f))
                    )
                    InlineStat(value = "$planned", label = "计划中")
                }
            }
            if (state.dryRun) {
                Text(
                    text = "预览模式：手动冻结/解冻只记录命令；开机/关机安全解冻仍会执行。",
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
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RescueModeCard(callbacks: DashboardCallbacks) {
    var showConfirm by remember { mutableStateOf(false) }
    SectionCard(
        title = "自动救砖已触发",
        subtitle = "3 次启动未确认；模块已禁用并尝试恢复。"
    ) {
        Text(
            "确认桌面、设置正常后再清除；清除不会立即重新应用。",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = { showConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) { Text("清除救援模式并重新启用模块") }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认清除救援模式") },
            text = {
                Text(
                    "先检查并恢复 Nemu 拥有的状态；任一步失败都会保留救援模式。" +
                        "boot_guard 的清除动作还会解冻其扫描到的暂停包，可能包括其他工具暂停的应用。"
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
private fun BootFreezeCard(state: DashboardState) {
    val boot = state.bootFreeze
    SectionCard(
        title = "开机与安全",
        subtitle = when {
            !boot.enabled -> "开机自动冻结已关闭；总开关在概览首卡。"
            state.dryRun ->
                "开机 ${boot.delaySeconds} 秒后执行；预览只记录，不修改手机。"
            else -> "开机 ${boot.delaySeconds} 秒后应用计划；总开关在概览首卡。"
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                text = if (boot.enabled) "开机自动冻结已开启" else "开机自动冻结已关闭",
                tone = if (boot.enabled) {
                    StatusChipTone.SUCCESS
                } else {
                    StatusChipTone.NEUTRAL
                }
            )
            StatusChip(
                text = when {
                    !boot.enabled ->
                        "计划 ${boot.plannedCount} 个 · 总开关关闭"
                    boot.plannedCount == 0 -> "无计划"
                    else -> "计划 ${boot.plannedCount} 个"
                },
                tone = if (boot.plannedCount == 0 || !boot.enabled) {
                    StatusChipTone.NEUTRAL
                } else {
                    StatusChipTone.INFO
                }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "上次结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusChip(
                text = boot.lastResult ?: "尚未执行",
                tone = bootResultTone(boot)
            )
        }
        if (boot.lastSkippedReason != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "本次开机已跳过：${boot.lastSkippedReason}",
                    modifier = Modifier.padding(12.dp),
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
    Text("上次安全解冻", style = MaterialTheme.typography.titleSmall)
    val shutdown = prettyResultText(state.lastShutdownResult)
    val boot = prettyResultText(state.lastBootResult)
    ResultRow("关机前", shutdown, safetyTone(shutdown))
    ResultRow("开机后", boot, safetyTone(boot))
    Text(
        "关机/重启前执行；adb reboot 不触发。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ResultRow(
    label: String,
    value: String,
    tone: StatusChipTone = StatusChipTone.NEUTRAL
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.width(56.dp),
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
    onOpenOverviewAuth: () -> Unit,
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
                            onOpenOverviewAuth
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
            .clickable(onClick = onClick)
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
    SectionCard(
        title = "开机自动冻结",
        subtitle = when {
            !boot.enabled -> "开机自动冻结已关闭；总开关在概览页。"
            state.dryRun ->
                "开机 ${boot.delaySeconds} 秒后执行；预览只记录，不修改手机。"
            else -> "开机 ${boot.delaySeconds} 秒后执行；总开关在概览页。"
        }
    ) {
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
            label = "计划",
            value = when {
                !boot.enabled -> "计划 ${boot.plannedCount} 个 · 总开关关闭"
                boot.plannedCount == 0 -> "无计划"
                else -> "计划 ${boot.plannedCount} 个"
            },
            tone = if (boot.plannedCount == 0 || !boot.enabled) {
                StatusChipTone.NEUTRAL
            } else {
                StatusChipTone.INFO
            }
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "上次结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusChip(
                text = boot.lastResult ?: "尚未执行",
                tone = bootResultTone(boot)
            )
        }
        if (boot.lastSkippedReason != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = "本次开机已跳过：${boot.lastSkippedReason}",
                    modifier = Modifier.padding(12.dp),
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

/** 固定的安全边界说明；具体不可用原因仍由应用页按目标给出。 */
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

@Composable
private fun ExecutionSettingsContent(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    onOpenOverviewAuth: () -> Unit
) {
    var showActualConfirm by remember { mutableStateOf(false) }
    SectionCard(title = "冻结与权限") {
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
                        "关闭：手动操作只预览；开机/关机安全清理仍执行"
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    StatusChip(
                        text = if (privilege.canFreeze) "可执行" else "需授权",
                        tone = if (privilege.canFreeze) {
                            StatusChipTone.SUCCESS
                        } else {
                            StatusChipTone.WARNING
                        }
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "授权在概览页的“授权”卡中管理。",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenOverviewAuth) {
                Text(
                    "去授权",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Text(
            text = "注：不卸载系统应用；手动冻结/解冻可在“恢复”页撤销，强停的应用首次打开后恢复后台。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
 * FlClash's theme picker, adapted to Nemu: eight iOS system colours as
 * swatches, four ColourStyle options and the three theme modes. Choices apply
 * and persist immediately.
 */
@Composable
private fun AppearanceCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    SectionCard(
        title = "外观",
        subtitle = "FlClash 配色 · 即时生效"
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
        color = MaterialTheme.colorScheme.flClashInset(),
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelected(palette) }
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.flClashBlock()
                        }
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(option) }
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
            Text("抓取启动 logcat")
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
        val availableLineCount = maxOf(recent.size, state.logSettings.fileLines.size)
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
            title = { Text("抓取原始启动日志？") },
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
            "WARN" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 6,
        overflow = TextOverflow.Ellipsis
    )
}

private fun bootResultTone(boot: BootFreezeUiState): StatusChipTone {
    val result = boot.lastResult ?: return StatusChipTone.NEUTRAL
    return when {
        result.startsWith("已跳过") -> StatusChipTone.WARNING
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
