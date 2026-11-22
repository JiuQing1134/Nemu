package com.nemu.hibernate

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudResult
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaController
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaResult
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.AntiFraudSettings
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.LogSettings
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.detection.DetectionReader
import com.nemu.hibernate.detection.DetectionSnapshot
import com.nemu.hibernate.detection.PackageProcessListReader
import com.nemu.hibernate.logging.PersistentLogger
import com.nemu.hibernate.policy.AntiFraudController
import com.nemu.hibernate.policy.BootCount
import com.nemu.hibernate.policy.BootFreezeScheduler
import com.nemu.hibernate.policy.BootGuard
import com.nemu.hibernate.policy.SafetyService
import com.nemu.hibernate.policy.TargetFreezeService
import com.nemu.hibernate.policy.TargetPolicy
import com.nemu.hibernate.privilege.AndroidPackageStatusReader
import com.nemu.hibernate.privilege.BackendManager
import com.nemu.hibernate.privilege.PackageStatusReader
import com.nemu.hibernate.privilege.PackageEnabledStateReader
import com.nemu.hibernate.privilege.AndroidComponentStateReader
import com.nemu.hibernate.privilege.AndroidPackageEnabledStateReader
import com.nemu.hibernate.privilege.ComponentStateReader
import com.nemu.hibernate.privilege.PrivilegeBackend
import com.nemu.hibernate.privilege.PrivilegeOverview
import com.nemu.hibernate.ui.AntiFraudUiState
import com.nemu.hibernate.ui.BootFreezeUiState
import com.nemu.hibernate.ui.CatalogTargetUiState
import com.nemu.hibernate.ui.CotaCallbacks
import com.nemu.hibernate.ui.CotaUiState
import com.nemu.hibernate.ui.DashboardCallbacks
import com.nemu.hibernate.ui.DashboardScreen
import com.nemu.hibernate.ui.DashboardState
import com.nemu.hibernate.ui.DashboardTab
import com.nemu.hibernate.ui.DetectionScreen
import com.nemu.hibernate.ui.DetectionScreenUiState
import com.nemu.hibernate.ui.DetectionUiState
import com.nemu.hibernate.ui.LogUiState
import com.nemu.hibernate.ui.TargetActualCache
import com.nemu.hibernate.ui.TargetActualUiState
import com.nemu.hibernate.ui.theme.NemuAppTheme
import com.nemu.hibernate.ui.theme.ThemeMode
import com.nemu.hibernate.ui.theme.ThemePalette
import com.nemu.hibernate.ui.theme.ThemeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var antiFraudSettings: AntiFraudSettings
    private lateinit var bootFreezeSettings: BootFreezeSettings
    private lateinit var logSettings: LogSettings
    private lateinit var manager: BackendManager
    private lateinit var statusReader: PackageStatusReader
    private lateinit var enabledStateReader: PackageEnabledStateReader
    private lateinit var componentStateReader: ComponentStateReader
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ActionLog.info("Shizuku 权限已授予")
            } else {
                ActionLog.warn("Shizuku 权限被拒绝")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()
        settings = Settings(this)
        antiFraudSettings = AntiFraudSettings(this)
        bootFreezeSettings = BootFreezeSettings(this)
        logSettings = LogSettings(this)
        manager = BackendManager(settings)
        statusReader = AndroidPackageStatusReader(this)
        enabledStateReader = AndroidPackageEnabledStateReader(this)
        componentStateReader = AndroidComponentStateReader(this)

        // The safety service is started only when something is actually
        // frozen; opening the app with no frozen targets must not leave a
        // foreground process behind (see SafetyService.sync).
        // Schedule even when the activity is started behind the keyguard and
        // Compose composition has not run yet (e.g. FinaSU boot script).
        CoroutineScope(Dispatchers.IO).launch {
            BootFreezeScheduler.ensureScheduled(this@MainActivity)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        setContent {
            var themeStyle by remember {
                mutableStateOf(
                    ThemeStyle.entries.firstOrNull { it.name == settings.themeStyle }
                        ?: ThemeStyle.MATERIAL3
                )
            }
            var themePalette by remember {
                mutableStateOf(
                    ThemePalette.entries.firstOrNull { it.name == settings.themePalette }
                        ?: ThemePalette.MINT
                )
            }
            var themeMode by remember {
                mutableStateOf(
                    ThemeMode.entries.firstOrNull { it.name == settings.themeMode }
                        ?: ThemeMode.SYSTEM
                )
            }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            NemuAppTheme(
                style = themeStyle,
                palette = themePalette,
                darkTheme = darkTheme
            ) {
                // System bars follow the app's forced light/dark choice, not
                // just the system setting. Dark uses true black for OLED.
                LaunchedEffect(darkTheme) {
                    val background = if (darkTheme) {
                        0xFF000000.toInt()
                    } else {
                        0xFFF2F7FA.toInt()
                    }
                    window.statusBarColor = background
                    window.navigationBarColor = background
                    WindowCompat.getInsetsController(
                        window,
                        window.decorView
                    ).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                val scope = rememberCoroutineScope()
                var backendStatus by remember { mutableStateOf("检测中…") }
                var privilegeOverview by remember {
                    mutableStateOf<PrivilegeOverview?>(null)
                }
                var busy by remember { mutableStateOf(false) }
                fun launchBusy(action: suspend () -> Unit) = scope.launch {
                    busy = true
                    try {
                        action()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        ActionLog.error(
                            "操作异常：${throwable.message ?: throwable.javaClass.simpleName}"
                        )
                    } finally {
                        busy = false
                    }
                }
                var dryRun by remember { mutableStateOf(settings.dryRun) }
                var lastShutdownResult by remember {
                    mutableStateOf(settings.lastShutdownResult ?: "尚未执行")
                }
                var lastBootResult by remember {
                    mutableStateOf(settings.lastBootResult ?: "尚未执行")
                }
                val logs by ActionLog.entries.collectAsState()

                // Anti-fraud state.
                var antiFraudConfig by remember {
                    mutableStateOf(antiFraudSettings.config())
                }
                var antiFraudResults by remember {
                    mutableStateOf<List<AntiFraudResult>>(emptyList())
                }
                var antiFraudLastResult by remember {
                    mutableStateOf(antiFraudSettings.lastResult ?: "尚未执行")
                }

                // COTA state.
                var cotaEnabled by remember { mutableStateOf(settings.cotaEnabled) }
                var cotaRootAvailable by remember { mutableStateOf(false) }
                var cotaFiles by remember {
                    mutableStateOf<List<CotaFile>>(emptyList())
                }
                var cotaSelected by remember {
                    mutableStateOf<Set<String>>(emptySet())
                }
                var cotaBackups by remember {
                    mutableStateOf<List<CotaBackupSummary>>(emptyList())
                }
                var cotaLastBackupId by remember { mutableStateOf<String?>(null) }
                var cotaMessage by remember { mutableStateOf<String?>(null) }

                // Boot freeze state. The persisted values are copied into
                // Compose state by refreshBootFreezeState() instead of being
                // re-read (and BootCount.current doing a ContentResolver
                // binder call) on every recomposition.
                var bootFreezeEnabled by remember {
                    mutableStateOf(bootFreezeSettings.bootFreezeEnabled)
                }
                var bootFreezeDelaySeconds by remember {
                    mutableStateOf(bootFreezeSettings.bootFreezeDelaySeconds)
                }
                var bootFreezeResultBootCount by remember {
                    mutableStateOf(bootFreezeSettings.lastBootFreezeBootCount)
                }
                var currentBoot by remember {
                    mutableStateOf(BootCount.current(this@MainActivity))
                }
                var bootFreezeResult by remember {
                    mutableStateOf(bootFreezeSettings.lastBootFreezeResult)
                }
                var bootFreezeSucceeded by remember {
                    mutableStateOf(bootFreezeSettings.lastBootFreezeSucceeded)
                }
                var bootFreezeAttemptTime by remember {
                    mutableStateOf(bootFreezeSettings.lastBootFreezeAttemptTime)
                }
                var bootFreezeFinishTime by remember {
                    mutableStateOf(bootFreezeSettings.lastBootFreezeTime)
                }
                var rescueMode by remember { mutableStateOf(false) }
                var targetActual by remember {
                    mutableStateOf<Map<String, TargetActualUiState>>(emptyMap())
                }
                var catalog by remember {
                    mutableStateOf<List<CatalogTargetUiState>>(emptyList())
                }
                var lastCatalogResult by remember {
                    mutableStateOf<String?>(null)
                }

                // Detection/log state.
                var detectionSummary by remember { mutableStateOf(DetectionUiState()) }
                var detectionScreen by remember {
                    mutableStateOf(DetectionScreenUiState())
                }
                var logUi by remember {
                    mutableStateOf(
                        LogUiState(enabled = logSettings.loggingEnabled)
                    )
                }

                fun refreshBootGuardState() {
                    scope.launch {
                        rescueMode = BootGuard.isRescue(manager.getRoot())
                    }
                }

                fun refreshBootFreezeState() {
                    currentBoot = BootCount.current(this@MainActivity)
                    bootFreezeDelaySeconds =
                        bootFreezeSettings.bootFreezeDelaySeconds
                    bootFreezeResultBootCount =
                        bootFreezeSettings.lastBootFreezeBootCount
                    bootFreezeResult = bootFreezeSettings.lastBootFreezeResult
                    bootFreezeSucceeded =
                        bootFreezeSettings.lastBootFreezeSucceeded
                    bootFreezeAttemptTime =
                        bootFreezeSettings.lastBootFreezeAttemptTime
                    bootFreezeFinishTime =
                        bootFreezeSettings.lastBootFreezeTime
                }

                val actualCache = remember { TargetActualCache() }
                val refreshQueue = remember { RefreshQueue() }
                val detectionFlight = remember { DetectionFlight() }
                var shutdownScriptJob by remember { mutableStateOf<Job?>(null) }

                fun scheduleShutdownScriptWrite() {
                    shutdownScriptJob?.cancel()
                    shutdownScriptJob = scope.launch {
                        // Debounce: several policy edits in a row (selection,
                        // strategy switch) only need one root write.
                        delay(SHUTDOWN_SCRIPT_DEBOUNCE_MS)
                        withContext(Dispatchers.IO) {
                            runCatching {
                                manager.getRoot()?.let { root ->
                                    com.nemu.hibernate.policy.ShutdownScript.write(
                                        root,
                                        TargetPolicy(this@MainActivity)
                                    )
                                }
                            }
                        }
                    }
                }

                fun cleanupStaleHiddenMarkers() {
                    // Clear HIDE markers that no longer have an ownership
                    // baseline (e.g. written by an older build). Never unhide
                    // here: a package hidden by the user/OEM is not ours.
                    val cleanupPolicy = TargetPolicy(this@MainActivity)
                    TargetCatalog.all.forEach { target ->
                        if (cleanupPolicy.strategy(target) == FreezeStrategy.HIDE &&
                            cleanupPolicy.isApplied(target) &&
                            !cleanupPolicy.ownsHidden(target)
                        ) {
                            cleanupPolicy.setApplied(target, false)
                            cleanupPolicy.setOriginalHidden(target, null)
                        }
                    }
                }

                fun rebuildCatalog() {
                    scope.launch {
                        catalog = withContext(Dispatchers.IO) {
                            buildCatalog(targetActual)
                        }
                    }
                }

                fun refreshTargetActual(
                    targets: List<Target> = TargetCatalog.all,
                    force: Boolean = true
                ) {
                    // Selection-only redraws reuse the cached snapshot. Only an
                    // explicit refresh or a real freeze/restore passes
                    // force = true.
                    if (!force && actualCache.isFresh(targets)) {
                        rebuildCatalog()
                        return
                    }
                    refreshQueue.pending += targets.map { it.packageName }
                    refreshQueue.pendingForce = refreshQueue.pendingForce || force
                    if (refreshQueue.job?.isActive == true) {
                        // Coalesce: the running read picks this batch up when
                        // it finishes instead of stacking another shell probe.
                        return
                    }
                    refreshQueue.job = scope.launch {
                        try {
                            while (true) {
                                val batch = refreshQueue.pending.toSet()
                                val batchForce = refreshQueue.pendingForce
                                refreshQueue.pending.clear()
                                refreshQueue.pendingForce = false
                                if (batch.isEmpty()) break
                                val batchTargets = TargetCatalog.all.filter {
                                    it.packageName in batch
                                }
                                val actual = actualCache.read(
                                    targets = batchTargets,
                                    force = batchForce,
                                    advanceTtl = batchTargets.size ==
                                        TargetCatalog.all.size,
                                    reader = { readTargetActual(it) }
                                )
                                targetActual = actual
                                catalog = withContext(Dispatchers.IO) {
                                    buildCatalog(actual)
                                }
                                // Ownership cleanup and the shutdown restore
                                // script are durability work, never UI work.
                                val root = withContext(Dispatchers.IO) {
                                    cleanupStaleHiddenMarkers()
                                    manager.getRoot()
                                }
                                cotaRootAvailable = root != null
                                SafetyService.sync(this@MainActivity)
                                scheduleShutdownScriptWrite()
                                if (refreshQueue.pending.isEmpty()) break
                            }
                        } finally {
                            refreshQueue.job = null
                        }
                    }
                }

                fun refreshPrivilege() {
                    scope.launch {
                        try {
                            privilegeOverview = manager.readPrivilegeOverview(
                                this@MainActivity
                            )
                            backendStatus = manager.status()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            ActionLog.error(
                                "读取授权状态失败：" +
                                    (throwable.message ?: throwable.javaClass.simpleName)
                            )
                        }
                    }
                }

                suspend fun targetService(backend: PrivilegeBackend) =
                    TargetFreezeService(
                        context = this@MainActivity,
                        backend = backend,
                        dryRun = settings.dryRun,
                        markerBackend = manager.getRootForShutdown(),
                        processSnapshotReader = {
                            val processBackend = manager.getRoot() ?: backend
                            PackageProcessListReader(processBackend).read()
                        },
                        suspendedStateReader = {
                            statusReader.read(it)?.suspended
                        }
                    )

                fun refreshLogUi() {
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            PersistentLogger.files(this@MainActivity)
                        }
                        val newest = files
                            .maxByOrNull { it.lastModified() }
                        val fileLines = withContext(Dispatchers.IO) {
                            PersistentLogger.recentLines(
                                this@MainActivity,
                                40
                            )
                        }
                        logUi = LogUiState(
                            enabled = logSettings.loggingEnabled,
                            filePath = newest?.absolutePath,
                            fileSizeBytes = newest?.length() ?: 0L,
                            busy = false,
                            message = logUi.message,
                            fileLines = fileLines
                        )
                    }
                }

                fun refreshDetection() {
                    // A detection probe runs a root process list, COTA reads
                    // and PackageManager queries. Page entry plus an action
                    // must not stack several of those; the latest result is
                    // enough. Explicit "刷新" during a run reuses that run.
                    if (detectionFlight.job?.isActive == true) return
                    detectionFlight.job = scope.launch {
                        detectionScreen = detectionScreen.copy(
                            loading = true,
                            error = null
                        )
                        val snapshot = runCatching {
                            readDetectionSnapshot()
                        }.getOrNull()
                        if (snapshot == null) {
                            detectionScreen = detectionScreen.copy(
                                loading = false,
                                error = "检测失败（权限或读取错误）"
                            )
                            detectionSummary = DetectionUiState(
                                lastCheckedText = null,
                                mismatchCount = 0,
                                note = "检测失败；请重试"
                            )
                        } else {
                            detectionScreen = DetectionScreenUiState(
                                snapshot = snapshot,
                                loading = false,
                                error = null
                            )
                            detectionSummary = DetectionUiState(
                                lastCheckedText = formatEpoch(
                                    snapshot.generatedAtMillis
                                ),
                                mismatchCount = snapshot.mismatchCount +
                                    snapshot.cloudMismatches.size,
                                note = snapshot.notes.firstOrNull()
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    // Rewrite persisted strategies that are no longer supported
                    // (e.g. powergenie COMPONENT -> OBSERVE-only) once per launch.
                    withContext(Dispatchers.IO) {
                        TargetPolicy(this@MainActivity).normalizeStrategies()
                    }
                    val root = manager.getRoot()
                    cotaRootAvailable = root != null
                    backendStatus = manager.status()
                    privilegeOverview = manager.readPrivilegeOverview(this@MainActivity)
                    BootFreezeScheduler.ensureScheduled(this@MainActivity)
                    refreshTargetActual()
                    refreshLogUi()
                    refreshDetection()
                    refreshBootGuardState()
                }

                fun refreshCota(moduleEnabled: Boolean = cotaEnabled) {
                    launchBusy {
                        val root = manager.getRoot()
                        cotaRootAvailable = root != null
                        val controller = CotaController(root, moduleEnabled)
                        val filesResult = controller.listFiles()
                        // The user may have switched the module off while the
                        // read was in flight; do not repopulate stale state.
                        if (!cotaEnabled) {
                            return@launchBusy
                        }
                        when (filesResult) {
                            is CotaResult.Ok -> {
                                cotaFiles = filesResult.value
                                cotaSelected = emptySet()
                                cotaMessage = "读取到 ${filesResult.value.size} 个文件"
                            }
                            is CotaResult.Error -> cotaMessage = filesResult.message
                            CotaResult.Disabled ->
                                cotaMessage = "模块未启用或没有 Root"
                        }
                        val backupsResult = controller.listBackups()
                        if (!cotaEnabled) {
                            return@launchBusy
                        }
                        when (backupsResult) {
                            is CotaResult.Ok -> {
                                cotaBackups = backupsResult.value
                                if (cotaLastBackupId == null) {
                                    cotaLastBackupId =
                                        backupsResult.value.firstOrNull()?.id
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                DashboardScreen(
                    state = DashboardState(
                        backendStatus = backendStatus,
                        busy = busy,
                        dryRun = dryRun,
                        lastShutdownResult = lastShutdownResult,
                        lastBootResult = lastBootResult,
                        bootFreeze = BootFreezeUiState(
                            enabled = bootFreezeEnabled,
                            delaySeconds = bootFreezeDelaySeconds,
                            bootCount = currentBoot,
                            resultBootCount = bootFreezeResultBootCount,
                            lastResult = bootFreezeResult,
                            lastSucceeded = bootFreezeSucceeded,
                            lastSkippedReason = bootFreezeResult
                                ?.takeIf {
                                    it.startsWith("已跳过：") &&
                                        bootFreezeResultBootCount == currentBoot
                                }
                                ?.removePrefix("已跳过："),
                            lastAttemptText = formatEpoch(bootFreezeAttemptTime),
                            lastSuccessText = formatEpoch(bootFreezeFinishTime),
                            plannedCount = catalog.count {
                                it.enabled && !it.observeOnly &&
                                    it.strategy != FreezeStrategy.OBSERVE
                            }
                        ),
                        antiFraud = AntiFraudUiState(
                            config = antiFraudConfig,
                            results = antiFraudResults,
                            lastResult = antiFraudLastResult
                        ),
                        cota = CotaUiState(
                            moduleEnabled = cotaEnabled,
                            rootAvailable = cotaRootAvailable,
                            busy = busy,
                            files = cotaFiles,
                            selected = cotaSelected,
                            backups = cotaBackups,
                            lastBackupId = cotaLastBackupId,
                            message = cotaMessage
                        ),
                        logs = logs,
                        logSettings = logUi,
                        detection = detectionSummary,
                        rescueMode = rescueMode,
                        catalog = catalog,
                        lastCatalogResult = lastCatalogResult,
                        rootAvailable = cotaRootAvailable,
                        privilege = privilegeOverview,
                        themeStyle = themeStyle,
                        themePalette = themePalette,
                        themeMode = themeMode
                    ),
                    callbacks = DashboardCallbacks(
                        onDryRunChange = {
                            settings.dryRun = it
                            dryRun = it
                        },
                        onRequestShizuku = {
                            runCatching {
                                if (!Shizuku.pingBinder()) {
                                    ActionLog.warn(
                                        "Shizuku 未运行，请先用 Shizuku Store/无线调试启动"
                                    )
                                } else if (
                                    Shizuku.checkSelfPermission() !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    Shizuku.requestPermission(0)
                                } else {
                                    ActionLog.info("Shizuku 权限已授予")
                                }
                            }
                        },
                        onRequestPrivilege = {
                            launchBusy {
                                val root = manager.requestRoot()
                                if (!root) {
                                    val running = runCatching {
                                        Shizuku.pingBinder()
                                    }.getOrDefault(false)
                                    if (running) {
                                        val granted = runCatching {
                                            Shizuku.checkSelfPermission() ==
                                                PackageManager.PERMISSION_GRANTED
                                        }.getOrDefault(false)
                                        if (!granted) {
                                            Shizuku.requestPermission(0)
                                            ActionLog.info(
                                                "已请求 Shizuku 授权，请在弹窗中允许"
                                            )
                                        }
                                    } else {
                                        ActionLog.warn(
                                            "Root 未授权，Shizuku 也没有运行；" +
                                                "请先启动 Shizuku 或授予 Root"
                                        )
                                    }
                                } else {
                                    ActionLog.info("Root 授权可用")
                                }
                                privilegeOverview = manager.readPrivilegeOverview(
                                    this@MainActivity
                                )
                                backendStatus = manager.status()
                            }
                        },
                        onRefreshPrivilege = { refreshPrivilege() },
                        onOpenShizuku = {
                            val opened = runCatching {
                                packageManager.getLaunchIntentForPackage(
                                    "moe.shizuku.privileged.api"
                                )?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                                    startActivity(it)
                                    true
                                } ?: false
                            }.getOrDefault(false)
                            if (!opened) {
                                ActionLog.warn("没有找到 Shizuku 应用")
                            }
                        },
                        onOpenDeveloperSettings = {
                            runCatching {
                                startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        onThemeStyleChange = { style ->
                            themeStyle = style
                            settings.themeStyle = style.name
                        },
                        onThemePaletteChange = { palette ->
                            themePalette = palette
                            settings.themePalette = palette.name
                        },
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            settings.themeMode = mode.name
                        },
                        onOpenStartupSettings = {
                            val honorIntent = Intent(
                                "hihonor.intent.action.HSM_STARTUPAPP_MANAGER"
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            val opened = runCatching {
                                startActivity(honorIntent)
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                runCatching {
                                    startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                }
                            }
                        },
                        onAntiFraudApply = { config: AntiFraudConfig ->
                            launchBusy {
                                val backend = manager.get()
                                if (backend == null) {
                                    ActionLog.warn("无可用权限；反诈设置未执行")
                                } else if (BootGuard.isRescue(manager.getRoot())) {
                                    ActionLog.warn("自动救砖模式：已拒绝应用反诈停用策略")
                                    antiFraudLastResult = "已拒绝：自动救砖模式"
                                    antiFraudSettings.lastResult = antiFraudLastResult
                                } else {
                                    val identityState =
                                        enabledStateReader.isEnabled(
                                            com.nemu.hibernate.core.AntiFraudTargets.NUMBER_IDENTITY
                                        )
                                    val identityBaseline = antiFraudSettings
                                        .numberIdentityOriginalEnabled
                                        ?: identityState
                                    if (!settings.dryRun &&
                                        config.numberIdentityMode !=
                                        com.nemu.hibernate.core.NumberIdentityMode.OFF &&
                                        antiFraudSettings.numberIdentityOriginalEnabled == null &&
                                        identityBaseline != null
                                    ) {
                                        antiFraudSettings.numberIdentityOriginalEnabled =
                                            identityBaseline
                                    }
                                    val results = AntiFraudController(
                                        backend,
                                        settings.dryRun,
                                        previousConfig = antiFraudConfig,
                                        numberIdentityWasEnabled = identityBaseline,
                                        numberIdentityCurrentlyEnabled = identityState,
                                        beforeDisableByNemu = {
                                            if (identityBaseline == true) {
                                                BootGuard.markDisabledByNemu(
                                                    manager.getRootForShutdown(),
                                                    com.nemu.hibernate.core.AntiFraudTargets.NUMBER_IDENTITY,
                                                    disabledByNemu = true
                                                )
                                            } else true
                                        },
                                        afterRestoreByNemu = {
                                            BootGuard.markDisabledByNemu(
                                                manager.getRootForShutdown(),
                                                com.nemu.hibernate.core.AntiFraudTargets.NUMBER_IDENTITY,
                                                disabledByNemu = false
                                            )
                                        },
                                        enabledStateReader = {
                                            enabledStateReader.isEnabled(it)
                                        }
                                    ).apply(config)
                                    antiFraudResults = results
                                    if (results.all { it.ok }) {
                                        antiFraudConfig = config
                                        antiFraudSettings.save(config)
                                    }
                                    val text =
                                        "${AntiFraudPolicy.summarize(results)}" +
                                            "（${System.currentTimeMillis()}）"
                                    antiFraudLastResult = text
                                    antiFraudSettings.lastResult = text
                                    refreshTargetActual()
                                    backendStatus = manager.status()
                                }
                            }
                        },
                        onAntiFraudRestore = {
                            launchBusy {
                                val backend = manager.get()
                                if (backend == null) {
                                    ActionLog.warn("无可用权限；无法恢复反诈设置")
                                } else {
                                    val moduleDryRun = settings.dryRun
                                    val identityState =
                                        enabledStateReader.isEnabled(
                                            com.nemu.hibernate.core.AntiFraudTargets.NUMBER_IDENTITY
                                        )
                                    val results = AntiFraudController(
                                        backend,
                                        moduleDryRun,
                                        numberIdentityWasEnabled = antiFraudSettings
                                            .numberIdentityOriginalEnabled,
                                        numberIdentityCurrentlyEnabled = identityState,
                                        afterRestoreByNemu = {
                                            BootGuard.markDisabledByNemu(
                                                manager.getRootForShutdown(),
                                                com.nemu.hibernate.core.AntiFraudTargets.NUMBER_IDENTITY,
                                                disabledByNemu = false
                                            )
                                        },
                                        enabledStateReader = {
                                            enabledStateReader.isEnabled(it)
                                        }
                                    ).restoreAll()
                                    antiFraudResults = results
                                    if (!moduleDryRun && results.all { it.ok }) {
                                        antiFraudConfig = AntiFraudConfig()
                                        antiFraudSettings.reset()
                                        antiFraudSettings.numberIdentityOriginalEnabled = null
                                        TargetCatalog.byPackage(
                                            com.nemu.hibernate.core.AntiFraudTargets.NUMBER_IDENTITY
                                        )?.let { target ->
                                            val policy = TargetPolicy(this@MainActivity)
                                            policy.setApplied(target, false)
                                            policy.setOriginalEnabled(target, null)
                                        }
                                    }
                                    val text = "恢复：" +
                                        "${AntiFraudPolicy.summarize(results)}" +
                                        "（${System.currentTimeMillis()}）"
                                    antiFraudLastResult = text
                                    antiFraudSettings.lastResult = text
                                    refreshTargetActual()
                                    backendStatus = manager.status()
                                }
                            }
                        },
                        cotaCallbacks = CotaCallbacks(
                            onEnabledChange = { value ->
                                settings.cotaEnabled = value
                                cotaEnabled = value
                                if (value) {
                                    refreshCota(true)
                                } else {
                                    cotaFiles = emptyList()
                                    cotaSelected = emptySet()
                                    cotaMessage =
                                        "模块已关闭；已删除的文件不会自动恢复"
                                }
                            },
                            onRefresh = { refreshCota() },
                            onToggleSelected = { name, checked ->
                                cotaSelected = if (checked) {
                                    cotaSelected + name
                                } else {
                                    cotaSelected - name
                                }
                            },
                            onBackup = {
                                launchBusy {
                                    if (settings.dryRun) {
                                        cotaMessage = "预览：备份未执行"
                                        return@launchBusy
                                    }
                                    val root = manager.getRoot()
                                    cotaRootAvailable = root != null
                                    val controller = CotaController(root, cotaEnabled)
                                    when (val result = controller.backup(cotaSelected)) {
                                        is CotaResult.Ok -> {
                                            cotaLastBackupId = result.value.id
                                            cotaMessage =
                                                "备份完成：${result.value.dir}"
                                            when (val backups = controller.listBackups()) {
                                                is CotaResult.Ok ->
                                                    cotaBackups = backups.value
                                                else -> Unit
                                            }
                                        }
                                        is CotaResult.Error ->
                                            cotaMessage = result.message
                                        CotaResult.Disabled ->
                                            cotaMessage = "模块未启用或没有 Root"
                                    }
                                }
                            },
                            onDeleteConfirmed = {
                                launchBusy {
                                    if (settings.dryRun) {
                                        cotaMessage = "预览：删除未执行"
                                        return@launchBusy
                                    }
                                    val backupId = cotaLastBackupId
                                    if (backupId == null) {
                                        cotaMessage = "请先备份，再删除"
                                    } else {
                                        val root = manager.getRoot()
                                        cotaRootAvailable = root != null
                                        val controller =
                                            CotaController(root, cotaEnabled)
                                        when (
                                            val result = controller.delete(
                                                names = cotaSelected,
                                                backupId = backupId,
                                                riskAcknowledged = true
                                            )
                                        ) {
                                            is CotaResult.Ok -> {
                                                cotaMessage =
                                                    "删除完成；备份 $backupId 可用于恢复"
                                                cotaSelected = emptySet()
                                                when (
                                                    val listed =
                                                        controller.listFiles()
                                                ) {
                                                    is CotaResult.Ok ->
                                                        cotaFiles = listed.value
                                                    else -> Unit
                                                }
                                            }
                                            is CotaResult.Error ->
                                                cotaMessage = result.message
                                            CotaResult.Disabled ->
                                                cotaMessage = "模块未启用或没有 Root"
                                        }
                                    }
                                }
                            },
                            onRestore = { backupId ->
                                launchBusy {
                                    if (settings.dryRun) {
                                        cotaMessage = "预览：恢复未执行"
                                        return@launchBusy
                                    }
                                    val root = manager.getRoot()
                                    cotaRootAvailable = root != null
                                    val controller = CotaController(root, cotaEnabled)
                                    when (
                                        val result = controller.restore(backupId)
                                    ) {
                                        is CotaResult.Ok -> {
                                            cotaMessage =
                                                "已从备份 $backupId 恢复"
                                            when (
                                                val listed = controller.listFiles()
                                            ) {
                                                is CotaResult.Ok ->
                                                    cotaFiles = listed.value
                                                else -> Unit
                                            }
                                        }
                                        is CotaResult.Error ->
                                            cotaMessage = result.message
                                        CotaResult.Disabled ->
                                            cotaMessage = "模块未启用或没有 Root"
                                    }
                                }
                            }
                        ),
                        onBootFreezeEnabledChange = { value ->
                            bootFreezeSettings.bootFreezeEnabled = value
                            bootFreezeEnabled = value
                            if (value) {
                                scope.launch {
                                    BootFreezeScheduler.ensureScheduled(
                                        this@MainActivity
                                    )
                                }
                            } else {
                                scope.launch(Dispatchers.IO) {
                                    BootFreezeScheduler.cancel(this@MainActivity)
                                }
                            }
                        },
                        onLoggingEnabledChange = { value ->
                            PersistentLogger.setEnabled(this@MainActivity, value)
                            logSettings.loggingEnabled = value
                            if (value) {
                                // Give a newly enabled log file a baseline even
                                // when the process started while logging was off.
                                PersistentLogger.startup(this@MainActivity)
                            }
                            logUi = logUi.copy(enabled = value)
                            ActionLog.info(
                                if (value) "文件日志已开启" else "文件日志已关闭"
                            )
                            refreshLogUi()
                        },
                        onRefreshLogs = {
                            refreshLogUi()
                        },
                        onCaptureLogcat = {
                            scope.launch {
                                logUi = logUi.copy(busy = true, message = "抓取中…")
                                var resultMessage = "抓取失败"
                                try {
                                    val backend = manager.getRoot() ?: manager.get()
                                    resultMessage = PersistentLogger.captureLogcat(
                                        this@MainActivity,
                                        backend
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    resultMessage =
                                        "抓取失败：${throwable.message ?: throwable.javaClass.simpleName}"
                                } finally {
                                    logUi = logUi.copy(
                                        busy = false,
                                        message = resultMessage
                                    )
                                    refreshLogUi()
                                }
                            }
                        },
                        onClearLogs = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    PersistentLogger.clear(this@MainActivity)
                                }
                                logUi = logUi.copy(message = "日志已清空")
                                refreshLogUi()
                            }
                        },
                        onClearRescue = {
                            scope.launch {
                                val backend = manager.getRoot()
                                if (backend == null) {
                                    ActionLog.warn("无 root，无法清除救援模式")
                                } else if (BootGuard.clear(backend)) {
                                    rescueMode = false
                                    ActionLog.info("救援模式已清除；下次开机模块恢复")
                                } else {
                                    ActionLog.warn("清除救援模式失败")
                                }
                            }
                        },
                        onCatalogToggle = { packageName, value ->
                            val target = TargetCatalog.byPackage(packageName)
                            if (target == null) {
                                ActionLog.warn("未知目标：$packageName")
                            } else if (value && target.supportedStrategies ==
                                setOf(FreezeStrategy.OBSERVE)
                            ) {
                                ActionLog.warn("${target.label} 仅观察，不能启用冻结")
                            } else {
                                TargetPolicy(this@MainActivity)
                                    .setEnabled(target, value)
                                // Flip the row immediately: selection is pure
                                // policy and must never wait on a shell read.
                                catalog = catalog.map { row ->
                                    if (row.packageName == packageName) {
                                        row.copy(enabled = value)
                                    } else {
                                        row
                                    }
                                }
                                rebuildCatalog()
                            }
                        },
                        onCatalogStrategyChange = { packageName, strategy ->
                            val target = TargetCatalog.byPackage(packageName)
                            if (target != null) {
                                val policy = TargetPolicy(this@MainActivity)
                                val previous = policy.strategy(target)
                                val row = catalog.firstOrNull {
                                    it.packageName == packageName
                                }
                                val hasFrozenState = policy.isApplied(target) ||
                                    row?.actual?.suspended == true ||
                                    row?.actual?.packageEnabled == false ||
                                    row?.componentsDisabled == true ||
                                    row?.ownedHidden == true
                                if (hasFrozenState &&
                                    previous != strategy
                                ) {
                                    // A strategy switch leaves the old mode's
                                    // state behind; force an explicit restore
                                    // first instead of silently mixing modes.
                                    lastCatalogResult =
                                        "${target.label} 已处于 ${previous.name} / " +
                                            "实际停用状态；请先在解冻页恢复，再切换策略"
                                    ActionLog.warn(lastCatalogResult!!)
                                } else {
                                    policy.setStrategy(target, strategy)
                                    catalog = catalog.map { row ->
                                        if (row.packageName == packageName) {
                                            row.copy(strategy = strategy)
                                        } else {
                                            row
                                        }
                                    }
                                    rebuildCatalog()
                                    // The restore script encodes the strategy;
                                    // regenerate it off the UI path.
                                    scheduleShutdownScriptWrite()
                                }
                            }
                        },
                        onCatalogApply = { selected ->
                            launchBusy {
                                val backend = manager.get()
                                val policy = TargetPolicy(this@MainActivity)
                                val targets = TargetCatalog.all.filter {
                                    it.packageName in selected &&
                                        policy.isEnabled(it) &&
                                        policy.strategy(it) != FreezeStrategy.OBSERVE
                                }
                                when {
                                    backend == null -> {
                                        lastCatalogResult = "失败：无可用权限"
                                        ActionLog.warn("无可用权限；无法应用冻结策略")
                                    }
                                    targets.isEmpty() -> {
                                        lastCatalogResult = "没有已启用的目标可应用"
                                        ActionLog.warn(lastCatalogResult!!)
                                    }
                                    BootGuard.isRescue(manager.getRoot()) -> {
                                        lastCatalogResult = "已拒绝：自动救砖模式"
                                        ActionLog.warn("自动救砖模式：请先清除救援标记再冻结")
                                    }
                                    else -> {
                                        val bootCount = BootCount.current(this@MainActivity)
                                        if (!settings.dryRun) {
                                            BootFreezeScheduler.cancel(this@MainActivity)
                                            bootFreezeSettings.recordAttempt(
                                                bootCount,
                                                System.currentTimeMillis()
                                            )
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            targetService(backend).apply(targets)
                                        }
                                        lastCatalogResult = result.summary()
                                        if (!settings.dryRun) {
                                            bootFreezeSettings.recordResult(
                                                bootCount = bootCount,
                                                result = if (result.ok) {
                                                    "手动冻结成功"
                                                } else {
                                                    "手动冻结有失败"
                                                },
                                                detail = result.summary(),
                                                succeeded = result.ok,
                                                timestampMillis =
                                                    System.currentTimeMillis()
                                            )
                                        }
                                        if (result.ok) {
                                            ActionLog.info("应用冻结：${result.summary()}")
                                        } else {
                                            ActionLog.warn("应用冻结：${result.summary()}")
                                        }
                                        refreshBootFreezeState()
                                        refreshTargetActual()
                                        refreshDetection()
                                        backendStatus = manager.status()
                                    }
                                }
                            }
                        },
                        onCatalogRestore = { selected ->
                            launchBusy {
                                val backend = manager.get()
                                val targets = TargetCatalog.all.filter {
                                    it.packageName in selected
                                }
                                when {
                                    backend == null -> {
                                        lastCatalogResult = "失败：无可用权限"
                                        ActionLog.warn("无可用权限；无法解冻所选")
                                    }
                                    targets.isEmpty() -> {
                                        lastCatalogResult = "未选择可解冻的目标"
                                        ActionLog.warn(lastCatalogResult!!)
                                    }
                                    else -> {
                                        val policy = TargetPolicy(this@MainActivity)
                                        if (!settings.dryRun) {
                                            BootFreezeScheduler.cancel(this@MainActivity)
                                            val bootCount =
                                                BootCount.current(this@MainActivity)
                                            bootFreezeSettings.bootFreezeSkippedBootCount =
                                                bootCount
                                            bootFreezeSettings.lastBootFreezeBootCount =
                                                bootCount
                                            bootFreezeSettings.lastBootFreezeResult =
                                                "手动选择性解冻：本次开机不再自动冻结"
                                            bootFreezeSettings.lastBootFreezeSucceeded =
                                                false
                                        }
                                        val service = targetService(backend)
                                        // Restore with the stored strategy before
                                        // disabling the policy; otherwise a legacy
                                        // disable-user target could be treated as
                                        // suspend and left disabled.
                                        val result = withContext(Dispatchers.IO) {
                                            service.restore(targets, force = true)
                                        }
                                        // Scope component restoration to the
                                        // selected targets: restoring A must
                                        // not silently re-enable B's
                                        // components while B stays marked.
                                        val components = withContext(Dispatchers.IO) {
                                            service.restoreOwnedComponents(targets)
                                        }
                                        if (!settings.dryRun && result.ok && components.ok) {
                                            targets.forEach {
                                                policy.setEnabled(it, false)
                                            }
                                        }
                                        lastCatalogResult =
                                            (if (settings.dryRun) "预览：" else "") +
                                                "解冻所选 ${targets.size} 项：" +
                                                result.summary() +
                                                "；组件：${components.summary()}"
                                        if (result.ok && components.ok) {
                                            ActionLog.info(lastCatalogResult!!)
                                        } else {
                                            ActionLog.warn(lastCatalogResult!!)
                                        }
                                        refreshBootFreezeState()
                                        refreshTargetActual()
                                        refreshDetection()
                                        backendStatus = manager.status()
                                    }
                                }
                            }
                        },
                        onCatalogRestoreAll = {
                            launchBusy {
                                val backend = manager.get()
                                if (backend == null) {
                                    lastCatalogResult = "失败：无可用权限"
                                    ActionLog.warn("无可用权限；无法全部解冻")
                                } else {
                                    if (!settings.dryRun) {
                                        BootFreezeScheduler.cancel(this@MainActivity)
                                        val bootCount =
                                            BootCount.current(this@MainActivity)
                                        bootFreezeSettings.bootFreezeSkippedBootCount =
                                            bootCount
                                        bootFreezeSettings.lastBootFreezeBootCount =
                                            bootCount
                                        bootFreezeSettings.lastBootFreezeResult =
                                            "手动全部解冻：本次开机不再自动冻结"
                                        bootFreezeSettings.lastBootFreezeSucceeded =
                                            false
                                    }
                                    val service = targetService(backend)
                                    val targets = (
                                        service.appliedTargets() +
                                            catalog.filter {
                                                it.actual.suspended == true ||
                                                    (FreezeStrategy.DISABLE_USER in
                                                        it.target.supportedStrategies &&
                                                        it.actual.packageEnabled == false &&
                                                        (it.applied || it.ownedDisable))
                                            }.map { it.target }
                                        ).distinctBy { it.packageName }
                                    val result = withContext(Dispatchers.IO) {
                                            service.restore(targets, force = true)
                                        }
                                    // residualUnfreeze is an always-on safety
                                    // path and ignores dryRun. Never call it
                                    // from a dry-run user action: the preview
                                    // must not change actual suspend state.
                                    val residualText = if (settings.dryRun) {
                                        "预览：安全残留清理跳过"
                                    } else {
                                        val residual = withContext(Dispatchers.IO) {
                                            service.residualUnfreeze()
                                        }
                                        "残留清理：${residual.summary()}"
                                    }
                                    lastCatalogResult =
                                        (if (settings.dryRun) "预览：" else "") +
                                            "全部解冻：${result.summary()}；$residualText"
                                    if (result.ok) {
                                        ActionLog.info(lastCatalogResult!!)
                                    } else {
                                        ActionLog.warn(lastCatalogResult!!)
                                    }
                                    refreshBootFreezeState()
                                    refreshTargetActual()
                                    refreshDetection()
                                    backendStatus = manager.status()
                                }
                            }
                        },
                        onCatalogRefresh = {
                            refreshTargetActual()
                            refreshDetection()
                        },
                        onCustomTargetAdd = { packageName, label ->
                            if (com.nemu.hibernate.core.CustomTargetGuard.isDenied(packageName)) {
                                ActionLog.warn("拒绝添加系统关键应用：$packageName")
                            } else if (TargetCatalog.byPackage(packageName) != null) {
                                ActionLog.warn("已在目标目录中，不能重复添加：$packageName")
                            } else {
                                settings.addCustomTarget(packageName, label)
                                TargetCatalog.setCustomTargets(
                                    settings.customTargets().map { (pkg, name) ->
                                        TargetCatalog.customTarget(pkg, name)
                                    }
                                )
                                TargetCatalog.byPackage(packageName)?.let { target ->
                                    TargetPolicy(this@MainActivity)
                                        .setEnabled(target, true)
                                    // Only the new package needs an actual-state
                                    // read; a full-catalog refresh plus a
                                    // detection probe here would be needless.
                                    refreshTargetActual(
                                        listOf(target),
                                        force = true
                                    )
                                } ?: rebuildCatalog()
                            }
                        },
                        onCustomTargetRemove = { packageName ->
                            val target = TargetCatalog.byPackage(packageName)
                            val policy = TargetPolicy(this@MainActivity)
                            if (target != null && policy.isApplied(target)) {
                                ActionLog.warn("请先解冻再移除自定义目标：$packageName")
                            } else {
                                settings.removeCustomTarget(packageName)
                                TargetCatalog.setCustomTargets(
                                    settings.customTargets().map { (pkg, name) ->
                                        TargetCatalog.customTarget(pkg, name)
                                    }
                                )
                                // Remove the row immediately; the cached
                                // rebuild only reconciles policy fields.
                                catalog = catalog.filterNot {
                                    it.packageName == packageName
                                }
                                rebuildCatalog()
                                scheduleShutdownScriptWrite()
                            }
                        },
                        onTabSelected = { tab ->
                            // Page switches are pure UI. Only the detection
                            // page queries, and the unfreeze page reuses the
                            // cached actual snapshot unless it is stale.
                            when (tab) {
                                DashboardTab.DETECTION -> refreshDetection()
                                DashboardTab.OVERVIEW -> refreshBootFreezeState()
                                DashboardTab.FREEZE -> rebuildCatalog()
                                DashboardTab.UNFREEZE -> {
                                    val policy = TargetPolicy(this@MainActivity)
                                    val applied = TargetCatalog.all.filter {
                                        policy.isApplied(it)
                                    }
                                    if (applied.isEmpty()) rebuildCatalog()
                                    else refreshTargetActual(applied, force = false)
                                }
                                DashboardTab.SETTINGS -> Unit
                            }
                        }
                    ),
                    detectionContent = {
                        DetectionScreen(
                            state = detectionScreen,
                            onRefresh = { refreshDetection() }
                        )
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    /**
     * Builds the Freeze/Unfreeze rows from TargetPolicy + the last actual-state
     * snapshot. Called only after a fresh read so the UI never shows a stale
     * "applied" claim.
     */
    private fun buildCatalog(
        actual: Map<String, TargetActualUiState>
    ): List<CatalogTargetUiState> {
        val policy = TargetPolicy(this)
        return TargetCatalog.all.map { target ->
            val strategy = policy.strategy(target)
            val applied = policy.isApplied(target)
            val componentStates = if (strategy == FreezeStrategy.COMPONENT) {
                target.components.map { componentStateReader.state(it.className) }
            } else {
                emptyList()
            }
            val componentsDisabled = when {
                target.components.isEmpty() -> null
                componentStates.isEmpty() -> null
                componentStates.all { it == null } -> null
                else -> componentStates.any { state ->
                    state == 2 || state == 3 || state == 4
                }
            }
            val actualState = actual[target.packageName] ?: TargetActualUiState()
            // Do not show "applied" when the actual state is gone (e.g. a
            // shutdown cleared suspensions but a marker survived). The UI must
            // reflect PackageManager, not a stale preference.
            val actuallyFrozen = actualState.suspended == true ||
                actualState.packageEnabled == false ||
                componentsDisabled == true ||
                // A hidden package is missing from PackageManager lookups; the
                // root package list check in readTargetActual sets `hidden`.
                (strategy == FreezeStrategy.HIDE && actualState.hidden == true)
            // Cloud packages whose recorded baseline was "already disabled"
            // were never changed by Nemu; do not claim applied ownership.
            val preDisabledAdopted = target.category ==
                com.nemu.hibernate.core.TargetCategory.CLOUD &&
                policy.originalEnabled(target) == false
            CatalogTargetUiState(
                target = target,
                enabled = policy.isEnabled(target),
                strategy = strategy,
                applied = policy.isApplied(target) && actuallyFrozen &&
                    !preDisabledAdopted,
                actual = actualState,
                componentsDisabled = componentsDisabled,
                ownedDisable = policy.originalEnabled(target) != null,
                ownedHidden = policy.ownsHidden(target)
            )
        }
    }

    private suspend fun readTargetActual(
        targets: List<Target>
    ): Map<String, TargetActualUiState> = withContext(Dispatchers.IO) {
        val processSnapshot = try {
            manager.getRoot()?.let { PackageProcessListReader(it).read() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        // `pm hide` removes the package from normal PackageManager lookups, so
        // the applied marker alone cannot prove the state. Ask the root shell
        // which applied HIDE packages are still missing from the package list.
        val policy = TargetPolicy(this@MainActivity)
        val hideCandidates = targets.filter {
            policy.strategy(it) == FreezeStrategy.HIDE && policy.isApplied(it)
        }
        val hiddenPackages: Set<String> = if (hideCandidates.isEmpty()) {
            emptySet()
        } else {
            runCatching {
                val root = manager.getRoot() ?: return@runCatching emptySet()
                val reader = com.nemu.hibernate.privilege.BackendPackageSnapshotReader(
                    root
                )
                hideCandidates.mapNotNull { target ->
                    if (reader.read(target.packageName)?.hidden == true) {
                        target.packageName
                    } else {
                        null
                    }
                }.toSet()
            }.getOrDefault(emptySet())
        }
        targets.mapNotNull { target ->
            val hidden = target.packageName in hiddenPackages
            val status = statusReader.read(target.packageName)
            val stopped = runCatching {
                val info = if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getApplicationInfo(
                        target.packageName,
                        PackageManager.ApplicationInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(target.packageName, 0)
                }
                (info.flags and ApplicationInfo.FLAG_STOPPED) != 0
            }.getOrDefault(false)
            if (status == null && !hidden) {
                null
            } else {
                target.packageName to TargetActualUiState(
                    suspended = status?.suspended,
                    packageEnabled = status?.enabled,
                    stopped = stopped,
                    processRunning = processSnapshot?.isRunning(target.packageName),
                    hidden = hidden
                )
            }
        }.toMap()
    }

    private suspend fun readDetectionSnapshot(): DetectionSnapshot {
        val root = manager.getRoot()
        return DetectionReader(
            context = this,
            packageStatusReader = statusReader,
            rootBackend = root,
            // The hiview trio is strategy-editable on the Freeze page; render
            // them as strategy-aware catalog rows instead of the legacy
            // fixed-action cloud rows (their suspend is refused by the platform).
            cloudTargets = CloudTargets.all.filterNot {
                it.packageName in STRATEGY_CLOUD_PACKAGES
            }
        ).read(settings)
    }

    private fun applyHighRefreshRate() {
        runCatching {
            val bestMode = window.windowManager.defaultDisplay
                .supportedModes
                .maxByOrNull { it.refreshRate }
            window.attributes = window.attributes.apply {
                preferredRefreshRate = 120f
                if (bestMode != null) preferredDisplayModeId = bestMode.modeId
            }
        }
    }

    private fun formatEpoch(millis: Long): String? {
        if (millis <= 0L) return null
        return epochFormatter.get()!!.format(Date(millis))
    }

    /**
     * DashboardState is rebuilt on every recomposition; creating a new
     * SimpleDateFormat there was needless main-thread work per frame.
     */
    private val epochFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val STRATEGY_CLOUD_PACKAGES = setOf(
        "com.hihonor.hiview",
        "com.hihonor.hiviewpush",
        "com.hihonor.hiviewtunnel"
    )

    override fun onResume() {
        super.onResume()
        SafetyService.sync(this)
    }
}

/**
 * Single-flight request queue for actual-state reads. Requests that arrive
 * while a read is running are merged into one follow-up batch, so a burst of
 * taps or tab switches can never stack overlapping PackageManager/shell work.
 */
private class RefreshQueue {
    var job: Job? = null
    val pending: MutableSet<String> = mutableSetOf()
    var pendingForce: Boolean = false
}

/** Detection is read-only and idempotent: one probe at a time is enough. */
private class DetectionFlight {
    var job: Job? = null
}

/** One root write per burst of policy edits instead of one per tap. */
private const val SHUTDOWN_SCRIPT_DEBOUNCE_MS = 400L
