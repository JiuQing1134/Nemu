package com.nemu.hibernate.detection

import com.nemu.hibernate.core.RiskLevel
import com.nemu.hibernate.core.StrategyPreset
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
enum class FreezeMode { SUSPEND, DISABLE, COMPONENT, HIDE, OBSERVE, RESTRICT }

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
    /** True only when every selected component is disabled. */
    val componentDisabled: Boolean? = null,
    /** True when some (not all) selected components are disabled. */
    val componentPartial: Boolean = false,
    /** True when Nemu owns an AppOps/standby restriction for this package. */
    val restrictionApplied: Boolean = false,
    /**
     * Live AppOps/standby readback for RESTRICT rows. Null means the current
     * state could not be read (no root or command failure); never render
     * that as "not restricted".
     */
    val restrictionActual: Boolean? = null,
    /**
     * True once the stored policy was executed (or the actual state already
     * matches it). A configured-but-never-applied target is "未应用", not a
     * mismatch.
     */
    val policyApplied: Boolean = true,
    /** Derived UI preset; null for legacy rows without options. */
    val preset: StrategyPreset? = null
) {
    private val pending: Boolean
        get() = policyEnabled && !policyApplied && !actuallyFrozen

    val actuallyFrozen: Boolean
        get() = when (mode) {
            FreezeMode.SUSPEND -> suspended
            FreezeMode.DISABLE -> installed && !enabled
            FreezeMode.COMPONENT -> componentDisabled == true
            // A successful hide removes the package from this app's
            // PackageManager view, but an uninstalled/never-applied target is
            // also invisible. Require the applied marker to claim a hide.
            FreezeMode.HIDE -> policyApplied && !installed
            FreezeMode.OBSERVE -> false
            FreezeMode.RESTRICT -> restrictionActual ?: restrictionApplied
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
            !pending &&
            policyApplied != actuallyFrozen

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
        pending -> "已纳入（未应用）"
        policyEnabled -> "已纳入"
        else -> "未纳入"
    }

    /**
     * Mode-specific actual-state label for the detection row. Do not use
     * "暂停" for disable/restrict: those are different system states.
     * Null for OBSERVE rows, which only have the "未执行" value.
     */
    /** User-facing word for the SUSPEND strategy, following the preset. */
    fun suspendWord(): String =
        if (preset == StrategyPreset.PAUSE) "暂停" else "冻结"

    fun detailLabel(): String? = when (mode) {
        FreezeMode.SUSPEND -> suspendWord()
        FreezeMode.DISABLE -> "停用"
        FreezeMode.COMPONENT -> "组件"
        FreezeMode.HIDE -> "隐藏"
        FreezeMode.RESTRICT -> "限制"
        FreezeMode.OBSERVE -> null
    }

    fun detailValue(): String = if (mode == FreezeMode.HIDE) {
        // A hidden package is invisible to PackageManager, so actualKnown is
        // false by design; the applied marker is the positive proof here.
        when {
            installed -> "未生效"
            policyApplied -> "已生效"
            else -> "未知"
        }
    } else if (!actualKnown) {
        "未知"
    } else when (mode) {
        FreezeMode.SUSPEND -> yesNo(suspended)
        FreezeMode.DISABLE -> yesNo(!enabled)
        FreezeMode.COMPONENT -> when {
            componentPartial -> "部分停用"
            componentDisabled == true -> "已停用"
            componentDisabled == false -> "启用"
            else -> "未知"
        }
        FreezeMode.HIDE -> "未知"
        FreezeMode.RESTRICT ->
            if (restrictionActual ?: restrictionApplied) "已生效" else "未生效"
        FreezeMode.OBSERVE -> "未执行"
    }

    /** Backwards-compatible single string; the UI renders label/value separately. */
    fun detailLine(): String = detailLabel()?.let { "$it ${detailValue()}" }
        ?: detailValue()

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    fun describeActual(): String = when {
        mode == FreezeMode.RESTRICT && !actualKnown -> "读取失败"
        mode == FreezeMode.RESTRICT ->
            if (restrictionActual ?: restrictionApplied) "已限制" else "未限制"
        mode == FreezeMode.HIDE -> when {
            installed -> "仍可见：未隐藏或已恢复"
            policyApplied -> "不可见：已隐藏"
            else -> "不可见：未安装或未应用"
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
                when {
                    componentPartial -> "组件部分停用"
                    componentDisabled == true -> "组件已停用"
                    componentDisabled == false -> "组件启用"
                    else -> "组件状态未知"
                }
            )
            append("；")
            append(describeSuspendActual(enabled, suspended, stopped, processRunning))
        }
        else -> buildString {
            append(
                describeSuspendActual(
                    enabled,
                    suspended,
                    stopped,
                    processRunning,
                    suspendWord()
                )
            )
        }
    }

    fun statusLabel(): String = when {
        mode == FreezeMode.OBSERVE -> "仅观察"
        mode == FreezeMode.RESTRICT && !actualKnown -> "读取失败"
        mode == FreezeMode.RESTRICT ->
            if (restrictionActual ?: restrictionApplied) "已限制" else "未限制"
        mode == FreezeMode.HIDE && !installed && policyApplied -> "已隐藏"
        mode == FreezeMode.HIDE && !installed -> "不可见（未安装）"
        !installed -> "未安装"
        !actualKnown -> "读取失败"
        mode == FreezeMode.COMPONENT && componentDisabled == null -> "组件状态未知"
        mode == FreezeMode.COMPONENT && componentPartial -> "组件部分停用"
        pending -> "未应用"
        mismatch() -> "不一致"
        mode == FreezeMode.DISABLE -> if (!enabled) "已停用" else "仍启用"
        mode == FreezeMode.COMPONENT ->
            if (componentDisabled == true) "组件已停用" else "组件启用"
        partiallyApplied() -> "已${suspendWord()} · 存活"
        suspended && processRunning == null -> "已${suspendWord()} · 未知"
        preset == StrategyPreset.PAUSE ->
            if (suspended) "已暂停" else "未暂停"
        preset == StrategyPreset.FREEZE ->
            if (suspended) "已冻结" else "未冻结"
        else -> if (suspended) "已冻结" else "未冻结"
    }

    fun describe(): String = "策略：${describePolicy()}；实际：${describeActual()}"
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
    val readOnlyNote: String get() = "检测页只读；冻结/策略在冻结/解冻页操作"

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
    val aiTargetsEnabledAny: Boolean = false,
    val lastBootFreezeResult: String? = null,
    val lastBootFreezeDetail: String? = null,
    val lastBootFreezeTime: Long = 0L,
    val lastBootFreezeAttemptTime: Long = 0L,
    val lastBootFreezeSucceeded: Boolean = false,
    val lastBootFreezeBootCount: Int = -1,
    val lastBootFreezeAttemptBootCount: Int = -1,
    val skippedBootCount: Int = -1,
    /** Root guard's planned-shutdown epoch, already converted to millis. */
    val lastPlannedShutdownTime: Long = 0L
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
        val appClean = lastShutdownClean() &&
            lastShutdownTime >= lastBootFreezeAttemptTime
        val plannedClean = lastPlannedShutdownTime > 0 &&
            lastPlannedShutdownTime >= lastBootFreezeAttemptTime
        return !(appClean || plannedClean)
    }

    fun describeLastShutdown(zone: ZoneId = ZoneId.systemDefault()): String =
        describeTimestamped(lastShutdownResult, lastShutdownTime, zone)

    fun describeLastBoot(zone: ZoneId = ZoneId.systemDefault()): String =
        describeTimestamped(lastBootResult, lastBootTime, zone)

    fun describeLastBootFreeze(zone: ZoneId = ZoneId.systemDefault()): String =
        describeTimestamped(lastBootFreezeResult, lastBootFreezeTime, zone)

    /** Mirrors the precedence in [BootFreezePolicy.evaluate]. */
    /** Non-default guard event, or null when the chips already say everything. */
    fun guardNote(): String? = when {
        // Report a tripped guard even when the master switch is now off;
        // otherwise the row says “未启用” while the overview says 异常.
        bootloopGuardTripped() ->
            "守卫触发 · 上次未净关机"
        !bootFreezeEnabled -> null
        skippedThisBoot() -> "手动解冻，已跳过"
        bootCount >= 0 && lastBootFreezeBootCount == bootCount ->
            "本 boot 已处理"
        bootCount >= 0 && lastBootFreezeAttemptBootCount == bootCount ->
            "本 boot 已尝试"
        else -> null
    }

    fun describeGuard(): String = guardNote() ?: if (bootFreezeEnabled) {
        "开机自动冻结已开启（延迟 ${bootFreezeDelaySeconds}s）"
    } else {
        "开机自动冻结已关闭"
    }
}

/**
 * Complete read-only snapshot. [freezeStates] contains one strategy-aware row
 * per catalog target; cloud/telemetry targets carry [FreezeSource.CLOUD].
 */
data class DetectionSnapshot(
    val freezeStates: List<FreezeState> = emptyList(),
    val cota: CotaState = CotaState(),
    val thermal: ThermalState = ThermalState(),
    val bootSafety: BootSafetyState = BootSafetyState(),
    val generatedAtMillis: Long = 0L,
    val notes: List<String> = emptyList()
) {
    val freezeMismatches: List<FreezeState>
        get() = freezeStates.filter { it.mismatch() }

    val mismatchCount: Int get() = freezeMismatches.size

    fun describeSummary(): String = buildString {
        append(
            "目录目标 ${freezeStates.size} 项，" +
                "策略与实际不一致 ${freezeMismatches.size} 项"
        )
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
    processRunning: Boolean?,
    word: String = "暂停"
): String = buildString {
    append(if (suspended) "已$word" else "未$word")
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
