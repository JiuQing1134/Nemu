package com.nemu.hibernate.detection

import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.cota.CotaDirectoryInventory
import com.nemu.hibernate.cota.CotaFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where a freeze state came from. Detection never merges the policies. */
enum class FreezeSource { CORE, AI, CLOUD, CATALOG }

/**
 * What "frozen" means for this target. COMPONENT means at least one registered
 * component is disabled; HIDE means the package is hidden from PackageManager
 * lookups and looks uninstalled; OBSERVE rows are informational only.
 */
enum class FreezeMode { SUSPEND, DISABLE, COMPONENT, HIDE, OBSERVE }

/**
 * Policy vs actual state for one package. Pure Kotlin so the mapping,
 * mismatch and label rules can be unit-tested on the JVM.
 */
data class FreezeState(
    val packageName: String,
    val label: String,
    val source: FreezeSource,
    val mode: FreezeMode,
    val policyEnabled: Boolean,
    val installed: Boolean,
    val actualKnown: Boolean,
    val enabled: Boolean,
    val suspended: Boolean,
    val stopped: Boolean = false,
    /** True process presence is separate from Android's package STOPPED flag. */
    val processRunning: Boolean? = null,
    val risk: RiskLevel? = null,
    /** Null when the mode is not COMPONENT or the component states are unreadable. */
    val componentDisabled: Boolean? = null
) {
    val actuallyFrozen: Boolean
        get() = when (mode) {
            FreezeMode.SUSPEND -> suspended
            FreezeMode.DISABLE -> installed && !enabled
            FreezeMode.COMPONENT -> componentDisabled == true
            // A successful hide removes the package from this app's
            // PackageManager view, so "not installed" is the observable state.
            FreezeMode.HIDE -> !installed
            FreezeMode.OBSERVE -> false
        }

    /**
     * True only when a policy decision exists and the observed state differs.
     * Missing packages and failed reads are not reported as mismatches.
     */
    fun mismatch(): Boolean =
        mode != FreezeMode.OBSERVE &&
            installed &&
            actualKnown &&
            (mode != FreezeMode.COMPONENT || componentDisabled != null) &&
            policyEnabled != actuallyFrozen

    /**
     * A live process is a partial result even when PackageManager reports the
     * package suspended. The STOPPED flag is not used as a proxy for process
     * presence because it can disagree with the actual process table.
     */
    fun partiallyApplied(): Boolean =
        mode == FreezeMode.SUSPEND &&
            installed &&
            actualKnown &&
            suspended &&
            processRunning == true

    fun describePolicy(): String = when {
        mode == FreezeMode.OBSERVE -> "仅观察"
        policyEnabled -> "已纳入"
        else -> "未纳入"
    }

    fun describeActual(): String = when {
        mode == FreezeMode.HIDE -> if (installed) {
            "仍可见（未隐藏或已被恢复）"
        } else {
            "对系统不可见：已隐藏，或已卸载"
        }
        !installed -> "未安装"
        !actualKnown -> "读取失败"
        mode == FreezeMode.DISABLE -> buildString {
            append(if (enabled) "已启用" else "已停用")
            if (suspended) append("，已暂停")
            if (stopped || processRunning != null) {
                appendStoppedAndProcess(stopped, processRunning)
            }
        }
        mode == FreezeMode.COMPONENT -> buildString {
            append(
                when (componentDisabled) {
                    true -> "组件已停用"
                    false -> "组件启用"
                    null -> "组件状态未知"
                }
            )
            append("；")
            append(describeSuspendActual(enabled, suspended, stopped, processRunning))
        }
        else -> buildString {
            append(describeSuspendActual(enabled, suspended, stopped, processRunning))
        }
    }

    fun statusLabel(): String = when {
        mode == FreezeMode.OBSERVE -> "仅观察"
        mode == FreezeMode.HIDE && !installed -> "已隐藏"
        !installed -> "未安装"
        !actualKnown -> "读取失败"
        mode == FreezeMode.COMPONENT && componentDisabled == null -> "组件状态未知"
        mismatch() -> "不一致"
        mode == FreezeMode.DISABLE -> if (!enabled) "已停用" else "仍启用"
        mode == FreezeMode.COMPONENT ->
            if (componentDisabled == true) "组件已停用" else "组件启用"
        partiallyApplied() -> "已暂停 · 进程存活"
        suspended && processRunning == null -> "已暂停 · 进程未知"
        else -> if (suspended) "已冻结" else "未冻结"
    }

    fun describe(): String = "策略：${describePolicy()}；实际：${describeActual()}"
}

/**
 * Cloud/telemetry row. The action decides what "applied" means:
 * disable-user expects the package to be disabled, suspend expects the
 * package to be suspended, observe-only has no policy state.
 */
data class CloudState(
    val packageName: String,
    val label: String,
    val action: CloudAction,
    val policyEnabled: Boolean,
    /**
     * True once the policy has been attempted since it was last changed.
     * Without this, "configured but never applied" would look like a failure.
     */
    val policyApplied: Boolean = true,
    val installed: Boolean,
    val actualKnown: Boolean,
    val enabled: Boolean,
    val suspended: Boolean,
    val stopped: Boolean = false,
    val processRunning: Boolean? = null,
    val note: String? = null,
    val protected: Boolean = false
) {
    val actuallyApplied: Boolean
        get() = when (action) {
            CloudAction.DISABLE_USER -> installed && !enabled
            CloudAction.SUSPEND -> suspended
            CloudAction.OBSERVE_ONLY -> false
        }

    fun mismatch(): Boolean =
        action != CloudAction.OBSERVE_ONLY &&
            policyApplied &&
            installed &&
            actualKnown &&
            policyEnabled != actuallyApplied

    fun describePolicy(): String = when {
        protected -> "禁止操作"
        action == CloudAction.OBSERVE_ONLY -> "仅观察"
        policyEnabled && !policyApplied -> "已纳入（未应用）"
        policyEnabled -> "已纳入"
        else -> "未纳入"
    }

    fun describeActual(): String = when {
        !installed -> "未安装"
        !actualKnown -> "读取失败"
        action == CloudAction.DISABLE_USER -> buildString {
            append(if (enabled) "已启用" else "已停用")
            if (suspended) append("，已暂停")
            if (stopped || processRunning != null) {
                appendStoppedAndProcess(stopped, processRunning)
            }
        }
        else -> describeSuspendActual(enabled, suspended, stopped, processRunning)
    }

    fun statusLabel(): String = when {
        protected -> "禁止操作"
        action == CloudAction.OBSERVE_ONLY -> "仅观察"
        !installed -> "未安装"
        !actualKnown -> "读取失败"
        policyEnabled && !policyApplied -> "未应用"
        mismatch() -> "不一致"
        action == CloudAction.DISABLE_USER -> if (!enabled) "已停用" else "仍启用"
        action == CloudAction.SUSPEND && suspended && processRunning == true ->
            "已暂停 · 进程存活"
        action == CloudAction.SUSPEND && suspended && processRunning == null ->
            "已暂停 · 进程未知"
        else -> if (suspended) "已冻结" else "未冻结"
    }

    fun describe(): String = "策略：${describePolicy()}；实际：${describeActual()}"

    fun asFreezeState(): FreezeState = FreezeState(
        packageName = packageName,
        label = label,
        source = FreezeSource.CLOUD,
        mode = when (action) {
            CloudAction.SUSPEND -> FreezeMode.SUSPEND
            CloudAction.DISABLE_USER -> FreezeMode.DISABLE
            CloudAction.OBSERVE_ONLY -> FreezeMode.OBSERVE
        },
        policyEnabled = policyEnabled,
        installed = installed,
        actualKnown = actualKnown,
        enabled = enabled,
        suspended = suspended,
        stopped = stopped,
        processRunning = processRunning
    )
}

/** Result of the read-only COTA listing. UNKNOWN is never treated as success. */
enum class CotaReadStatus { OK, NO_ROOT, ERROR, UNKNOWN }

data class CotaState(
    val status: CotaReadStatus = CotaReadStatus.UNKNOWN,
    val files: List<CotaFile> = emptyList(),
    val message: String? = null,
    val directories: List<CotaDirectoryInventory> = emptyList()
) {
    val hasFiles: Boolean
        get() = status == CotaReadStatus.OK &&
            (files.isNotEmpty() || directories.any { it.fileCount > 0 })

    fun describe(): String = when (status) {
        CotaReadStatus.OK -> {
            val nestedFiles = directories.sumOf { it.fileCount }
            when {
                directories.isEmpty() && files.isEmpty() -> "未检测到文件"
                directories.isEmpty() -> "检测到 ${files.size} 个文件"
                files.isEmpty() && nestedFiles == 0 ->
                    "未检测到参数文件（子目录 ${directories.size} 个）"
                else ->
                    "根目录 ${files.size} 个文件 · 子目录 ${directories.size} 个 / $nestedFiles 个文件"
            }
        }
        CotaReadStatus.NO_ROOT -> "无 root，未读取"
        CotaReadStatus.ERROR ->
            "读取失败${message?.let { "：$it" } ?: ""}"
        CotaReadStatus.UNKNOWN -> "未检测"
    }
}

/** Read-only thermal base service state. There is deliberately no stop action. */
data class ThermalServiceState(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val actualKnown: Boolean,
    val enabled: Boolean,
    val suspended: Boolean
) {
    fun installedAndEnabled(): Boolean = installed && actualKnown && enabled

    fun describe(): String = when {
        !installed -> "未安装"
        !actualKnown -> "读取失败"
        !enabled && suspended -> "已停用，已暂停"
        !enabled -> "已停用"
        suspended -> "已启用，已暂停"
        else -> "已启用"
    }
}

data class ThermalState(
    val services: List<ThermalServiceState> = emptyList(),
    val message: String? = null
) {
    val readOnlyNote: String get() = "只读；不提供停止/冻结操作"

    fun describe(): String {
        if (services.isEmpty()) return message ?: "未检测"
        val installed = services.count { it.installed }
        val enabled = services.count { it.installedAndEnabled() }
        return "已安装 $installed/${services.size}；已启用 $enabled/${services.size}"
    }
}

/** Boot/shutdown/safety fields read from Settings and the boot-freeze keys. */
data class BootSafetyState(
    val readOk: Boolean = false,
    val bootCount: Int = -1,
    val lastShutdownResult: String? = null,
    val lastShutdownDetail: String? = null,
    val lastShutdownTime: Long = 0L,
    val lastBootResult: String? = null,
    val lastBootDetail: String? = null,
    val lastBootTime: Long = 0L,
    val bootFreezeEnabled: Boolean = false,
    val bootFreezeDelaySeconds: Int = 120,
    val aiDeepFreezeEnabled: Boolean = false,
    val lastBootFreezeResult: String? = null,
    val lastBootFreezeDetail: String? = null,
    val lastBootFreezeTime: Long = 0L,
    val lastBootFreezeAttemptTime: Long = 0L,
    val lastBootFreezeSucceeded: Boolean = false,
    val lastBootFreezeBootCount: Int = -1,
    val lastBootFreezeAttemptBootCount: Int = -1,
    val skippedBootCount: Int = -1
) {
    fun lastShutdownClean(): Boolean = lastShutdownResult
        ?.let { it.startsWith("成功") || it.contains("安全解冻成功") } == true

    fun skippedThisBoot(): Boolean =
        bootCount >= 0 && skippedBootCount == bootCount

    /**
     * Mirrors the boot-freeze guard input from docs/07 section 5.3: the
     * previous boot attempted a freeze, but no clean shutdown happened after
     * that attempt.
     */
    fun bootloopGuardTripped(): Boolean {
        if (bootCount <= 0) return false
        if (lastBootFreezeAttemptBootCount != bootCount - 1) return false
        return !(lastShutdownClean() && lastShutdownTime >= lastBootFreezeAttemptTime)
    }

    fun describeLastShutdown(zone: ZoneId = ZoneId.systemDefault()): String =
        describeTimestamped(lastShutdownResult, lastShutdownTime, zone)

    fun describeLastBoot(zone: ZoneId = ZoneId.systemDefault()): String =
        describeTimestamped(lastBootResult, lastBootTime, zone)

    fun describeLastBootFreeze(zone: ZoneId = ZoneId.systemDefault()): String =
        describeTimestamped(lastBootFreezeResult, lastBootFreezeTime, zone)

    /** Mirrors the precedence in [BootFreezePolicy.evaluate]. */
    fun describeGuard(): String = when {
        !bootFreezeEnabled -> "开机自动冻结已关闭"
        bootCount >= 0 && lastBootFreezeBootCount == bootCount ->
            "本 boot 已处理过开机冻结"
        skippedThisBoot() -> "用户已在本 boot 手动恢复，跳过自动冻结"
        bootCount >= 0 && lastBootFreezeAttemptBootCount == bootCount ->
            "本 boot 已尝试过开机冻结"
        bootloopGuardTripped() ->
            "守卫触发：上次冻结尝试后未检测到干净关机，本次开机应跳过自动冻结"
        else -> "开机自动冻结已开启（延迟 ${bootFreezeDelaySeconds}s）"
    }
}

/**
 * Complete read-only snapshot. [freezeStates] contains core and AI rows;
 * [cloudStates] carries the richer cloud/telemetry details for its own section
 * (kept separate to avoid duplicate rows and double-counted mismatches).
 */
data class DetectionSnapshot(
    val freezeStates: List<FreezeState> = emptyList(),
    val cloudStates: List<CloudState> = emptyList(),
    val cota: CotaState = CotaState(),
    val thermal: ThermalState = ThermalState(),
    val bootSafety: BootSafetyState = BootSafetyState(),
    val generatedAtMillis: Long = 0L,
    val notes: List<String> = emptyList()
) {
    val freezeMismatches: List<FreezeState>
        get() = freezeStates.filter { it.mismatch() }

    val cloudMismatches: List<CloudState>
        get() = cloudStates.filter { it.mismatch() }

    val mismatchCount: Int get() = freezeMismatches.size

    fun describeSummary(): String = buildString {
        append(
            "冻结/目录目标 ${freezeStates.size} 项，" +
                "策略与实际不一致 ${freezeMismatches.size} 项"
        )
        if (cloudStates.isNotEmpty()) {
            append("；云控/遥测 ${cloudStates.size} 项，不一致 ${cloudMismatches.size} 项")
        }
        append("；COTA：${cota.describe()}")
        append("；温控：${thermal.describe()}")
    }
}

/** Formats wall-clock millis for the detection UI; kept pure for JVM tests. */
fun formatDetectionTimestamp(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String = Instant.ofEpochMilli(millis)
    .atZone(zone)
    .format(TIMESTAMP_FORMAT)

private fun describeSuspendActual(
    enabled: Boolean,
    suspended: Boolean,
    stopped: Boolean,
    processRunning: Boolean?
): String = buildString {
    append(if (suspended) "已暂停" else "未暂停")
    append(if (enabled) "，已启用" else "，已停用")
    appendStoppedAndProcess(stopped, processRunning)
}

private fun StringBuilder.appendStoppedAndProcess(
    stopped: Boolean,
    processRunning: Boolean?
) {
    append(if (stopped) "，停止标记已设置" else "，停止标记未设置")
    append(
        when (processRunning) {
            true -> "，进程存活"
            false -> "，无目标进程"
            null -> "，进程未知"
        }
    )
}

private fun describeTimestamped(
    text: String?,
    millis: Long,
    zone: ZoneId
): String {
    // Older builds embedded a raw epoch in the text; drop it so it is not
    // shown twice next to the formatted timestamp.
    val base = text
        ?.replace(Regex("（\\d{13}）"), "")
        ?.ifBlank { null }
        ?: "尚未执行"
    return if (millis > 0) "$base（${formatDetectionTimestamp(millis, zone)}）" else base
}

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
