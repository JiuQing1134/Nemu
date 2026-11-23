package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.ResultCopy
import com.nemu.hibernate.core.ResultNote
import com.nemu.hibernate.core.ResultReason
import com.nemu.hibernate.core.StrategyOptions
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.PrivilegeBackend

enum class RestrictionKind { APP_OPS, STANDBY }

data class RestrictionResult(
    val ok: Boolean,
    val packageName: String,
    val appOpsOk: Boolean? = null,
    val standbyOk: Boolean? = null,
    val commands: List<String> = emptyList(),
    val reasons: List<ResultNote> = emptyList(),
    val label: String = packageName
) {
    /** Chinese UI lines; raw command output stays in ActionLog. */
    val notes: List<String> get() = reasons.map { it.line() }

    fun summary(): String = ResultCopy.resultLine(
        ok = ok,
        subject = label,
        reasons = reasons,
        qualifier = "限制"
    )
}

/**
 * AppOps/standby ownership. Unlike the old anti-fraud path, this controller
 * captures the original mode/bucket before writing, only restores values it
 * owns, and verifies the read-back. Marker callbacks are invoked before the
 * command so a failed marker write aborts without changing the device.
 */
class RestrictionController(
    private val backend: PrivilegeBackend,
    private val dryRun: Boolean,
    private val appOpsReader: suspend (String) -> String? = { null },
    private val standbyReader: suspend (String) -> Int? = { null },
    private val originalAppOps: (String) -> String? = { null },
    private val onOriginalAppOps: (String, String?) -> Boolean = { _, _ -> true },
    private val appOpsOwned: (String) -> Boolean = { false },
    private val setAppOpsOwned: (String, Boolean) -> Boolean = { _, _ -> true },
    private val originalStandby: (String) -> Int? = { null },
    private val onOriginalStandby: (String, Int?) -> Boolean = { _, _ -> true },
    private val standbyOwned: (String) -> Boolean = { false },
    private val setStandbyOwned: (String, Boolean) -> Boolean = { _, _ -> true },
    private val beforeOwn: suspend (String, RestrictionKind) -> Boolean = { _, _ -> true },
    private val afterRestore: suspend (String, RestrictionKind) -> Boolean = { _, _ -> true }
) {
    suspend fun apply(target: Target, options: StrategyOptions): RestrictionResult {
        val commands = mutableListOf<String>()
        val reasons = mutableListOf<ResultNote>()
        val appOpsOk = if (options.appOps) {
            applyAppOps(target, commands, reasons)
        } else {
            null
        }
        val standbyOk = if (options.standby) {
            applyStandby(target, commands, reasons)
        } else {
            null
        }
        val ok = appOpsOk != false && standbyOk != false
        if (ok) {
            ActionLog.info("限制策略完成：${target.packageName}")
        } else {
            ActionLog.warn(
                "限制策略有警告：${target.packageName} / " +
                    reasons.joinToString { it.line() }
            )
        }
        return RestrictionResult(
            ok = ok,
            packageName = target.packageName,
            appOpsOk = appOpsOk,
            standbyOk = standbyOk,
            commands = commands,
            reasons = reasons,
            label = target.label
        )
    }

    suspend fun restore(target: Target, options: StrategyOptions): RestrictionResult {
        val commands = mutableListOf<String>()
        val reasons = mutableListOf<ResultNote>()
        val appOpsOk = if (options.appOps) {
            restoreAppOps(target, commands, reasons)
        } else {
            null
        }
        val standbyOk = if (options.standby) {
            restoreStandby(target, commands, reasons)
        } else {
            null
        }
        return RestrictionResult(
            ok = appOpsOk != false && standbyOk != false,
            packageName = target.packageName,
            appOpsOk = appOpsOk,
            standbyOk = standbyOk,
            commands = commands,
            reasons = reasons,
            label = target.label
        )
    }

    private suspend fun applyAppOps(
        target: Target,
        commands: MutableList<String>,
        reasons: MutableList<ResultNote>
    ): Boolean {
        val packageName = target.packageName
        val current = appOpsReader(packageName)
        if (current == null) {
            reasons += ResultReason.BASELINE_READ_FAILED.at(target.label)
            return false
        }
        if (current.equals("ignore", ignoreCase = true) && !appOpsOwned(packageName)) {
            reasons += ResultReason.ALREADY_RESTRICTED.at(target.label)
            return true
        }
        val command = CommandBuilder.appOpsBackgroundMode(packageName, "ignore")
        commands += command
        if (dryRun) {
            ActionLog.info("DRY-RUN（限制后台）: $command")
            return true
        }
        var claimedHere = false
        if (!appOpsOwned(packageName)) {
            val baseline = originalAppOps(packageName) ?: current
            if (!onOriginalAppOps(packageName, baseline)) {
                reasons += ResultReason.BASELINE_WRITE_FAILED.at(target.label)
                return false
            }
            if (!beforeOwn(packageName, RestrictionKind.APP_OPS)) {
                onOriginalAppOps(packageName, null)
                reasons += ResultReason.MARKER_WRITE_FAILED.at(target.label)
                return false
            }
            if (!setAppOpsOwned(packageName, true)) {
                onOriginalAppOps(packageName, null)
                reasons += ResultReason.OWNERSHIP_WRITE_FAILED.at(target.label)
                return false
            }
            claimedHere = true
        }
        val result = backend.exec(command)
        val confirmed = appOpsRestricted(packageName)
        if (!result.ok) {
            ActionLog.error(
                "限制后台失败：$packageName / ${result.output.take(300)}"
            )
            reasons += if (confirmed == true) {
                ResultReason.COMMAND_FAILED.at(target.label)
            } else {
                ResultReason.RESTRICTION_NOT_CONFIRMED.at(target.label)
            }
            // A readable "not restricted" is proof the command did nothing;
            // an unreadable state keeps ownership because it may be live.
            if (confirmed == false && claimedHere) {
                rollbackAppOpsOwnership(packageName)
            }
            // A non-zero exit with a confirmed read-back means the state did
            // change; keep ownership so the user can still restore it.
            return confirmed == true
        }
        if (confirmed != true) {
            reasons += ResultReason.RESTRICTION_NOT_CONFIRMED.at(target.label)
            if (confirmed == false && claimedHere) {
                // `appops set` exited 0 but the value is readable and still
                // not "ignore": the restriction did not apply. Do not claim
                // ownership (the UI must not say 已限制).
                rollbackAppOpsOwnership(packageName)
            }
            // confirmed == null: both reads failed. The command may have
            // applied, so keep ownership + baseline for restore/residual.
            return false
        }
        return true
    }

    /**
     * true = restricted, false = readable and definitively not restricted,
     * null = both reads unreadable. A single transient `appops get` failure
     * must not look like "not restricted", and a readable non-restricted
     * value must not be kept as ownership.
     */
    private suspend fun appOpsRestricted(packageName: String): Boolean? {
        val first = appOpsReader(packageName)
        if (first.equals("ignore", ignoreCase = true)) return true
        val second = appOpsReader(packageName)
        if (second.equals("ignore", ignoreCase = true)) return true
        return if (first == null && second == null) null else false
    }

    private fun rollbackAppOpsOwnership(packageName: String) {
        setAppOpsOwned(packageName, false)
        onOriginalAppOps(packageName, null)
    }

    private fun rollbackStandbyOwnership(packageName: String) {
        setStandbyOwned(packageName, false)
        onOriginalStandby(packageName, null)
    }

    private suspend fun restoreAppOps(
        target: Target,
        commands: MutableList<String>,
        reasons: MutableList<ResultNote>
    ): Boolean? {
        val packageName = target.packageName
        if (!appOpsOwned(packageName)) return null
        val baseline = originalAppOps(packageName)
        if (baseline == null) {
            reasons += ResultReason.BASELINE_READ_FAILED.at(target.label)
            return false
        }
        val command = CommandBuilder.appOpsBackgroundMode(packageName, baseline)
        commands += command
        if (dryRun) {
            ActionLog.info("DRY-RUN（恢复后台）: $command")
            return true
        }
        val result = backend.exec(command)
        if (!result.ok) {
            ActionLog.error(
                "恢复后台失败：$packageName / ${result.output.take(300)}"
            )
            reasons += ResultReason.COMMAND_FAILED.at(target.label)
            return false
        }
        if (!appOpsReader(packageName).equals(baseline, ignoreCase = true)) {
            reasons += ResultReason.RESTORE_NOT_CONFIRMED.at(target.label)
            return false
        }
        if (!afterRestore(packageName, RestrictionKind.APP_OPS)) {
            reasons += ResultReason.MARKER_CLEAR_FAILED.at(target.label)
            return false
        }
        setAppOpsOwned(packageName, false)
        onOriginalAppOps(packageName, null)
        return true
    }

    private suspend fun applyStandby(
        target: Target,
        commands: MutableList<String>,
        reasons: MutableList<ResultNote>
    ): Boolean {
        val packageName = target.packageName
        val current = standbyReader(packageName)
        if (current == null) {
            reasons += ResultReason.BASELINE_READ_FAILED.at(target.label)
            return false
        }
        if (current == RESTRICTED_BUCKET && !standbyOwned(packageName)) {
            reasons += ResultReason.ALREADY_RESTRICTED.at(target.label)
            return true
        }
        val command = CommandBuilder.standbyBucketValue(
            packageName,
            RESTRICTED_BUCKET
        )
        commands += command
        if (dryRun) {
            ActionLog.info("DRY-RUN（限制待机）: $command")
            return true
        }
        var claimedHere = false
        if (!standbyOwned(packageName)) {
            val baseline = originalStandby(packageName) ?: current
            if (!onOriginalStandby(packageName, baseline)) {
                reasons += ResultReason.BASELINE_WRITE_FAILED.at(target.label)
                return false
            }
            if (!beforeOwn(packageName, RestrictionKind.STANDBY)) {
                onOriginalStandby(packageName, null)
                reasons += ResultReason.MARKER_WRITE_FAILED.at(target.label)
                return false
            }
            if (!setStandbyOwned(packageName, true)) {
                onOriginalStandby(packageName, null)
                reasons += ResultReason.OWNERSHIP_WRITE_FAILED.at(target.label)
                return false
            }
            claimedHere = true
        }
        val result = backend.exec(command)
        val confirmed = standbyRestricted(packageName)
        if (!result.ok) {
            ActionLog.error(
                "限制待机失败：$packageName / ${result.output.take(300)}"
            )
            reasons += if (confirmed == true) {
                ResultReason.COMMAND_FAILED.at(target.label)
            } else {
                ResultReason.RESTRICTION_NOT_CONFIRMED.at(target.label)
            }
            if (confirmed == false && claimedHere) {
                rollbackStandbyOwnership(packageName)
            }
            return confirmed == true
        }
        if (confirmed != true) {
            reasons += ResultReason.RESTRICTION_NOT_CONFIRMED.at(target.label)
            if (confirmed == false && claimedHere) {
                // Readable, still not the restricted bucket: the command did
                // not apply, so ownership must not claim it.
                rollbackStandbyOwnership(packageName)
            }
            // null keeps ownership: both reads failed and the restriction
            // might be live (e.g. STANDBY_BUCKET_EXEMPTED edge cases).
            return false
        }
        return true
    }

    /** true/false/null as [appOpsRestricted]; see its contract. */
    private suspend fun standbyRestricted(packageName: String): Boolean? {
        val first = standbyReader(packageName)
        if (first == RESTRICTED_BUCKET) return true
        val second = standbyReader(packageName)
        if (second == RESTRICTED_BUCKET) return true
        return if (first == null && second == null) null else false
    }

    private suspend fun restoreStandby(
        target: Target,
        commands: MutableList<String>,
        reasons: MutableList<ResultNote>
    ): Boolean? {
        val packageName = target.packageName
        if (!standbyOwned(packageName)) return null
        val baseline = originalStandby(packageName)
        if (baseline == null) {
            reasons += ResultReason.BASELINE_READ_FAILED.at(target.label)
            return false
        }
        val command = CommandBuilder.standbyBucketValue(packageName, baseline)
        commands += command
        if (dryRun) {
            ActionLog.info("DRY-RUN（恢复待机）: $command")
            return true
        }
        val result = backend.exec(command)
        if (!result.ok) {
            ActionLog.error(
                "恢复待机失败：$packageName / ${result.output.take(300)}"
            )
            reasons += ResultReason.COMMAND_FAILED.at(target.label)
            return false
        }
        if (standbyReader(packageName) != baseline) {
            reasons += ResultReason.RESTORE_NOT_CONFIRMED.at(target.label)
            return false
        }
        if (!afterRestore(packageName, RestrictionKind.STANDBY)) {
            reasons += ResultReason.MARKER_CLEAR_FAILED.at(target.label)
            return false
        }
        setStandbyOwned(packageName, false)
        onOriginalStandby(packageName, null)
        return true
    }

    companion object {
        const val RESTRICTED_BUCKET = 45
    }
}
