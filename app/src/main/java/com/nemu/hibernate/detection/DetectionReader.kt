package com.nemu.hibernate.detection

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.RestrictionParsers
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.StrategyOptionsCodec
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetCategory
import com.nemu.hibernate.core.Targets
import com.nemu.hibernate.cota.CotaCommandBuilder
import com.nemu.hibernate.cota.CotaController
import com.nemu.hibernate.cota.CotaParsers
import com.nemu.hibernate.cota.CotaResult
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.AndroidComponentStateReader
import com.nemu.hibernate.privilege.ComponentStateReader
import com.nemu.hibernate.policy.BootCount
import com.nemu.hibernate.policy.BootGuard
import com.nemu.hibernate.policy.RestrictionController
import com.nemu.hibernate.policy.TargetPolicy
import com.nemu.hibernate.privilege.PackageStatusReader
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only detection. Every probe is best-effort: failures become explicit
 * state ([CotaReadStatus.ERROR], "读取失败", snapshot notes) and never throw
 * out of [read]. No write commands are built here; COTA uses only
 * [CotaController.listFiles]. Every row uses the same stored strategy as the
 * Applications page; there is no separate fixed-action cloud model.
 */
class DetectionReader(
    private val context: Context,
    private val packageStatusReader: PackageStatusReader,
    private val coreTargets: List<Target> = Targets.all,
    private val aiTargets: List<Target> = AiTargets.all,
    private val rootBackend: RootPrivilegeBackend? = null,
    /** Every installed-target row, including cloud/telemetry entries. */
    private val catalogTargets: List<Target> = TargetCatalog.all,
    private val componentStateReader: ComponentStateReader =
        AndroidComponentStateReader(context)
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun read(settings: Settings): DetectionSnapshot =
        withContext(Dispatchers.IO) {
            val notes = mutableListOf<String>()
            val processSnapshot = if (rootBackend == null) {
                null
            } else {
                readProcessSnapshot().also { snapshot ->
                    if (snapshot == null) {
                        notes += "Root 进程列表读取失败；进程存活状态未知"
                    }
                }
            }

            // One strategy-aware row per catalog target (core/AI/update/
            // cloud/safety/thermal), using the stored strategy, never a
            // hard-coded per-section action.
            val catalogStates = readSectionSafe(
                label = "统一目录状态读取失败",
                notes = notes,
                fallback = emptyList()
            ) { readCatalogStates(processSnapshot) }

            val thermal = readSectionSafe(
                label = "温控服务状态读取失败",
                notes = notes,
                fallback = ThermalState(message = "读取失败")
            ) { readThermalState() }

            val cota = readSectionSafe(
                label = "COTA 状态读取失败",
                notes = notes,
                fallback = CotaState(CotaReadStatus.ERROR, message = "读取异常")
            ) { readCotaState(settings) }

            val bootSafety = readSectionSafe(
                label = "启动/安全状态读取失败",
                notes = notes,
                fallback = BootSafetyState()
            ) { readBootSafetyState(settings) }

            DetectionSnapshot(
                freezeStates = catalogStates,
                cota = cota,
                thermal = thermal,
                bootSafety = bootSafety,
                generatedAtMillis = System.currentTimeMillis(),
                notes = notes.toList()
            )
        }

    // ---- Freeze policy vs actual ----

    private fun aiTargetKey(packageName: String): String =
        "ai_target_$packageName"

    /**
     * Strategy-aware rows for every catalog target. COMPONENT rows read the
     * actual component enabled state, so a successful command is never
     * mistaken for a still-enabled component.
     */
    private suspend fun readCatalogStates(
        processSnapshot: ProcessListSnapshot?
    ): List<FreezeState> {
        val corePackages = coreTargets.map { it.packageName }.toSet()
        val aiPackages = aiTargets.map { it.packageName }.toSet()
        return catalogTargets
            .map { target ->
                val actual = readActual(target.packageName, processSnapshot)
                val strategy = catalogStrategy(target)
                val options = readOptions(target)
                val appOpsOwned = prefs.getBoolean(
                    "strategy_appops_owned_${target.packageName}",
                    false
                )
                val standbyOwned = prefs.getBoolean(
                    "strategy_standby_owned_${target.packageName}",
                    false
                )
                val restrictionApplied = appOpsOwned || standbyOwned
                val restrictionRequested = options?.let {
                    it.appOps || it.standby
                } == true
                val mode = when (strategy) {
                    FreezeStrategy.SUSPEND -> FreezeMode.SUSPEND
                    FreezeStrategy.COMPONENT -> FreezeMode.COMPONENT
                    FreezeStrategy.DISABLE_USER -> FreezeMode.DISABLE
                    FreezeStrategy.HIDE -> FreezeMode.HIDE
                    FreezeStrategy.OBSERVE ->
                        if (restrictionRequested || restrictionApplied) {
                            FreezeMode.RESTRICT
                        } else {
                            FreezeMode.OBSERVE
                        }
                }
                val selected = options?.components
                val components = if (mode == FreezeMode.COMPONENT &&
                    selected != null
                ) {
                    target.components.filter { it.className in selected }
                } else {
                    target.components
                }
                val componentStates = if (mode == FreezeMode.COMPONENT) {
                    components.map {
                        componentStateReader.state(it.className)
                    }
                } else {
                    emptyList()
                }
                val disabledComponents =
                    componentStates.count { it == 2 || it == 3 || it == 4 }
                // "Frozen" for COMPONENT means every selected component is
                // disabled. A single disabled component is only a partial
                // result and must not be reported as fully applied.
                val componentDisabled = if (mode == FreezeMode.COMPONENT) {
                    components.isNotEmpty() &&
                        disabledComponents == components.size
                } else {
                    null
                }
                val componentPartial = mode == FreezeMode.COMPONENT &&
                    disabledComponents in 1 until components.size
                val componentKnown = mode != FreezeMode.COMPONENT ||
                    (componentStates.isNotEmpty() &&
                        componentStates.all { it != null })
                val restrictionActual = if (mode == FreezeMode.RESTRICT) {
                    readRestrictionActual(
                        packageName = target.packageName,
                        options = options,
                        appOpsOwned = appOpsOwned,
                        standbyOwned = standbyOwned
                    )
                } else {
                    null
                }
                val appliedMarker = readBoolean(
                    "strategy_applied_${target.packageName}",
                    false
                ) || (target.category == TargetCategory.CLOUD &&
                    readBoolean("cloud_applied_${target.packageName}", false))
                FreezeState(
                    packageName = target.packageName,
                    label = target.label,
                    source = when {
                        target.packageName in corePackages -> FreezeSource.CORE
                        target.packageName in aiPackages -> FreezeSource.AI
                        target.category == TargetCategory.CLOUD ->
                            FreezeSource.CLOUD
                        else -> FreezeSource.CATALOG
                    },
                    mode = mode,
                    policyEnabled = catalogPolicyEnabled(target),
                    installed = actual.installed,
                    actualKnown = actual.known && componentKnown &&
                        (mode != FreezeMode.RESTRICT ||
                            restrictionActual != null),
                    enabled = actual.enabled,
                    suspended = actual.suspended,
                    stopped = actual.stopped,
                    processRunning = actual.processRunning,
                    risk = target.risk,
                    componentDisabled = componentDisabled,
                    componentPartial = componentPartial,
                    restrictionApplied = restrictionApplied,
                    restrictionActual = restrictionActual,
                    // OBSERVE + AppOps/standby never writes strategy_applied;
                    // ownership of the restriction is its own applied proof.
                    policyApplied = appliedMarker || restrictionApplied,
                    preset = options?.presetOf(strategy)
                )
            }
    }

    /**
     * Live AppOps/standby readback for a RESTRICT row. Returns null when any
     * requested value cannot be read; callers then mark the row unknown
     * instead of claiming "not restricted". Without a root backend this is
     * always null (the shell commands would silently fail).
     */
    private suspend fun readRestrictionActual(
        packageName: String,
        options: StrategyOptions?,
        appOpsOwned: Boolean,
        standbyOwned: Boolean
    ): Boolean? {
        val backend = rootBackend ?: return null
        val checkAppOps = options?.appOps == true || (options == null && appOpsOwned)
        val checkStandby =
            options?.standby == true || (options == null && standbyOwned)
        if (!checkAppOps && !checkStandby) return null
        val values = mutableListOf<Boolean>()
        if (checkAppOps) {
            val result = backend.exec(CommandBuilder.appOpsGet(packageName))
            val mode = if (result.ok) {
                RestrictionParsers.parseAppOpsMode(result.output)
            } else {
                null
            } ?: return null
            values += mode.equals("ignore", ignoreCase = true)
        }
        if (checkStandby) {
            val result = backend.exec(CommandBuilder.standbyGet(packageName))
            val bucket = if (result.ok) {
                RestrictionParsers.parseStandbyBucket(result.output)
            } else {
                null
            } ?: return null
            values += bucket == RestrictionController.RESTRICTED_BUCKET
        }
        return values.all { it }
    }

    private fun readOptions(target: Target): StrategyOptions? =
        prefs.getString("strategy_opts_${target.packageName}", null)
            ?.let { StrategyOptionsCodec.decode(it) }

    private fun catalogStrategy(target: Target): FreezeStrategy =
        DetectionStrategy.resolve(
            target = target,
            legacyNumberMode = readString("antifraud_numberidentity_mode"),
            storedRaw = readString("strategy_${target.packageName}")
        )

    private fun catalogPolicyEnabled(target: Target): Boolean {
        if (target.packageName == "com.hihonor.numberidentity") {
            return (readString("antifraud_numberidentity_mode") ?: "OFF") != "OFF"
        }
        if (target.packageName in AI_DEEP_FREEZE_PACKAGES) {
            // Per-target flag is the only AI policy store; the old global
            // switch is gone.
            return readBoolean(aiTargetKey(target.packageName), false)
        }
        if (target.category == TargetCategory.CLOUD) {
            return readBoolean("cloud_target_${target.packageName}", false)
        }
        return readBoolean("target_${target.packageName}", false)
    }

    // ---- Thermal base services (observe only) ----

    private fun readThermalState(): ThermalState = ThermalState(
        services = THERMAL_SERVICES.map { (packageName, label) ->
            val actual = readActual(packageName)
            ThermalServiceState(
                packageName = packageName,
                label = label,
                installed = actual.installed,
                actualKnown = actual.known,
                enabled = actual.enabled,
                suspended = actual.suspended
            )
        }
    )

    // ---- COTA (read-only listFiles only) ----

    private suspend fun readCotaState(settings: Settings): CotaState {
        if (!settings.cotaEnabled) {
            return CotaState(
                status = CotaReadStatus.UNKNOWN,
                message = "COTA 模块未启用"
            )
        }
        val backend = rootBackend
            ?: return CotaState(
                status = CotaReadStatus.NO_ROOT,
                message = "未提供 Root 后端"
            )
        val rootFiles = when (
            val result = CotaController(
                backend,
                settings.cotaEnabled,
                context.filesDir.resolve("cota-backup").absolutePath
            ).listFiles()
        ) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return CotaState(
                status = CotaReadStatus.ERROR,
                message = result.message.take(300)
            )
            CotaResult.Disabled -> return CotaState(
                status = CotaReadStatus.NO_ROOT,
                message = "Root 后端不可用"
            )
        }
        val inventory = backend.exec(CotaCommandBuilder.listSubsystemFiles())
        if (!inventory.ok) {
            return CotaState(
                status = CotaReadStatus.ERROR,
                files = rootFiles,
                message = "读取子目录索引失败：${inventory.output.take(250)}"
            )
        }
        return CotaState(
            status = CotaReadStatus.OK,
            files = rootFiles,
            directories = CotaParsers.parseSubsystemFiles(inventory.output)
        )
    }

    // ---- Boot / safety ----

    private suspend fun readBootSafetyState(settings: Settings): BootSafetyState =
        BootSafetyState(
            readOk = true,
            bootCount = readBootCount(),
            lastPlannedShutdownTime =
                BootGuard.lastPlannedShutdownTime(rootBackend) ?: 0L,
            lastShutdownResult = settings.lastShutdownResult,
            lastShutdownDetail = settings.lastShutdownDetail,
            lastShutdownTime = readLong(KEY_LAST_SHUTDOWN_TIME, 0L),
            lastBootResult = settings.lastBootResult,
            lastBootDetail = settings.lastBootDetail,
            lastBootTime = settings.lastBootTime,
            bootFreezeEnabled = readBoolean(KEY_BOOT_FREEZE_ENABLED, false),
            bootFreezeDelaySeconds = readInt(
                KEY_BOOT_FREEZE_DELAY_SECONDS,
                120
            ).coerceAtLeast(60),
            aiTargetsEnabledAny = TargetCatalog.all
                .filter { it.category == TargetCategory.AI }
                .any { TargetPolicy(context).isEnabled(it) },
            lastBootFreezeResult = readString(KEY_LAST_BOOT_FREEZE_RESULT),
            lastBootFreezeDetail = readString(KEY_LAST_BOOT_FREEZE_DETAIL),
            lastBootFreezeTime = readLong(KEY_LAST_BOOT_FREEZE_TIME, 0L),
            lastBootFreezeAttemptTime =
                readLong(KEY_LAST_BOOT_FREEZE_ATTEMPT_TIME, 0L),
            lastBootFreezeSucceeded =
                readBoolean(KEY_LAST_BOOT_FREEZE_SUCCEEDED, false),
            lastBootFreezeBootCount =
                readInt(KEY_LAST_BOOT_FREEZE_BOOT_COUNT, -1),
            lastBootFreezeAttemptBootCount =
                readInt(KEY_LAST_BOOT_FREEZE_ATTEMPT_BOOT_COUNT, -1),
            skippedBootCount = readInt(KEY_BOOT_FREEZE_SKIPPED_BOOT_COUNT, -1)
        )

    private fun readBootCount(): Int = BootCount.current(context)

    private fun readInt(key: String, default: Int): Int =
        runCatching { prefs.getInt(key, default) }.getOrDefault(default)

    private fun readLong(key: String, default: Long): Long =
        runCatching { prefs.getLong(key, default) }.getOrDefault(default)

    private fun readBoolean(key: String, default: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

    private fun readString(key: String): String? =
        runCatching { prefs.getString(key, null) }.getOrNull()

    // ---- Package probes ----

    private data class PackageActual(
        val installed: Boolean,
        val known: Boolean,
        val enabled: Boolean,
        val suspended: Boolean,
        val stopped: Boolean,
        val processRunning: Boolean?
    )

    private fun readActual(
        packageName: String,
        processSnapshot: ProcessListSnapshot? = null
    ): PackageActual {
        val info = runCatching { applicationInfo(packageName) }.getOrNull()
        val status = runCatching {
            packageStatusReader.read(packageName)
        }.getOrNull()
        return PackageActual(
            installed = info != null,
            known = status != null,
            enabled = status?.enabled ?: false,
            suspended = status?.suspended ?: false,
            stopped = info != null &&
                (info.flags and ApplicationInfo.FLAG_STOPPED) != 0,
            processRunning = processSnapshot?.isRunning(packageName)
        )
    }

    private suspend fun readProcessSnapshot(): ProcessListSnapshot? {
        return try {
            val backend = rootBackend ?: return null
            PackageProcessListReader(backend).read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
    }

    private fun applicationInfo(packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }

    private suspend fun <T> readSectionSafe(
        label: String,
        notes: MutableList<String>,
        fallback: T,
        block: suspend () -> T
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        notes += "$label：${throwable.message ?: throwable.javaClass.simpleName}"
        fallback
    }

    companion object {
        private const val PREFS_NAME = "nemu"

        // Settings.lastShutdownTime is owned by the main session; read the
        // persisted key directly so this module compiles before that lands.
        private const val KEY_LAST_SHUTDOWN_TIME = "last_shutdown_time"

        // Keys from docs/07 section 5.1, owned by data/BootFreezeSettings.kt.
        private const val KEY_BOOT_FREEZE_ENABLED = "boot_freeze_enabled"
        private const val KEY_BOOT_FREEZE_DELAY_SECONDS = "boot_freeze_delay_seconds"
        private const val KEY_LAST_BOOT_FREEZE_BOOT_COUNT =
            "last_boot_freeze_boot_count"
        private const val KEY_LAST_BOOT_FREEZE_ATTEMPT_BOOT_COUNT =
            "last_boot_freeze_attempt_boot_count"
        private const val KEY_LAST_BOOT_FREEZE_ATTEMPT_TIME =
            "last_boot_freeze_attempt_time"
        private const val KEY_LAST_BOOT_FREEZE_TIME = "last_boot_freeze_time"
        private const val KEY_LAST_BOOT_FREEZE_RESULT = "last_boot_freeze_result"
        private const val KEY_LAST_BOOT_FREEZE_DETAIL = "last_boot_freeze_detail"
        private const val KEY_LAST_BOOT_FREEZE_SUCCEEDED =
            "last_boot_freeze_succeeded"
        private const val KEY_BOOT_FREEZE_SKIPPED_BOOT_COUNT =
            "boot_freeze_skipped_boot_count"

        private val AI_DEEP_FREEZE_PACKAGES =
            AiTargets.all.map { it.packageName }.toSet()

        private val THERMAL_SERVICES = listOf(
            "com.hihonor.powergenie" to "PowerGenie（功耗策略）",
            "com.hihonor.iaware" to "iAware（资源调度）"
        )
    }
}
