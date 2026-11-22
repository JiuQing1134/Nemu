package com.nemu.hibernate.detection

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudTarget
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.DenyList
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
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
import com.nemu.hibernate.privilege.PackageStatusReader
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only detection. Every probe is best-effort: failures become explicit
 * state ([CotaReadStatus.ERROR], "读取失败", snapshot notes) and never throw
 * out of [read]. No write commands are built here; COTA uses only
 * [CotaController.listFiles]. PowerGenie/iAware are observed, never stopped.
 */
class DetectionReader(
    private val context: Context,
    private val packageStatusReader: PackageStatusReader,
    private val cloudTargets: List<CloudTarget> = CloudTargets.all,
    private val coreTargets: List<Target> = Targets.all,
    private val aiTargets: List<Target> = AiTargets.all,
    private val rootBackend: RootPrivilegeBackend? = null,
    /**
     * Optional policy snapshot from CloudSettings. Missing entries fall back
     * to the same `cloud_target_<pkg>` preference key, so the reader does not
     * need a compile-time dependency on CloudSettings.
     */
    private val cloudEnabled: Map<String, Boolean> = emptyMap(),
    /** Unified catalog rows not covered by the legacy core/AI/cloud lists. */
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

            val cloudStates = readSectionSafe(
                label = "云控/遥测状态读取失败",
                notes = notes,
                fallback = emptyList()
            ) { readCloudStates(processSnapshot) }

            // One strategy-aware row per catalog target. Cloud targets have
            // their own richer section and are excluded here to avoid
            // duplicates; every other target (core/AI/update/safety/thermal)
            // is rendered with its actual strategy, not a hard-coded SUSPEND.
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
            ) { readCotaState() }

            val bootSafety = readSectionSafe(
                label = "启动/安全状态读取失败",
                notes = notes,
                fallback = BootSafetyState()
            ) { readBootSafetyState(settings) }

            DetectionSnapshot(
                // Cloud rows have their own detailed section; do not duplicate
                // them in the freeze list or double-count mismatches.
                freezeStates = catalogStates,
                cloudStates = cloudStates,
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
     * Strategy-aware rows for every catalog target except the cloud section's
     * own packages (including the observe-only pushagent extra). COMPONENT
     * rows read the actual component enabled state, so a successful command is
     * never mistaken for a still-enabled component.
     */
    private fun readCatalogStates(
        processSnapshot: ProcessListSnapshot?
    ): List<FreezeState> {
        val corePackages = coreTargets.map { it.packageName }.toSet()
        val aiPackages = aiTargets.map { it.packageName }.toSet()
        val covered = (
            cloudTargets.map { it.packageName } + CLOUD_EXTRA_PACKAGES
            ).toSet()
        return catalogTargets
            .filter { it.packageName !in covered }
            .map { target ->
                val actual = readActual(target.packageName, processSnapshot)
                val strategy = catalogStrategy(target)
                val mode = when (strategy) {
                    FreezeStrategy.SUSPEND -> FreezeMode.SUSPEND
                    FreezeStrategy.COMPONENT -> FreezeMode.COMPONENT
                    FreezeStrategy.DISABLE_USER -> FreezeMode.DISABLE
                    FreezeStrategy.HIDE -> FreezeMode.HIDE
                    FreezeStrategy.OBSERVE -> FreezeMode.OBSERVE
                }
                val componentStates = if (mode == FreezeMode.COMPONENT) {
                    target.components.map {
                        componentStateReader.state(it.className)
                    }
                } else {
                    emptyList()
                }
                val componentDisabled = if (mode == FreezeMode.COMPONENT) {
                    componentStates.any { it == 2 || it == 3 || it == 4 }
                } else {
                    null
                }
                val componentKnown = mode != FreezeMode.COMPONENT ||
                    (componentStates.isNotEmpty() &&
                        componentStates.all { it != null })
                FreezeState(
                    packageName = target.packageName,
                    label = target.label,
                    source = when (target.packageName) {
                        in corePackages -> FreezeSource.CORE
                        in aiPackages -> FreezeSource.AI
                        else -> FreezeSource.CATALOG
                    },
                    mode = mode,
                    policyEnabled = catalogPolicyEnabled(target),
                    installed = actual.installed,
                    actualKnown = actual.known && componentKnown,
                    enabled = actual.enabled,
                    suspended = actual.suspended,
                    stopped = actual.stopped,
                    processRunning = actual.processRunning,
                    risk = target.risk,
                    componentDisabled = componentDisabled
                )
            }
    }

    private fun catalogStrategy(target: Target): FreezeStrategy {
        if (target.packageName == "com.hihonor.numberidentity") {
            when (readString("antifraud_numberidentity_mode") ?: "OFF") {
                "DISABLE_USER" -> return FreezeStrategy.DISABLE_USER
                "SUSPEND" -> return FreezeStrategy.SUSPEND
            }
        }
        val raw = readString("strategy_${target.packageName}")
        val stored = raw?.let {
            runCatching { FreezeStrategy.valueOf(it) }.getOrNull()
        } ?: target.defaultStrategy
        return if (
            stored in target.supportedStrategies &&
            FreezeStrategyPolicy.isAllowed(target, stored)
        ) {
            stored
        } else {
            target.defaultStrategy
        }
    }

    private fun catalogPolicyEnabled(target: Target): Boolean {
        if (target.packageName == "com.hihonor.numberidentity") {
            return (readString("antifraud_numberidentity_mode") ?: "OFF") != "OFF"
        }
        if (target.packageName in AI_DEEP_FREEZE_PACKAGES) {
            return readBoolean("ai_deep_freeze_enabled", false) &&
                readBoolean(aiTargetKey(target.packageName), false)
        }
        if (target.category == TargetCategory.CLOUD) {
            return readBoolean("cloud_target_${target.packageName}", false)
        }
        return readBoolean("target_${target.packageName}", false)
    }

    private fun readCloudStates(processSnapshot: ProcessListSnapshot?): List<CloudState> {
        val configured = cloudTargets.map { target -> cloudState(target, processSnapshot) }
        val extras = OBSERVE_ONLY_EXTRAS
            .filter { extra ->
                cloudTargets.none { it.packageName == extra.packageName }
            }
            .map { target -> cloudState(target, processSnapshot) }
        return configured + extras
    }

    private fun cloudState(
        target: CloudTarget,
        processSnapshot: ProcessListSnapshot?
    ): CloudState {
        val actual = readActual(target.packageName, processSnapshot)
        val policy = if (target.action == CloudAction.OBSERVE_ONLY) {
            false
        } else {
            cloudPolicy(target.packageName)
        }
        val state = CloudState(
            packageName = target.packageName,
            label = target.label,
            action = target.action,
            policyEnabled = policy,
            policyApplied = false,
            installed = actual.installed,
            actualKnown = actual.known,
            enabled = actual.enabled,
            suspended = actual.suspended,
            stopped = actual.stopped,
            processRunning = actual.processRunning,
            note = target.description,
            protected = DenyList.isProtected(target.packageName)
        )
        // Legacy installs may have applied a policy before the marker existed.
        // If the actual state already matches, treat it as applied; a visible
        // difference without a marker stays "未应用", never a false mismatch.
        val applied = cloudApplied(target.packageName) ||
            (policy && state.actuallyApplied)
        return state.copy(policyApplied = applied)
    }

    private fun cloudApplied(packageName: String): Boolean =
        runCatching {
            prefs.getBoolean("cloud_applied_$packageName", false)
        }.getOrDefault(false)

    private fun cloudPolicy(packageName: String): Boolean =
        cloudEnabled[packageName] ?: runCatching {
            prefs.getBoolean("cloud_target_$packageName", false)
        }.getOrDefault(false)

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

    private suspend fun readCotaState(): CotaState {
        val backend = rootBackend
            ?: return CotaState(
                status = CotaReadStatus.NO_ROOT,
                message = "未提供 Root 后端"
            )
        val rootFiles = when (val result = CotaController(backend, enabled = true).listFiles()) {
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

    private fun readBootSafetyState(settings: Settings): BootSafetyState =
        BootSafetyState(
            readOk = true,
            bootCount = readBootCount(),
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
            aiDeepFreezeEnabled = readBoolean(KEY_AI_DEEP_FREEZE_ENABLED, false),
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

    private fun readBootCount(): Int {
        val fromSystem = runCatching {
            AndroidSettings.Global.getInt(
                context.contentResolver,
                AndroidSettings.Global.BOOT_COUNT,
                -1
            )
        }.getOrDefault(-1)
        if (fromSystem >= 0) return fromSystem
        // docs/07 section 5.2 fallback: coarse elapsedRealtime hour bucket.
        return (SystemClock.elapsedRealtime() / 1000L / 3600L).toInt()
    }

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
        private const val KEY_AI_DEEP_FREEZE_ENABLED = "ai_deep_freeze_enabled"
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

        /** Cloud-section-only rows that must not be duplicated as catalog rows. */
        private val CLOUD_EXTRA_PACKAGES = setOf(
            "com.hihonor.android.pushagent"
        )

        /** Read-only extra rows requested by docs/07 section 9.2. */
        private val OBSERVE_ONLY_EXTRAS = listOf(
            CloudTarget(
                packageName = "com.hihonor.android.pushagent",
                label = "荣耀推送服务（pushagent）",
                action = CloudAction.OBSERVE_ONLY,
                description = "禁止操作：在 DenyList 中；本页只读展示状态。"
            )
        )

        private val THERMAL_SERVICES = listOf(
            "com.hihonor.powergenie" to "PowerGenie（功耗策略）",
            "com.hihonor.iaware" to "iAware（资源调度）"
        )
    }
}
