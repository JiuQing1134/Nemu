package com.nemu.hibernate.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemu.hibernate.ui.theme.nemuCard
import com.nemu.hibernate.ui.theme.nemuInset
import com.nemu.hibernate.core.CriticalRoleResolver
import com.nemu.hibernate.core.CustomTargetGuard
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Role-based groups. Root managers, LSPosed itself and LSPosed plugins are
 * three different things; only Honor/OS image packages are "system".
 */
internal enum class AppKind(val label: String) {
    ROOT("Root 管理器"),
    SHIZUKU("Shizuku"),
    SHIZUKU_APP("Shizuku 应用"),
    LSPOSED("LSPosed"),
    LSP_PLUGIN("LSP 插件"),
    SYSTEM("系统"),
    USER("用户")
}

/**
 * Root *managers* only: the app that owns/grant root. Apps that merely
 * request root (Hail, Scene, freezers, tuning tools) are user tools or LSP
 * plugins, not root managers.
 */
private val rootToolPackages = setOf(
    "me.weishu.kernelsu",
    "me.yuki.fina",
    "com.topjohnwu.magisk",
    "com.rifsxd.ksunext",
    "com.sukisu.ultra",
    "me.bmax.apatch"
)

private val rootToolTokens = listOf(
    "kernelsu",
    "magisk",
    "ksunext",
    "sukisu",
    "apatch",
    "wildksu",
    "wildsu"
)

/** Shizuku itself (the privileged service + manager UI). */
private val shizukuManagerPackages = setOf(
    "moe.shizuku.privileged.api",
    // Stellar is a Shizuku-compatible privileged-service manager (fork).
    "roro.stellar.manager"
)

/** Curated from awesome-shizuku; apps that use Shizuku as their backend. */
private val shizukuAppPackages = setOf(
    "app.simple.inure.play",
    "be.casperverswijvelt.unifiedinternetqs",
    "cf.playhi.freezeyou",
    "cn.a10miaomiao.bilidown",
    "cn.ac.lz233.tarnhelm",
    "com.adilhanney.no_more_background",
    "com.aefyr.sai.fdroid",
    "com.akylas.enforcedoze",
    "com.arslan.customanimator",
    "com.aurora.adroid",
    "com.aurora.store",
    "dev.zwander.installwithoptions",
    "me.timschneeberger.shizustore",
    "com.catchingnow.icebox",
    "com.ericlowry.dnstoggle",
    "com.farmerbb.secondscreen.free",
    "com.farmerbb.taskbar",
    "com.leekleak.trafficlight",
    "com.looker.droidify",
    "com.lpw.graypause",
    "com.machiav3lli.fdroid",
    "com.monstera.harbor",
    "com.oasisfeng.island",
    "com.oasisfeng.island.fdroid",
    "com.rbn.qtsettings",
    "com.smartpack.packagemanager",
    "com.thewizrd.simplewear",
    "com.valhalla.thor",
    "com.ysy.switcherfiveg",
    "cu.axel.smartdock",
    "de.marmaro.krt.ffupdater",
    "dev.bikram.obtainx",
    "dev.lexip.hecate",
    "dev.zwander.cellreader",
    "eu.darken.sdmse",
    "io.github.dorumrr.privacyflip",
    "io.github.muntashirakon.AppManager",
    "io.github.probecat.airplanescheduler",
    "io.github.samolego.canta",
    "io.github.sds100.keymapper",
    "io.github.ulysseszh.displaytoggle",
    "io.github.vvb2060.packageinstaller",
    "io.nekohasekai.sfa",
    "me.biplobsd.rsm",
    "me.timschneeberger.rootlessjamesdsp",
    "moe.dic1911.autodnd",
    "moe.reimu.catshare",
    "net.xzos.upgradeall",
    "neth.iecal.curbox",
    "org.alberto97.aodtoggle",
    "org.elnix.dragonlauncher",
    "sh.haven.app",
    "tk.zwander.lockscreenwidgets",
    "tk.zwander.rootactivitylauncher",
    "tk.zwander.wifilist",
)

/** LSPosed manager / patcher packages (the framework UI itself). */
private fun isLsposedManager(packageName: String): Boolean =
    packageName == "org.lsposed.manager" ||
        packageName.startsWith("org.lsposed.manager.") ||
        packageName == "org.lsposed.lspatch" ||
        packageName.startsWith("org.lsposed.lspatch.")

/**
 * Factory/preinstalled apps are system apps. User-installed apps never carry
 * FLAG_SYSTEM (or FLAG_UPDATED_SYSTEM_APP unless they are updating a built-in
 * app), so package-name prefixes are not a reliable or future-proof test.
 */

internal fun classifyApp(
    packageName: String,
    label: String,
    systemFlag: Boolean,
    xposedModule: Boolean = false,
    /** APK contains META-INF/xposed/module.prop or assets/xposed_init. */
    hasXposedModuleFiles: Boolean = false
): AppKind {
    if (packageName in shizukuManagerPackages) return AppKind.SHIZUKU
    if (packageName in rootToolPackages) return AppKind.ROOT
    if (isLsposedManager(packageName)) return AppKind.LSPOSED
    // Covers old meta-data modules (Scene) and modern LSPosed modules that
    // only ship META-INF/xposed/module.prop (Hail, FuseHide). A root-request
    // tool with module files is a plugin first, exactly like Scene.
    if (xposedModule || hasXposedModuleFiles) return AppKind.LSP_PLUGIN
    if (packageName in shizukuAppPackages) return AppKind.SHIZUKU_APP
    val rootTool = rootToolTokens.any { token ->
        packageName.contains(token, ignoreCase = true) ||
            label.contains(token, ignoreCase = true)
    }
    if (rootTool) return AppKind.ROOT
    return if (systemFlag) AppKind.SYSTEM else AppKind.USER
}

/**
 * Modern LSPosed modules do not need an xposedmodule meta-data entry; the
 * module manifest lives inside the APK. Legacy modules use assets/xposed_init.
 * Read the ZIP central directory only (never load code).
 */
private fun hasXposedModuleFiles(info: ApplicationInfo): Boolean =
    runCatching {
        java.util.zip.ZipFile(info.sourceDir).use { zip ->
            zip.getEntry("META-INF/xposed/module.prop") != null ||
                zip.getEntry("META-INF/xposed/java_init.list") != null ||
                zip.getEntry("assets/xposed_init") != null
        }
    }.getOrDefault(false)

/** One installed package, including system services without a launcher icon. */
internal data class AllAppEntry(
    val label: String,
    val packageName: String,
    val kind: AppKind,
    val criticalReason: String?,
    val isSelf: Boolean,
    /** PackageInfo.firstInstallTime; 0 when PackageManager has no record. */
    val installedAt: Long = 0L
)

/**
 * Process-wide app-list cache. Opening 应用 used to re-run PackageManager +
 * a 374-APK module scan on every visit, so the screen sat on a loading card.
 * MainActivity warms this cache off the UI thread; the screen then renders
 * the cached list immediately and refreshes it in the background.
 */
internal object AllAppsCache {
    private const val TTL_MS = 30_000L
    private val mutex = Mutex()

    @Volatile
    private var entries: List<AllAppEntry>? = null

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var warming = false

    fun cached(): List<AllAppEntry>? = entries

    fun warm(context: Context) {
        if (warming || entries != null) return
        warming = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                load(context.applicationContext, force = false)
            } catch (_: Throwable) {
                // The screen has its own retry path.
            } finally {
                warming = false
            }
        }
    }

    suspend fun load(
        context: Context,
        force: Boolean
    ): List<AllAppEntry> = mutex.withLock {
        val now = System.currentTimeMillis()
        val hit = entries
        if (!force && hit != null && now - loadedAt < TTL_MS) {
            return@withLock hit
        }
        val fresh = withContext(Dispatchers.IO) {
            loadAllApps(context.applicationContext)
        }
        entries = fresh
        loadedAt = now
        fresh
    }
}

private enum class AllAppsFilter(val label: String) {
    ALL("全部"),
    ROOT("Root 管理器"),
    SHIZUKU("Shizuku"),
    LSPOSED("LSPosed"),
    LSP_PLUGIN("插件"),
    SYSTEM("系统"),
    USER("用户"),
    ADDED("已添加")
}

/** The risk a target would get when added from this screen. */
private fun allAppRisk(app: AllAppEntry): RiskLevel =
    if (app.kind != AppKind.USER || app.criticalReason != null) {
        RiskLevel.RED
    } else {
        RiskLevel.YELLOW
    }

/** A throw-away target used only to render strategy labels/availability. */
private fun allAppTarget(app: AllAppEntry): Target =
    TargetCatalog.customTarget(app.packageName, app.label, allAppRisk(app))

internal enum class AppSortField(val label: String) {
    NAME("按名称排序"),
    INSTALL("按安装时间排序")
}

private fun sortApps(
    apps: List<AllAppEntry>,
    field: AppSortField,
    nameAscending: Boolean,
    timeAscending: Boolean
): List<AllAppEntry> = when (field) {
    AppSortField.NAME -> {
        val comparator = if (nameAscending) {
            compareBy<AllAppEntry> { it.label.lowercase() }
                .thenBy { it.packageName }
        } else {
            compareByDescending<AllAppEntry> { it.label.lowercase() }
                .thenBy { it.packageName }
        }
        apps.sortedWith(comparator)
    }
    AppSortField.INSTALL -> {
        val comparator = if (timeAscending) {
            compareBy<AllAppEntry> { it.installedAt }
                .thenBy { it.label.lowercase() }
        } else {
            compareByDescending<AllAppEntry> { it.installedAt }
                .thenBy { it.label.lowercase() }
        }
        apps.sortedWith(comparator)
    }
}

private fun loadAllApps(
    context: Context,
    deepScan: Boolean = true
): List<AllAppEntry> {
    val pm = context.packageManager
    // Installed launchers/IMEs are not identifiable by a name heuristic:
    // e.g. Teslacoil's launcher or com.baidu.input. Resolve the real roles
    // once per list load and use them for warnings/risk.
    val dynamicRoles = CriticalRoleResolver.roles(context)
    return pm.getInstalledPackages(PackageManager.GET_META_DATA)
        .mapNotNull { pkg ->
            val info = pkg.applicationInfo ?: return@mapNotNull null
            val label = runCatching {
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(info.packageName)
            val system = info.flags and (
                ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                ) != 0
            val self = CustomTargetGuard.isSelf(info.packageName)
            val xposedModule = runCatching {
                info.metaData?.get("xposedmodule")?.toString()
                    ?.equals("true", ignoreCase = true) == true
            }.getOrDefault(false)
            // Avoid opening the ZIP for modules already proven by meta-data.
            // The first paint uses the fast path; the deep pass updates the
            // module chips seconds later without blocking the list.
            val xposedModuleFiles = deepScan && !xposedModule &&
                hasXposedModuleFiles(info)
            AllAppEntry(
                label = label.ifBlank { info.packageName },
                packageName = info.packageName,
                kind = classifyApp(
                    info.packageName,
                    label,
                    system,
                    xposedModule,
                    xposedModuleFiles
                ),
                criticalReason = if (self) {
                    null
                } else {
                    CustomTargetGuard.criticalReason(
                        info.packageName,
                        dynamicRoles
                    )
                },
                isSelf = self,
                installedAt = pkg.firstInstallTime
            )
        }
        .sortedWith(
            // Root tools first, then ordinary apps, then the long tail of
            // iconless system services. Enum ordinal would put SYSTEM before
            // USER, burying the user's real apps under hundreds of services.
            compareBy<AllAppEntry> {
                when (it.kind) {
                    AppKind.ROOT -> 0
                    AppKind.SHIZUKU -> 1
                    AppKind.SHIZUKU_APP -> 2
                    AppKind.LSPOSED -> 3
                    AppKind.LSP_PLUGIN -> 4
                    AppKind.USER -> 5
                    AppKind.SYSTEM -> 6
                }
            }.thenBy { it.label.lowercase() }
        )
}

@Composable
private fun SortArrow(up: Boolean, active: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                if (active) scheme.primary else scheme.nemuInset()
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(9.dp)) {
            val color = if (active) scheme.onPrimary else scheme.onSurfaceVariant
            val stroke = 1.4.dp.toPx()
            val cx = size.width / 2f
            val top = size.height * 0.15f
            val bottom = size.height * 0.85f
            val headY = if (up) top else bottom
            val headDy = if (up) size.height * 0.28f else -size.height * 0.28f
            drawLine(
                color = color,
                start = Offset(cx, top),
                end = Offset(cx, bottom),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(cx, headY),
                end = Offset(size.width * 0.15f, headY + headDy),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(cx, headY),
                end = Offset(size.width * 0.85f, headY + headDy),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SortMenuRow(
    label: String,
    ascending: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.width(136.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) scheme.primary else scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // Only the active field shows its direction; the other row
                // keeps the same width via an invisible spacer.
                if (active) {
                    SortArrow(up = ascending, active = true)
                } else {
                    Spacer(Modifier.size(18.dp))
                }
            }
        },
        onClick = onClick
    )
}

@Composable
internal fun AppSortButton(
    field: AppSortField,
    nameAscending: Boolean,
    timeAscending: Boolean,
    onNameClick: () -> Unit,
    onTimeClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Box(
            modifier = Modifier
                // No 30dp circle / ripple: the hit area hugs the glyph; a
                // press dims the bars instead of flashing a grey circle.
                .size(30.dp)
                .noRippleClickable { expanded = true }
                .semantics { contentDescription = "排序" },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                Modifier.size(width = 11.dp, height = 14.dp)
            ) {
                val stroke = 2.8.dp.toPx()
                // Material's standard "sort" glyph proportions:
                // 1 : 2/3 : 1/3 (18 / 12 / 6 on a 24dp grid), centered here.
                // Vertical gap reduced by ~1/3 (6.1dp -> 4dp between
                // stroke centers) while keeping the group centered.
                val ys = listOf(
                    size.height * 0.25f,
                    size.height * 0.5f,
                    size.height * 0.75f
                )
                val lengths = listOf(
                    size.width,
                    size.width * 0.667f,
                    size.width * 0.333f
                )
                ys.indices.forEach { index ->
                    val length = lengths[index]
                    val startX = (size.width - length) / 2f
                    drawLine(
                        color = scheme.onSurfaceVariant,
                        start = Offset(startX, ys[index]),
                        end = Offset(startX + length, ys[index]),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SortMenuRow(
                label = "按名称排序",
                ascending = nameAscending,
                active = field == AppSortField.NAME,
                onClick = {
                    expanded = false
                    onNameClick()
                }
            )
            SortMenuRow(
                label = "按安装时间排序",
                ascending = timeAscending,
                active = field == AppSortField.INSTALL,
                onClick = {
                    expanded = false
                    onTimeClick()
                }
            )
        }
    }
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
    onOpenApplications: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(AllAppsCache.cached()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var sortFieldIndex by rememberSaveable { mutableIntStateOf(0) }
    var nameAscending by rememberSaveable { mutableStateOf(true) }
    var timeAscending by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingCritical by remember { mutableStateOf<List<AllAppEntry>>(emptyList()) }
    var riskAcknowledged by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastAddedPackages by remember { mutableStateOf(emptySet<String>()) }
    // Per-app strategy chosen before the target is added. Batch add uses
    // this map, so adding and strategy selection are one step.
    var presetByPackage by remember {
        mutableStateOf<Map<String, StrategyPreset>>(emptyMap())
    }
    // Boot-freeze selection made before the target exists. For an added
    // target the switch writes through immediately via onCatalogToggle.
    var planByPackage by remember {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    var expandedPackages by remember {
        mutableStateOf(emptySet<String>())
    }
    var showAddedGroup by rememberSaveable { mutableStateOf(true) }
    var showOtherGroup by rememberSaveable { mutableStateOf(true) }

    // Back unwinds the current picker step instead of leaving the app:
    // keyboard → search → category → selection → (then normal back). Hiding
    // the IME first keeps the current results visible while the user picks.
    val backFocusManager = LocalFocusManager.current
    val backKeyboard = LocalSoftwareKeyboardController.current
    val backImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(
        enabled = backImeVisible ||
            query.isNotBlank() ||
            filterIndex != 0 ||
            selected.isNotEmpty()
    ) {
        when {
            backImeVisible -> {
                backKeyboard?.hide()
                backFocusManager.clearFocus()
            }
            query.isNotBlank() -> query = ""
            filterIndex != 0 -> filterIndex = 0
            else -> selected = emptySet()
        }
    }

    LaunchedEffect(context, reloadToken) {
        loadError = null
        if (apps == null) apps = AllAppsCache.cached()
        if (apps == null) {
            // First paint: PackageManager only, no 374-APK module scan.
            apps = runCatching {
                withContext(Dispatchers.IO) {
                    loadAllApps(context.applicationContext, deepScan = false)
                }
            }.getOrNull()
        }
        runCatching {
            AllAppsCache.load(
                context.applicationContext,
                force = reloadToken > 0
            )
        }.onSuccess {
            apps = it
            loadError = null
        }.onFailure {
            // A stale cached list is still better than an error card.
            if (apps == null) {
                loadError = it.message ?: it.javaClass.simpleName
            }
        }
    }

    val filter = AllAppsFilter.entries.getOrElse(filterIndex) {
        AllAppsFilter.ALL
    }
    val addedPackages = remember(state.catalog) {
        state.catalog.map { it.packageName }.toSet()
    }
    val catalogByPackage = remember(state.catalog) {
        state.catalog.associateBy { it.packageName }
    }
    val all = apps.orEmpty()
    val sortField = AppSortField.entries.getOrElse(sortFieldIndex) {
        AppSortField.NAME
    }
    val filtered = remember(
        all,
        filter,
        query,
        addedPackages,
        sortField,
        nameAscending,
        timeAscending
    ) {
        val byFilter = when (filter) {
            AllAppsFilter.ALL -> all
            AllAppsFilter.ROOT -> all.filter { it.kind == AppKind.ROOT }
            AllAppsFilter.SHIZUKU -> all.filter {
                it.kind == AppKind.SHIZUKU ||
                    it.kind == AppKind.SHIZUKU_APP
            }
            AllAppsFilter.LSPOSED -> all.filter { it.kind == AppKind.LSPOSED }
            AllAppsFilter.LSP_PLUGIN ->
                all.filter { it.kind == AppKind.LSP_PLUGIN }
            AllAppsFilter.SYSTEM -> all.filter { it.kind == AppKind.SYSTEM }
            AllAppsFilter.USER -> all.filter { it.kind == AppKind.USER }
            AllAppsFilter.ADDED -> all.filter { it.packageName in addedPackages }
        }
        val searched = if (query.isBlank()) {
            byFilter
        } else {
            byFilter.filter {
                matchesAppQuery(it.label, it.packageName, query)
            }
        }
        val sorted = sortApps(
            searched,
            field = sortField,
            nameAscending = nameAscending,
            timeAscending = timeAscending
        )
        // Targets already added to Nemu are the ones users come back to
        // manage; keep them pinned above the rest of the installed list while
        // preserving the selected name/time order inside each group.
        val (addedRows, otherRows) = sorted.partition {
            it.packageName in addedPackages
        }
        addedRows + otherRows
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
    val addedVisible = filtered.filter { it.packageName in addedPackages }
    val otherVisible = filtered.filter { it.packageName !in addedPackages }
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
                allAppRisk(app),
                presetByPackage[app.packageName] ?: StrategyPreset.PAUSE,
                planByPackage[app.packageName] == true
            )
        }
        lastAddedPackages = entries.mapTo(mutableSetOf()) { it.packageName }
        message = "已添加 ${entries.size} 个。"
        // The inline message is at the top of the list and can be off-screen
        // after adding from deep in the list; make the next step visible.
        Toast.makeText(
            context,
            "已添加 ${entries.size} 个",
            Toast.LENGTH_LONG
        ).show()
        selected = emptySet()
    }

    val renderAppRow: @Composable (AllAppEntry) -> Unit = { app ->
        val catalogRow = catalogByPackage[app.packageName]
        val target = remember(app.packageName) {
            allAppTarget(app)
        }
        val preset = catalogRow?.preset
            ?: presetByPackage[app.packageName]
            ?: StrategyPreset.PAUSE
        val plan = catalogRow?.enabled
            ?: (planByPackage[app.packageName] == true)
        val planEnabled = if (catalogRow != null) {
            catalogRow.active && !state.busy && !state.rescueMode
        } else {
            preset != StrategyPreset.OBSERVE && !state.busy
        }
        AllAppRow(
            app = app,
            added = catalogRow != null,
            addedPreset = catalogRow?.preset,
            selected = app.packageName in selected,
            preset = preset,
            plan = plan,
            planEnabled = planEnabled,
            target = target,
            rootAvailable = state.rootAvailable,
            expanded = app.packageName in expandedPackages,
            onToggleExpanded = {
                if (app.packageName in expandedPackages) {
                    expandedPackages =
                        expandedPackages - app.packageName
                } else {
                    expandedPackages =
                        expandedPackages + app.packageName
                }
            },
            onPresetChange = { next ->
                presetByPackage =
                    presetByPackage + (app.packageName to next)
                if (next == StrategyPreset.OBSERVE) {
                    planByPackage =
                        planByPackage + (app.packageName to false)
                }
            },
            onPlanChange = { next ->
                if (catalogRow != null) {
                    callbacks.onCatalogToggle(
                        app.packageName,
                        next
                    )
                } else {
                    planByPackage =
                        planByPackage + (app.packageName to next)
                }
            },
            onToggle = {
                selected = if (app.packageName in selected) {
                    selected - app.packageName
                } else {
                    selected + app.packageName
                }
            }
        )
    }

    val listState = rememberLazyListState()
    Column(modifier = modifier.fillMaxSize()) {
        // Pinned header: 批量/全选 must stay reachable while scrolling a
        // long installed-app list. Search stays here too, so the selection
        // controls never scroll away from the top-right corner.
        CatalogSearchRow(
            query = query,
            onQueryChange = { query = it },
            placeholder = "应用 / 包名 / 拼音 / 首字母",
            busy = state.busy,
            allSelected = selectable.isNotEmpty() &&
                selectable.all { it.packageName in selected },
            selectAllEnabled = selectable.isNotEmpty(),
            onSelectAll = {
                selected = selected +
                    selectable.map { it.packageName }
            },
            onClearVisible = {
                // 全选 pill toggles only the current visible/filtered set;
                // hidden selections survive because the catalog keeps them.
                // The bottom bar's × pill clears everything.
                selected = selected -
                    selectable.mapTo(mutableSetOf()) { it.packageName }
            },
            modifier = Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 4.dp
            )
        )
        // Categories stay pinned with the search row; changing category deep
        // in a 300+ app list otherwise forces a scroll back to the top.
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
                horizontalArrangement = Arrangement.spacedBy(FilterChipGap)
            ) {
                AllAppsFilter.entries.forEachIndexed { index, option ->
                    NemuFilterChip(
                        selected = filterIndex == index,
                        onClick = { filterIndex = index },
                        label = {
                            Text(
                                if (option == AllAppsFilter.ALL) {
                                    "全部 ${all.size}"
                                } else {
                                    option.label
                                },
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontSize = 11.sp)
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
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The ALL filter chip already shows the total; repeating it here
            // as "共 N 个" was redundant. Only the post-add message remains.
            message?.let { text ->
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                onOpenApplications(lastAddedPackages)
                            }
                        ) {
                            Text("去设置策略")
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
                                "没有名称、包名或拼音包含“$query”的应用。" +
                                    "可尝试全拼或首字母，如 xitong、xtgx。"
                            } else if (filter == AllAppsFilter.ADDED) {
                                "还没有添加到 Nemu 的应用。"
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
                if (otherVisible.isNotEmpty()) {
                    item(key = "other-header") {
                        CollapsibleGroupHeader(
                            title = "未添加",
                            count = otherVisible.size,
                            expanded = showOtherGroup,
                            onToggle = { showOtherGroup = !showOtherGroup }
                        )
                    }
                    if (showOtherGroup) {
                        items(otherVisible, key = { it.packageName }) { app ->
                            renderAppRow(app)
                        }
                    }
                }
                if (addedVisible.isNotEmpty()) {
                    item(key = "added-header") {
                        CollapsibleGroupHeader(
                            title = "已添加",
                            count = addedVisible.size,
                            expanded = showAddedGroup,
                            onToggle = { showAddedGroup = !showAddedGroup }
                        )
                    }
                    if (showAddedGroup) {
                        items(addedVisible, key = { it.packageName }) { app ->
                            renderAppRow(app)
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(4.dp)) }
        }
        FastScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
        )
        }

        if (selectedEntries.isNotEmpty()) {
            SelectionActionBar(
                selectedCount = selectedEntries.size,
                actionLabel = "添加 ${selectedEntries.size} 个",
                enabled = !state.busy,
                onClear = { selected = emptySet() },
                onAction = {
                    val critical = selectedEntries.filter {
                        it.criticalReason != null ||
                            it.kind == AppKind.ROOT ||
                            it.kind == AppKind.SHIZUKU ||
                            it.kind == AppKind.SHIZUKU_APP ||
                            it.kind == AppKind.LSPOSED ||
                            it.kind == AppKind.LSP_PLUGIN
                    }
                    if (critical.isEmpty()) {
                        commit(selectedEntries)
                    } else {
                        // Confirm exactly what will be added: the whole
                        // selection, not only the warning rows.
                        pendingCritical = selectedEntries
                        riskAcknowledged = false
                    }
                }
            )
        }
    }

    if (pendingCritical.isNotEmpty()) {
        val toolEntries = pendingCritical.filter {
            it.kind == AppKind.ROOT ||
                it.kind == AppKind.SHIZUKU ||
                it.kind == AppKind.SHIZUKU_APP ||
                it.kind == AppKind.LSPOSED ||
                it.kind == AppKind.LSP_PLUGIN
        }
        val systemTools = pendingCritical.filter {
            it.kind == AppKind.SYSTEM
        }
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
                            "以下系统或关键应用若被冻结、停用或隐藏，可能导致无法开机、没有桌面、没有输入法，或无法恢复：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (toolEntries.isNotEmpty()) {
                        Text(
                            "以下 Root 管理器、Shizuku、LSPosed、LSP 插件不是系统应用，但被冻结、停用或隐藏后可能丢失 Root、模块、插件或授权，导致无法恢复：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    val keyEntries = pendingCritical.filter {
                        it.kind != AppKind.USER || it.criticalReason != null
                    }
                    val normalEntries = pendingCritical - keyEntries.toSet()
                    keyEntries.forEach { app ->
                        Text(
                            "${appTitle(app.label, app.packageName)} · " +
                                (app.criticalReason ?: app.kind.label),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (normalEntries.isNotEmpty()) {
                        Text(
                            "其他应用：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        normalEntries.forEach { app ->
                            Text(
                                appTitle(app.label, app.packageName),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "只登记目标，不改变应用状态。",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllAppRow(
    app: AllAppEntry,
    added: Boolean,
    addedPreset: StrategyPreset?,
    selected: Boolean,
    preset: StrategyPreset,
    plan: Boolean,
    planEnabled: Boolean,
    target: Target,
    rootAvailable: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPresetChange: (StrategyPreset) -> Unit,
    onPlanChange: (Boolean) -> Unit,
    onToggle: () -> Unit
) {
    val canSelect = !app.isSelf && !added
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = cardPress,
                    indication = null,
                    onClick = onToggleExpanded
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NemuSmallCheckbox(
                    checked = selected,
                    enabled = canSelect,
                    onCheckedChange = { onToggle() },
                    contentDescription = "选择 ${app.label}",
                    touchSize = 32.dp,
                    // Flush with the icon's top edge (32dp box, 14dp glyph).
                    modifier = Modifier.offset(y = (-9).dp)
                )
                AppIcon(packageName = app.packageName, size = 34.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (shouldShowPackageName(app.label, app.packageName)) {
                        Text(
                            app.packageName,
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
                        StatusChip(
                            if (app.isSelf) "Nemu 自身" else app.kind.label,
                            // System categories are red; user/Root/Shizuku/
                            // LSPosed categories are the blue capsule. The
                            // deep-uninstall list uses the same mapping.
                            tone = if (app.kind == AppKind.SYSTEM) {
                                StatusChipTone.ERROR
                            } else {
                                StatusChipTone.CATEGORY
                            }
                        )
                        // The group header already says 已添加; the per-card
                        // "已添加" tag is redundant. Only the active strategy
                        // stays on an added card. Unadded cards show the
                        // category only; expanding the card reveals the
                        // strategy chooser and the boot plan.
                        if (added) {
                            addedPreset?.let {
                                StatusChip(
                                    TargetCopy.label(it),
                                    tone = StatusChipTone.THEME
                                )
                            }
                        }
                    }
                }
                Text(
                    text = if (expanded) "⌃" else "›",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (expanded) {
                if (!added) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FilterChipGap),
                        verticalArrangement = Arrangement.spacedBy(FilterChipGap)
                    ) {
                        TargetCopy.presetsFor(target).forEach { option ->
                            NemuFilterChip(
                                selected = option == preset,
                                onClick = { onPresetChange(option) },
                                enabled = TargetCopy.unavailableReason(
                                    target,
                                    option,
                                    rootAvailable
                                ) == null,
                                label = {
                                    Text(
                                        TargetCopy.label(option),
                                        style = MaterialTheme.typography
                                            .labelSmall.copy(fontSize = 11.sp)
                                    )
                                }
                            )
                        }
                    }
                }
                // The plan switch is revealed by tapping the card, exactly
                // like the observation/freeze strategy chips above.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "开机冻结",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    NemuMiniSwitch(
                        checked = plan,
                        onCheckedChange = onPlanChange,
                        enabled = planEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "开机冻结：${app.label}"
                        }
                    )
                }
            }
        }
    }
}
