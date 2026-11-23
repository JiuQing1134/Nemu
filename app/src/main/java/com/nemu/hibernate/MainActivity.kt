package com.nemu.hibernate

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nemu.hibernate.core.BootStatusCopy
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.ResultNote
import com.nemu.hibernate.core.ResultReason
import com.nemu.hibernate.core.StrategyPreset
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetCopy
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaController
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaResult
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.BootFreezeSettings
import com.nemu.hibernate.data.LogSettings
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.detection.DetectionReader
import com.nemu.hibernate.detection.DetectionSnapshot
import com.nemu.hibernate.detection.PackageProcessListReader
import com.nemu.hibernate.logging.PersistentLogger
import com.nemu.hibernate.policy.BootCount
import com.nemu.hibernate.policy.BootFreezeScheduler
import com.nemu.hibernate.policy.BootGuard
import com.nemu.hibernate.policy.BootReceiver
import com.nemu.hibernate.policy.FreezeOperationLock
import com.nemu.hibernate.policy.RestoreExecutionPolicy
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
import com.nemu.hibernate.privilege.PrivilegeLabels
import com.nemu.hibernate.privilege.PrivilegeOverview
import com.nemu.hibernate.privilege.PrivilegeOverviewReader
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
import com.nemu.hibernate.ui.LogUiState
import com.nemu.hibernate.ui.isOwnershipPending
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : ComponentActivity() {
    /** App-private COTA backups: other apps cannot rewrite manifest + files. */
    private val cotaBackupRoot: String
        get() = File(filesDir, "cota-backup").absolutePath

    private lateinit var settings: Settings
    private lateinit var bootFreezeSettings: BootFreezeSettings
    private lateinit var logSettings: LogSettings
    private lateinit var manager: BackendManager
    private lateinit var statusReader: PackageStatusReader
    private lateinit var enabledStateReader: PackageEnabledStateReader
    private lateinit var componentStateReader: ComponentStateReader
    /** Set from composition so the Shizuku callback can refresh the privilege card. */
    private var privilegeRefresh: (() -> Unit)? = null
    /** Shizuku-only refresh for onResume; never probes root. */
    private var privilegeQuickRefresh: (() -> Unit)? = null
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            if (granted) {
                ActionLog.info("Shizuku 权限已授予")
            } else {
                ActionLog.warn("Shizuku 权限被拒绝")
            }
            Toast.makeText(
                this,
                if (granted) "Shizuku 已授权" else "Shizuku 授权被拒绝",
                Toast.LENGTH_SHORT
            ).show()
            runOnUiThread { privilegeRefresh?.invoke() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A process can survive direct boot after a failed unlock
        // registration; re-run the idempotent CE reload once the UI starts.
        NemuApplication.reloadAfterUnlock(this)
        applyHighRefreshRate()
        settings = Settings(this)
        bootFreezeSettings = BootFreezeSettings(this)
        logSettings = LogSettings(this)
        manager = BackendManager(settings)
        statusReader = AndroidPackageStatusReader(this)
        enabledStateReader = AndroidPackageEnabledStateReader(this)
        componentStateReader = AndroidComponentStateReader(this)

        // If the process was killed between LOCKED_BOOT and USER_UNLOCKED,
        // the runtime receiver never fired; resume the pending safety pass.
        if (savedInstanceState == null) {
            BootReceiver.ensureBootSafetyIfPending(this)
        }

        // The safety service is started only when something is actually
        // frozen; opening the app with no frozen targets must not leave a
        // foreground process behind (see SafetyService.sync).
        // Schedule even when the activity is started behind the keyguard and
        // Compose composition has not run yet (e.g. FinaSU boot script).
        CoroutineScope(Dispatchers.IO).launch {
            BootFreezeScheduler.ensureScheduled(this@MainActivity)
        }
        // Warm the installed-app list before the user opens 应用. The scan
        // (PackageManager + module markers) stays off the UI thread.
        com.nemu.hibernate.ui.AllAppsCache.warm(this)
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
                        ?: ThemeStyle.VIVID
                )
            }
            var themePalette by remember {
                mutableStateOf(
                    ThemePalette.entries.firstOrNull { it.name == settings.themePalette }
                        ?: ThemePalette.BLUE
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
                // System bars follow the Nemu page colour, not a fixed
                // blue-grey, so every palette reaches the window edge.
                val pageColor = MaterialTheme.colorScheme.background
                LaunchedEffect(darkTheme, pageColor) {
                    val background = pageColor.toArgb()
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
                // Freezing/restoring can take several seconds. Do not let an
                // accidental Back destroy the activity and cancel the work.
                BackHandler(enabled = busy) {
                    Toast.makeText(
                        this@MainActivity,
                        "操作进行中，请稍候",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                fun launchBusy(action: suspend () -> Unit) {
                    // Set the gate synchronously on the click path. Setting it
                    // inside launch() left a one-frame window where a second
                    // tap could start a duplicate apply/backup/delete.
                    if (busy) return
                    busy = true
                    scope.launch {
                        try {
                            action()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            ActionLog.error(
                                "操作异常：" +
                                    (throwable.message
                                        ?: throwable.javaClass.simpleName)
                            )
                        } finally {
                            busy = false
                        }
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

                // COTA state.
                var cotaEnabled by remember { mutableStateOf(settings.cotaEnabled) }
                var cotaRootAvailable by remember { mutableStateOf(false) }
                var cotaLoaded by remember { mutableStateOf(false) }
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
                var bootFreezeWindowMissed by remember {
                    mutableStateOf(
                        currentBoot >= 0 &&
                            bootFreezeSettings
                                .bootFreezeWindowMissedBootCount == currentBoot
                    )
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
                var catalogRefreshing by remember { mutableStateOf(false) }
                var focusPackage by remember {
                    mutableStateOf<String?>(null)
                }

                // Detection/log state.
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
                    lastBootResult = settings.lastBootResult ?: "尚未执行"
                    lastShutdownResult =
                        settings.lastShutdownResult ?: "尚未执行"
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
                    bootFreezeWindowMissed = currentBoot >= 0 &&
                        bootFreezeSettings.bootFreezeWindowMissedBootCount ==
                        currentBoot
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

                fun refreshBootFreezeSchedule() {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            BootFreezeScheduler.ensureScheduled(
                                this@MainActivity
                            )
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
                    catalogRefreshing = true
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
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            // A failed read must be visible; the previous list
                            // stays on screen rather than pretending to be
                            // fresh.
                            val message = throwable.message
                                ?: throwable.javaClass.simpleName
                            lastCatalogResult = "刷新失败：$message"
                            ActionLog.warn("刷新实际状态失败：$message")
                        } finally {
                            catalogRefreshing = false
                            refreshQueue.job = null
                        }
                    }
                }

                suspend fun loadPrivilegeOverview() {
                    val overview = manager.readPrivilegeOverview(this@MainActivity)
                    privilegeOverview = overview
                    // Root/Shizuku changes must also re-evaluate the root-only
                    // gates (components, disable-user, hide, COTA), not just
                    // the top-bar label.
                    cotaRootAvailable = overview.rootAvailable
                    backendStatus = manager.status()
                }

                fun refreshPrivilege(notify: Boolean = false) {
                    scope.launch {
                        try {
                            loadPrivilegeOverview()
                            if (notify) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "授权状态已刷新",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
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

                fun refreshShizukuOnly() {
                    val previous = privilegeOverview ?: return
                    scope.launch {
                        try {
                            val updated = PrivilegeOverviewReader.readShizuku(
                                this@MainActivity,
                                previous
                            )
                            privilegeOverview = updated
                            cotaRootAvailable = updated.rootAvailable
                            backendStatus = when {
                                updated.rootAvailable -> "Root"
                                updated.shizukuGranted ->
                                    PrivilegeLabels.shizukuBackendLabel(
                                        updated.shizukuUid
                                    )
                                updated.shizukuRunning -> "Shizuku（未授权）"
                                else -> "未授权"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            ActionLog.error(
                                "读取 Shizuku 状态失败：" +
                                    (throwable.message ?: throwable.javaClass.simpleName)
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    privilegeRefresh = { refreshPrivilege() }
                    privilegeQuickRefresh = { refreshShizukuOnly() }
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

                /**
                 * Always-on safety path: resume/rescue must never be disabled
                 * by the preview switch. Only user-initiated apply/restore
                 * honors [settings.dryRun].
                 */
                suspend fun safetyService(backend: PrivilegeBackend) =
                    TargetFreezeService(
                        context = this@MainActivity,
                        backend = backend,
                        dryRun = false,
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
                        } else {
                            detectionScreen = DetectionScreenUiState(
                                snapshot = snapshot,
                                loading = false,
                                error = null
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    // Materialize component options for a stored COMPONENT
                    // strategy written by an older build.
                    withContext(Dispatchers.IO) {
                        TargetPolicy(this@MainActivity).normalizeStrategies()
                    }
                    loadPrivilegeOverview()
                    // Restore states written by older builds when their stored
                    // strategy is no longer applicable (for example COMPONENT
                    // on a target with no registered components). Never mark
                    // it done from preview mode; the restore must really run.
                    if (!settings.policyMigrationDone && !settings.dryRun) {
                        try {
                            val backend = manager.get()
                            if (backend != null) {
                                val policy = TargetPolicy(this@MainActivity)
                                val legacy = TargetCatalog.all.filter {
                                    policy.storedStrategy(it) != policy.strategy(it)
                                }
                                val migrated = if (legacy.isEmpty()) {
                                    true
                                } else {
                                    withContext(Dispatchers.IO) {
                                        targetService(backend)
                                            .restore(legacy, force = true)
                                            .ok
                                    }
                                }
                                if (migrated) settings.policyMigrationDone = true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            ActionLog.error(
                                "策略迁移失败：" +
                                    (throwable.message
                                        ?: throwable.javaClass.simpleName)
                            )
                        }
                    }
                    BootFreezeScheduler.ensureScheduled(this@MainActivity)
                    // ensureScheduled may write the window-missed marker.
                    refreshBootFreezeState()
                    refreshTargetActual()
                    refreshLogUi()
                    refreshDetection()
                    refreshBootGuardState()
                }

                /**
                 * Re-read the file list after a backup action. Even a failed
                 * delete/restore can delete some files before erroring, so the
                 * list must never stay stale; an unreadable re-list clears it.
                 */
                suspend fun refreshCotaFilesAfterAction(
                    controller: CotaController
                ) {
                    if (!cotaEnabled) return
                    when (val listed = controller.listFiles()) {
                        is CotaResult.Ok -> {
                            cotaFiles = listed.value
                            cotaLoaded = true
                            val names = listed.value.map { it.name }.toSet()
                            cotaSelected = cotaSelected intersect names
                        }
                        else -> {
                            cotaFiles = emptyList()
                            cotaLoaded = false
                            cotaSelected = emptySet()
                        }
                    }
                }

                fun refreshCota(moduleEnabled: Boolean = cotaEnabled) {
                    launchBusy {
                        val root = manager.getRoot()
                        cotaRootAvailable = root != null
                        val controller = CotaController(
                            root,
                            moduleEnabled,
                            backupRoot = cotaBackupRoot
                        )
                        val filesResult = controller.listFiles()
                        // The user may have switched the module off while the
                        // read was in flight; do not repopulate stale state.
                        if (!cotaEnabled) {
                            return@launchBusy
                        }
                        when (filesResult) {
                            is CotaResult.Ok -> {
                                cotaFiles = filesResult.value
                                cotaLoaded = true
                                // Keep selections that still exist after a
                                // refresh; disappearances are pruned.
                                val names = filesResult.value
                                    .map { it.name }
                                    .toSet()
                                cotaSelected = cotaSelected intersect names
                                // The grouped list already shows the count;
                                // keeping a message here duplicated it.
                                cotaMessage = null
                            }
                            is CotaResult.Error -> {
                                cotaMessage = filesResult.message
                                cotaLoaded = false
                                cotaFiles = emptyList()
                                cotaSelected = emptySet()
                            }
                            CotaResult.Disabled -> {
                                cotaMessage = "模块未启用或没有 Root"
                                cotaLoaded = false
                                cotaFiles = emptyList()
                                cotaSelected = emptySet()
                            }
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
                            is CotaResult.Error ->
                                cotaMessage =
                                    "读取备份列表失败：${backupsResult.message}"
                            CotaResult.Disabled ->
                                cotaMessage = "模块未启用或没有 Root"
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
                                    bootFreezeResultBootCount == currentBoot
                                }
                                ?.let { BootStatusCopy.of(it).skipReason },
                            lastAttemptText = formatEpoch(bootFreezeAttemptTime),
                            lastSuccessText = formatEpoch(bootFreezeFinishTime),
                            plannedCount = catalog.count {
                                // Match BootFreezeScheduler/Worker: AppOps or
                                // standby-only targets are active plans too.
                                it.enabled && it.active
                            },
                            windowMissed = bootFreezeWindowMissed
                        ),
                        cota = CotaUiState(
                            moduleEnabled = cotaEnabled,
                            rootAvailable = cotaRootAvailable,
                            busy = busy,
                            dryRun = dryRun,
                            loaded = cotaLoaded,
                            files = cotaFiles,
                            selected = cotaSelected,
                            backups = cotaBackups,
                            lastBackupId = cotaLastBackupId,
                            message = cotaMessage
                        ),
                        logs = logs,
                        logSettings = logUi,
                        rescueMode = rescueMode,
                        catalog = catalog,
                        lastCatalogResult = lastCatalogResult,
                        catalogRefreshing = catalogRefreshing,
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
                        onAuthorizeRoot = {
                            val checking = privilegeOverview?.rootAvailable == true
                            scope.launch {
                                val granted = runCatching {
                                    manager.requestRoot()
                                }.getOrDefault(false)
                                if (!checking || !granted) {
                                    loadPrivilegeOverview()
                                }
                                Toast.makeText(
                                    this@MainActivity,
                                    when {
                                        granted && checking -> "Root 可用"
                                        granted -> "Root 已授权"
                                        checking ->
                                            "Root 已不可用，请重新授权"
                                        else ->
                                            "未获得 Root；请在 KernelSU/Magisk 中允许 Nemu"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onAuthorizeShizuku = {
                            val running = runCatching {
                                Shizuku.pingBinder()
                            }.getOrDefault(false)
                            val granted = running && runCatching {
                                Shizuku.checkSelfPermission() ==
                                    PackageManager.PERMISSION_GRANTED
                            }.getOrDefault(false)
                            when {
                                granted -> Toast.makeText(
                                    this@MainActivity,
                                    "Shizuku 已授权",
                                    Toast.LENGTH_SHORT
                                ).show()
                                running -> {
                                    // Canonical Shizuku permission dialog.
                                    val requested = runCatching {
                                        Shizuku.requestPermission(0)
                                        true
                                    }.getOrDefault(false)
                                    if (!requested) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "无法弹出授权窗口；请在 Shizuku 中手动授权",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                else -> {
                                    // Shizuku is not running: open Stellar's
                                    // manager window when installed so the
                                    // user has a visible next step.
                                    val stellarMain =
                                        packageManager.getLaunchIntentForPackage(
                                            "roro.stellar.manager"
                                        )
                                    if (stellarMain != null) {
                                        startActivity(
                                            stellarMain.addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK
                                            )
                                        )
                                        Toast.makeText(
                                            this@MainActivity,
                                            "请在 Stellar 中启动并授权",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Shizuku 未运行，请先启动 Shizuku",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        onRefreshPrivilege = { refreshPrivilege(notify = true) },
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
                                val fallbackOpened = runCatching {
                                    startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                    true
                                }.getOrDefault(false)
                                if (!fallbackOpened) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "请在系统设置中允许 Nemu 自启动",
                                        Toast.LENGTH_SHORT
                                    ).show()
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
                                    cotaLoaded = false
                                    cotaFiles = emptyList()
                                    cotaSelected = emptySet()
                                    cotaMessage =
                                        "模块已关闭；不会删除或恢复文件，备份在重新开启后仍可恢复"
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
                                    // Backup only writes an app-private copy;
                                    // it is safe in preview and is the required
                                    // prerequisite for delete.
                                    val root = manager.getRoot()
                                    cotaRootAvailable = root != null
                                    val controller = CotaController(
                                            root,
                                            cotaEnabled,
                                            backupRoot = cotaBackupRoot
                                        )
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
                                            CotaController(
                                            root,
                                            cotaEnabled,
                                            backupRoot = cotaBackupRoot
                                        )
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
                                            }
                                            is CotaResult.Error ->
                                                cotaMessage = result.message
                                            CotaResult.Disabled ->
                                                cotaMessage = "模块未启用或没有 Root"
                                        }
                                        // A failure can still have deleted
                                        // files before the error; always
                                        // re-list instead of leaving stale rows.
                                        refreshCotaFilesAfterAction(controller)
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
                                    val controller = CotaController(
                                            root,
                                            cotaEnabled,
                                            backupRoot = cotaBackupRoot
                                        )
                                    when (
                                        val result = controller.restore(backupId)
                                    ) {
                                        is CotaResult.Ok -> {
                                            cotaMessage =
                                                "已从备份 $backupId 恢复"
                                        }
                                        is CotaResult.Error ->
                                            cotaMessage = result.message
                                        CotaResult.Disabled ->
                                            cotaMessage = "模块未启用或没有 Root"
                                    }
                                    // Restore may be partial on failure; the
                                    // list must reflect what is actually there.
                                    refreshCotaFilesAfterAction(controller)
                                }
                            },
                            onDeleteBackup = { backupId ->
                                launchBusy {
                                    if (settings.dryRun) {
                                        cotaMessage = "预览：删除备份未执行"
                                        return@launchBusy
                                    }
                                    val root = manager.getRoot()
                                    cotaRootAvailable = root != null
                                    val controller = CotaController(
                                            root,
                                            cotaEnabled,
                                            backupRoot = cotaBackupRoot
                                        )
                                    when (
                                        val result =
                                            controller.deleteBackup(backupId)
                                    ) {
                                        is CotaResult.Ok -> {
                                            cotaBackups = cotaBackups.filterNot {
                                                it.id == backupId
                                            }
                                            if (cotaLastBackupId == backupId) {
                                                cotaLastBackupId =
                                                    cotaBackups
                                                        .firstOrNull()?.id
                                            }
                                            cotaMessage =
                                                "已删除备份 $backupId"
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
                                ActionLog.clear()
                                logUi = logUi.copy(message = "日志已清空")
                                refreshLogUi()
                            }
                        },
                        onClearRescue = {
                            scope.launch {
                                val backend = manager.getRoot()
                                if (backend == null) {
                                    lastCatalogResult = "无 Root，无法清除救援模式"
                                    ActionLog.warn(lastCatalogResult!!)
                                    Toast.makeText(
                                        this@MainActivity,
                                        lastCatalogResult,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else if (BootGuard.clear(backend)) {
                                    rescueMode = false
                                    lastCatalogResult =
                                        "救援模式已清除；下次开机模块恢复"
                                    ActionLog.info(lastCatalogResult!!)
                                    Toast.makeText(
                                        this@MainActivity,
                                        lastCatalogResult,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshBootGuardState()
                                } else {
                                    lastCatalogResult = "清除救援模式失败"
                                    ActionLog.warn(lastCatalogResult!!)
                                    Toast.makeText(
                                        this@MainActivity,
                                        lastCatalogResult,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onCatalogToggle = { packageName, value ->
                            val target = TargetCatalog.byPackage(packageName)
                            if (target == null) {
                                ActionLog.warn("未知目标：$packageName")
                            } else if (value &&
                                !TargetPolicy(this@MainActivity).isActive(target)
                            ) {
                                ActionLog.warn("${target.label} 当前为观察，不能加入开机计划")
                            } else {
                                val policy = TargetPolicy(this@MainActivity)
                                val reason = if (value) {
                                    TargetCopy.unavailableReason(
                                        target,
                                        policy.preset(target),
                                        cotaRootAvailable
                                    )
                                } else {
                                    null
                                }
                                if (value && reason != null) {
                                    ActionLog.warn(
                                        "${target.label} 当前策略不可用（$reason），" +
                                            "不能加入开机计划"
                                    )
                                } else {
                                    policy.setEnabled(target, value)
                                    // Flip the row immediately: selection is
                                    // pure policy and must never wait on a
                                    // shell read.
                                    catalog = catalog.map { row ->
                                        if (row.packageName == packageName) {
                                            row.copy(enabled = value)
                                        } else {
                                            row
                                        }
                                    }
                                    rebuildCatalog()
                                    scheduleShutdownScriptWrite()
                                    refreshBootFreezeSchedule()
                                }
                            }
                        },
                        onCatalogPresetChange = { packageName, preset ->
                            launchBusy {
                                val target = TargetCatalog.byPackage(packageName)
                                if (target != null) {
                                    val policy = TargetPolicy(this@MainActivity)
                                    val previous = policy.preset(target)
                                    if (previous != preset) {
                                        val row = catalog.firstOrNull {
                                            it.packageName == packageName
                                        }
                                        // Ownership-based: externally disabled
                                        // or suspended packages must not block a
                                        // preset switch, otherwise the card can
                                        // be stuck on "停用" with every control
                                        // disabled.
                                        val hasFrozenState =
                                            policy.isApplied(target) ||
                                                policy.ownsHidden(target) ||
                                                policy.originalEnabled(target) != null ||
                                                policy.hasComponentOwnership(target) ||
                                                policy.ownsAppOps(target) ||
                                                policy.ownsStandby(target) ||
                                                row?.ownsAnyState == true

                                        fun persistPreset() {
                                            policy.setPreset(target, preset)
                                            catalog = catalog.map { item ->
                                                if (item.packageName == packageName) {
                                                    item.copy(
                                                        preset = preset,
                                                        strategy = TargetCopy
                                                            .baseStrategy(preset)
                                                    )
                                                } else {
                                                    item
                                                }
                                            }
                                            rebuildCatalog()
                                            // The restore script encodes the
                                            // strategy; regenerate it off the UI
                                            // path.
                                            scheduleShutdownScriptWrite()
                                            refreshBootFreezeSchedule()
                                        }

                                        if (!hasFrozenState) {
                                            persistPreset()
                                            return@launchBusy
                                        }
                                        // A frozen target cannot just swap the
                                        // stored strategy: the old mode's live
                                        // state (suspension, components,
                                        // disable-user, hide or restrictions)
                                        // would remain. Undo it first, then
                                        // persist and apply the new plan.
                                        if (settings.dryRun) {
                                            lastCatalogResult =
                                                "预览：将先解冻，再改用「" +
                                                    TargetCopy.label(preset) +
                                                    "」；未修改手机与计划"
                                            ActionLog.info(lastCatalogResult!!)
                                            return@launchBusy
                                        }
                                        val backend = manager.get()
                                        if (backend == null) {
                                            lastCatalogResult =
                                                ResultNote(
                                                    ResultReason.BACKEND_UNAVAILABLE
                                                ).line()
                                            ActionLog.warn(
                                                "无可用权限；无法切换策略"
                                            )
                                            return@launchBusy
                                        }
                                        val newStrategy =
                                            TargetCopy.baseStrategy(preset)
                                        val requiresMarkers =
                                            newStrategy == FreezeStrategy.COMPONENT ||
                                                newStrategy == FreezeStrategy.DISABLE_USER ||
                                                newStrategy == FreezeStrategy.HIDE
                                        val rootBackend = manager.getRoot()
                                        val rescue =
                                            BootGuard.rescueState(rootBackend)
                                        val rescueBlocks = preset != StrategyPreset.OBSERVE &&
                                            (rescue == true ||
                                                (rescue == null &&
                                                    (rootBackend != null ||
                                                        requiresMarkers)))
                                        if (rescueBlocks) {
                                            lastCatalogResult =
                                                ResultNote(
                                                    ResultReason.RESCUE_MODE,
                                                    detail = if (rescue == true) {
                                                        null
                                                    } else {
                                                        "救援状态未知"
                                                    }
                                                ).line()
                                            ActionLog.warn(
                                                "自动救砖模式/状态未知：" +
                                                    "请先清除救援标记再切换策略"
                                            )
                                            return@launchBusy
                                        }
                                        val bootCount =
                                            BootCount.current(this@MainActivity)
                                        if (!bootFreezeSettings.recordAttempt(
                                                bootCount,
                                                System.currentTimeMillis()
                                            )
                                        ) {
                                            lastCatalogResult =
                                                ResultNote(
                                                    ResultReason.OWNERSHIP_WRITE_FAILED
                                                ).line()
                                            ActionLog.warn(
                                                "策略切换尝试无法持久化，未执行命令"
                                            )
                                            return@launchBusy
                                        }
                                        BootFreezeScheduler.cancel(
                                            this@MainActivity
                                        )
                                        val service = targetService(backend)
                                        val restore = withContext(Dispatchers.IO) {
                                            service.restore(
                                                listOf(target),
                                                force = true
                                            )
                                        }
                                        // Restore owned components too: a
                                        // component owned outside the stored
                                        // strategy must not survive the switch.
                                        val components = withContext(Dispatchers.IO) {
                                            FreezeOperationLock.mutex.withLock {
                                                service.restoreOwnedComponents(
                                                    listOf(target)
                                                )
                                            }
                                        }
                                        if (!restore.ok || !components.ok) {
                                            // Keep the old preset: it is the
                                            // recovery key for whatever state
                                            // the failed restore left behind.
                                            lastCatalogResult =
                                                "切换「" + TargetCopy.label(preset) +
                                                    "」失败：撤销「" +
                                                    TargetCopy.label(previous) +
                                                    "」未完成（" +
                                                    restore.summary() +
                                                    "；组件：" +
                                                    components.summary() +
                                                    "）；计划仍为「" +
                                                    TargetCopy.label(previous) + "」"
                                            ActionLog.warn(lastCatalogResult!!)
                                            bootFreezeSettings.recordResult(
                                                bootCount = bootCount,
                                                result = "策略切换失败：旧状态未撤销",
                                                detail = lastCatalogResult,
                                                succeeded = false,
                                                timestampMillis =
                                                    System.currentTimeMillis()
                                            )
                                            refreshBootFreezeState()
                                            refreshTargetActual()
                                            refreshDetection()
                                            backendStatus = manager.status()
                                            return@launchBusy
                                        }
                                        persistPreset()
                                        val result = withContext(Dispatchers.IO) {
                                            service.apply(listOf(target))
                                        }
                                        val applyText = if (
                                            preset == StrategyPreset.OBSERVE
                                        ) {
                                            "已解冻，不再执行冻结"
                                        } else {
                                            result.summary()
                                        }
                                        lastCatalogResult =
                                            "已切换「" + TargetCopy.label(preset) +
                                                "」：" + applyText
                                        if (result.ok) {
                                            ActionLog.info(lastCatalogResult!!)
                                        } else {
                                            ActionLog.warn(lastCatalogResult!!)
                                        }
                                        bootFreezeSettings.recordResult(
                                            bootCount = bootCount,
                                            result = if (result.ok) {
                                                "策略切换成功"
                                            } else {
                                                "策略已切换，应用新策略有失败"
                                            },
                                            detail = lastCatalogResult,
                                            succeeded = result.ok,
                                            timestampMillis =
                                                System.currentTimeMillis()
                                        )
                                        refreshBootFreezeState()
                                        refreshTargetActual()
                                        refreshDetection()
                                        backendStatus = manager.status()
                                    }
                                }
                            }
                        },
                        onCatalogComponentsChange = { packageName, selected ->
                            launchBusy {
                                val target = TargetCatalog.byPackage(packageName)
                                if (target != null) {
                                    val policy = TargetPolicy(this@MainActivity)
                                    // Selection is just a plan. Any owned
                                    // component that is no longer selected is
                                    // restored by the next Apply, together
                                    // with the rest of the freeze operation.
                                    if (policy.setComponentSelection(target, selected)) {
                                        lastCatalogResult =
                                            "已选择 ${selected.size} 个组件"
                                        rebuildCatalog()
                                        scheduleShutdownScriptWrite()
                                        refreshBootFreezeSchedule()
                                    } else {
                                        lastCatalogResult =
                                            ResultNote(
                                                ResultReason.COMPONENT_SELECTION_EMPTY
                                            ).line()
                                        ActionLog.warn(lastCatalogResult!!)
                                    }
                                }
                            }
                        },
                        onCatalogApply = { selected ->
                            launchBusy {
                                val backend = manager.get()
                                val policy = TargetPolicy(this@MainActivity)
                                val targets = TargetCatalog.all.filter {
                                    it.packageName in selected &&
                                        policy.isActive(it)
                                }
                                // Null means the guard marker could not be
                                // read. With no root (Shizuku-only) a pure
                                // SUSPEND can still run; root-marker
                                // strategies must stay blocked.
                                val rootBackend = manager.getRoot()
                                val rescue = BootGuard.rescueState(rootBackend)
                                val requiresMarkers = targets.any {
                                    policy.strategy(it) ==
                                        FreezeStrategy.COMPONENT ||
                                        policy.strategy(it) ==
                                        FreezeStrategy.DISABLE_USER ||
                                        policy.strategy(it) ==
                                        FreezeStrategy.HIDE
                                }
                                val rescueBlocks = rescue == true ||
                                    (rescue == null &&
                                        (rootBackend != null || requiresMarkers))
                                when {
                                    backend == null -> {
                                        lastCatalogResult =
                                            ResultNote(
                                                ResultReason.BACKEND_UNAVAILABLE
                                            ).line()
                                        ActionLog.warn("无可用权限；无法应用冻结策略")
                                    }
                                    targets.isEmpty() -> {
                                        lastCatalogResult =
                                            ResultNote(
                                                ResultReason.NO_ENABLED_TARGETS
                                            ).line()
                                        ActionLog.warn(lastCatalogResult!!)
                                    }
                                    rescueBlocks -> {
                                        lastCatalogResult =
                                            ResultNote(
                                                ResultReason.RESCUE_MODE,
                                                detail = if (rescue == true) {
                                                    null
                                                } else {
                                                    "救援状态未知"
                                                }
                                            ).line()
                                        ActionLog.warn(
                                            "自动救砖模式/状态未知：请先清除救援标记再冻结"
                                        )
                                    }
                                    else -> {
                                        val bootCount = BootCount.current(this@MainActivity)
                                        if (!settings.dryRun) {
                                            if (!bootFreezeSettings.recordAttempt(
                                                    bootCount,
                                                    System.currentTimeMillis()
                                                )
                                            ) {
                                                lastCatalogResult =
                                                    ResultNote(
                                                        ResultReason.OWNERSHIP_WRITE_FAILED
                                                    ).line()
                                                ActionLog.warn(
                                                    "冻结尝试无法持久化，未执行命令"
                                                )
                                                return@launchBusy
                                            }
                                            BootFreezeScheduler.cancel(
                                                this@MainActivity
                                            )
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            targetService(backend).apply(targets)
                                        }
                                        val summary = result.summary()
                                        lastCatalogResult =
                                            if (settings.dryRun) {
                                                "预览：$summary"
                                            } else {
                                                summary
                                            }
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
                                        val logText =
                                            (if (settings.dryRun) "预览：" else "") +
                                                "应用冻结：$summary"
                                        if (result.ok) {
                                            ActionLog.info(logText)
                                        } else {
                                            ActionLog.warn(logText)
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
                                        lastCatalogResult =
                                            ResultNote(
                                                ResultReason.BACKEND_UNAVAILABLE
                                            ).line()
                                        ActionLog.warn("无可用权限；无法解冻所选")
                                    }
                                    targets.isEmpty() -> {
                                        lastCatalogResult =
                                            ResultNote(ResultReason.NO_SELECTION).line()
                                        ActionLog.warn(lastCatalogResult!!)
                                    }
                                    else -> {
                                        val policy = TargetPolicy(this@MainActivity)
                                        val ownedTargets = targets.filter { target ->
                                            policy.isApplied(target) ||
                                                policy.ownsHidden(target) ||
                                                policy.originalEnabled(target) != null ||
                                                policy.hasComponentOwnership(target) ||
                                                policy.ownsAppOps(target) ||
                                                policy.ownsStandby(target)
                                        }
                                        if (ownedTargets.isEmpty()) {
                                            lastCatalogResult =
                                                "所选目标没有 Nemu 所有权，未解冻他人暂停"
                                            ActionLog.warn(lastCatalogResult!!)
                                            return@launchBusy
                                        }
                                        val foreignCount =
                                            targets.size - ownedTargets.size
                                        if (!settings.dryRun) {
                                            BootFreezeScheduler.cancel(this@MainActivity)
                                            val bootCount =
                                                BootCount.current(this@MainActivity)
                                            bootFreezeSettings.recordManualSkip(
                                                bootCount = bootCount,
                                                result = "已跳过：手动解冻",
                                                detail = "本次开机不自动冻结"
                                            )
                                        }
                                        val service = targetService(backend)
                                        val previouslyOwned = ownedTargets.filter {
                                            policy.isApplied(it) || policy.ownsHidden(it)
                                        }.mapTo(mutableSetOf()) { it.packageName }
                                        // Restore with the stored strategy before
                                        // disabling the policy; otherwise a legacy
                                        // disable-user target could be treated as
                                        // suspend and left disabled.
                                        val result = withContext(Dispatchers.IO) {
                                            service.restore(
                                                ownedTargets,
                                                force = true
                                            )
                                        }
                                        // Scope component restoration to the
                                        // selected targets: restoring A must
                                        // not silently re-enable B's
                                        // components while B stays marked.
                                        val components = withContext(Dispatchers.IO) {
                                            FreezeOperationLock.mutex.withLock {
                                                service.restoreOwnedComponents(
                                                    ownedTargets
                                                )
                                            }
                                        }
                                        // Per-target plan cleanup: a restored
                                        // target loses its ownership markers;
                                        // a failed one keeps them and therefore
                                        // keeps its plan. Do not use the batch
                                        // result, which would leave successful
                                        // targets planned after a partial fail.
                                        if (!settings.dryRun) {
                                            ownedTargets.forEach { target ->
                                                val stillOwned =
                                                    policy.isApplied(target) ||
                                                        policy.ownsHidden(target)
                                                if (target.packageName in previouslyOwned &&
                                                    !stillOwned
                                                ) {
                                                    policy.setEnabled(target, false)
                                                }
                                            }
                                        }
                                        lastCatalogResult =
                                            (if (settings.dryRun) "预览：" else "") +
                                                "解冻所选 ${ownedTargets.size} 项：" +
                                                result.summary() +
                                                "；组件：${components.summary()}" +
                                                if (foreignCount > 0) {
                                                    "；跳过 $foreignCount 项他人暂停"
                                                } else {
                                                    ""
                                                }
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
                                    lastCatalogResult =
                                        ResultNote(
                                            ResultReason.BACKEND_UNAVAILABLE
                                        ).line()
                                    ActionLog.warn("无可用权限；无法全部解冻")
                                } else {
                                    val policy = TargetPolicy(this@MainActivity)
                                    if (!settings.dryRun) {
                                        BootFreezeScheduler.cancel(this@MainActivity)
                                        val bootCount =
                                            BootCount.current(this@MainActivity)
                                        bootFreezeSettings.recordManualSkip(
                                            bootCount = bootCount,
                                            result = "已跳过：全部解冻",
                                            detail = "本次开机不自动冻结"
                                        )
                                    }
                                    val service = targetService(backend)
                                    // "全部解冻" means everything Nemu owns, not
                                    // every package another tool suspended.
                                    val targets = TargetCatalog.all.filter { target ->
                                        policy.isApplied(target) ||
                                            policy.ownsHidden(target) ||
                                            policy.originalEnabled(target) != null ||
                                            policy.hasComponentOwnership(target) ||
                                            policy.ownsAppOps(target) ||
                                            policy.ownsStandby(target)
                                    }.distinctBy { it.packageName }
                                    val result = withContext(Dispatchers.IO) {
                                            service.restore(targets, force = true)
                                        }
                                    // A preview must not touch the device. The
                                    // always-on residual path still runs for
                                    // real restores, shutdown and boot recovery.
                                    val residualText =
                                        if (RestoreExecutionPolicy.runsResidualCleanup(
                                                settings.dryRun
                                            )
                                        ) {
                                            val residual = withContext(Dispatchers.IO) {
                                                safetyService(backend).residualUnfreeze()
                                            }
                                            "${RestoreExecutionPolicy.residualLabel(false)}：${residual.summary()}"
                                        } else {
                                            RestoreExecutionPolicy.residualLabel(true)
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
                        onCustomTargetAdd = { packageName, label, risk ->
                            if (com.nemu.hibernate.core.CustomTargetGuard.isSelf(packageName)) {
                                ActionLog.warn("不能添加 Nemu 自身：$packageName")
                            } else if (TargetCatalog.byPackage(packageName) != null) {
                                ActionLog.warn("已在目标目录中，不能重复添加：$packageName")
                            } else {
                                settings.addCustomTarget(packageName, label, risk)
                                TargetCatalog.setCustomTargets(
                                    settings.customTargets().map { record ->
                                        TargetCatalog.customTarget(
                                            record.packageName,
                                            record.label,
                                            record.risk
                                        )
                                    }
                                )
                                TargetCatalog.byPackage(packageName)?.let { target ->
                                    // Adding a target must never enroll it in the
                                    // boot plan implicitly; the user enables the
                                    // plan explicitly on the card.
                                    TargetPolicy(this@MainActivity)
                                        .setEnabled(target, false)
                                    // Only the new package needs an actual-state
                                    // read; a full-catalog refresh plus a
                                    // detection probe here would be needless.
                                    refreshTargetActual(
                                        listOf(target),
                                        force = true
                                    )
                                } ?: rebuildCatalog()
                                refreshBootFreezeSchedule()
                            }
                        },
                        onCustomTargetRemove = { packageName ->
                            val target = TargetCatalog.byPackage(packageName)
                            val policy = TargetPolicy(this@MainActivity)
                            val row = catalog.firstOrNull {
                                it.packageName == packageName
                            }
                            val blocked = com.nemu.hibernate.core.CustomTargetGuard
                                .shouldBlockRemoval(
                                    owned = target != null &&
                                        policy.isApplied(target),
                                    hasFrozenState = row?.hasFrozenState == true
                                )
                            if (blocked) {
                                lastCatalogResult =
                                    "请先解冻再移除自定义目标：$packageName"
                                ActionLog.warn(lastCatalogResult!!)
                                Toast.makeText(
                                    this@MainActivity,
                                    "请先解冻再移除",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                settings.removeCustomTarget(packageName)
                                TargetCatalog.setCustomTargets(
                                    settings.customTargets().map { record ->
                                        TargetCatalog.customTarget(
                                            record.packageName,
                                            record.label,
                                            record.risk
                                        )
                                    }
                                )
                                // Remove the row immediately; the cached
                                // rebuild only reconciles policy fields.
                                catalog = catalog.filterNot {
                                    it.packageName == packageName
                                }
                                rebuildCatalog()
                                scheduleShutdownScriptWrite()
                                refreshBootFreezeSchedule()
                            }
                        },
                        onTabSelected = { tab ->
                            // Page switches are pure UI. Only the detection
                            // page queries, and the unfreeze page reuses the
                            // cached actual snapshot unless it is stale.
                            if (tab != DashboardTab.APPLICATIONS) {
                                focusPackage = null
                            }
                            when (tab) {
                                DashboardTab.DETECTION -> refreshDetection()
                                DashboardTab.OVERVIEW -> refreshBootFreezeState()
                                DashboardTab.APPLICATIONS -> {
                                    rebuildCatalog()
                                    val policy = TargetPolicy(this@MainActivity)
                                    val applied = TargetCatalog.all.filter {
                                        policy.isApplied(it)
                                    }
                                    if (applied.isNotEmpty()) {
                                        refreshTargetActual(applied, force = false)
                                    }
                                }
                                DashboardTab.ALL_APPS -> rebuildCatalog()
                                DashboardTab.SETTINGS -> Unit
                            }
                        }
                    ),
                    focusPackage = focusPackage,
                    onClearFocus = { focusPackage = null },
                    detectionContent = {
                        DetectionScreen(
                            state = detectionScreen,
                            onRefresh = { refreshDetection() },
                            onOpenTarget = { focusPackage = it }
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
        val installTimes = TargetCatalog.all.associate { target ->
            target.packageName to runCatching {
                packageManager.getPackageInfo(
                    target.packageName,
                    0
                ).firstInstallTime
            }.getOrDefault(0L)
        }
        return TargetCatalog.all.map { target ->
            val strategy = policy.strategy(target)
            val options = policy.options(target)
            val preset = options?.presetOf(strategy)
                ?: com.nemu.hibernate.core.StrategyPreset.OBSERVE
            val active = policy.isActive(target)
            val effectiveComponents = policy.effectiveComponents(target)
            val componentStates = if (strategy == FreezeStrategy.COMPONENT) {
                effectiveComponents.map { componentStateReader.state(it.className) }
            } else {
                emptyList()
            }
            val allComponentStates = if (strategy == FreezeStrategy.COMPONENT) {
                target.components.associate {
                    it.className to componentStateReader.state(it.className)
                }
            } else {
                emptyMap()
            }
            val selectedComponents = if (strategy == FreezeStrategy.COMPONENT) {
                policy.selectedComponents(target)
            } else {
                emptySet()
            }
            // Ownership survives an app update that removes every registered
            // component; never hide the row just because the strategy fell
            // back to its default.
            val ownedComponents = policy.ownedComponentNames(target).toSet()
            val ownedComponentStates = ownedComponents.mapNotNull {
                componentStateReader.state(it)
            }
            val componentsDisabled = when {
                strategy == FreezeStrategy.COMPONENT -> when {
                    effectiveComponents.isEmpty() -> null
                    componentStates.all { it == null } -> null
                    else -> componentStates.any { state ->
                        state == 2 || state == 3 || state == 4
                    }
                }
                ownedComponents.isEmpty() -> null
                ownedComponentStates.isEmpty() -> null
                else -> ownedComponentStates.any { it == 2 || it == 3 || it == 4 }
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
                preset = preset,
                active = active,
                applied = policy.isApplied(target) && actuallyFrozen &&
                    !preDisabledAdopted,
                actual = actualState,
                componentsDisabled = componentsDisabled,
                selectedComponents = selectedComponents,
                ownedComponents = ownedComponents,
                componentStates = allComponentStates,
                ownedDisable = policy.originalEnabled(target) != null,
                ownedHidden = policy.ownsHidden(target),
                appOpsOwned = policy.ownsAppOps(target),
                standbyOwned = policy.ownsStandby(target),
                ownershipPending = isOwnershipPending(
                    applied = policy.isApplied(target),
                    actuallyFrozen = actuallyFrozen,
                    ownsAppOps = policy.ownsAppOps(target),
                    ownsStandby = policy.ownsStandby(target)
                ),
                installedAt = installTimes[target.packageName] ?: 0L
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
            rootBackend = root
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

    override fun onResume() {
        super.onResume()
        SafetyService.sync(this)
        // Returning from Stellar (or Shizuku's own UI) should update the auth
        // card without triggering another root probe.
        privilegeQuickRefresh?.invoke()
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
