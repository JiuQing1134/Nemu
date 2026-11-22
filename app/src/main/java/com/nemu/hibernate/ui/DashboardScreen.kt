package com.nemu.hibernate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.nemu.hibernate.R
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.data.LogEntry
import com.nemu.hibernate.privilege.PrivilegeOverview
import com.nemu.hibernate.ui.theme.ThemeMode
import com.nemu.hibernate.ui.theme.ThemePalette
import com.nemu.hibernate.ui.theme.ThemeStyle
import com.nemu.hibernate.ui.theme.swatchColorFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 底部导航的 5 个目的地：冻结与解冻是两个独立页面。 */
enum class DashboardTab(val label: String) {
    OVERVIEW("概览"),
    FREEZE("冻结"),
    UNFREEZE("解冻"),
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

/** 检测页摘要；详细检测内容由 caller 通过 detectionContent 槽提供。 */
data class DetectionUiState(
    val lastCheckedText: String? = null,
    val mismatchCount: Int = 0,
    val note: String? = null
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
    val antiFraud: AntiFraudUiState,
    val cota: CotaUiState,
    val logs: List<LogEntry> = emptyList(),
    val logSettings: LogUiState = LogUiState(),
    val detection: DetectionUiState = DetectionUiState(),
    val rescueMode: Boolean = false,
    val catalog: List<CatalogTargetUiState> = emptyList(),
    val lastCatalogResult: String? = null,
    /** Root is required for component ownership markers and whole-package disable. */
    val rootAvailable: Boolean = false,
    /** Plain-language authorization snapshot; null while the first read runs. */
    val privilege: PrivilegeOverview? = null,
    val themeStyle: ThemeStyle = ThemeStyle.M3E2025,
    val themePalette: ThemePalette = ThemePalette.MINT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

/**
 * 仪表盘只调用这些回调；业务逻辑仍留在 MainActivity/controllers。
 * 可选能力（开机冻结/日志/外观）提供默认空实现，避免 UI 反向持有控制器。
 */
data class DashboardCallbacks(
    val onDryRunChange: (Boolean) -> Unit,
    val onRequestShizuku: () -> Unit,
    val onOpenStartupSettings: () -> Unit,
    val onAntiFraudApply: (AntiFraudConfig) -> Unit,
    val onAntiFraudRestore: () -> Unit,
    val cotaCallbacks: CotaCallbacks,
    val onBootFreezeEnabledChange: (Boolean) -> Unit = {},
    val onLoggingEnabledChange: (Boolean) -> Unit = {},
    val onRefreshLogs: () -> Unit = {},
    val onCaptureLogcat: () -> Unit = {},
    val onClearLogs: () -> Unit = {},
    val onClearRescue: () -> Unit = {},
    val onTabSelected: (DashboardTab) -> Unit = {},
    val onCatalogToggle: (String, Boolean) -> Unit = { _, _ -> },
    val onCatalogStrategyChange: (String, FreezeStrategy) -> Unit = { _, _ -> },
    val onCatalogApply: (Set<String>) -> Unit = {},
    val onCatalogRestore: (Set<String>) -> Unit = {},
    val onCatalogRestoreAll: () -> Unit = {},
    val onCatalogRefresh: () -> Unit = {},
    val onCustomTargetAdd: (String, String) -> Unit = { _, _ -> },
    val onCustomTargetRemove: (String) -> Unit = {},
    val onRequestPrivilege: () -> Unit = {},
    val onOpenShizuku: () -> Unit = {},
    val onOpenDeveloperSettings: () -> Unit = {},
    val onRefreshPrivilege: () -> Unit = {},
    val onThemeStyleChange: (ThemeStyle) -> Unit = {},
    val onThemePaletteChange: (ThemePalette) -> Unit = {},
    val onThemeModeChange: (ThemeMode) -> Unit = {}
)

/**
 * Nemu 布局：Scaffold + TopAppBar + 5 项底部导航。
 *
 * 页面内容全部来自 [DashboardState]，操作只通过 [DashboardCallbacks] 触发。
 * 检测 / 设置 / 日志 / 云控 / 反诈 / COTA 都提供 composable 槽位；槽位为
 * null 时使用内置的只读展示或现有 section。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier,
    initialTab: DashboardTab = DashboardTab.OVERVIEW,
    detectionContent: (@Composable ColumnScope.() -> Unit)? = null,
    settingsContent: (@Composable ColumnScope.() -> Unit)? = null,
    logsContent: (@Composable ColumnScope.() -> Unit)? = null,
    cloudContent: (@Composable ColumnScope.() -> Unit)? = null,
    antiFraudContent: (@Composable ColumnScope.() -> Unit)? = null,
    cotaContent: (@Composable ColumnScope.() -> Unit)? = null,
    advancedContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val selectedTab = DashboardTab.entries[selectedTabIndex]
    val tabStateHolder = rememberSaveableStateHolder()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { DashboardTopBar(state) },
        bottomBar = {
            DashboardNavigationBar(selectedTab) { tab ->
                selectedTabIndex = tab.ordinal
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
            DashboardTab.FREEZE -> FreezeScreen(
                state = state,
                callbacks = callbacks,
                modifier = pageModifier
            )
            DashboardTab.UNFREEZE -> UnfreezeScreen(
                state = state,
                callbacks = callbacks,
                modifier = pageModifier
            )
            DashboardTab.DETECTION -> DetectionPage(
                state = state,
                detectionContent = detectionContent,
                modifier = pageModifier
            )
            DashboardTab.SETTINGS -> SettingsPage(
                state = state,
                callbacks = callbacks,
                settingsContent = settingsContent,
                logsContent = logsContent,
                advancedContent = advancedContent,
                cloudContent = cloudContent,
                antiFraudContent = antiFraudContent,
                cotaContent = cotaContent,
                modifier = pageModifier
            )
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTopBar(state: DashboardState) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NemuMark(size = 34.dp)
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        text = "Nemu",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = topBarSubtitle(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        actions = {
            StatusChip(
                text = state.backendStatus,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .widthIn(max = 176.dp),
                tone = backendTone(state.backendStatus)
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
    Column {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            DashboardTab.entries.forEach { tab ->
                val isSelected = tab == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(tab) },
                    icon = { DashboardNavIcon(tab, isSelected) },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

private enum class NemuGlyph { HOME, SNOWFLAKE, UNLOCK, SEARCH, SETTINGS }

@Composable
private fun NemuMark(size: Dp) {
    // Same resource as the launcher / notification large icon, so the mark is
    // identical everywhere instead of a separately drawn letter.
    Image(
        painter = painterResource(R.drawable.ic_launcher_large),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.22f))
    )
}


@Composable
private fun DashboardNavIcon(tab: DashboardTab, selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glyph = when (tab) {
        DashboardTab.OVERVIEW -> NemuGlyph.HOME
        DashboardTab.FREEZE -> NemuGlyph.SNOWFLAKE
        DashboardTab.UNFREEZE -> NemuGlyph.UNLOCK
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
            for (ray in 0 until 6) {
                val angle = -PI / 2.0 + ray * PI / 3.0
                val ux = cos(angle).toFloat()
                val uy = sin(angle).toFloat()
                drawLine(
                    color,
                    Offset(cx, cy),
                    Offset(cx + ux * radius, cy + uy * radius),
                    stroke,
                    cap = StrokeCap.Round
                )
                val branchX = cx + ux * radius * 0.56f
                val branchY = cy + uy * radius * 0.56f
                for (branch in 0..1) {
                    val side = if (branch == 0) -1.0 else 1.0
                    val branchAngle = angle + PI + side * PI / 4.0
                    drawLine(
                        color,
                        Offset(branchX, branchY),
                        Offset(
                            branchX + cos(branchAngle).toFloat() * radius * 0.28f,
                            branchY + sin(branchAngle).toFloat() * radius * 0.28f
                        ),
                        stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
            drawCircle(color, radius = stroke * 0.9f, center = Offset(cx, cy))
        }
        NemuGlyph.UNLOCK -> {
            // Open padlock: body outline plus a shackle whose right leg is
            // lifted, so Freeze (snowflake) and Unfreeze are distinguishable.
            line(0.24f, 0.50f, 0.76f, 0.50f)
            line(0.24f, 0.50f, 0.24f, 0.82f)
            line(0.76f, 0.50f, 0.76f, 0.82f)
            line(0.24f, 0.82f, 0.76f, 0.82f)
            line(0.34f, 0.50f, 0.34f, 0.32f)
            line(0.34f, 0.32f, 0.62f, 0.32f)
            line(0.62f, 0.32f, 0.70f, 0.22f)
            line(0.50f, 0.62f, 0.50f, 0.72f)
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
        item { StatusExplanationCard(state) }
        item { PrivilegeCard(state, callbacks) }
        if (state.rescueMode) item { RescueModeCard(callbacks) }
        item { BootFreezeCard(state, callbacks) }
        item { SafetyResultCard(state) }
    }
}

/**
 * Plain-language answer to "what is the phone doing right now?". Written for
 * someone who has never used a freeze tool before.
 */
@Composable
private fun StatusExplanationCard(state: DashboardState) {
    val frozen = state.catalog.count { it.applied }
    val planned = state.catalog.count {
        it.enabled && !it.observeOnly && it.strategy != FreezeStrategy.OBSERVE
    }
    SectionCard(
        title = "现在的状态",
        subtitle = "当前策略与实际状态"
    ) {
        StatusLine(
            label = "现在",
            text = if (frozen == 0) {
                "没有应用冻结；Nemu 完全待机。"
            } else {
                "$frozen 个应用已冻结，不能后台运行或自动更新。"
            },
            dotColor = MaterialTheme.colorScheme.primary
        )
        StatusLine(
            label = "计划",
            text = "$planned 个已选；点“立即冻结”才执行。",
            dotColor = MaterialTheme.colorScheme.tertiary
        )
        StatusLine(
            label = "开机",
            text = if (!state.bootFreeze.enabled) {
                "已关闭；重启后需手动冻结。"
            } else if (state.bootFreeze.plannedCount == 0) {
                "开机不会冻结；当前没有已选目标。"
            } else {
                "开机 ${state.bootFreeze.delaySeconds} 秒后冻结 ${state.bootFreeze.plannedCount} 个目标。"
            },
            dotColor = MaterialTheme.colorScheme.secondary
        )
        StatusLine(
            label = "关机",
            text = "正常关机前自动解冻；强制断电后下次开机清理。",
            dotColor = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun StatusLine(label: String, text: String, dotColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrivilegeCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val privilege = state.privilege
    var showAdbHelp by remember { mutableStateOf(false) }
    SectionCard(
        title = "授权",
        subtitle = "Root / Shizuku 执行；ADB 只启动 Shizuku。"
    ) {
        if (privilege == null) {
            Text("正在检查权限…", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
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
                "未授权"
            },
            chip = if (privilege.rootAvailable) "已授权" else "未授权",
            tone = if (privilege.rootAvailable) {
                StatusChipTone.SUCCESS
            } else {
                StatusChipTone.NEUTRAL
            }
        )
        PrivilegeRow(
            title = "Shizuku",
            detail = when {
                privilege.shizukuGranted -> "可执行冻结与解冻"
                privilege.shizukuRunning -> "已运行，未授权"
                else -> "未运行"
            },
            chip = if (privilege.shizukuGranted) "已授权" else "未授权",
            tone = if (privilege.shizukuGranted) {
                StatusChipTone.SUCCESS
            } else {
                StatusChipTone.NEUTRAL
            }
        )
        PrivilegeRow(
            title = "ADB",
            detail = "只用于启动 Shizuku",
            chip = when {
                privilege.shizukuGranted && privilege.shizukuUid == 2000 ->
                    "Shizuku 正在使用"
                privilege.adbEnabled || privilege.wirelessDebuggingEnabled ->
                    "已开启"
                else -> "未开启"
            },
            tone = when {
                privilege.shizukuGranted && privilege.shizukuUid == 2000 ->
                    StatusChipTone.SUCCESS
                privilege.adbEnabled || privilege.wirelessDebuggingEnabled ->
                    StatusChipTone.INFO
                else -> StatusChipTone.NEUTRAL
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = callbacks.onRequestPrivilege,
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (privilege.canFreeze) "重新请求授权" else "请求授权")
            }
            OutlinedButton(
                onClick = callbacks.onRefreshPrivilege,
                enabled = !state.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text("刷新状态")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = callbacks.onOpenShizuku,
                enabled = !state.busy
            ) { Text("打开 Shizuku") }
            TextButton(
                onClick = callbacks.onOpenDeveloperSettings,
                enabled = !state.busy
            ) { Text("开发者选项") }
            TextButton(
                onClick = { showAdbHelp = true },
                enabled = !state.busy
            ) { Text("ADB") }
        }
        Text(
            "请求授权会先尝试 Root，再尝试 Shizuku；没有权限时不会假装成功。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (showAdbHelp) {
        AlertDialog(
            onDismissRequest = { showAdbHelp = false },
            title = { Text("用 ADB 给 Shizuku 授权") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "ADB 是电脑上的调试工具。Nemu 不能直接调用电脑的 ADB，" +
                            "但可以用它启动 Shizuku，再由 Shizuku 把权限借给 Nemu。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("步骤：", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. 手机：设置 → 关于手机 → 连续点版本号开启开发者选项；\n" +
                            "2. 手机：开发者选项 → 打开“USB 调试”（或“无线调试”）；\n" +
                            "3. 电脑：安装 adb，连接手机后执行 adb devices，确认看到设备；\n" +
                            "4. 手机：安装并打开 Shizuku；\n" +
                            "5. 电脑：执行下面的命令启动 Shizuku：\n\n" +
                            "adb shell sh /storage/emulated/0/Android/data/" +
                            "moe.shizuku.privileged.api/start.sh\n\n" +
                            "6. 回到 Nemu，点“请求授权”，允许 Shizuku 授权。",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "如果使用无线调试，Shizuku 里会显示配对码步骤；" +
                            "按 Shizuku 应用内的指引操作即可。没有电脑时，Root 是更简单的办法。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdbHelp = false }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun PrivilegeRow(
    title: String,
    detail: String,
    chip: String,
    tone: StatusChipTone
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusChip(
            text = chip,
            tone = tone
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OverviewHeroCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    val frozen = state.catalog.count { it.applied }
    val planned = state.catalog.count {
        it.enabled && !it.observeOnly && it.strategy != FreezeStrategy.OBSERVE
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onSurface
        ),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            scheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.primaryContainer.copy(alpha = 0.55f),
                            scheme.surfaceContainerLow
                        )
                    )
                )
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("系统状态", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "后台安静，待机更省电",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                NemuMark(56.dp)
            }
            Surface(
                color = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroStat(value = "$frozen", label = "已冻结")
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(scheme.outlineVariant.copy(alpha = 0.55f))
                    )
                    HeroStat(value = "$planned", label = "计划中")
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(scheme.outlineVariant.copy(alpha = 0.55f))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = state.bootFreeze.enabled,
                            onCheckedChange = callbacks.onBootFreezeEnabledChange,
                            enabled = !state.busy,
                            modifier = Modifier.scale(0.78f)
                        )
                        Text(
                            text = "开机冻结",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state.dryRun) {
                Text(
                    text = "预览模式：只记录命令，不修改手机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RescueModeCard(callbacks: DashboardCallbacks) {
    var showConfirm by remember { mutableStateOf(false) }
    SectionCard(
        title = "自动救砖已触发",
        subtitle = "连续 3 次启动未确认成功；模块已禁用，守卫已尝试恢复应用状态。"
    ) {
        Text(
            "系统可能处于不稳定状态。请先确认桌面、设置和 YOYO 正常，" +
                "再清除救援标记。清除后下次开机会重新启用自启动模块，" +
                "但不会立即重新冻结。",
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
                    "清除前会再次检查冻结状态，并尝试恢复 Nemu 停用的应用。" +
                        "任一步未确认成功都会保留救援模式。确定系统已稳定？"
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
    callbacks: DashboardCallbacks
) {
    val boot = state.bootFreeze
    SectionCard(
        title = "开机自动冻结",
        subtitle = "开机 ${boot.delaySeconds} 秒后只执行你已勾选的目标"
    ) {
        if (boot.plannedCount == 0) {
            Text(
                text = "当前没有勾选任何冻结目标；即使开关开启，开机也不会冻结任何应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "开机只处理已勾选的 ${boot.plannedCount} 个目标，不会自动冻结其他应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "仅下次开机生效；关闭不会解冻当前应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = boot.enabled,
                onCheckedChange = callbacks.onBootFreezeEnabledChange,
                enabled = !state.busy
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
        if (boot.lastAttemptText != null) {
            Text(
                text = "上次尝试：${boot.lastAttemptText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (boot.lastSuccessText != null) {
            Text(
                text = "上次处理：${boot.lastSuccessText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick = callbacks.onOpenStartupSettings,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开「应用启动管理」允许自启动")
        }
        Text(
            text = "守卫：未检测到上次干净关机会跳过本次冻结。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SafetyResultCard(state: DashboardState) {
    SectionCard(
        title = "上次安全解冻",
        subtitle = "关机/重启前由 Nemu 的关机保护服务执行；电脑 adb reboot 不会触发。"
    ) {
        ResultRow("关机前", prettyResultText(state.lastShutdownResult))
        ResultRow("开机后", prettyResultText(state.lastBootResult))
        Text(
            text = "安全解冻始终开启；取消勾选只移出策略，不会解冻。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DetectionPage(
    state: DashboardState,
    detectionContent: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier
) {
    if (detectionContent != null) {
        // A full detection screen (with its own LazyColumn) is embedded here.
        // Do not nest it inside the summary LazyColumn.
        Column(modifier = modifier.fillMaxSize()) {
            detectionContent.invoke(this)
        }
        return
    }

    val detection = state.detection
    val hasSummary = detection.lastCheckedText != null ||
        detection.mismatchCount > 0 ||
        detection.note != null

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (hasSummary) {
            item {
                SectionCard(
                    title = "检测摘要",
                    subtitle = detection.note ?: "只读；不提供暂停/停用操作。"
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChip(
                            text = when {
                                detection.mismatchCount > 0 ->
                                    "${detection.mismatchCount} 项策略与实际不一致"
                                detection.lastCheckedText != null ->
                                    "策略与实际一致"
                                else -> "尚未检测"
                            },
                            modifier = Modifier.weight(1f),
                            tone = when {
                                detection.mismatchCount > 0 -> StatusChipTone.WARNING
                                detection.lastCheckedText != null -> StatusChipTone.SUCCESS
                                else -> StatusChipTone.NEUTRAL
                            }
                        )
                        if (detection.lastCheckedText != null) {
                            Text(
                                text = detection.lastCheckedText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        item {
            EmptySlotCard(
                title = "检测页",
                message = "由主会话通过 detectionContent 槽接入 DetectionScreen。" +
                    "此页只读展示策略与实际状态，不自动修复。"
            )
        }
    }
}

@Composable
private fun SettingsPage(
    state: DashboardState,
    callbacks: DashboardCallbacks,
    settingsContent: (@Composable ColumnScope.() -> Unit)?,
    logsContent: (@Composable ColumnScope.() -> Unit)?,
    advancedContent: (@Composable ColumnScope.() -> Unit)?,
    cloudContent: (@Composable ColumnScope.() -> Unit)?,
    antiFraudContent: (@Composable ColumnScope.() -> Unit)?,
    cotaContent: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    label = { Text("偏好设置") }
                )
                FilterChip(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    label = { Text("运行日志") }
                )
                FilterChip(
                    selected = selectedSection == 2,
                    onClick = { selectedSection = 2 },
                    label = { Text("高级控制") }
                )
            }
        }
        if (selectedSection == 0) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val slot = settingsContent
                    if (slot != null) slot.invoke(this)
                    else DefaultSettingsContent(state, callbacks)
                }
            }
        } else if (selectedSection == 1) {
            item {
                SectionHeader(
                    title = "日志",
                    subtitle = "Nemu 操作记录与可选的启动 logcat。"
                )
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val slot = logsContent
                    if (slot != null) slot.invoke(this)
                    else DefaultLogsContent(state, callbacks)
                }
            }
        } else {
            item {
                SectionHeader(
                    title = "高级控制",
                    subtitle = "反诈后台限制（AppOps/待机）与 COTA 厂商云控参数；" +
                        "包级冻结/云控策略统一在“冻结/解冻”页。"
                )
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val slot = advancedContent
                    if (slot != null) {
                        slot.invoke(this)
                    } else {
                        // The legacy fixed-action cloud controls are
                        // intentionally not shown: cloud packages are now
                        // configured on the unified Freeze page, and the old
                        // controls would conflict with the selected
                        // FreezeStrategy.
                        val antiFraud = antiFraudContent
                        if (antiFraud != null) {
                            antiFraud.invoke(this)
                        } else {
                            AntiFraudSection(
                                state = state.antiFraud,
                                busy = state.busy,
                                onApply = callbacks.onAntiFraudApply,
                                onRestore = callbacks.onAntiFraudRestore
                            )
                        }
                        val cota = cotaContent
                        if (cota != null) {
                            cota.invoke(this)
                        } else {
                            CotaSection(
                                state = state.cota,
                                callbacks = callbacks.cotaCallbacks
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSettingsContent(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    AppearanceCard(state, callbacks)

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
                        "关闭：只预览，不修改手机"
                    } else {
                        "开启：点击后会修改手机"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.dryRun) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            Switch(
                checked = !state.dryRun,
                onCheckedChange = { callbacks.onDryRunChange(!it) },
                enabled = !state.busy
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("授权状态", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = state.privilege?.let { privilege ->
                        privilege.activeBackendLabel + if (privilege.canFreeze) {
                            " · 可执行"
                        } else {
                            " · 需授权"
                        }
                    } ?: state.backendStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = callbacks.onRequestPrivilege,
                enabled = !state.busy
            ) {
                Text("请求授权")
            }
        }
        Text(
            text = "不卸载系统应用，所有操作可在“解冻”页恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Theme picker: three visual styles, five palettes, three light/dark modes.
 * All choices persist immediately and apply without restarting the app.
 */
@Composable
private fun AppearanceCard(
    state: DashboardState,
    callbacks: DashboardCallbacks
) {
    SectionCard(
        title = "外观",
        subtitle = "即时生效；深色为纯黑。"
    ) {
        Text("界面风格", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeStyle.entries.forEach { style ->
                FilterChip(
                    selected = state.themeStyle == style,
                    onClick = { callbacks.onThemeStyleChange(style) },
                    label = { Text(style.label) }
                )
            }
        }
        Text("配色", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemePalette.entries.forEach { palette ->
                FilterChip(
                    selected = state.themePalette == palette,
                    onClick = { callbacks.onThemePaletteChange(palette) },
                    leadingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = swatchColorFor(palette),
                            modifier = Modifier.size(16.dp)
                        ) {}
                    },
                    label = { Text(palette.label) }
                )
            }
        }
        Text("明暗模式", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.themeMode == mode,
                    onClick = { callbacks.onThemeModeChange(mode) },
                    label = { Text(mode.label) }
                )
            }
        }
        Text(
            "风格和配色即时生效；深色模式为纯黑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                Text(
                    text = if (state.logSettings.enabled) "日志已开启" else "日志已关闭",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "开关控制常规 Nemu 日志；启动 logcat 抓取会单独写入原始系统日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.logSettings.enabled,
                onCheckedChange = callbacks.onLoggingEnabledChange,
                enabled = !state.logSettings.busy
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
            OutlinedButton(
                onClick = callbacks.onRefreshLogs,
                enabled = !state.logSettings.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text("刷新")
            }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = !state.logSettings.busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("清空")
            }
        }
        OutlinedButton(
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

@Composable
private fun EmptySlotCard(title: String, message: String) {
    SectionCard(title = title) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun topBarSubtitle(state: DashboardState): String = buildString {
    append("睡眠管理")
    val bootCount = state.bootFreeze.bootCount
    if (bootCount != null) {
        append(" · boot #")
        append(bootCount)
    }
}

private fun backendTone(status: String): StatusChipTone = when {
    status.contains("Root") -> StatusChipTone.SUCCESS
    status.contains("Shizuku") && status.contains("未授权") -> StatusChipTone.WARNING
    status.contains("Shizuku") -> StatusChipTone.INFO
    status.contains("未授权") || status.contains("无可用") -> StatusChipTone.ERROR
    else -> StatusChipTone.NEUTRAL
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
